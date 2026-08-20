package net.extrawdw.apps.notisync.sshagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshKeyDescriptor

/** The complete database-backed model rendered by the SSH management screen. */
data class SshAgentManagementSnapshot(
    val keys: List<SshKeyDescriptor>,
    val requests: List<StoredSshProviderRequest>,
    val knownHosts: List<SshKnownHost>,
    val rememberedAuthorizations: List<SshRememberedAuthorization>,
)

data class SshAgentManagementState(
    val snapshot: SshAgentManagementSnapshot? = null,
    val errorMessage: String? = null,
)

/**
 * Process-wide cache for the SSH management screen.
 *
 * The SSH database performs integrity and lifecycle checks on its first open. Preloading the complete screen model
 * while the application graph is already being built off-main keeps that one-time work out of navigation. Subsequent
 * store mutations are observed through [SshKeyProviderStore.changeVersion] and refresh the cache once per version.
 */
class SshAgentManagementRepository internal constructor(
    private val changeVersion: StateFlow<Long>,
    private val loadSnapshot: () -> SshAgentManagementSnapshot,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        store: SshKeyProviderStore,
        providerClientId: ClientId,
        scope: CoroutineScope,
    ) : this(
        changeVersion = store.changeVersion,
        loadSnapshot = {
            SshAgentManagementSnapshot(
                keys = store.snapshot(providerClientId, null, System.currentTimeMillis()).keys,
                requests = store.requests(),
                knownHosts = store.knownHosts(),
                rememberedAuthorizations = store.rememberedAuthorizations(),
            )
        },
        scope = scope,
        ioDispatcher = Dispatchers.IO,
    )

    private val _state = MutableStateFlow(SshAgentManagementState())
    val state: StateFlow<SshAgentManagementState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var loadedVersion: Long? = null
    private var observerJob: Job? = null

    /** Called from the application's I/O initialization thread before the graph is exposed to UI. */
    fun preload() {
        publish(loadStableSnapshot())
    }

    /** Starts the single process-wide store observer after [preload]. */
    @Synchronized
    fun start() {
        if (observerJob != null) return
        observerJob = scope.launch {
            changeVersion.collect { observedVersion ->
                if (observedVersion != loadedVersion || state.value.snapshot == null) refresh()
            }
        }
    }

    /** Refreshes only when the durable store is newer than the currently published snapshot. */
    suspend fun refresh() {
        refreshMutex.withLock {
            if (loadedVersion == changeVersion.value && state.value.snapshot != null) return
            val result = try {
                withContext(ioDispatcher) { loadStableSnapshot() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
            publish(result)
        }
    }

    private fun loadStableSnapshot(): Result<VersionedSnapshot> = try {
        // A load is four individually synchronized reads. Repeat when a mutation lands between them so the
        // published aggregate always represents one stable change-version boundary.
        var versionBefore: Long
        var versionAfter: Long
        lateinit var snapshot: SshAgentManagementSnapshot
        do {
            versionBefore = changeVersion.value
            snapshot = loadSnapshot()
            versionAfter = changeVersion.value
        } while (versionBefore != versionAfter)
        Result.success(VersionedSnapshot(versionAfter, snapshot))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun publish(result: Result<VersionedSnapshot>) {
        result.onSuccess { loaded ->
            loadedVersion = loaded.version
            _state.value = SshAgentManagementState(snapshot = loaded.snapshot)
        }.onFailure { failure ->
            _state.value = _state.value.copy(errorMessage = failure.summary())
        }
    }

    private data class VersionedSnapshot(
        val version: Long,
        val snapshot: SshAgentManagementSnapshot,
    )
}

private fun Throwable.summary(): String =
    "${javaClass.simpleName}${message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
