package net.extrawdw.notisync.daemon.peer.storage

import java.time.Clock
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.PendingSend
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.DurableJsonState
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.peer.channel.MessageDedup
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.peer.transport.AuthTokenStore
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse

/** Durable `trust.json` adapter preserving the trust store's stable string keys verbatim. */
class FileTrustPersistence(
    layout: DaemonStorageLayout,
    fileSystem: SecureFileSystem = SecureFileSystem(),
) : TrustPersistence {
    private val state: DurableJsonState<TrustFileState>

    init {
        layout.prepare(fileSystem)
        state = DurableJsonState(
            path = layout.trustStateFile,
            serializer = TrustFileState.serializer(),
            defaultValue = ::TrustFileState,
            fileSystem = fileSystem,
        )
    }

    override fun read(key: String): String? {
        validateStateKey(key)
        return state.load().validated().values[key]
    }

    /** Apply the complete batch with one atomic replacement; null values remove their keys. */
    override fun write(values: Map<String, String?>) {
        values.keys.forEach(::validateStateKey)
        if (values.isEmpty()) return
        state.update { current ->
            current.validated()
            val merged = current.values.toMutableMap()
            values.forEach { (key, value) ->
                if (value == null) merged.remove(key) else merged[key] = value
            }
            current.copy(values = merged)
        }
    }

    private fun validateStateKey(key: String) {
        require(key.isNotBlank() && key.length <= MAXIMUM_KEY_LENGTH) { "invalid trust state key" }
    }

    private companion object {
        const val MAXIMUM_KEY_LENGTH = 256
    }
}

@Serializable
private data class TrustFileState(
    val schemaVersion: Int = 1,
    val values: Map<String, String> = emptyMap(),
) {
    fun validated(): TrustFileState = also {
        require(schemaVersion == 1) { "unsupported trust file version $schemaVersion" }
    }
}

/** Durable `auth.json` broker bearer repository. The initial provider stores the token in plaintext. */
class FileAuthTokenRepository(
    layout: DaemonStorageLayout,
    fileSystem: SecureFileSystem = SecureFileSystem(),
) : AuthTokenStore {
    private val state: DurableJsonState<AuthFileState>

    init {
        layout.prepare(fileSystem)
        state = DurableJsonState(
            path = layout.authStateFile,
            serializer = AuthFileState.serializer(),
            defaultValue = ::AuthFileState,
            fileSystem = fileSystem,
        )
    }

    override fun load(): IntegrityVerificationResponse? = state.load().validated().token

    override fun save(token: IntegrityVerificationResponse?) {
        state.save(AuthFileState(token = token))
    }
}

@Serializable
private data class AuthFileState(
    val schemaVersion: Int = 1,
    val token: IntegrityVerificationResponse? = null,
) {
    fun validated(): AuthFileState = also {
        require(schemaVersion == 1) { "unsupported auth file version $schemaVersion" }
    }
}

/**
 * The one atomic durable state boundary used by the generic application bridge and relay deduplication.
 *
 * Local receive interests and application inboxes are deliberately absent: they are memory-only and
 * disappear with the daemon. The incompatible pre-release schema has no migration path so an old
 * development database is rejected without touching trust, keys, auth, or configuration.
 */
class DaemonDatabaseRepository(
    layout: DaemonStorageLayout,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumDedupEntries: Int = DEFAULT_MAXIMUM_DEDUP_ENTRIES,
    fileSystem: SecureFileSystem = SecureFileSystem(),
) : MessageDedup {
    private val state: DurableJsonState<DaemonDatabase>

    init {
        require(maximumDedupEntries > 0) { "maximumDedupEntries must be positive" }
        layout.prepare(fileSystem)
        state = DurableJsonState(
            path = layout.databaseFile,
            serializer = DaemonDatabase.serializer(),
            defaultValue = ::DaemonDatabase,
            fileSystem = fileSystem,
        )
        if (state.exists()) validateDatabase(state.load())
    }

    /** Return one consistent snapshot and reject corrupt or incompatible schemas. */
    fun load(): DaemonDatabase = validateDatabase(state.load())

    /** Atomically update application, generic outbox, profile-publication, or dedup state. */
    fun update(transform: (DaemonDatabase) -> DaemonDatabase): DaemonDatabase =
        state.update { current -> validateDatabase(transform(validateDatabase(current))) }

    override fun seen(messageId: String): Boolean {
        validateDedupMessageId(messageId)
        return load().deduplication.containsKey(messageId)
    }

    override fun record(messageId: String) {
        validateDedupMessageId(messageId)
        update { current -> current.copy(deduplication = current.deduplication.recorded(messageId)) }
    }

    private fun validateDatabase(database: DaemonDatabase): DaemonDatabase {
        if (database.schemaVersion != DAEMON_DATABASE_SCHEMA_VERSION) {
            throw IllegalStateException(
                "Incompatible notisyncd development database schema ${database.schemaVersion} at ${state.path}. " +
                    "Remove only ${state.path} manually and restart notisyncd; trust, keys, auth, and config " +
                    "files are retained.",
            )
        }
        return database.validated()
    }

    private fun Map<String, Long>.recorded(messageId: String): Map<String, Long> {
        val result = toMutableMap()
        result.putIfAbsent(messageId, clock.millis())
        if (result.size > maximumDedupEntries) {
            result.entries
                .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(result.size - maximumDedupEntries)
                .forEach { result.remove(it.key) }
        }
        return result
    }

    private companion object {
        const val DAEMON_DATABASE_SCHEMA_VERSION = 2
        const val DEFAULT_MAXIMUM_DEDUP_ENTRIES = 50_000
    }
}

