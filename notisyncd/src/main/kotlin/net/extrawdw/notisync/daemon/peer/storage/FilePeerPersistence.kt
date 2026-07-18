package net.extrawdw.notisync.daemon.peer.storage

import java.time.Clock
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.NotificationOutbox
import net.extrawdw.notisync.daemon.PendingNotification
import net.extrawdw.notisync.daemon.PendingRunState
import net.extrawdw.notisync.daemon.PendingRunControlResult
import net.extrawdw.notisync.daemon.RunOutbox
import net.extrawdw.notisync.daemon.RunResultOutbox
import net.extrawdw.notisync.daemon.RunIosNotificationOutbox
import net.extrawdw.notisync.daemon.coalesceRunIosNotifications
import net.extrawdw.notisync.daemon.coalesceRuns
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.DurableJsonState
import net.extrawdw.notisync.daemon.storage.SecureFileSystem
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.LocalRunPromptKind
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
 * One atomic durable state boundary for relay deduplication and outbound notification intents.
 *
 * Sharing one [DurableJsonState] prevents independent adapters from overwriting each other in
 * `notisyncd.db`. Completion removes all older states for its source and periodic updates coalesce,
 * matching [net.extrawdw.notisync.daemon.InMemoryNotificationOutbox] while surviving daemon restarts.
 */
