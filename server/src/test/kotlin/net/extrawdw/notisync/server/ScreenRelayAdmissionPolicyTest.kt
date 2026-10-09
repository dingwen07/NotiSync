package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayJoin
import net.extrawdw.notisync.protocol.ScreenRelayRole
import net.extrawdw.notisync.server.delivery.ScreenRelayAdmissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenRelayAdmissionPolicyTest {
    private val now = 1_800_000_000_000L
    private val requester = ClientId("requester")
    private val source = ClientId("source")

    @Test
    fun acceptsFullLifetimeWithRequesterClockAheadOrBehind() {
        for (offset in listOf(-120_000L, -700L, 0L, 2_100L, 120_000L)) {
            for (role in ScreenRelayRole.entries) {
                val join = join(now + 300_000L + offset, role)
                assertNull(
                    "clock offset=$offset, role=$role",
                    ScreenRelayAdmissionPolicy.rejection(join, principal(role), true, now),
                )
            }
        }
    }

    @Test
    fun rejectsExpiredAndExcessivelyFutureRequestsEvenWithoutSecurity() {
        for (expiresAt in listOf(now - 1L, now, now + 420_001L, Long.MAX_VALUE)) {
            for (securityEnabled in listOf(false, true)) {
                assertEquals(
                    "expiry=$expiresAt, security=$securityEnabled",
                    "bad_expiry",
                    ScreenRelayAdmissionPolicy.rejection(join(expiresAt), requester, securityEnabled, now),
                )
            }
        }
        assertNull(ScreenRelayAdmissionPolicy.rejection(join(now + 1L), requester, true, now))
    }

    @Test
    fun skewAllowanceDoesNotBypassRoleOwnership() {
        for (role in ScreenRelayRole.entries) {
            val otherPeer = if (role == ScreenRelayRole.REQUESTER) source else requester
            assertEquals(
                "client_mismatch",
                ScreenRelayAdmissionPolicy.rejection(join(now + 302_100L, role), otherPeer, true, now),
            )
        }
    }

    @Test
    fun rendezvousWaitRemainsCappedAtFiveMinutes() {
        assertEquals(300_000L, ScreenRelayAdmissionPolicy.rendezvousTimeoutMillis(now + 420_000L, now))
        assertEquals(300_000L, ScreenRelayAdmissionPolicy.rendezvousTimeoutMillis(now + 302_100L, now))
        assertEquals(180_000L, ScreenRelayAdmissionPolicy.rendezvousTimeoutMillis(now + 180_000L, now))
        assertEquals(1L, ScreenRelayAdmissionPolicy.rendezvousTimeoutMillis(now, now))
        assertEquals(1L, ScreenRelayAdmissionPolicy.rendezvousTimeoutMillis(now - 1L, now))
    }

    private fun principal(role: ScreenRelayRole): ClientId =
        if (role == ScreenRelayRole.REQUESTER) requester else source

    private fun join(expiresAt: Long, role: ScreenRelayRole = ScreenRelayRole.REQUESTER) = ScreenRelayJoin(
        relayId = "abcdefghijklmnopqrstuvwxABCDEFGH",
        requesterPeerId = requester,
        sourcePeerId = source,
        role = role,
        channel = ScreenRelayChannel.VIDEO,
        expiresAt = expiresAt,
    )
}
