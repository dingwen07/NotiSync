package net.extrawdw.notisync.server.delivery.push

import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.delivery.PushOutcome
import net.extrawdw.notisync.server.delivery.PushTransport
import net.extrawdw.notisync.server.data.StoredRoute

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
