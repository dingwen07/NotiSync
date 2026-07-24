package net.extrawdw.apps.notisync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRelayPolicyTest {
    private val now = 10_000_000L

    @Test
    fun defersNotificationAtInclusiveTwoHourBrokerAge() {
        assertTrue(
            shouldDeferRelayNotification(
                acceptedAt = now - STALE_RELAY_AGE_MS,
                now = now,
                notificationProducing = true,
            )
        )
    }

    @Test
    fun missingTimestampFreshMessageAndNonNotificationStayImmediate() {
        assertFalse(shouldDeferRelayNotification(null, now, notificationProducing = true))
        assertFalse(
            shouldDeferRelayNotification(
                acceptedAt = now - STALE_RELAY_AGE_MS + 1,
                now = now,
                notificationProducing = true,
            )
        )
        assertFalse(
            shouldDeferRelayNotification(
                acceptedAt = now - STALE_RELAY_AGE_MS - 1,
                now = now,
                notificationProducing = false,
            )
        )
    }

    @Test
    fun ringingCallAlreadyRejectedByFreshnessGuardStaysOnExistingImmediatePath() {
        assertFalse(
            shouldDeferRelayNotification(
                acceptedAt = now - STALE_RELAY_AGE_MS,
                now = now,
                notificationProducing = true,
                rejectedByCallFreshnessGuard = true,
            )
        )
    }
}
