package net.extrawdw.apps.notisync.ios

import java.util.UUID

/** Data Source is optional. Only the confirmed Notification Source write completes ANCS setup. */
internal enum class AncsSubscription(val characteristic: UUID? = null) {
    UNAVAILABLE, DATA_SOURCE(Ancs.DATA_SOURCE), NOTIFICATION_SOURCE(Ancs.NOTIFICATION_SOURCE), SHARING;

    fun acknowledged(uuid: UUID?): AncsSubscription = when {
        uuid == null || uuid != characteristic -> this
        this == DATA_SOURCE -> NOTIFICATION_SOURCE
        else -> SHARING
    }

    companion object {
        fun begin(hasDataSource: Boolean, hasNotificationSource: Boolean): AncsSubscription = when {
            !hasNotificationSource -> UNAVAILABLE
            hasDataSource -> DATA_SOURCE
            else -> NOTIFICATION_SOURCE
        }
    }
}
