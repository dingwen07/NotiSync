package net.extrawdw.notisync.server.pairing

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.protocol.BrokerPairing
import net.extrawdw.notisync.protocol.PairingRelayReady
import net.extrawdw.notisync.protocol.PairingRelayRequest
import net.extrawdw.notisync.protocol.ProtocolCodec

/** In-memory, single-attempt rendezvous. No cards, passwords, authentication tokens, or DB writes. */
class PairingRelay(private val maxSessions: Int = 128, private val maxSessionsPerAddress: Int = 8) {
    class Room internal constructor(val id: String, internal val address: String) {
        internal var joined = false
        internal val toHost = Channel<ByteArray>(1)
        internal val toClient = Channel<ByteArray>(1)
    }
    private val rooms = mutableMapOf<String, Room>()
    private val random = SecureRandom()

    @Synchronized
    fun create(address: String): Room {
        require(rooms.size < maxSessions && rooms.values.count { it.address == address } < maxSessionsPerAddress) {
            "Pairing capacity reached"
        }
        var id: String
        do { id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(random::nextBytes)) }
        while (id in rooms)
        return Room(id, address).also { rooms[id] = it }
    }

    @Synchronized
    fun join(id: String): Room {
        require(BrokerPairing.validSessionId(id))
        val room = requireNotNull(rooms[id]) { "Pairing expired" }
        require(!room.joined) { "Pairing already used" }
        room.joined = true // Never release the slot for another guess, including after failure.
        return room
    }

    @Synchronized
    fun remove(room: Room) {
        if (rooms[room.id] === room) rooms.remove(room.id)
        // close (not cancel) preserves the final queued CARD until the other side drains it.
        room.toHost.close()
        room.toClient.close()
    }
}

fun Route.pairingRelay(relay: PairingRelay = PairingRelay(), sessionMillis: Long = BrokerPairing.SESSION_MILLIS) {
    webSocket(BrokerPairing.PATH) {
        maxFrameSize = BrokerPairing.MAX_FRAME_BYTES.toLong()
        var room: PairingRelay.Room? = null
        try {
            withTimeout(sessionMillis) {
                val request = withTimeout(10_000) {
                    ProtocolCodec.decodeFromCbor<PairingRelayRequest>(receivePairingFrame())
                }
                require(request.version == BrokerPairing.VERSION)
                val host = request.sessionId == null
                // Use the actual connection address, never a client-supplied forwarding header.
                val active = if (host) relay.create(call.request.local.remoteHost) else relay.join(request.sessionId!!)
                room = active
                send(Frame.Binary(true, ProtocolCodec.encodeToCbor(PairingRelayReady(sessionId = active.id))))
                val outgoing = if (host) active.toClient else active.toHost
                val incoming = if (host) active.toHost else active.toClient
                coroutineScope {
                    // Keep reading while waiting for the peer so cancellation/disconnect releases the room.
                    // The capacity-one queues propagate backpressure even if a client sends rounds early.
                    val reader = launch {
                        repeat(BrokerPairing.EXCHANGE_FRAMES) { outgoing.send(receivePairingFrame()) }
                    }
                    repeat(BrokerPairing.EXCHANGE_FRAMES) { send(Frame.Binary(true, incoming.receive())) }
                    reader.join()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pairing_unavailable"))
        } finally {
            room?.let(relay::remove)
        }
    }
}

private suspend fun WebSocketSession.receivePairingFrame(): ByteArray {
    val frame = incoming.receive()
    require(frame is Frame.Binary && frame.fin && frame.data.size in 1..BrokerPairing.MAX_FRAME_BYTES)
    return frame.data
}