class DaemonDatabaseRepository(
    layout: DaemonStorageLayout,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumDedupEntries: Int = DEFAULT_MAXIMUM_DEDUP_ENTRIES,
    fileSystem: SecureFileSystem = SecureFileSystem(),
    private val beforeRunAcceptanceCommit: (() -> Unit)? = null,
) : MessageDedup, NotificationOutbox, RunOutbox, RunResultOutbox, RunIosNotificationOutbox {
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
    }

    /** Return a consistent database snapshot, rejecting unknown/corrupt schema versions. */
    fun load(): DaemonDatabase = state.load().validated()

    /**
     * Atomically update any combination of sessions, unacknowledged events, outbox, and dedup state.
     * [LocalSessionRegistry] uses this seam so all daemon state sharing `notisyncd.db` participates in
     * one serialized read-modify-write transaction.
     */
    fun update(transform: (DaemonDatabase) -> DaemonDatabase): DaemonDatabase =
        state.update { current -> transform(current.validated()).validated() }

    override fun seen(messageId: String): Boolean {
        validateMessageId(messageId)
        return load().deduplication.containsKey(messageId)
    }

    override fun record(messageId: String) {
        validateMessageId(messageId)
        update { current ->
            current.copy(deduplication = current.deduplication.recorded(messageId))
        }
    }

    /**
     * Commit an inbound local event and its encrypted relay idempotency marker in one atomic rewrite.
     * SecureChannel records the same message id after its handler returns; that second put is harmless.
     * If the process crashes between this commit and the handler return, the marker makes redelivery a
     * durable duplicate instead of enqueueing the action or dismissal a second time.
     */
    fun persistSessionAndRecordRelay(session: StoredLocalSession, messageId: String) {
        session.validate()
        validateMessageId(messageId)
        update { current ->
            current.copy(
                sessions = current.sessions + (session.id to session),
                deduplication = current.deduplication.recorded(messageId),
            )
        }
    }

    /** Atomically stop redelivering an applied control and queue its result for retryable mesh delivery. */
    fun persistSessionAndEnqueueRunResult(session: StoredLocalSession, result: PendingRunControlResult) {
        session.validate()
        validateMessageId(result.id)
        update { current ->
            current.copy(
                sessions = current.sessions + (session.id to session),
                runResultOutbox = current.runResultOutbox
                    .filterNot { it.id == result.id }
                    .plus(result),
            )
        }
    }

    /**
     * Commit one accepted local Run snapshot as a single crash boundary: the authoritative session
     * revision/input context and both platform-specific durable delivery records become visible
     * together. A retry can therefore never encounter a persisted stale revision whose Android or
     * iOS delivery intent was lost.
     */
    fun persistRunAcceptance(
        session: StoredLocalSession,
        runState: PendingRunState,
        iosProjection: PendingNotification?,
    ) {
        session.validate()
        validateMessageId(runState.id)
        require(runState.sourceKey == session.sourceKey) { "Run state source does not match session" }
        iosProjection?.let {
            validateOutboxItem(it)
            require(it.sourceKey == session.sourceKey) { "Run iOS source does not match session" }
            require(it.audience == net.extrawdw.notisync.daemon.NotificationAudience.RUN_IOS_COMPAT)
        }
        update { current ->
            val runs = linkedMapOf<String, PendingRunState>()
            current.runOutbox.forEach { runs[it.id] = it }
            coalesceRuns(runs, runState)
            val ios = linkedMapOf<String, PendingNotification>()
            current.runIosOutbox.forEach { ios[it.id] = it }
            iosProjection?.let { coalesceRunIosNotifications(ios, it) }
            beforeRunAcceptanceCommit?.invoke()
            current.copy(
                sessions = current.sessions + (session.id to session),
                runOutbox = runs.values.toList(),
                runIosOutbox = ios.values.toList(),
            )
        }
    }

    override fun enqueue(item: PendingNotification) {
        validateOutboxItem(item)
        update { current ->
            val pending = current.outbox.toMutableList()
            val sameSource = pending.filter { it.sourceKey == item.sourceKey }
            if (sameSource.any {
                    it.request.phase in setOf(NotificationPhase.COMPLETED, NotificationPhase.FAILED) &&
                        it.request.generation >= item.request.generation
                }
            ) return@update current
            when (item.request.phase) {
                NotificationPhase.COMPLETED, NotificationPhase.FAILED -> {
                    if (sameSource.any { it.request.generation > item.request.generation }) return@update current
                    pending.removeAll { it.sourceKey == item.sourceKey }
                }
                NotificationPhase.PERIODIC -> {
                    if (sameSource.any {
                            it.request.phase == NotificationPhase.PERIODIC &&
                                (it.request.generation > item.request.generation || it.postTime > item.postTime)
                        }
                    ) return@update current
                    pending.removeAll {
                        it.sourceKey == item.sourceKey && it.request.phase == NotificationPhase.PERIODIC
                    }
                }
                else -> Unit
            }
            // UUIDs should be unique, but replacement makes retrying an accepted local request idempotent.
            pending.removeAll { it.id == item.id }
            pending += item
            current.copy(outbox = pending)
        }
    }

    override fun peek(): PendingNotification? = load().outbox.firstOrNull()

    override fun remove(id: String) {
        validateMessageId(id)
        update { current -> current.copy(outbox = current.outbox.filterNot { it.id == id }) }
    }

    override fun retryLater(id: String) {
        validateMessageId(id)
        // With one pending item there is nothing to reorder. Avoid rewriting/fsyncing the database on every
        // reconnect backoff tick while the user has no paired/display-capable device.
        if (load().outbox.let { it.size <= 1 && it.firstOrNull()?.id == id }) return
        update { current ->
            val pending = current.outbox.toMutableList()
            val index = pending.indexOfFirst { it.id == id }
            if (index < 0) current
            else current.copy(outbox = pending.apply { add(removeAt(index)) })
        }
    }

    override fun enqueue(item: PendingRunState) {
        validateMessageId(item.id)
        require(item.sourceKey.isNotBlank() && item.sourceKey.length <= MAXIMUM_SOURCE_KEY_LENGTH) {
            "invalid Run source key"
        }
        update { current ->
            val pending = linkedMapOf<String, PendingRunState>()
            current.runOutbox.forEach { pending[it.id] = it }
            coalesceRuns(pending, item)
            current.copy(runOutbox = pending.values.toList())
        }
    }

    override fun peekRun(): PendingRunState? = load().runOutbox.firstOrNull()

    override fun removeRun(id: String) {
        validateMessageId(id)
        update { current -> current.copy(runOutbox = current.runOutbox.filterNot { it.id == id }) }
    }

    override fun retryRunLater(id: String) {
        validateMessageId(id)
        if (load().runOutbox.let { it.size <= 1 && it.firstOrNull()?.id == id }) return
        update { current ->
            val pending = current.runOutbox.toMutableList()
            val index = pending.indexOfFirst { it.id == id }
            if (index < 0) current else current.copy(runOutbox = pending.apply { add(removeAt(index)) })
        }
    }

    override fun enqueueResult(item: PendingRunControlResult) {
        validateMessageId(item.id)
        update { current ->
            if (current.runResultOutbox.any { it.id == item.id }) current
            else current.copy(runResultOutbox = current.runResultOutbox + item)
        }
    }

    override fun peekResult(): PendingRunControlResult? = load().runResultOutbox.firstOrNull()

    override fun removeResult(id: String) {
        validateMessageId(id)
        update { current -> current.copy(runResultOutbox = current.runResultOutbox.filterNot { it.id == id }) }
    }

    override fun retryResultLater(id: String) {
        validateMessageId(id)
        if (load().runResultOutbox.let { it.size <= 1 && it.firstOrNull()?.id == id }) return
        update { current ->
            val pending = current.runResultOutbox.toMutableList()
            val index = pending.indexOfFirst { it.id == id }
            if (index < 0) current else current.copy(runResultOutbox = pending.apply { add(removeAt(index)) })
        }
    }

    override fun enqueueIos(item: PendingNotification) {
        validateOutboxItem(item)
        require(item.audience == net.extrawdw.notisync.daemon.NotificationAudience.RUN_IOS_COMPAT)
        update { current ->
            val pending = linkedMapOf<String, PendingNotification>()
            current.runIosOutbox.forEach { pending[it.id] = it }
            coalesceRunIosNotifications(pending, item)
            current.copy(runIosOutbox = pending.values.toList())
        }
    }

    override fun peekIos(): PendingNotification? = load().runIosOutbox.firstOrNull()

    override fun removeIos(id: String) {
        validateMessageId(id)
        update { current -> current.copy(runIosOutbox = current.runIosOutbox.filterNot { it.id == id }) }
    }

    override fun retryIosLater(id: String) {
        validateMessageId(id)
        if (load().runIosOutbox.let { it.size <= 1 && it.firstOrNull()?.id == id }) return
        update { current ->
            val pending = current.runIosOutbox.toMutableList()
            val index = pending.indexOfFirst { it.id == id }
            if (index < 0) current else current.copy(runIosOutbox = pending.apply { add(removeAt(index)) })
        }
    }

    fun pendingCount(): Int = load().outbox.size

    private fun validateMessageId(messageId: String) {
        require(messageId.isNotBlank() && messageId.length <= MAXIMUM_ID_LENGTH) { "invalid message id" }
    }

    private fun validateOutboxItem(item: PendingNotification) {
        validateMessageId(item.id)
        require(item.sourceKey.isNotBlank() && item.sourceKey.length <= MAXIMUM_SOURCE_KEY_LENGTH) {
            "invalid notification source key"
        }
    }

    private fun Map<String, Long>.recorded(messageId: String): Map<String, Long> {
        val result = toMutableMap()
        // Preserve the original handled time on harmless duplicate records.
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
        const val DEFAULT_MAXIMUM_DEDUP_ENTRIES = 50_000
        const val MAXIMUM_ID_LENGTH = 512
        const val MAXIMUM_SOURCE_KEY_LENGTH = 512
    }
}

