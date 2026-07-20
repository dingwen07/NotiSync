package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenMirrorRequesterTest {
    private val source = ClientId("source")
    private val requester = ClientId("requester")
    private val completeSource = setOf(
        Capability.CAPABILITY_ROUTING_V1,
        Capability.SCREEN_MIRROR_SOURCE_V1,
        Capability.SCREEN_MIRROR_CONTROL_V1,
        Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
        Capability.SCREEN_MIRROR_ENCODER_H264_HW,
        Capability.SCREEN_MIRROR_ENCODER_H265_HW,
        Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
    )

    @Test
    fun `automatic policy prefers hardware AV1 then hardware H265 then decodable H264`() {
        assertEquals(
            ScreenMirrorCodec.AV1,
            selectAndroidScreenCodec(
                completeSource,
                decoderSupport(
                    decodable = ScreenMirrorCodec.entries.toSet(),
                    hardware = setOf(ScreenMirrorCodec.AV1, ScreenMirrorCodec.H265, ScreenMirrorCodec.H264),
                ),
            ),
        )
        assertEquals(
            ScreenMirrorCodec.H265,
            selectAndroidScreenCodec(
                completeSource,
                decoderSupport(
                    decodable = ScreenMirrorCodec.entries.toSet(),
                    hardware = setOf(ScreenMirrorCodec.H265, ScreenMirrorCodec.H264),
                ),
            ),
        )
        assertEquals(
            ScreenMirrorCodec.H264,
            selectAndroidScreenCodec(
                completeSource,
                decoderSupport(
                    decodable = ScreenMirrorCodec.entries.toSet(),
                    hardware = setOf(ScreenMirrorCodec.H264),
                ),
            ),
        )
    }

    @Test
    fun `software AV1 and H265 do not outrank the H264 fallback`() {
        assertEquals(
            ScreenMirrorCodec.H264,
            selectAndroidScreenCodec(
                completeSource,
                decoderSupport(decodable = ScreenMirrorCodec.entries.toSet(), hardware = emptySet()),
            ),
        )
        assertEquals(
            setOf(ScreenMirrorCodec.H264),
            availableAndroidScreenCodecs(
                completeSource,
                decoderSupport(decodable = ScreenMirrorCodec.entries.toSet(), hardware = emptySet()),
            ),
        )
    }

    @Test
    fun `available override wins and stale override falls back to automatic`() {
        val support = decoderSupport(
            decodable = ScreenMirrorCodec.entries.toSet(),
            hardware = setOf(ScreenMirrorCodec.AV1, ScreenMirrorCodec.H265),
        )
        assertEquals(
            ScreenMirrorCodec.H264,
            selectAndroidScreenCodec(completeSource, support, ScreenMirrorCodec.H264),
        )
        assertEquals(
            ScreenMirrorCodec.H265,
            selectAndroidScreenCodec(
                completeSource - Capability.SCREEN_MIRROR_ENCODER_H264_HW -
                    Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
                support,
                ScreenMirrorCodec.AV1,
            ),
        )
    }

    @Test
    fun `requester refuses an incomplete source or missing compatible decoder`() {
        assertNull(
            selectAndroidScreenCodec(
                completeSource - Capability.SCREEN_MIRROR_CONTROL_V1,
                decoderSupport(setOf(ScreenMirrorCodec.H264), setOf(ScreenMirrorCodec.H264)),
            ),
        )
        assertNull(selectAndroidScreenCodec(completeSource, decoderSupport(emptySet(), emptySet())))
    }

    @Test
    fun `decoder probe distinguishes hardware from software and ignores encoders`() {
        val support = AndroidScreenDecoderCapabilities.supportFrom(
            listOf(
                AndroidScreenDecoderCapabilities.DecoderDescriptor(
                    encoder = false,
                    hardwareAccelerated = false,
                    supportedTypes = setOf("video/avc", "video/hevc"),
                ),
                AndroidScreenDecoderCapabilities.DecoderDescriptor(
                    name = "c2.vendor.av1.decoder",
                    encoder = false,
                    hardwareAccelerated = true,
                    supportedTypes = setOf("VIDEO/AV01"),
                ),
                AndroidScreenDecoderCapabilities.DecoderDescriptor(
                    encoder = true,
                    hardwareAccelerated = true,
                    supportedTypes = setOf("video/hevc"),
                ),
            ),
        )

        assertEquals(ScreenMirrorCodec.entries.toSet(), support.decodableCodecs)
        assertEquals(setOf(ScreenMirrorCodec.AV1), support.hardwareCodecs)
        assertEquals("c2.vendor.av1.decoder", support.hardwareDecoderName(ScreenMirrorCodec.AV1))
    }

    @Test
    fun `status policy binds signer roles session and signed timestamp`() {
        val now = 10_000_000L
        val ready = status(now)
        assertTrue(
            AndroidScreenRequesterStatusPolicy.accepts(
                ready,
                senderId = source,
                senderOwnDevice = true,
                envelopeCreatedAt = now,
                expectedSessionId = "screen:one",
                expectedSourceId = source,
                ownClientId = requester,
                now = now,
            ),
        )
        assertFalse(
            AndroidScreenRequesterStatusPolicy.accepts(
                ready.copy(sourcePeerId = ClientId("substituted")),
                source,
                true,
                now,
                "screen:one",
                source,
                requester,
                now,
            ),
        )
        assertFalse(
            AndroidScreenRequesterStatusPolicy.accepts(
                ready,
                source,
                senderOwnDevice = false,
                now,
                "screen:one",
                source,
                requester,
                now,
            ),
        )
        assertFalse(
            AndroidScreenRequesterStatusPolicy.accepts(
                ready,
                source,
                true,
                envelopeCreatedAt = now - 6 * 60_000,
                "screen:one",
                source,
                requester,
                now,
            ),
        )
    }

    private fun status(now: Long) = ScreenMirrorSync(
        action = ScreenMirrorAction.STATUS,
        sessionId = "screen:one",
        requesterPeerId = requester,
        sourcePeerId = source,
        issuedAt = now,
        status = ScreenMirrorStatus.READY,
    )

    private fun decoderSupport(
        decodable: Set<ScreenMirrorCodec>,
        hardware: Set<ScreenMirrorCodec>,
    ) = AndroidScreenDecoderSupport(decodable, hardware)
}
