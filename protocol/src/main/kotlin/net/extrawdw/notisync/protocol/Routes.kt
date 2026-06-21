package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable

/** A wake/delivery mechanism. FCM is the first Android transport; others map to the same model. */
@Serializable
enum class TransportType { FCM, WEBSOCKET, APNS, WEBPUSH }

@Serializable
enum class RouteEnvironment { PRODUCTION, DEVELOPMENT }

@Serializable
data class RouteCapabilities(
    val inlinePayloadLimitBytes: Int,
    val canWake: Boolean = true,
    val canDeliverInline: Boolean = true,
    val supportsCollapse: Boolean = false,
)

/**
 * A signed statement: "this route currently belongs to me." Peers and the broker may cache and
 * relay route claims, but the broker accepts a claim only if it is signed by the client that owns
 * the route. [epoch] increases when a client's route changes, so stale/invalid routes can be
 * distinguished from a newer claim.
 */
@Serializable
data class RouteClaim(
    val suite: String = CipherSuite.CURRENT_ID,
    val clientId: ClientId,
    val transport: TransportType,
    val environment: RouteEnvironment,
    /** Opaque transport endpoint: an FCM direct-send target, a WS session id, etc. */
    val routeRef: String,
    val capabilities: RouteCapabilities,
    val epoch: Int,
    val issuedAt: Long,
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