/** Complete durable schema for `state/notisyncd.db`. */
@Serializable
data class DaemonDatabase(
    val schemaVersion: Int = 2,
    val profilePublication: ApplicationProfilePublicationState = ApplicationProfilePublicationState(),
    /** Persistent same-UID application declarations keyed by application id. */
    val applications: Map<String, StoredApplicationRegistration> = emptyMap(),
    /** One globally ordered generic send queue. */
    val genericOutbox: List<PendingSend> = emptyList(),
    /** Application-scoped idempotency records, retained after broker acceptance. */
    val submissions: Map<String, Map<String, StoredSubmission>> = emptyMap(),
    /** Latest accepted sequence for each application-local stream key. */
    val streamSequences: Map<String, Map<String, Long>> = emptyMap(),
    /** Durable relay-envelope deduplication markers. */
    val deduplication: Map<String, Long> = emptyMap(),
) {
    fun validated(): DaemonDatabase = also {
        require(schemaVersion == 2) { "unsupported daemon database version $schemaVersion" }
        profilePublication.validateStored()
        require(applications.all { (id, application) -> id == application.applicationId }) {
            "daemon database contains an application under the wrong id"
        }
        applications.values.forEach(StoredApplicationRegistration::validate)
        require(genericOutbox.map(PendingSend::messageId).distinct().size == genericOutbox.size) {
            "daemon database contains duplicate generic outbox message ids"
        }
        genericOutbox.forEach { pending ->
            require(pending.applicationId in applications) {
                "daemon database contains a generic send for an unregistered application"
            }
            pending.validateStored()
        }
        require(submissions.keys.all { it in applications }) {
            "daemon database contains submissions for an unregistered application"
        }
        submissions.forEach { (_, records) ->
            records.keys.forEach(::validateStoredSubmissionId)
            records.values.forEach(StoredSubmission::validate)
        }
        require(
            submissions.values.flatMap { it.values }.map(StoredSubmission::messageId).let {
                it.distinct().size == it.size
            },
        ) { "daemon database contains duplicate submission message ids" }
        val submissionMessageIds = submissions.values.flatMap { it.values }.mapTo(hashSetOf()) { it.messageId }
        genericOutbox.forEach { pending ->
            val submissionId = pending.submissionId
            if (submissionId == null) {
                require(pending.messageId !in submissionMessageIds) {
                    "daemon database contains a non-idempotent send reusing a submission message id"
                }
            } else {
                require(submissions[pending.applicationId]?.get(submissionId)?.messageId == pending.messageId) {
                    "daemon database contains a generic send without its idempotency record"
                }
            }
            pending.queuePolicy?.streamKey?.let { streamKey ->
                val latest = streamSequences[pending.applicationId]?.get(streamKey)
                require(latest != null && checkNotNull(pending.queuePolicy.sequence) <= latest) {
                    "daemon database contains a generic send without its stream sequence state"
                }
            }
        }
        require(
            genericOutbox.mapNotNull { pending ->
                pending.queuePolicy?.coalesceKey?.let { pending.applicationId to it }
            }.let { it.distinct().size == it.size },
        ) { "daemon database contains duplicate application coalescing keys" }
        require(streamSequences.keys.all { it in applications }) {
            "daemon database contains stream sequences for an unregistered application"
        }
        streamSequences.values.forEach { sequences ->
            require(sequences.values.all { it >= 0 }) {
                "daemon database contains invalid stream sequence state"
            }
            sequences.keys.forEach(::validateStoredQueueKey)
        }
        require(deduplication.keys.none(String::isBlank)) {
            "daemon database contains an empty message id"
        }
    }
}

private fun validateDedupMessageId(messageId: String) {
    require(messageId.isNotBlank() && messageId.length <= 512) { "invalid message id" }
}
