package net.extrawdw.apps.notisync.run

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
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
    fun equalRevisionRerendersDurableSnapshotButOlderRevisionDoesNot() {
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

        assertEquals(listOf(durable), rendered)
        assertEquals(durable, repository.runs.value.single().state)
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
    fun maintenanceDelegatesToRepository() {
        val repository = FakeRepository()
        val harness = harness(repository)

        harness.engine.runMaintenanceNow()

        assertEquals(1, repository.pruneCalls)
        harness.close()
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
    fun inputAndArbitrarySignalBecomeNormalRunControls() = runBlocking {
        val repository = FakeRepository(running())
        val sent = mutableListOf<RunControl>()
        val harness = harness(repository, send = { control -> sent += control; true })
        val key = repository.runs.value.single().key

        assertTrue(harness.engine.writeInput(key, "yes\n", interactionGeneration = 7))
        assertTrue(harness.engine.signal(key, "RTMIN+1"))
        assertFalse(harness.engine.signal(key, "signal with spaces"))

        assertEquals(RunControlKind.WRITE_INPUT, sent[0].kind)
        assertEquals("yes\n", sent[0].inputText)
        assertEquals(7L, sent[0].interactionGeneration)
        assertEquals(RunControlKind.SIGNAL, sent[1].kind)
        assertEquals("RTMIN+1", sent[1].signal)
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
    ): Harness {
        val job = SupervisorJob()
        return Harness(
            RunEngine(
                repository = repository,
                presenter = presenter,
                scope = CoroutineScope(job + Dispatchers.Unconfined),
                sendControl = send,
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

        override fun prune() {
            pruneCalls++
        }
    }

    private fun message(sender: ClientId, own: Boolean) = InboundMessage(
        senderId = sender,
        senderOwnDevice = own,
        typ = MessageType.DATA_SYNC,
        body = byteArrayOf(),
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
