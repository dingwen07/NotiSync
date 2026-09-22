package net.extrawdw.apps.notisync.sshkeyprovider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshKeyDescriptor

/** Inventory and active requests; terminal request history is loaded through its own bounded pager. */
data class SshKeyProviderManagementSnapshot(
    val keys: List<SshKeyDescriptor>,
    /** All pending review/outbox requests, never the terminal history page. */
    val requests: List<StoredSshProviderRequest>,
    val knownHosts: List<SshKnownHost>,
    val rememberedAuthorizations: List<SshRememberedAuthorization>,
)

data class SshKeyProviderManagementState(
    val snapshot: SshKeyProviderManagementSnapshot? = null,
    val errorMessage: String? = null,
)

internal data class VersionedSshKeyProviderManagementSnapshot(
    val version: Long,
    val snapshot: SshKeyProviderManagementSnapshot,
)

/** Loads and observes the SSH management model only while the screen collects [state]. */
class SshKeyProviderManagementRepository internal constructor(
    private val changeVersion: StateFlow<Long>,
    private val expireRequests: () -> Unit,
    private val loadSnapshot: () -> VersionedSshKeyProviderManagementSnapshot,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        store: SshKeyProviderStore,
        providerClientId: ClientId,
        scope: CoroutineScope,
        dismissExpiredRequest: (String) -> Unit,
    ) : this(
        changeVersion = store.changeVersion,
        expireRequests = { store.expireDue(System.currentTimeMillis()).forEach(dismissExpiredRequest) },
        loadSnapshot = { store.managementSnapshot(providerClientId, System.currentTimeMillis()) },
        scope = scope,
        ioDispatcher = Dispatchers.IO,
    )

    private val _state = MutableStateFlow(SshKeyProviderManagementState())
    val state: StateFlow<SshKeyProviderManagementState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    @Volatile
    private var loadedVersion: Long? = null

    init {
        scope.launch {
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (observed) changeVersion.collect { refresh() }
            }
        }
    }

    /** Expire before checking the version: elapsed time alone does not invalidate the cached snapshot. */
    suspend fun refresh() {
        refreshMutex.withLock {
            val result = try {
                withContext(ioDispatcher) {
                    expireRequests()
                    if (loadedVersion == changeVersion.value && state.value.snapshot != null) null else loadSnapshot()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.summary())
                return
            }
            if (result == null) {
                if (state.value.errorMessage != null) _state.value = state.value.copy(errorMessage = null)
                return
            }
            loadedVersion = result.version
            _state.value = SshKeyProviderManagementState(snapshot = result.snapshot)
        }
    }
}

private fun Throwable.summary(): String =
    "${javaClass.simpleName}${message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
