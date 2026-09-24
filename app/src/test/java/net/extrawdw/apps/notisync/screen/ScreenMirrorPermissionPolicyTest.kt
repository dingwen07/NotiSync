package net.extrawdw.apps.notisync.screen

import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorPermissionPolicyTest {
    private val peer = RosterDevice(
        clientId = ClientId("controller"),
        status = TrustStatus.TRUSTED,
        displayName = "My computer",
        keyAvailable = true,
        introducedByName = null,
        revokedAt = null,
        ownDevice = true,
        verified = true,
    )

    @Test
    fun permissionRequiresCurrentTrustedOwnDeviceAndEnabledSharing() {
        assertTrue(canAuthorizeScreenControl(peer, enabled = true, quarantined = false))
        assertFalse(canAuthorizeScreenControl(peer, enabled = false, quarantined = false))
        assertFalse(canAuthorizeScreenControl(peer, enabled = true, quarantined = true))
        assertFalse(canAuthorizeScreenControl(null, enabled = true, quarantined = false))
        assertFalse(canAuthorizeScreenControl(peer.copy(ownDevice = false), true, false))
        assertFalse(canAuthorizeScreenControl(peer.copy(verified = false), true, false))
        TrustStatus.entries.filter { it != TrustStatus.TRUSTED }.forEach { status ->
            assertFalse(canAuthorizeScreenControl(peer.copy(status = status), true, false))
        }
    }
}
