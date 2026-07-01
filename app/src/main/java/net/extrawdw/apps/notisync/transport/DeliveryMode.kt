package net.extrawdw.apps.notisync.transport

import kotlinx.serialization.Serializable

/** Local diagnostic path for how an encrypted envelope reached this process. */
@Serializable
enum class DeliveryMode {
    UNKNOWN,
    WEBSOCKET,
    FCM_INLINE,
    FCM_RELAY_FETCH,

    /** The periodic RelayDrainWorker backstop — a message that sat queued (up to the 6h drain interval), so
     *  its delivery latency is unbounded. Kept distinct from the immediate FCM wake fetch above so a stale
     *  drained message doesn't skew live/wake e2e latency. */
    RELAY_DRAIN,
}

/** Null means "do not show a delivery label". */
fun DeliveryMode.ifKnown(): DeliveryMode? = takeUnless { it == DeliveryMode.UNKNOWN }
