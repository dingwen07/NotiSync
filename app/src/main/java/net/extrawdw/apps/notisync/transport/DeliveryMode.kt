package net.extrawdw.apps.notisync.transport

import kotlinx.serialization.Serializable

/** Local diagnostic path for how an encrypted envelope reached this process. */
@Serializable
enum class DeliveryMode {
    UNKNOWN,
    WEBSOCKET,
    FCM_INLINE,
    FCM_RELAY_FETCH,
}

/** Null means "do not show a delivery label". */
fun DeliveryMode.ifKnown(): DeliveryMode? = takeUnless { it == DeliveryMode.UNKNOWN }
