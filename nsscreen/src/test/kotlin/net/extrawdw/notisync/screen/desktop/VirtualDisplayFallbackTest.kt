package net.extrawdw.notisync.screen.desktop

import java.io.IOException
import java.io.StringReader
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.ScreenVirtualDisplay
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.SecureChannelPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayFallbackTest {
    @Test
    fun `virtual grant denial retries once with fresh physical request and closes old resources`() {
        val fixture = Fixture(ScreenMirrorStatus.VIRTUAL_DISPLAY_UNAUTHORIZED)
        fixture.connect()
        val (virtual, physical) = fixture.daemon.requests
        assertEquals(2, virtual.protocolVersion)
        assertNotNull(virtual.virtualDisplay)
        assertEquals(1, physical.protocolVersion)
        assertNull(physical.virtualDisplay)
        assertNotEquals(virtual.sessionId, physical.sessionId)
        assertFalse(virtual.routingToken!!.contentEquals(physical.routingToken!!))
        assertFalse(virtual.masterPsk!!.contentEquals(physical.masterPsk!!))
        assertEquals(virtual.sourcePeerId, physical.sourcePeerId)
        assertEquals(virtual.codec, physical.codec)
        assertEquals(2, fixture.listeners.size)
        assertTrue(fixture.listeners.all { it.closed.count == 0L })
        assertEquals(2, fixture.daemon.closedStreams)
        // The physical retry is deliberately denied: it must still respect normal authorization.
        assertTrue(fixture.output.contains("regular screen sharing"))
    }

    @Test
    fun `ordinary authorization and backend errors never retry`() {
        for (status in listOf(ScreenMirrorStatus.UNAUTHORIZED, ScreenMirrorStatus.EXPIRED,
            ScreenMirrorStatus.SHIZUKU_UNAVAILABLE, ScreenMirrorStatus.CODEC_START_FAILED)) {
            val fixture = Fixture(status)
            fixture.connect()
            assertEquals(status.name, 1, fixture.daemon.requests.size)
        }
    }

    @Test
    fun `terminal end cannot request physical fallback`() {
        val fixture = Fixture(ScreenMirrorStatus.VIRTUAL_DISPLAY_UNAUTHORIZED, ScreenMirrorAction.END)
        fixture.connect()
        assertEquals(1, fixture.daemon.requests.size)
    }

    private class Fixture(status: ScreenMirrorStatus, action: ScreenMirrorAction = ScreenMirrorAction.STATUS) {
        val daemon = RejectingDaemon(status, action)
        val listeners = mutableListOf<WaitingListener>()
        val output = StringBuilder()
        fun connect() {
            val runner = NSScreenRunner(
                daemonConnector = { daemon },
                helper = { _, _, _ -> error("Rejected requests must never open a viewer") },
                listenerFactory = { WaitingListener().also { listeners += it } },
                dnsAdvertiser = { _, _ -> null },
            )
            assertThrows(IOException::class.java) {
                runner.connect(ConnectOptions(deviceId = "source", codec = ScreenMirrorCodec.H264,
                    virtualDisplay = ScreenVirtualDisplay()), output, StringReader("").buffered(), false)
            }
        }
    }

    private class WaitingListener : ScreenSessionListener {
        val closed = CountDownLatch(1)
        override val candidates = listOf(ScreenConnectionCandidate("LAN_TCP", "192.0.2.1", 12345))
        override fun acceptPair(sessionId: String, registry: PskRegistry, timeout: Duration,
            handshakeTimeout: Duration, maximumAcceptedSockets: Int): SecureChannelPair {
            check(closed.await(5, TimeUnit.SECONDS)) { "Listener was not closed after rejection" }
            throw IOException("listener closed")
        }
        override fun close() = closed.countDown()
    }

    private class RejectingDaemon(
        val firstStatus: ScreenMirrorStatus,
        val firstAction: ScreenMirrorAction,
    ) : DaemonLocalApi {
        val requests = mutableListOf<ScreenMirrorSync>()
        private var records = LinkedBlockingQueue<ReceiveRecord>()
        var closedStreams = 0
        override fun devices() = DeviceListResponse(listOf(DeviceView(
            clientId = "source", name = "Source", classification = DeviceClassification.OWN,
            trustStatus = DeviceTrustStatus.TRUSTED, identityFingerprint = "fingerprint", keyAvailable = true,
            verified = true, capabilities = setOf(Capability.CAPABILITY_ROUTING_V1,
                Capability.SCREEN_MIRROR_SOURCE_V1, Capability.SCREEN_MIRROR_CONTROL_V1,
                Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1, Capability.SCREEN_MIRROR_ENCODER_H264_HW,
                Capability.SCREEN_VIRTUAL_DISPLAY_V1).mapTo(mutableSetOf()) { it.name },
        )))
        override fun status() = DaemonStatus("1", "requester", connectionState = DaemonConnectionState.CONNECTED)
        override fun putApplication(applicationId: String, request: ApplicationRegistrationRequest) =
            ApplicationView(applicationId, request.displayName, updatedAtEpochMillis = 1)
        override fun send(request: SendRequest): SendAccepted {
            val screen = ProtocolCodec.decodeFromCbor<DataSync>(Base64.getDecoder().decode(request.body)).screenMirror!!
            if (screen.action == ScreenMirrorAction.REQUEST) {
                requests += screen
                val now = System.currentTimeMillis()
                val response = ScreenMirrorSync(
                    action = if (requests.size == 1) firstAction else ScreenMirrorAction.STATUS,
                    protocolVersion = screen.protocolVersion, sessionId = screen.sessionId,
                    requesterPeerId = screen.requesterPeerId, sourcePeerId = screen.sourcePeerId,
                    issuedAt = now, status = if (requests.size == 1) firstStatus else ScreenMirrorStatus.UNAUTHORIZED,
                )
                records.put(ReceiveRecord(
                    ReceiveRecordType.MESSAGE, "nsscreen", envelopeId = "response-${requests.size}",
                    messageType = MessageType.DATA_SYNC,
                    body = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(
                        DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = response))),
                    senderClientId = "source", senderOwnDevice = true, receivedAtEpochMillis = now,
                    envelopeCreatedAtEpochMillis = now,
                ))
            }
            return SendAccepted("sent", 1, request.submissionId)
        }
        override fun openReceive(request: ReceiveRequest): ReceiveStream {
            val queue = LinkedBlockingQueue<ReceiveRecord>().also { records = it }
            return object : ReceiveStream {
                private var closed = false
                override fun next(): ReceiveRecord? = queue.poll(3, TimeUnit.SECONDS)
                override fun close() { if (!closed) { closed = true; closedStreams++ } }
            }
        }
        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit
        override fun sendAll(requests: List<SendRequest>) = requests.map(::send)
        override fun unregisterReceive(request: ReceiveRequest) = Unit
        override fun ack(applicationId: String, envelopeId: String) = Unit
        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) = Unit
    }
}
