package net.extrawdw.notisync.server.delivery

import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenRelayJoin
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayRole

/** In-memory rendezvous for short-lived Android screen Relay channels. */
class ScreenRelayHub(
    private val maximumConnections: Int = 1_024,
    private val maximumConnectionsPerClient: Int = 8,
) {
    internal data class Key(
        val relayId: String,
        val requester: ClientId,
        val source: ClientId,
        val channel: net.extrawdw.notisync.protocol.ScreenRelayChannel,
        val expiresAt: Long,
    )

    private class Slot {
        var requester: Handle? = null
        var source: Handle? = null
    }

    inner class Handle internal constructor(
        private val key: Key,
        val clientId: ClientId,
        val role: ScreenRelayRole,
        val session: DefaultWebSocketServerSession,
    ) : AutoCloseable {
        private val peerDeferred = CompletableDeferred<Handle>()
        private val videoQueue = if (
            key.channel == ScreenRelayChannel.VIDEO && role == ScreenRelayRole.REQUESTER
        ) ScreenRelayVideoQueue() else null
        private var ready = false
        private var closed = false

        suspend fun awaitPeer(): Handle = peerDeferred.await()

        internal suspend fun enqueueVideo(bytes: ByteArray): ScreenRelayVideoEnqueueResult =
            requireNotNull(videoQueue) { "video may only be enqueued toward the requester" }
                .enqueue(bytes)

        internal suspend fun takeVideo(): ByteArray? =
            requireNotNull(videoQueue) { "only the requester has a video delivery queue" }.take()

        fun markReady() = synchronized(this@ScreenRelayHub) {
            if (closed) return@synchronized
            ready = true
            pairIfReady(requireNotNull(slots[key]))
        }

        internal fun isReady(): Boolean = ready
        internal fun pair(peer: Handle) = peerDeferred.complete(peer)
        internal fun fail(error: Throwable) = peerDeferred.completeExceptionally(error)

        override fun close() = synchronized(this@ScreenRelayHub) {
            if (closed) return@synchronized
            closed = true
            videoQueue?.close()
            val slot = slots[key]
            if (role == ScreenRelayRole.REQUESTER && slot?.requester === this) slot.requester = null
            if (role == ScreenRelayRole.SOURCE && slot?.source === this) slot.source = null
            val remaining = slot?.requester ?: slot?.source
            remaining?.fail(IOException("screen relay peer disconnected"))
            if (slot != null && slot.requester == null && slot.source == null) slots.remove(key)
            connectionCount--
            perClient[clientId.value] = (perClient[clientId.value] ?: 1) - 1
            if (perClient[clientId.value] == 0) perClient.remove(clientId.value)
        }
    }

    private val slots = mutableMapOf<Key, Slot>()
    private val perClient = mutableMapOf<String, Int>()
    private var connectionCount = 0

    @Synchronized
    fun register(
        join: ScreenRelayJoin,
        clientId: ClientId,
        session: DefaultWebSocketServerSession,
    ): Handle? {
        if (connectionCount >= maximumConnections) return null
        if ((perClient[clientId.value] ?: 0) >= maximumConnectionsPerClient) return null
        val key = Key(
            join.relayId,
            join.requesterPeerId,
            join.sourcePeerId,
            join.channel,
            join.expiresAt,
        )
        val slot = slots.getOrPut(key, ::Slot)
        if (join.role == ScreenRelayRole.REQUESTER && slot.requester != null) return null
        if (join.role == ScreenRelayRole.SOURCE && slot.source != null) return null
        val handle = Handle(key, clientId, join.role, session)
        if (join.role == ScreenRelayRole.REQUESTER) slot.requester = handle else slot.source = handle
        connectionCount++
        perClient[clientId.value] = (perClient[clientId.value] ?: 0) + 1
        return handle
    }

    private fun pairIfReady(slot: Slot) {
        val requester = slot.requester?.takeIf(Handle::isReady) ?: return
        val source = slot.source?.takeIf(Handle::isReady) ?: return
        requester.pair(source)
        source.pair(requester)
    }
}

object ScreenRelayAdmissionPolicy {
    private val RELAY_ID = Regex("[A-Za-z0-9_-]{32}")
    private const val MAX_FUTURE_MS = 5L * 60 * 1_000

    fun rejection(
        join: ScreenRelayJoin,
        principal: ClientId,
        securityEnabled: Boolean,
        now: Long,
    ): String? {
        if (!join.relayId.matches(RELAY_ID)) return "bad_relay_id"
        if (join.requesterPeerId == join.sourcePeerId) return "same_peer"
        if (join.expiresAt <= now || join.expiresAt > now + MAX_FUTURE_MS) return "bad_expiry"
        if (!securityEnabled) return null
        val expected = when (join.role) {
            ScreenRelayRole.REQUESTER -> join.requesterPeerId
            ScreenRelayRole.SOURCE -> join.sourcePeerId
        }
        return if (principal == expected) null else "client_mismatch"
    }
}
