package net.extrawdw.apps.notisync.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.testsupport.TestActivityText
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEngineTest {
    @Test
    fun stateMustBindToAuthenticatedOwnHost() {
        val repository = FakeRepository()
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)
        val state = running()

        harness.engine.onRunSync(message(ClientId("other"), own = true), stateSync(state))
        harness.engine.onRunSync(message(state.hostClientId, own = false), stateSync(state))
        assertTrue(repository.runs.value.isEmpty())

        harness.engine.onRunSync(message(state.hostClientId, own = true), stateSync(state))
        assertEquals(listOf(state), repository.runs.value.map { it.state })
        assertEquals(listOf(state), rendered)
        harness.close()
    }

    @Test
    fun staleRevisionDoesNotReplaceOrRerender() {
        val repository = FakeRepository(running(revision = 2))
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)

        harness.engine.onRunSync(message(HOST, own = true), stateSync(running(revision = 1)))

        assertEquals(2, repository.runs.value.single().state.revision)
        assertTrue(rendered.isEmpty())
        harness.close()
    }

    @Test
    fun presentedEqualRevisionAndOlderRevisionDoNotRerender() {
        val durable = running(revision = 2)
        val repository = FakeRepository(
            listOf(StoredRun(durable, durable.updatedAt, presentedRevision = durable.revision))
        )
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)

        val alternateEqual = durable.copy(
            terminal = RunTerminalSnapshot("must not replace durable data", false, 29),
        )
        harness.engine.onRunSync(message(HOST, own = true), stateSync(alternateEqual))
        harness.engine.onRunSync(message(HOST, own = true), stateSync(running(revision = 1)))

        assertTrue(rendered.isEmpty())
        assertEquals(durable, repository.runs.value.single().state)
        harness.close()
    }

    @Test
    fun unpresentedEqualRevisionRendersDurableSnapshot() {
        val durable = running(revision = 2)
        val repository = FakeRepository(listOf(StoredRun(durable, durable.updatedAt)))
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)
        val alternateEqual = durable.copy(
            terminal = RunTerminalSnapshot("must not replace durable data", false, 29),
        )

        harness.engine.onRunSync(message(HOST, own = true), stateSync(alternateEqual))

        assertEquals(listOf(durable), rendered)
        assertFalse(repository.runs.value.single().presentationPending)
        harness.close()
    }

    @Test
    fun persistenceFailureIsRetryableAndDoesNotRender() {
        val repository = FakeRepository().apply { failWrites = true }
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)

        assertThrows(RetryableDeliveryException::class.java) {
            harness.engine.onRunSync(message(HOST, own = true), stateSync(running()))
        }
        assertTrue(rendered.isEmpty())
        harness.close()
    }

    @Test
    fun presentationFailureAfterCommitIsRetryableAndEqualDeliveryRecovers() {
        val repository = FakeRepository()
        val attempts = mutableListOf<RunState>()
        var failPresentation = true
        val harness = harness(
            repository = repository,
            presenter = RunStatePresenter { state ->
                attempts += state
                if (failPresentation) error("notification service unavailable")
                true
            },
        )
        val state = running()

        assertThrows(RetryableDeliveryException::class.java) {
            harness.engine.onRunSync(message(HOST, own = true), stateSync(state))
        }
        assertEquals(state, repository.runs.value.single().state)
        assertTrue(repository.runs.value.single().presentationPending)

        failPresentation = false
        harness.engine.onRunSync(message(HOST, own = true), stateSync(state))

        assertEquals(listOf(state, state), attempts)
        assertFalse(repository.runs.value.single().presentationPending)
        harness.close()
    }

    @Test
    fun presentationCheckpointFailureIsRetryableAndEqualDeliveryRecovers() {
        val repository = FakeRepository().apply { failPresentationWrites = true }
        val harness = harness(repository)
        val state = running()

        assertThrows(RetryableDeliveryException::class.java) {
            harness.engine.onRunSync(message(HOST, own = true), stateSync(state))
        }
        assertTrue(repository.runs.value.single().presentationPending)

        repository.failPresentationWrites = false
        harness.engine.onRunSync(message(HOST, own = true), stateSync(state))

        assertFalse(repository.runs.value.single().presentationPending)
        harness.close()
    }

    @Test
    fun unavailablePresentationStaysPendingUntilReconciliation() {
        val repository = FakeRepository()
        var available = false
        val rendered = mutableListOf<RunState>()
        val harness = harness(
            repository = repository,
            presenter = RunStatePresenter { state ->
                rendered += state
                available
            },
        )

        harness.engine.onRunSync(message(HOST, own = true), stateSync(running()))
        assertTrue(repository.runs.value.single().presentationPending)

        available = true
        harness.engine.reconcilePendingPresentations()
        assertFalse(repository.runs.value.single().presentationPending)
        assertEquals(2, rendered.size)
        harness.close()
    }

    @Test
    fun unavailableTerminalPresentationDoesNotQueueHistoricalAlertBacklog() {
        val terminal = completed()
        val repository = FakeRepository()
        val harness = harness(
            repository = repository,
            presenter = RunStatePresenter { false },
        )

        harness.engine.onRunSync(message(HOST, own = true), stateSync(terminal))

        assertFalse(repository.runs.value.single().presentationPending)
        harness.close()
    }

    @Test
    fun terminalUpdateDismissesThePreviousOngoingNotification() {
        val initial = running()
        val repository = FakeRepository(initial)
        val dismissed = mutableListOf<RunKey>()
        val job = SupervisorJob()
        val engine = RunEngine(
            repository = repository,
            presenter = object : RunStatePresenter {
                override fun render(state: RunState) = true
                override fun dismiss(key: RunKey) { dismissed += key }
            },
            scope = CoroutineScope(job + Dispatchers.Unconfined),
            sendControl = { true },
        )

        engine.onRunSync(message(HOST, own = true), stateSync(completed()))

        assertEquals(listOf(RunKey(HOST.value, initial.runId)), dismissed)
        assertFalse(repository.runs.value.single().active)
        job.cancel()
    }

    @Test
    fun reconciliationOnlyRendersUnpresentedRevisions() {
        val pending = completed(runId = "pending", revision = 2)
        val presented = running(runId = "presented", revision = 3)
        val repository = FakeRepository(
            listOf(
                StoredRun(pending, pending.updatedAt),
                StoredRun(presented, presented.updatedAt, presentedRevision = presented.revision),
            )
        )
        val rendered = mutableListOf<RunState>()
        val harness = harness(repository, rendered)

        harness.engine.reconcilePendingPresentations()

        assertEquals(listOf(pending), rendered)
        assertFalse(repository.find(RunKey(HOST.value, pending.runId))!!.presentationPending)
        harness.close()
    }

    @Test
    fun reconciliationDismissesLocallyInactiveRemoteActiveRun() {
        val state = running()
        val stored = StoredRun(
            state = state,
            receivedAt = state.updatedAt,
            presentedRevision = state.revision,
            active = false,
        )
        val repository = FakeRepository(listOf(stored))
        val dismissed = mutableListOf<RunKey>()
        val job = SupervisorJob()
        val engine = RunEngine(
            repository = repository,
            presenter = object : RunStatePresenter {
                override fun render(state: RunState) = true
                override fun dismiss(key: RunKey) { dismissed += key }
            },
            scope = CoroutineScope(job + Dispatchers.Unconfined),
            sendControl = { true },
        )

        engine.reconcilePendingPresentations()

        assertEquals(listOf(stored.key), dismissed)
        job.cancel()
    }

    @Test
    fun maintenanceDelegatesToRepository() {
        val repository = FakeRepository()
        val harness = harness(repository)

        harness.engine.runMaintenanceNow()

        assertEquals(1, repository.pruneCalls)
        harness.close()
    }

    @Test
    fun manualInactiveAndClearHistoryDismissStableNotifications() {
        val active = running(runId = "active")
        val history = completed(runId = "history")
        val repository = FakeRepository(
            listOf(
                StoredRun(active, active.updatedAt),
                StoredRun(history, history.updatedAt),
            )
        )
        val dismissed = mutableListOf<RunKey>()
        val job = SupervisorJob()
        val engine = RunEngine(
            repository = repository,
            presenter = object : RunStatePresenter {
                override fun render(state: RunState) = true
                override fun dismiss(key: RunKey) { dismissed += key }
            },
            scope = CoroutineScope(job + Dispatchers.Unconfined),
            sendControl = { true },
        )

        assertTrue(engine.markInactive(RunKey(HOST.value, active.runId)))
        assertFalse(repository.find(RunKey(HOST.value, active.runId))!!.active)
        assertTrue(engine.clearHistory())
        assertTrue(repository.runs.value.isEmpty())
        assertEquals(
            setOf(RunKey(HOST.value, active.runId), RunKey(HOST.value, history.runId)),
            dismissed.toSet(),
        )
        job.cancel()
    }

    @Test
    fun refreshStaysPendingUntilCorrelatedStateIsDurable() = runBlocking {
        val repository = FakeRepository(running())
        val sent = mutableListOf<RunControl>()
        val harness = harness(repository, send = { control -> sent += control; true })
        val key = repository.runs.value.single().key

        assertTrue(harness.engine.refresh(key))
        val refresh = sent.single()
        assertEquals(RunControlKind.REFRESH, refresh.kind)
        assertEquals(setOf(key), harness.engine.pendingRefreshes.value)

        repository.failWrites = true
        val response = running(revision = 2).copy(
            updateReason = RunUpdateReason.REFRESH,
            updatedAt = 1_001,
            responseToRequestId = refresh.requestId,
        )
        assertThrows(RetryableDeliveryException::class.java) {
            harness.engine.onRunSync(message(HOST, own = true), stateSync(response))
        }
        assertEquals(setOf(key), harness.engine.pendingRefreshes.value)

        repository.failWrites = false
        harness.engine.onRunSync(message(HOST, own = true), stateSync(response))
        assertTrue(harness.engine.pendingRefreshes.value.isEmpty())
        assertEquals(2, repository.runs.value.single().state.revision)
        harness.close()
    }

    @Test
    fun locallyStaleRemoteActiveRunCanRefreshAndReactivate() = runBlocking {
        val staleState = running(revision = 1)
        val stale = StoredRun(
            state = staleState,
            receivedAt = staleState.updatedAt,
            presentedRevision = staleState.revision,
            active = false,
        )
        val repository = FakeRepository(listOf(stale))
        val sent = mutableListOf<RunControl>()
        val harness = harness(repository, send = { control -> sent += control; true })

        assertTrue(harness.engine.refresh(stale.key))
        val refresh = sent.single()
        val response = staleState.copy(
            revision = 2,
            updateReason = RunUpdateReason.REFRESH,
            updatedAt = 1_001,
            responseToRequestId = refresh.requestId,
        )

        harness.engine.onRunSync(message(HOST, own = true), stateSync(response))

        assertTrue(repository.find(stale.key)!!.active)
        assertEquals(2, repository.find(stale.key)!!.state.revision)
        assertTrue(harness.engine.pendingRefreshes.value.isEmpty())
        harness.close()
    }

    @Test
    fun authenticatedHeartbeatAutomaticallyReactivatesLocallyStaleRun() {
        val staleState = running(revision = 1)
        val stale = StoredRun(
            state = staleState,
            receivedAt = staleState.updatedAt,
            presentedRevision = staleState.revision,
            active = false,
        )
        val repository = FakeRepository(listOf(stale))
        val harness = harness(repository)
        val heartbeat = staleState.copy(
            revision = 2,
            updateReason = RunUpdateReason.PERIODIC,
            updatedAt = 1_001,
        )

        harness.engine.onRunSync(message(HOST, own = true), stateSync(heartbeat))

        assertTrue(repository.find(stale.key)!!.active)
        assertEquals(2, repository.find(stale.key)!!.state.revision)
        harness.close()
    }

    @Test
    fun everyNewRunStateUpdateIsRecordedAsReceivedDiagnostics() {
        val repository = FakeRepository()
        val activity = ActivityLog()
        val harness = harness(repository, activityLog = activity)

        harness.engine.onRunSync(
            message(HOST, own = true, deliveryMode = DeliveryMode.FCM_INLINE),
            stateSync(running()),
        )
        harness.engine.onRunSync(
            message(HOST, own = true, deliveryMode = DeliveryMode.WEBSOCKET),
            stateSync(running(revision = 2)),
        )

        assertEquals(2, activity.events.value.size)
        val periodic = activity.events.value[0]
        assertEquals(ActivityEvent.Kind.RECEIVED, periodic.kind)
        assertEquals("NotiSync Run", periodic.title)
        assertEquals(
            "STATE/PERIODIC · RUNNING · r2 · run run-1 · from Desktop",
            periodic.detail,
        )
        assertEquals(DeliveryMode.WEBSOCKET, periodic.deliveryMode)
        assertEquals(
            "STATE/INITIAL · RUNNING · r1 · run run-1 · from Desktop",
            activity.events.value[1].detail,
        )
        assertEquals(DeliveryMode.FCM_INLINE, activity.events.value[1].deliveryMode)
        harness.close()
    }

    @Test
    fun staleAndEqualRunStateReplaysDoNotDuplicateActivity() {
        val current = running(revision = 2)
        val repository = FakeRepository(current)
        val activity = ActivityLog()
        val harness = harness(repository, activityLog = activity)

        harness.engine.onRunSync(message(HOST, own = true), stateSync(current))
        harness.engine.onRunSync(message(HOST, own = true), stateSync(running(revision = 1)))

        assertTrue(activity.events.value.isEmpty())
        harness.close()
    }

    @Test
    fun runControlResultIsRecordedAsReceivedDiagnostics() {
        val activity = ActivityLog()
        val harness = harness(FakeRepository(), activityLog = activity)
        val result = RunControlResult(
            requestId = "00000000-0000-4000-8000-000000000001",
            runId = "run-1",
            status = RunControlResultStatus.APPLIED,
            respondedAt = 1_500,
        )

        harness.engine.onRunSync(
            message(HOST, own = true, deliveryMode = DeliveryMode.FCM_RELAY_FETCH),
            DataSync(
                kind = DataSyncKind.RUN,
                run = RunSync(kind = RunSyncKind.CONTROL_RESULT, controlResult = result),
            ),
        )

        val event = activity.events.value.single()
        assertEquals(ActivityEvent.Kind.RECEIVED, event.kind)
        assertEquals(
            "CONTROL_RESULT/APPLIED · req 00000000 · run run-1 · from Desktop",
            event.detail,
        )
        assertEquals(DeliveryMode.FCM_RELAY_FETCH, event.deliveryMode)
        harness.close()
    }

    @Test
    fun terminalHistoryCannotBeRefreshed() = runBlocking {
        val terminal = completed()
        val repository = FakeRepository(terminal)
        val sent = mutableListOf<RunControl>()
        val harness = harness(repository, send = { control -> sent += control; true })

        assertFalse(harness.engine.refresh(repository.runs.value.single().key))

        assertTrue(sent.isEmpty())
        harness.close()
    }

    @Test
    fun inputAndArbitrarySignalBecomeNormalRunControls() = runBlocking {
        val repository = FakeRepository(running())
        val sent = mutableListOf<RunControl>()
        val activity = ActivityLog()
        val harness = harness(
            repository,
            send = { control -> sent += control; true },
            activityLog = activity,
        )
        val key = repository.runs.value.single().key

        assertTrue(harness.engine.writeInput(key, "yes\n", interactionGeneration = 7))
        assertTrue(harness.engine.signal(key, "RTMIN+1"))
        assertFalse(harness.engine.signal(key, "signal with spaces"))

        assertEquals(RunControlKind.WRITE_INPUT, sent[0].kind)
        assertEquals("yes\n", sent[0].inputText)
        assertEquals(7L, sent[0].interactionGeneration)
        assertEquals(RunControlKind.SIGNAL, sent[1].kind)
        assertEquals("RTMIN+1", sent[1].signal)
        assertEquals(2, activity.events.value.size)
        assertTrue(activity.events.value.all { it.kind == ActivityEvent.Kind.SENT })
        assertEquals(
            "CONTROL/SIGNAL · req ${sent[1].requestId.take(8)} · run run-1 · to Desktop",
            activity.events.value[0].detail,
        )
        assertEquals(
            "CONTROL/WRITE_INPUT · req ${sent[0].requestId.take(8)} · run run-1 · to Desktop",
            activity.events.value[1].detail,
        )
        assertFalse(activity.events.value.any { "yes" in it.detail || "RTMIN" in it.detail })
        harness.close()
    }

    @Test
    fun failedOutboundRunControlIsNotRecordedAsSent() = runBlocking {
        val repository = FakeRepository(running())
        val activity = ActivityLog()
        val harness = harness(repository, send = { false }, activityLog = activity)

        assertFalse(harness.engine.signal(repository.runs.value.single().key, "TERM"))

        assertTrue(activity.events.value.isEmpty())
        harness.close()
    }

    @Test
    fun terminalTextAffordanceAppendsExactlyOneNewline() {
        assertEquals("hello\n", "hello".asRunTerminalLine())
        assertEquals("hello\n", "hello\r\n\n".asRunTerminalLine())
        assertEquals("\n", "".asRunTerminalLine())
    }

    private data class Harness(val engine: RunEngine, val job: kotlinx.coroutines.Job) {
        fun close() = job.cancel()
    }

    private fun harness(
        repository: FakeRepository,
        rendered: MutableList<RunState> = mutableListOf(),
        presenter: RunStatePresenter = RunStatePresenter { state -> rendered.add(state); true },
        send: suspend (RunControl) -> Boolean = { true },
        activityLog: ActivityLog? = null,
    ): Harness {
        val job = SupervisorJob()
        return Harness(
            RunEngine(
                repository = repository,
                presenter = presenter,
                scope = CoroutineScope(job + Dispatchers.Unconfined),
                sendControl = send,
                activityLog = activityLog,
                activityText = activityLog?.let { TestActivityText },
                deviceNameOf = { "Desktop" },
                now = { 2_000 },
            ),
            job,
        )
    }

    private class FakeRepository(initial: List<StoredRun> = emptyList()) : RunRepository {
        constructor(initial: RunState) : this(listOf(StoredRun(initial, initial.updatedAt)))

        private val mutable = MutableStateFlow(initial)
        override val runs: StateFlow<List<StoredRun>> = mutable
        var failWrites = false
        var failPresentationWrites = false
        var pruneCalls = 0

        override fun apply(state: RunState): RunApplyResult {
            if (failWrites) error("disk unavailable")
            val existing = find(RunKey(state.hostClientId.value, state.runId))
            if (existing != null && existing.state.revision == state.revision) return RunApplyResult.EQUAL
            if (existing != null && existing.state.revision > state.revision) return RunApplyResult.OLDER
            mutable.value = mutable.value.filterNot { it.key == RunKey(state.hostClientId.value, state.runId) } +
                StoredRun(
                    state,
                    state.updatedAt,
                    existing?.presentedRevision ?: StoredRun.NO_PRESENTED_REVISION,
                )
            return if (existing == null) RunApplyResult.INSERTED else RunApplyResult.UPDATED
        }

        override fun find(key: RunKey): StoredRun? = mutable.value.firstOrNull { it.key == key }

        override fun markPresented(key: RunKey, revision: Long) {
            if (failPresentationWrites) error("disk unavailable")
            mutable.value = mutable.value.map { stored ->
                if (stored.key == key && stored.state.revision == revision) {
                    stored.copy(presentedRevision = revision)
                } else stored
            }
        }

        override fun markInactive(key: RunKey): Boolean {
            val existing = find(key) ?: return false
            if (!existing.active) return false
            mutable.value = mutable.value.map { stored ->
                if (stored.key == key) {
                    stored.copy(active = false, presentedRevision = stored.state.revision)
                } else stored
            }
            return true
        }

        override fun clearHistory() {
            mutable.value = mutable.value.filter { it.active }
        }

        override fun prune() {
            pruneCalls++
        }
    }

    private fun message(
        sender: ClientId,
        own: Boolean,
        deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN,
    ) = InboundMessage(
        senderId = sender,
        senderOwnDevice = own,
        typ = MessageType.DATA_SYNC,
        body = byteArrayOf(),
        deliveryMode = deliveryMode,
    )

    private fun stateSync(state: RunState) = DataSync(
        kind = DataSyncKind.RUN,
        run = RunSync(kind = RunSyncKind.STATE, state = state),
    )

    private fun running(runId: String = "run-1", revision: Long = 1) = RunState(
        hostClientId = HOST,
        runId = runId,
        revision = revision,
        phase = RunPhase.RUNNING,
        updateReason = if (revision == 1L) RunUpdateReason.INITIAL else RunUpdateReason.PERIODIC,
        startedAt = 1_000,
        updatedAt = 1_000,
        argv = listOf("make", "test"),
        cwd = "/work",
        usesPty = true,
        terminal = RunTerminalSnapshot("building", truncated = false, rawBytesSeen = 8),
    )

    private fun completed(runId: String = "run-1", revision: Long = 2) =
        running(runId, revision).copy(
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            updatedAt = 1_100,
            endedAt = 1_100,
            exitCode = 0,
        )

    companion object {
        private val HOST = ClientId("host")
    }
}