/** Complete durable schema for `state/notisyncd.db`. */
@Serializable
data class DaemonDatabase(
    val schemaVersion: Int = 1,
    /** Keyed by session id. Map iteration order is the durable event/session creation order. */
    val sessions: Map<String, StoredLocalSession> = emptyMap(),
    val deduplication: Map<String, Long> = emptyMap(),
    val outbox: List<PendingNotification> = emptyList(),
    val runOutbox: List<PendingRunState> = emptyList(),
    val runResultOutbox: List<PendingRunControlResult> = emptyList(),
    val runIosOutbox: List<PendingNotification> = emptyList(),
) {
    fun validated(): DaemonDatabase = also {
        require(schemaVersion == 1) { "unsupported daemon database version $schemaVersion" }
        require(sessions.all { (id, session) -> id == session.id }) {
            "daemon database contains a session under the wrong id"
        }
        sessions.values.forEach(StoredLocalSession::validate)
        require(deduplication.keys.none(String::isBlank)) { "daemon database contains an empty message id" }
        require(outbox.map(PendingNotification::id).distinct().size == outbox.size) {
            "daemon database contains duplicate outbox ids"
        }
        require(runOutbox.map(PendingRunState::id).distinct().size == runOutbox.size) {
            "daemon database contains duplicate Run outbox ids"
        }
        require(runResultOutbox.map(PendingRunControlResult::id).distinct().size == runResultOutbox.size) {
            "daemon database contains duplicate Run result outbox ids"
        }
        require(runIosOutbox.map(PendingNotification::id).distinct().size == runIosOutbox.size) {
            "daemon database contains duplicate Run iOS outbox ids"
        }
    }
}

