package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

/** A wake/delivery mechanism. Push and live transports all map to the same route model. */
@Serializable
enum class TransportType { FCM, WEBSOCKET, APNS, WEBPUSH }

@Serializable
enum class RouteEnvironment { PRODUCTION, DEVELOPMENT }

@Serializable
data class RouteCapabilities(
    @CborLabel(0) val inlinePayloadLimitBytes: Int,
    @CborLabel(1) val canWake: Boolean = true,
    @CborLabel(2) val canDeliverInline: Boolean = true,
    @CborLabel(3) val supportsCollapse: Boolean = false,
)

/**
 * A signed statement: "this route currently belongs to me." Peers and the broker may cache and
 * relay route claims, but the broker accepts a claim only if it is signed by the client that owns
 * the route. [epoch] increases when a client's route changes, so stale/invalid routes can be
 * distinguished from a newer claim.
 */
@Serializable
data class RouteClaim(
    @CborLabel(0) val suite: String = CipherSuite.CURRENT_ID,
    @CborLabel(1) val clientId: ClientId,
    @CborLabel(2) val transport: TransportType,
    @CborLabel(3) val environment: RouteEnvironment,
    /** Opaque transport endpoint: an FCM registration token, APNs device token, WS session id, etc.
     * Empty means "clear my existing route for this transport" on the broker. */
    @CborLabel(4) val routeRef: String,
    @CborLabel(5) val capabilities: RouteCapabilities,
    @CborLabel(6) val epoch: Int,
    @CborLabel(7) val issuedAt: Long,
)

/** How the broker currently regards a route for a target client. */
@Serializable
enum class RouteState {
    AVAILABLE,          // a usable route claim is cached
    UNAVAILABLE,        // no usable route claim — ask a reachable peer for one
    STALE,              // may work, but request a newer claim
    INVALID,            // the push provider rejected the route as no longer valid
    REJECTED,           // provider rejected it as malformed/mismatched for this app/environment
    TRANSIENT_FAILURE,  // temporary provider/network failure
    RATE_LIMITED,       // provider asked us to slow down
}
