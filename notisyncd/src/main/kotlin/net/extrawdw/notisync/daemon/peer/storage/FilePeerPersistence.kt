package net.extrawdw.notisync.daemon.peer.storage

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.DurableJsonState
import net.extrawdw.notisync.desktop.SecureFileSystem
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

/** Small metadata snapshot. Deduplication is queried separately in SQLite, never loaded here. */
@Serializable
data class DaemonDatabase(
    val profilePublication: ApplicationProfilePublicationState = ApplicationProfilePublicationState(),
    /** Persistent same-UID application declarations keyed by application id. */
    val applications: Map<String, StoredApplicationRegistration> = emptyMap(),
) {
    fun validated(): DaemonDatabase = also {
        profilePublication.validateStored()
        require(applications.all { (id, application) -> id == application.applicationId }) {
            "daemon database contains an application under the wrong id"
        }
        applications.values.forEach(StoredApplicationRegistration::validate)
    }
}
