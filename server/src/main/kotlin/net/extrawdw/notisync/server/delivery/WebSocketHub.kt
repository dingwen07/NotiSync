package net.extrawdw.notisync.server.delivery

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.extrawdw.notisync.protocol.ClientId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** One authenticated live WebSocket connection (the foreground delivery link). */
class WsConnection(val clientId: ClientId, val session: DefaultWebSocketServerSession)

/** Minimal live-delivery boundary used by the broker; split from connection registration for policy tests. */
interface LiveDeliveryHub {
    fun isOnline(clientId: ClientId): Boolean
    suspend fun deliverText(clientId: ClientId, text: String): Boolean
}

/** Tracks live connections per client so the broker can deliver in realtime when a peer is online. */
class WebSocketHub : LiveDeliveryHub {
    private val log = LoggerFactory.getLogger(WebSocketHub::class.java)
    private val connections = ConcurrentHashMap<String, MutableSet<WsConnection>>()

    fun register(conn: WsConnection) {
        connections.compute(conn.clientId.value) { _, set ->
            (set ?: java.util.Collections.newSetFromMap(ConcurrentHashMap())).apply { add(conn) }
        }
    }

    fun unregister(conn: WsConnection) {
        connections.compute(conn.clientId.value) { _, set ->
            set?.apply { remove(conn) }?.takeIf { it.isNotEmpty() }
        }
    }

    override fun isOnline(clientId: ClientId): Boolean = connections[clientId.value]?.isNotEmpty() == true

    /** Push a text frame to every live connection of [clientId]. Returns true if any delivered. */
    override suspend fun deliverText(clientId: ClientId, text: String): Boolean {
        val set = connections[clientId.value] ?: return false
        var delivered = false
        for (c in set.toList()) {
            try {
                c.session.send(Frame.Text(text))
                delivered = true
            } catch (error: Exception) {
                // This write belongs to the sender's request. Its cancellation says nothing about
                // the recipient's connection, so leave that registration and session intact.
                currentCoroutineContext().ensureActive()
                unregister(c)
                // Eviction must also terminate the socket, otherwise ping/pong can keep an unroutable
                // recipient connected forever. Cancellation does not wait on a blocked close write.
                c.session.cancel("Live delivery failed", error)
                log.warn(
                    "ws delivery failed; closing connection client={} cause={}",
                    c.clientId.shortForm(),
                    error.javaClass.simpleName,
                )
            }
        }
        return delivered
    }
}
