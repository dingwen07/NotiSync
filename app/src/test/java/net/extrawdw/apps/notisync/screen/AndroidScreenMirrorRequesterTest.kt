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
    fun `requester prefers H264 then falls through locally decodable codecs`() {
        assertEquals(
            ScreenMirrorCodec.H264,
            selectAndroidScreenCodec(completeSource, ScreenMirrorCodec.entries.toSet()),
        )
        assertEquals(
            ScreenMirrorCodec.H265,
            selectAndroidScreenCodec(
                completeSource,
                setOf(ScreenMirrorCodec.H265, ScreenMirrorCodec.AV1),
            ),
        )
        assertEquals(
            ScreenMirrorCodec.AV1,
            selectAndroidScreenCodec(completeSource, setOf(ScreenMirrorCodec.AV1)),
        )
    }

    @Test
    fun `requester refuses an incomplete source or missing local decoder`() {
        assertNull(
            selectAndroidScreenCodec(
                completeSource - Capability.SCREEN_MIRROR_CONTROL_V1,
                setOf(ScreenMirrorCodec.H264),
            ),
        )
        assertNull(selectAndroidScreenCodec(completeSource, emptySet()))
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
}
