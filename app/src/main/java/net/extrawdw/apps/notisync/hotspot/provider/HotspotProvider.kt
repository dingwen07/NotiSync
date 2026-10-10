package net.extrawdw.apps.notisync.hotspot.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.protocol.*

internal data class HotspotReading(
    val result: HotspotResult,
    val snapshot: HotspotSnapshot? = null,
    val platformError: Int? = null,
)

internal interface HotspotBackend {
    suspend fun query(): HotspotReading
    suspend fun setEnabled(enabled: Boolean, stillAuthorized: () -> Boolean): HotspotReading
}

/**
 * Own-mesh policy is resolved again at execution and immediately before sealing each publication.
 * Permission revocation stops future disclosure/operations without sending a cache-erasure command.
 */
internal class HotspotProvider(
    private val ownId: ClientId,
    private val scope: CoroutineScope,
    private val backend: HotspotBackend,
    private val trustedPeer: (ClientId) -> Boolean,
    private val authorized: (ClientId) -> Boolean,
    private val recipients: () -> List<ClientId>,
    private val consumeCommand: suspend (ClientId, Long) -> Boolean,
    private val send: suspend (Set<ClientId>, HotspotSync, Urgency) -> Int,
    private val scheduleRequest: (ClientId, HotspotSync, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Request(val sender: ClientId, val sync: HotspotSync, val createdAt: Long)
    private data class Broadcast(val reading: HotspotReading, val audience: Set<ClientId>)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val operation = Mutex()
    private val publication = Mutex()
    private var lastBroadcast: Broadcast? = null
    private var lastBroadcastAt: Long? = null
    private var lastIssuedAt = 0L

    init {
        scope.launch {
            for (ignored in changes) {
                delay(300) // Coalesce bursts of interface callbacks before reading current state.
                operation.withLock {
                    if (recipients().any(::mayAccess)) broadcast(read { backend.query() })
                }
            }
        }
    }

    fun onPlatformChanged() { changes.trySend(Unit) }

    fun onSync(message: InboundMessage, data: DataSync) {
        val sync = data.hotspot ?: return
        if (data.kind != DataSyncKind.HOTSPOT || !message.senderOwnDevice || !trustedPeer(message.senderId) ||
            !sync.isValid(now(), message.createdAt)
        ) return
        if (acceptsTarget(sync)) {
            scheduleRequest(message.senderId, sync, message.createdAt)
        }
    }

    /** WorkManager owns the process lifetime for authenticated requests received during an FCM wake. */
    suspend fun executeScheduledRequest(sender: ClientId, sync: HotspotSync, envelopeCreatedAt: Long) {
        if (!acceptsTarget(sync)) return
        operation.withLock { execute(Request(sender, sync, envelopeCreatedAt)) }
    }

    private fun acceptsTarget(sync: HotspotSync): Boolean = when (sync.action) {
        HotspotAction.QUERY, HotspotAction.REFRESH -> sync.hotspotDeviceId == null || sync.hotspotDeviceId == ownId
        HotspotAction.SET_ENABLED -> sync.hotspotDeviceId == ownId
        HotspotAction.STATUS -> false
    }

    private suspend fun execute(request: Request) {
        if (!trustedPeer(request.sender)) return
        val failure = when {
            !mayAccess(request.sender) -> HotspotResult.UNAUTHORIZED
            !request.sync.isValid(now(), request.createdAt) -> HotspotResult.EXPIRED
            else -> null
        }
        if (failure != null) {
            if (request.sync.action != HotspotAction.REFRESH) reply(request, HotspotReading(failure))
            return
        }
        // This check and the entire broadcast run under operation, so simultaneous hints share one
        // publication. Ignored hints never extend the cooldown; explicit queries always run.
        if (request.sync.action == HotspotAction.REFRESH && lastBroadcastAt?.let {
            now() - it in 0L until REFRESH_COOLDOWN_MS
        } == true) return
        val reading = if (request.sync.action == HotspotAction.SET_ENABLED) {
            // Durable, non-sensitive per-peer high-water mark rejects replay/reordered state changes.
            val consumed = try { consumeCommand(request.sender, request.sync.issuedAt) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (consumed == null) HotspotReading(HotspotResult.UNAVAILABLE)
            else if (!consumed) HotspotReading(HotspotResult.EXPIRED)
            else if (!mayAccess(request.sender)) HotspotReading(HotspotResult.UNAUTHORIZED)
            else read { backend.setEnabled(requireNotNull(request.sync.enabled)) {
                mayAccess(request.sender) && request.sync.isValid(now(), request.createdAt)
            } }
        } else read { backend.query() }
        broadcast(reading, request)
    }

    private fun mayAccess(peer: ClientId) = trustedPeer(peer) && authorized(peer)

    private suspend fun broadcast(reading: HotspotReading, request: Request? = null) = publication.withLock {
        val audience = (recipients() + listOfNotNull(request?.sender)).filter(::mayAccess).toSet()
        val current = Broadcast(reading, audience)
        // Deduplicate the whole callback publication, never individual recipients. A request that
        // reaches this point publishes to everyone, even if the reading has not changed.
        if (request == null && current == lastBroadcast) return@withLock
        lastBroadcast = null // A partial or cancelled newer send invalidates the previous publication.
        // One payload/envelope for the entire audience. Only the controller whose pending request id
        // matches this id treats it as a reply; the other controllers consume the same status update.
        val response = status(reading, nextIssuedAt(), request?.takeIf {
            it.sync.action != HotspotAction.REFRESH
        }?.sync?.requestId)
        val sent = safelySend(audience, response, Urgency.NORMAL)
        if (sent > 0) lastBroadcastAt = now()
        if (sent == audience.size) lastBroadcast = current
    }

    private suspend fun reply(request: Request, reading: HotspotReading) = publication.withLock {
        if (trustedPeer(request.sender)) {
            safelySend(setOf(request.sender), status(reading, nextIssuedAt(), request.sync.requestId), Urgency.NORMAL)
        }
    }

    private fun status(reading: HotspotReading, issued: Long, id: String?) = HotspotSync(
        action = HotspotAction.STATUS, hotspotDeviceId = ownId, issuedAt = issued,
        expiresAt = issued + HotspotSync.LIFETIME_MS, requestId = id,
        result = reading.result, snapshot = reading.snapshot.takeIf { reading.result == HotspotResult.OK },
        platformError = reading.platformError,
    )

    @Synchronized private fun nextIssuedAt(): Long = maxOf(now(), lastIssuedAt + 1).also { lastIssuedAt = it }

    private suspend fun safelySend(peers: Set<ClientId>, sync: HotspotSync, urgency: Urgency): Int = try {
        // Final gate immediately before serialization/sealing; never retry an already-encoded PSK.
        val allowed = peers.filterTo(mutableSetOf()) {
            trustedPeer(it) && (sync.snapshot == null || mayAccess(it))
        }
        if (allowed.isEmpty()) 0 else send(allowed, sync, urgency)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { 0 }

    private suspend fun read(block: suspend () -> HotspotReading): HotspotReading = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { HotspotReading(HotspotResult.UNAVAILABLE) }

    private companion object {
        const val REFRESH_COOLDOWN_MS = 10_000L
    }
}
