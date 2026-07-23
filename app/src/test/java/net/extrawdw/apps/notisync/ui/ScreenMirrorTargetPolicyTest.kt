package net.extrawdw.apps.notisync.ui

import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorTargetPolicyTest {
    @Test
    fun supportedTrustedOwnSourceIsActionable() {
        assertTrue(device().supportsScreenMirrorRequest())
    }

    @Test
    fun capabilityAdvertisementRemainsActionableWithoutPlatformHint() {
        assertTrue(device(platform = null).supportsScreenMirrorRequest())
    }

    @Test
    fun everyIdentityAndTrustGateIsRequired() {
        assertFalse(device(ownDevice = false).supportsScreenMirrorRequest())
        assertFalse(device(status = TrustStatus.PENDING_TRUST).supportsScreenMirrorRequest())
        assertFalse(device(keyAvailable = false).supportsScreenMirrorRequest())
        assertFalse(device(verified = false).supportsScreenMirrorRequest())
        assertFalse(device(currentEpoch = 0).supportsScreenMirrorRequest())
    }

    @Test
    fun completeProtocolAndAtLeastOneHardwareEncoderAreRequired() {
        REQUIRED.forEach { missing ->
            assertFalse(device(capabilities = FULL_CAPABILITIES - missing).supportsScreenMirrorRequest())
        }
        ENCODERS.forEach { encoder ->
            assertTrue(device(capabilities = REQUIRED + encoder).supportsScreenMirrorRequest())
        }
        assertFalse(device(capabilities = REQUIRED).supportsScreenMirrorRequest())
    }

    private fun device(
        ownDevice: Boolean = true,
        status: TrustStatus = TrustStatus.TRUSTED,
        keyAvailable: Boolean = true,
        verified: Boolean = true,
        currentEpoch: Int = 2,
        platform: String? = "android",
        capabilities: Set<Capability> = FULL_CAPABILITIES,
    ) = RosterDevice(
        clientId = ClientId("screen-source"),
        status = status,
        displayName = "Screen source",
        keyAvailable = keyAvailable,
        introducedByName = null,
        revokedAt = null,
        ownDevice = ownDevice,
        currentEpoch = currentEpoch,
        platform = platform,
        capabilities = capabilities.toList(),
        verified = verified,
    )

    private companion object {
        val REQUIRED = setOf(
            Capability.CAPABILITY_ROUTING_V1,
            Capability.SCREEN_MIRROR_SOURCE_V1,
            Capability.SCREEN_MIRROR_CONTROL_V1,
            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
        )
        val ENCODERS = setOf(
            Capability.SCREEN_MIRROR_ENCODER_H264_HW,
            Capability.SCREEN_MIRROR_ENCODER_H265_HW,
            Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
        )
        val FULL_CAPABILITIES = REQUIRED + ENCODERS
    }
}
