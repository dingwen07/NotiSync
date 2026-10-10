package net.extrawdw.apps.notisync.hotspot.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.protocol.*

internal data class RemoteHotspotState(
    val status: HotspotSync? = null,
    val pendingRequest: String? = null,
    val failure: HotspotResult? = null,
) {
    val canToggle: Boolean get() = pendingRequest == null && failure == null && status?.result == HotspotResult.OK &&
        status.snapshot?.state in listOf(HotspotState.ENABLED, HotspotState.DISABLED)
}

/** Remote requests and received state. Persisted credentials survive permission revocation. */
internal class HotspotController(
    private val scope: CoroutineScope,
    private val trustedPeer: (ClientId) -> Boolean,
    private val hotspotProvider: (ClientId) -> Boolean,
    private val persistSnapshot: (ClientId, HotspotSnapshot, Long) -> Unit,
    private val send: suspend (ClientId?, HotspotSync, Urgency) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val _remote = MutableStateFlow<Map<ClientId, RemoteHotspotState>>(emptyMap())
    val remote = _remote.asStateFlow()
    private var lastIssuedAt = 0L

    fun onPolicyChanged() {
        _remote.update { entries -> entries.filterKeys { trustedPeer(it) && hotspotProvider(it) } }
    }

    fun onSync(message: InboundMessage, data: DataSync) {
        val sync = data.hotspot ?: return
        if (data.kind != DataSyncKind.HOTSPOT || sync.action != HotspotAction.STATUS ||
            !message.senderOwnDevice || !trustedPeer(message.senderId) ||
            sync.hotspotDeviceId != message.senderId || !hotspotProvider(message.senderId) ||
            !sync.isValid(now(), message.createdAt)
        ) return
        // Commit before secure-channel acknowledgement. The store rejects older timestamps too.
        sync.snapshot?.let { persistSnapshot(message.senderId, it, sync.issuedAt) }
        _remote.update { entries ->
            val old = entries[message.senderId] ?: RemoteHotspotState()
            if (sync.issuedAt <= (old.status?.issuedAt ?: 0)) return@update entries
            entries + (message.senderId to RemoteHotspotState(
                status = sync,
                pendingRequest = old.pendingRequest.takeUnless { it == sync.requestId },
            ))
        }
    }

    /** Sheet entry reuses status for two minutes, then sends a hint without waiting for a reply. */
    fun refreshIfStale(peer: ClientId) {
        val updatedAt = _remote.value[peer]?.status?.issuedAt
        if (updatedAt != null && now() - updatedAt in 0L until 120_000L) return
        request(peer, HotspotAction.REFRESH)
    }

    fun refresh(peer: ClientId) = request(peer, HotspotAction.QUERY)
    fun setEnabled(peer: ClientId, enabled: Boolean) = request(peer, HotspotAction.SET_ENABLED, enabled)

    /** One best-effort startup fanout; its sender resolves all trusted hotspot providers together. */
    fun refreshProviders() = request(null, HotspotAction.REFRESH, urgency = Urgency.NORMAL)

    fun toggleOrQuery(peer: ClientId) {
        val state = _remote.value[peer]
        if (state?.canToggle == true) setEnabled(peer, state.status?.snapshot?.state == HotspotState.DISABLED)
        else refresh(peer)
    }

    private fun request(peer: ClientId?, action: HotspotAction, enabled: Boolean? = null, urgency: Urgency = Urgency.HIGH) {
        if (peer != null && (!trustedPeer(peer) || !hotspotProvider(peer))) return
        val waitForReply = peer != null && action != HotspotAction.REFRESH
        val id = requestId()
        while (peer != null) {
            val entries = _remote.value
            val old = entries[peer] ?: RemoteHotspotState()
            if (old.pendingRequest != null) return
            if (!waitForReply) break
            if (_remote.compareAndSet(entries, entries + (peer to old.copy(pendingRequest = id, failure = null)))) break
        }
        scope.launch {
            val issued = nextIssuedAt()
            val sync = HotspotSync(
                action = action,
                hotspotDeviceId = peer, issuedAt = issued, expiresAt = issued + HotspotSync.LIFETIME_MS,
                requestId = id, enabled = enabled,
            )
            val sent = try {
                (peer == null || (trustedPeer(peer) && hotspotProvider(peer))) && send(peer, sync, urgency)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!waitForReply) return@launch
            if (sent) delay(45_000)
            _remote.update { entries ->
                val old = entries[peer] ?: return@update entries
                if (old.pendingRequest != id) entries else entries + (peer to old.copy(
                    pendingRequest = null, failure = if (sent) HotspotResult.TIMEOUT else HotspotResult.UNAVAILABLE,
                ))
            }
        }
    }

    @Synchronized private fun nextIssuedAt(): Long = maxOf(now(), lastIssuedAt + 1).also { lastIssuedAt = it }
}
