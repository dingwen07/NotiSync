package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareScreenEncoderProbeTest {
    @Test
    fun onlyHardwareEncodersAreReported() {
        val result = HardwareScreenEncoderProbe.codecsFrom(
            listOf(
                EncoderDescriptor(true, true, setOf("video/avc", "video/hevc")),
                EncoderDescriptor(true, false, setOf("video/av01")),
                EncoderDescriptor(false, true, setOf("video/av01")),
            ),
        )

        assertEquals(setOf(ScreenMirrorCodec.H264, ScreenMirrorCodec.H265), result)
    }

    @Test
    fun advertisementDependsOnOptInAndNormalProcessHardwareEncoders() {
        val hardware = setOf(ScreenMirrorCodec.H264)
        assertTrue(
            screenMirrorCapabilitiesFor(
                enabled = false,
                hardwareCodecs = hardware,
            ).isEmpty(),
        )
        assertTrue(
            screenMirrorCapabilitiesFor(
                enabled = true,
                hardwareCodecs = emptySet(),
            ).isEmpty(),
        )
        assertEquals(
            listOf(
                Capability.SCREEN_MIRROR_SOURCE_V1,
                Capability.SCREEN_MIRROR_CONTROL_V1,
                Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1,
                Capability.SCREEN_MIRROR_BROKER_RELAY_V1,
                Capability.SCREEN_MIRROR_ENCODER_H264_HW,
            ),
            screenMirrorCapabilitiesFor(
                enabled = true,
                hardwareCodecs = hardware,
            ),
        )
    }

    @Test
    fun advertisementIsStableAndOrdersHardwareCodecCapabilities() {
        val expected = listOf(
            Capability.SCREEN_MIRROR_SOURCE_V1,
            Capability.SCREEN_MIRROR_CONTROL_V1,
            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
            Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1,
            Capability.SCREEN_MIRROR_BROKER_RELAY_V1,
            Capability.SCREEN_MIRROR_ENCODER_H264_HW,
            Capability.SCREEN_MIRROR_ENCODER_H265_HW,
            Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
        )

        assertEquals(
            expected,
            screenMirrorCapabilitiesFor(
                enabled = true,
                hardwareCodecs = setOf(
                    ScreenMirrorCodec.AV1,
                    ScreenMirrorCodec.H264,
                    ScreenMirrorCodec.H265,
                ),
            ),
        )
        // There is intentionally no Shizuku/readiness input: transient runtime failures cannot alter this.
        assertEquals(
            expected,
            screenMirrorCapabilitiesFor(
                enabled = true,
                hardwareCodecs = setOf(
                    ScreenMirrorCodec.H265,
                    ScreenMirrorCodec.AV1,
                    ScreenMirrorCodec.H264,
                ),
            ),
        )
    }
}
