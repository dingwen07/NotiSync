package net.extrawdw.notisync.run

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.Base64
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.LocalApiException
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunReporterTest {
    @Test
    fun `refresh completion atomically sends refreshed state before its result`() {
        val daemon = FakeDaemon()
        reporter(daemon).use { reporter ->
            reporter.complete("event-1", refreshCompletion())
        }

        val sends = daemon.completions.single().second
        assertEquals(2, sends.size)
        val stateSync = decode(sends[0])
        val resultSync = decode(sends[1])
        assertEquals(RunSyncKind.STATE, stateSync.run?.kind)
        assertEquals(refreshState(), stateSync.run?.state)
        assertEquals(RunSyncKind.CONTROL_RESULT, resultSync.run?.kind)
        assertEquals(REQUEST_ID, resultSync.run?.controlResult?.requestId)
    }

    @Test
    fun `response-loss retry completes before publishing a later revision`() {
        val daemon = FakeDaemon().apply {
            completeBehaviors += { _, _ -> throw IOException("response lost after commit") }
        }
        val reporter = reporter(daemon)
        try {
            expectIOException { reporter.complete("event-1", refreshCompletion()) }

            assertTrue(reporter.postRun(periodicState(3)))

            assertEquals(listOf("complete:event-1", "complete:event-1", "send:3"), daemon.operations)
            assertEquals(2, daemon.completions.size)
            assertEquals(daemon.completions[0].second, daemon.completions[1].second)
        } finally {
            reporter.close()
        }
    }

    @Test
    fun `deferred revisions remain ordered when retry and immediate publication fail`() {
        val daemon = FakeDaemon().apply {
            completeBehaviors += { _, _ -> throw IOException("first response lost") }
            completeBehaviors += { _, _ -> throw IOException("retry unavailable") }
            completeBehaviors += { _, _ -> Unit }
            sendBehaviors += { _ -> throw IOException("deferred publication unavailable") }
        }
        val reporter = reporter(daemon)
        try {
            expectIOException { reporter.complete("event-1", refreshCompletion()) }
            // The refresh completion is still uncertain, so revision 3 is retained locally.
            assertTrue(reporter.postRun(periodicState(3)))

            // A replay confirms completion. Its immediate deferred publication fails, but must not
            // resurrect the already-ACKed event or discard revision 3.
            reporter.complete("event-1", refreshCompletion())
            assertTrue(reporter.postRun(periodicState(4)))

            assertEquals(
                listOf(
                    "complete:event-1",
                    "complete:event-1",
                    "complete:event-1",
                    "send:3",
                    "send:3",
                    "send:4",
                ),
                daemon.operations,
            )
            assertEquals(listOf(3L, 3L, 4L), daemon.sendAttempts.map(::publishedRevision))
        } finally {
            reporter.close()
        }
    }

    @Test
    fun `daemon restart event-not-pending conflict abandons only the lost completion`() {
        val daemon = FakeDaemon().apply {
            completeBehaviors += { _, _ -> throw IOException("daemon disconnected") }
            completeBehaviors += { _, _ ->
                throw LocalApiException(
                    409,
                    ApiError("event-not-pending", "event is not pending for application nsrun"),
                    "event is not pending for application nsrun",
                )
            }
        }
        val reporter = reporter(daemon)
        try {
            expectIOException { reporter.complete("event-1", refreshCompletion()) }

            assertTrue(reporter.postRun(periodicState(3)))
            assertTrue(reporter.postRun(periodicState(4)))

            assertEquals(2, daemon.completions.size)
            assertEquals(listOf("complete:event-1", "complete:event-1", "send:3", "send:4"), daemon.operations)
        } finally {
            reporter.close()
        }
    }

    private fun reporter(daemon: FakeDaemon): RunReporter = RunReporter(
        RunApplicationBridge.connect(daemon, RUN_ID),
        warning = {},
    )

    private fun refreshCompletion() = ControlCompletion(
        sender = SENDER,
        result = RunControlResult(
            requestId = REQUEST_ID,
            runId = RUN_ID,
            status = RunControlResultStatus.APPLIED,
            respondedAt = CLOCK.millis(),
        ),
        refreshState = refreshState(),
    )

    private fun refreshState() = state(2, RunUpdateReason.REFRESH).copy(
        responseToRequestId = REQUEST_ID,
    )

    private fun periodicState(revision: Long) = state(revision, RunUpdateReason.PERIODIC)

    private fun state(revision: Long, reason: RunUpdateReason) = RunState(
        hostClientId = HOST,
        runId = RUN_ID,
        revision = revision,
        phase = RunPhase.RUNNING,
        updateReason = reason,
        startedAt = 1_000,
        updatedAt = 1_000 + revision,
        argv = listOf("build"),
        cwd = "/work",
        usesPty = false,
        terminal = RunTerminalSnapshot("running", false, 7),
    )

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private class FakeDaemon : DaemonLocalApi {
        val operations = mutableListOf<String>()
        val completions = mutableListOf<Pair<String, List<SendRequest>>>()
        val sendAttempts = mutableListOf<List<SendRequest>>()
        val completeBehaviors = ArrayDeque<(String, List<SendRequest>) -> Unit>()
        val sendBehaviors = ArrayDeque<(List<SendRequest>) -> Unit>()

        override fun status() = DaemonStatus(
            version = "1",
            clientId = HOST.value,
            connectionState = DaemonConnectionState.CONNECTED,
        )

        override fun putApplication(
            applicationId: String,
            request: ApplicationRegistrationRequest,
        ) = ApplicationView(
            applicationId,
            request.displayName,
            request.version,
            request.capabilities.toList(),
            CLOCK.millis(),
        )

        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit

        override fun send(request: SendRequest): SendAccepted {
            publish(listOf(request))
            return accepted(request, sendAttempts.size)
        }

        override fun sendAll(requests: List<SendRequest>): List<SendAccepted> {
            publish(requests)
            return requests.mapIndexed { index, request -> accepted(request, index) }
        }

        private fun publish(requests: List<SendRequest>) {
            sendAttempts += requests
            operations += "send:${publishedRevision(requests)}"
            if (sendBehaviors.isNotEmpty()) sendBehaviors.removeFirst().invoke(requests)
        }

        override fun openReceive(request: ReceiveRequest): ReceiveStream = object : ReceiveStream {
            override fun next(): ReceiveRecord? = null
            override fun close() = Unit
        }

        override fun unregisterReceive(request: ReceiveRequest) = Unit
        override fun ack(applicationId: String, envelopeId: String) = Unit

        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) {
            completions += envelopeId to sends
            operations += "complete:$envelopeId"
            if (completeBehaviors.isNotEmpty()) completeBehaviors.removeFirst().invoke(envelopeId, sends)
        }

        private fun accepted(request: SendRequest, index: Int) =
            SendAccepted("message-$index", CLOCK.millis(), request.submissionId)
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC)
        val HOST = ClientId("desktop")
        val SENDER = ClientId("phone")
        const val RUN_ID = "run-1"
        const val REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000"

        fun decode(request: SendRequest): DataSync =
            ProtocolCodec.decodeFromCbor(Base64.getDecoder().decode(request.body))

        fun publishedRevision(requests: List<SendRequest>): Long =
            requireNotNull(decode(requests.first()).run?.state?.revision)
    }
}
