package net.extrawdw.notisync.screen.desktop

import java.util.Base64
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenApplicationBridgeTest {
    @Test
    fun `request uses high exact capable unicast and opaque CBOR body`() {
        val daemon = RecordingDaemon()
        val bridge = ScreenApplicationBridge(daemon)
        assertEquals(ClientId("desktop-b"), bridge.register())
        val request = request()

        bridge.sendRequest(request)

        assertEquals("nsscreen", daemon.registration?.first)
        val send = daemon.sends.single()
        assertEquals(Urgency.HIGH, send.urgency)
        assertEquals(
            Recipients.OnlyCapable(request.sourcePeerId, request.requiredSourceCapabilities()),
            send.scope,
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(Base64.getDecoder().decode(send.body))
        val decodedRequest = requireNotNull(decoded.screenMirror)
        assertEquals(request.copy(routingToken = null, masterPsk = null), decodedRequest.copy(routingToken = null, masterPsk = null))
        assertArrayEquals(request.routingToken, decodedRequest.routingToken)
        assertArrayEquals(request.masterPsk, decodedRequest.masterPsk)
        assertTrue(Capability.SCREEN_MIRROR_ENCODER_H264_HW in request.requiredSourceCapabilities())
    }

    @Test
    fun `receive interest is installed for kind and exact session`() {
        val daemon = RecordingDaemon()
        val bridge = ScreenApplicationBridge(daemon)
        bridge.openSessionStream("screen:one").close()

        val request = daemon.receiveRequests.single()
        assertEquals(2, request.filters.size)
        assertEquals("/kind", request.filters[0].path)
        assertEquals("/screenMirror/sessionId", request.filters[1].path)
        assertEquals(listOf(request), daemon.unregistered)
    }

    @Test
    fun `pending cancellation and active end use normal exact unicast`() {
        val daemon = RecordingDaemon()
        val bridge = ScreenApplicationBridge(daemon)
        val request = request()

        bridge.sendCancel(request, "pending viewer stopped")
        bridge.sendEnd(request, "active viewer stopped")

        assertEquals(2, daemon.sends.size)
        daemon.sends.forEach { send ->
            assertEquals(Urgency.NORMAL, send.urgency)
            assertEquals(Recipients.Only(request.sourcePeerId), send.scope)
        }
        val cancel = decodeScreen(daemon.sends[0])
        assertEquals(ScreenMirrorAction.CANCEL, cancel.action)
        assertEquals(null, cancel.status)
        val end = decodeScreen(daemon.sends[1])
        assertEquals(ScreenMirrorAction.END, end.action)
        assertEquals(ScreenMirrorStatus.ENDED, end.status)
        assertEquals("screen/${request.sessionId}/cancel", daemon.sends[0].submissionId)
        assertEquals("screen/${request.sessionId}/end", daemon.sends[1].submissionId)
    }

    private fun request() = ScreenMirrorSync(
        action = ScreenMirrorAction.REQUEST,
        sessionId = "screen:one",
        requesterPeerId = ClientId("desktop-b"),
        sourcePeerId = ClientId("android-a"),
        issuedAt = 1_000,
        expiresAt = 301_000,
        routingToken = ByteArray(16) { 1 },
        masterPsk = ByteArray(32) { 2 },
        codec = ScreenMirrorCodec.H264,
        requestControl = true,
        requestClipboard = true,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
        candidates = listOf(ScreenMirrorConnectionCandidate("LAN_TCP", "192.0.2.1", 12345)),
    )

    private fun decodeScreen(send: SendRequest): ScreenMirrorSync = requireNotNull(
        ProtocolCodec.decodeFromCbor<DataSync>(Base64.getDecoder().decode(send.body)).screenMirror,
    )

    private class RecordingDaemon : DaemonLocalApi {
        var registration: Pair<String, ApplicationRegistrationRequest>? = null
        val sends = mutableListOf<SendRequest>()
        val receiveRequests = mutableListOf<ReceiveRequest>()
        val unregistered = mutableListOf<ReceiveRequest>()

        override fun status() = DaemonStatus(
            version = "1",
            clientId = "desktop-b",
            connectionState = DaemonConnectionState.CONNECTED,
        )

        override fun devices() = DeviceListResponse(emptyList())

        override fun putApplication(applicationId: String, request: ApplicationRegistrationRequest): ApplicationView {
            registration = applicationId to request
            return ApplicationView(applicationId, request.displayName, updatedAtEpochMillis = 1)
        }

        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit
        override fun send(request: SendRequest): SendAccepted {
            sends += request
            return SendAccepted("message", 1, request.submissionId)
        }
        override fun sendAll(requests: List<SendRequest>) = requests.map(::send)
        override fun openReceive(request: ReceiveRequest): ReceiveStream {
            receiveRequests += request
            return object : ReceiveStream {
                override fun next(): ReceiveRecord? = null
                override fun close() = Unit
            }
        }
        override fun unregisterReceive(request: ReceiveRequest) {
            unregistered += request
        }
        override fun ack(applicationId: String, envelopeId: String) = Unit
        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) = Unit
    }
}
