package net.extrawdw.notisync.run

import java.util.Base64
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunApplicationBridgeTest {
    @Test
    fun `registers application and opens full-name targeted interests before publication`() {
        val daemon = FakeDaemon()
        val bridge = RunApplicationBridge.connect(daemon, "run-1")

        bridge.openInitialStreams().close()

        assertEquals(setOf(Capability.CAPTURE, Capability.PUBLISH_RUNS), daemon.registration?.capabilities)
        assertEquals(listOf(MessageType.DATA_SYNC), daemon.receives[0].messageTypes)
        assertEquals(
            listOf("/kind", "/run/kind", "/run/control/hostClientId", "/run/control/runId"),
            daemon.receives[0].filters.map { it.path },
        )
        assertEquals(listOf(MessageType.ACTION), daemon.receives[1].messageTypes)
        assertEquals("/sourceKey", daemon.receives[1].filters.single().path)
        assertTrue(bridge.sourceKey.startsWith("run:"))
        assertEquals("desktop", bridge.hostClientId.value)
    }

    @Test
    fun `publishes native first and iOS fallback atomically with independent queue lanes`() {
        val daemon = FakeDaemon()
        val bridge = RunApplicationBridge.connect(daemon, "run-1")

        assertTrue(bridge.publish(state()))

        val request = daemon.batches.single()
        assertEquals(listOf(MessageType.DATA_SYNC, MessageType.NOTIFICATION), request.map { it.messageType })
        val native = request[0]
        val sync = decode<DataSync>(native.body)
        assertEquals(DataSyncKind.RUN, sync.kind)
        assertEquals(RunSyncKind.STATE, sync.run?.kind)
        assertEquals(state(), sync.run?.state)
        val nativeScope = native.scope as Recipients.OwnMeshFiltered
        assertEquals(
            setOf(
                Capability.RECEIVE_RUNS,
                Capability.DISPLAY,
                Capability.BACKGROUND_WAKE,
                Capability.PUSH_FILTERING,
            ),
            nativeScope.requiredCapabilities,
        )
        assertTrue(nativeScope.requireCapabilityRoutingV1)
        assertEquals(Urgency.HIGH, native.urgency)
        assertEquals("${bridge.sourceKey}/native/1", native.submissionId)
        assertEquals("${bridge.sourceKey}/native", native.queuePolicy?.streamKey)
        assertEquals(1L, native.queuePolicy?.sequence)

        val fallback = request[1]
        val notification = decode<CapturedNotification>(fallback.body)
        assertEquals(bridge.sourceKey, notification.sourceKey)
        assertEquals(ClientId("desktop"), notification.sourceClientId)
        assertEquals(listOf("Interrupt", "Terminate"), notification.actions.map { it.title })
        assertTrue(notification.actions.all { !it.actionToken.isNullOrBlank() })
        val fallbackScope = fallback.scope as Recipients.OwnMeshFiltered
        assertEquals(setOf(Capability.DISPLAY, Capability.BACKGROUND_WAKE), fallbackScope.requiredCapabilities)
        assertEquals(setOf(Capability.PUSH_FILTERING), fallbackScope.forbiddenCapabilities)
        assertEquals(Urgency.HIGH, fallback.urgency)
        assertEquals("${bridge.sourceKey}/ios/1", fallback.submissionId)
    }

    @Test
    fun `periodic state is normal native-only while lifecycle state is high`() {
        val daemon = FakeDaemon()
        val bridge = RunApplicationBridge.connect(daemon, "run-1")

        bridge.publish(state().copy(revision = 2, updateReason = RunUpdateReason.PERIODIC))

        assertEquals(1, daemon.singles.size)
        assertEquals(MessageType.DATA_SYNC, daemon.singles.single().messageType)
        assertEquals(Urgency.NORMAL, daemon.singles.single().urgency)
        assertEquals("${bridge.sourceKey}/native/2", daemon.singles.single().submissionId)
    }

    @Test
    fun `compatibility actions require own sender exact source generation title and constant-time token`() {
        val daemon = FakeDaemon()
        val bridge = RunApplicationBridge.connect(daemon, "run-1")
        val blocked = state().copy(
            revision = 2,
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            blockedReason = RunBlockedReason.TERMINAL_INPUT,
            prompt = RunPromptKind.TEXT,
            interactionGeneration = 1,
        )
        val notification = decode<CapturedNotification>(requireNotNull(bridge.iosNotificationSend(blocked)).body)
        val input = notification.actions.first { it.title == "Input" }
        val event = ActionEvent(
            sourceClientId = bridge.hostClientId,
            sourceKey = bridge.sourceKey,
            kind = ActionKind.PERFORM,
            actionIndex = input.index,
            actionTitle = input.title,
            remoteInputText = "answer",
            actedAt = 2_000,
            actionGeneration = input.actionGeneration,
            actionToken = input.actionToken,
        )
        val inbound = bridge.decode(actionRecord(event)) as RunInbound.Action

        assertEquals(RunActionCommand("input", "answer"), bridge.validateAction(inbound, 1))
        assertNull(bridge.validateAction(inbound, 2))
        assertNull(
            bridge.validateAction(
                inbound.copy(event = event.copy(actionToken = "forged")),
                1,
            ),
        )
        assertNull(
            bridge.validateAction(
                inbound.copy(event = event.copy(remoteInputText = "😀".repeat(16_385))),
                1,
            ),
        )
        assertNull(bridge.validateAction(inbound.copy(senderOwnDevice = false), 1))
    }

    @Test
    fun `control results are normal unicast append-only submissions`() {
        val bridge = RunApplicationBridge.connect(FakeDaemon(), "run-1")
        val result = RunControlResult(
            requestId = "123e4567-e89b-12d3-a456-426614174000",
            runId = "run-1",
            status = RunControlResultStatus.APPLIED,
            respondedAt = 2_000,
        )

        val send = bridge.controlResultSend(ClientId("android"), result)

        assertEquals(Recipients.Only(ClientId("android")), send.scope)
        assertEquals(Urgency.NORMAL, send.urgency)
        assertNull(send.queuePolicy)
        assertEquals("${bridge.sourceKey}/control/${result.requestId}/result", send.submissionId)
        val sync = decode<DataSync>(send.body)
        assertEquals(result, sync.run?.controlResult)
    }

    private fun actionRecord(event: ActionEvent) = ReceiveRecord(
        recordType = ReceiveRecordType.MESSAGE,
        applicationId = "nsrun",
        envelopeId = "envelope",
        messageType = MessageType.ACTION,
        body = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(event)),
        senderClientId = "android",
        senderOwnDevice = true,
        signerEpoch = 1,
        deliveryMode = "RELAY",
        receivedAtEpochMillis = 2_000,
    )

    private fun state() = RunState(
        hostClientId = ClientId("desktop"),
        runId = "run-1",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1_000,
        updatedAt = 1_000,
        argv = listOf("/bin/echo", "hello"),
        cwd = "/tmp",
        usesPty = false,
        terminal = RunTerminalSnapshot("", false, 0),
    )

    private inline fun <reified T> decode(body: String): T =
        ProtocolCodec.decodeFromCbor(Base64.getDecoder().decode(body))

    private class FakeDaemon : DaemonLocalApi {
        var registration: ApplicationRegistrationRequest? = null
        val receives = mutableListOf<ReceiveRequest>()
        val singles = mutableListOf<SendRequest>()
        val batches = mutableListOf<List<SendRequest>>()

        override fun status() = DaemonStatus(
            version = "1",
            clientId = "desktop",
            connectionState = DaemonConnectionState.CONNECTED,
        )

        override fun putApplication(
            applicationId: String,
            request: ApplicationRegistrationRequest,
        ): ApplicationView {
            registration = request
            return ApplicationView(applicationId, request.displayName, request.version, request.capabilities.toList(), 1)
        }

        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit

        override fun send(request: SendRequest): SendAccepted {
            singles += request
            return accepted(request, singles.size)
        }

        override fun sendAll(requests: List<SendRequest>): List<SendAccepted> {
            batches += requests
            return requests.mapIndexed(::accepted)
        }

        override fun openReceive(request: ReceiveRequest): ReceiveStream {
            receives += request
            return object : ReceiveStream {
                override fun next(): ReceiveRecord? = null
                override fun close() = Unit
            }
        }

        override fun unregisterReceive(request: ReceiveRequest) = Unit
        override fun ack(applicationId: String, envelopeId: String) = Unit
        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) = Unit

        private fun accepted(index: Int, request: SendRequest) = accepted(request, index)
        private fun accepted(request: SendRequest, index: Int) =
            SendAccepted("message-$index", 1_000, request.submissionId)
    }

}