/** Serializable counterpart of daemon `WireAction`, kept in storage to avoid reversing wire indices. */
@Serializable
data class StoredWireAction(
    val index: Int,
    val id: String,
    val title: String,
    val generation: Long,
    val remoteInput: Boolean,
    val remoteInputLabel: String? = null,
    /** Opaque origin-issued capability; empty only when migrating a pre-token database. */
    val actionToken: String = "",
    val kind: NotificationActionKind = if (remoteInput) {
        NotificationActionKind.REMOTE_INPUT
    } else {
        NotificationActionKind.WRITE_INPUT
    },
    val lifetime: NotificationActionLifetime = NotificationActionLifetime.GENERATION,
    val signal: String? = null,
)

/** Process binding and durable-until-ACK event state for one local source namespace. */
@Serializable
data class StoredLocalSession(
    val id: String,
    val sourceKey: String,
    val bearerHash: ByteArray,
    val uid: Long,
    val pid: Long? = null,
    val startTime: String? = null,
    val clientName: String,
    val createdAt: Long,
    val latestGeneration: Long = -1,
    val latestPostTime: Long = -1,
    val actions: List<StoredWireAction> = emptyList(),
    /** Process-control capabilities retained until this process-owned session closes. */
    val sessionActions: List<StoredWireAction> = emptyList(),
    /** Keyed by event id; iteration order is delivery order. */
    val events: Map<String, net.extrawdw.notisync.localapi.LocalEvent> = emptyMap(),
    val runId: String? = null,
    val latestRunRevision: Long = -1,
    val interactionGeneration: Long = 0,
    val runActive: Boolean = false,
    val runPrompt: LocalRunPromptKind? = null,
    val handledRunControlRequestIds: Map<String, Long> = emptyMap(),
) {
    fun validate() {
        require(id.isNotBlank()) { "stored session has an empty id" }
        require(sourceKey.isNotBlank()) { "stored session has an empty source key" }
        require(bearerHash.size == SHA_256_BYTES) { "stored session has an invalid bearer hash" }
        require(uid >= 0) { "stored session has an invalid uid" }
        require(pid == null || pid > 0) { "stored session has an invalid pid" }
        require(clientName.isNotBlank()) { "stored session has an empty client name" }
        require(latestPostTime >= -1) { "stored session has an invalid post time" }
        require(actions.map(StoredWireAction::index).distinct().size == actions.size) {
            "stored session has duplicate wire action indices"
        }
        require(sessionActions.size <= MAXIMUM_SESSION_ACTIONS) {
            "stored session has too many session-lifetime actions"
        }
        require(sessionActions.all { it.lifetime == NotificationActionLifetime.SESSION }) {
            "stored session has an invalid session-lifetime action"
        }
        require(sessionActions.all { it.kind == NotificationActionKind.SIGNAL && !it.signal.isNullOrBlank() }) {
            "stored session has an invalid process-control action"
        }
        require(sessionActions.map(StoredWireAction::index).distinct().size == sessionActions.size) {
            "stored session has duplicate session action indices"
        }
        require(events.size <= MAXIMUM_EVENTS) { "stored session has too many unacknowledged events" }
        require(events.all { (eventId, event) -> eventId == event.id && event.sessionId == id }) {
            "stored session contains an event under the wrong id or session"
        }
        require(runId == null || runId.isNotBlank()) { "stored session has an invalid Run id" }
        require(latestRunRevision >= -1) { "stored session has an invalid Run revision" }
        require(interactionGeneration >= 0) { "stored session has an invalid interaction generation" }
        require(!runActive || runId != null) { "active stored Run session has no Run id" }
        require(runPrompt == null || runActive) { "inactive stored Run session has an input prompt" }
        require(handledRunControlRequestIds.size <= MAXIMUM_RUN_CONTROL_IDS) {
            "stored session has too many handled Run control ids"
        }
        require(handledRunControlRequestIds.keys.none(String::isBlank)) {
            "stored session has an invalid handled Run control id"
        }
    }

    private companion object {
        const val SHA_256_BYTES = 32
        const val MAXIMUM_EVENTS = 1_024
        const val MAXIMUM_SESSION_ACTIONS = 16
        const val MAXIMUM_RUN_CONTROL_IDS = 1_024
    }
}
