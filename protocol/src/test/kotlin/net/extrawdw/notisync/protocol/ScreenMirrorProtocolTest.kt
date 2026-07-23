package net.extrawdw.notisync.protocol

import net.extrawdw.notisync.peer.channel.Recipients
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorProtocolTest {
    @Test
    fun screenMirrorRequest_roundTripsThroughCompactCbor() {
        val request = request()
        val body = DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = request)

        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))

        assertEquals(DataSyncKind.SCREEN_MIRRORING, decoded.kind)
        val mirror = requireNotNull(decoded.screenMirror)
        assertEquals(request.action, mirror.action)
        assertEquals(request.protocolVersion, mirror.protocolVersion)
        assertEquals(request.sessionId, mirror.sessionId)
        assertEquals(request.requesterPeerId, mirror.requesterPeerId)
        assertEquals(request.sourcePeerId, mirror.sourcePeerId)
        assertArrayEquals(request.routingToken, mirror.routingToken)
        assertArrayEquals(request.masterPsk, mirror.masterPsk)
        assertEquals(request.codec, mirror.codec)
        assertEquals(request.candidates, mirror.candidates)
    }

    @Test
    fun requestCapabilities_requireCompleteV1SourceAndSelectedCodec() {
        val request = request()

        assertEquals(
            setOf(
                Capability.CAPABILITY_ROUTING_V1,
                Capability.SCREEN_MIRROR_SOURCE_V1,
                Capability.SCREEN_MIRROR_CONTROL_V1,
                Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                Capability.SCREEN_MIRROR_ENCODER_H265_HW,
            ),
            request.requiredSourceCapabilities(),
        )
        assertEquals(
            request.requiredSourceCapabilities(),
            request.copy(requestControl = false, requestClipboard = false).requiredSourceCapabilities(),
        )
        assertTrue(
            ScreenMirrorSync(
                action = ScreenMirrorAction.STATUS,
                sessionId = "session",
                requesterPeerId = ClientId("requester"),
                sourcePeerId = ClientId("source"),
                issuedAt = 1,
            ).requiredSourceCapabilities().isEmpty(),
        )
    }

    @Test
    fun capableUnicast_roundTripsThroughJson() {
        val request = request()
        val selector: Recipients = Recipients.OnlyCapable(
            request.sourcePeerId,
            request.requiredSourceCapabilities(),
        )

        assertEquals(
            selector,
            ProtocolCodec.decodeFromJson<Recipients>(ProtocolCodec.encodeToJson(selector)),
        )
    }

    private fun request() = ScreenMirrorSync(
        action = ScreenMirrorAction.REQUEST,
        sessionId = "session-1",
        requesterPeerId = ClientId("requester"),
        sourcePeerId = ClientId("source"),
        issuedAt = 1_000,
        expiresAt = 301_000,
        routingToken = ByteArray(16) { it.toByte() },
        masterPsk = ByteArray(32) { (it + 16).toByte() },
        codec = ScreenMirrorCodec.H265,
        requestControl = true,
        requestClipboard = true,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
        candidates = listOf(
            ScreenMirrorConnectionCandidate(
                kind = ScreenMirrorConnectionCandidate.LAN_TCP,
                host = "192.0.2.10",
                port = 27_171,
            ),
            ScreenMirrorConnectionCandidate(
                kind = ScreenMirrorConnectionCandidate.DNS_SD,
                serviceName = "random._notisync-screen._tcp",
            ),
        ),
    )
}
