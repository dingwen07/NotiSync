package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency

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

/**
 * Fans a wake out to the transport that owns the route's [TransportType] (FCM, APNs, …). The composition
 * root for push: each provider stays in its own file ([FcmPushTransport], [ApnsPushTransport]); this only
 * routes by transport and reports DISABLED when no provider is configured for it.
 */
class CompositePushTransport(private val delegates: Map<TransportType, PushTransport>) : PushTransport {
    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome =
        delegates[route.transport]?.wake(route, data, urgency) ?: PushOutcome.DISABLED

    companion object {
        fun create(config: ServerConfig): CompositePushTransport {
            val delegates = buildMap {
                FcmPushTransport.createOrNull(config)?.let { put(TransportType.FCM, it) }
                ApnsPushTransport.createOrNull(config)?.let { put(TransportType.APNS, it) }
            }
            return CompositePushTransport(delegates)
        }
    }
}
