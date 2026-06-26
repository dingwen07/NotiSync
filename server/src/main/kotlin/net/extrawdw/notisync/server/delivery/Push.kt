package net.extrawdw.notisync.server.delivery

import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.server.data.StoredRoute

/**
 * Outcome of a wake/inline push, mapped to NotiSync route-state semantics.
 *
 * [PERMANENT_FAILURE] is distinct from [TRANSIENT_FAILURE]: the route token is fine, but the push can't
 * succeed as sent (a broker payload/config error like BadTopic or PayloadTooLarge), so retrying it
 * unchanged is pointless — surfaced separately so it isn't silently retried as a transient blip.
 */
enum class PushOutcome { DELIVERED, ROUTE_INVALID, TRANSIENT_FAILURE, PERMANENT_FAILURE, DISABLED }

/** A wake + small-message transport. Trusted only to wake and carry opaque ciphertext, never plaintext. */
interface PushTransport {
    suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome
}

/** No-op transport used when provider credentials are absent (dev runs rely on the WebSocket path). */
object DisabledPushTransport : PushTransport {
    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency) = PushOutcome.DISABLED
}
