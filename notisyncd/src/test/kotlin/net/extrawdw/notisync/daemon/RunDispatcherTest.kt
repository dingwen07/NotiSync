package net.extrawdw.notisync.daemon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.LocalRunBlockedReason
import net.extrawdw.notisync.localapi.LocalRunLlmSummary
import net.extrawdw.notisync.localapi.LocalRunPhase
import net.extrawdw.notisync.localapi.LocalRunPromptKind
import net.extrawdw.notisync.localapi.LocalRunTerminalSnapshot
import net.extrawdw.notisync.localapi.LocalRunUpdateReason
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDispatcherTest {
    private val resolver = ProcessIdentityResolver()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC)

    @Test
    fun `accept wakes independent Android and iOS delivery lanes`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("nsrun"))
        val runOutbox = InMemoryRunOutbox()
        val iosOutbox = InMemoryRunIosNotificationOutbox()
        val sentRuns = CopyOnWriteArrayList<PendingRunState>()
        val sentNotifications = CopyOnWriteArrayList<PendingNotification>()
        val runSent = CountDownLatch(1)
        val notificationSent = CountDownLatch(1)
        val dispatcher = RunDispatcher(
            sessions = registry,
            runOutbox = runOutbox,
            resultOutbox = runOutbox,
            iosOutbox = iosOutbox,
            iosSender = object : NotificationMeshSender {
                override val clientId = ClientId("desktop")
                override suspend fun send(item: PendingNotification) {
                    sentNotifications += item
                    notificationSent.countDown()
                }
            },
            sender = object : RunMeshSender {
                override val clientId = ClientId("desktop")
                override suspend fun sendState(item: PendingRunState) {
                    sentRuns += item
                    runSent.countDown()
                }
                override suspend fun sendControlResult(recipient: ClientId, result: RunControlResult) = 1
            },
            clock = clock,
        )
        dispatcher.start()
        try {
            dispatcher.accept(runState(session.sessionId), session.bearerToken, peer)

            assertTrue(runSent.await(2, TimeUnit.SECONDS))
            assertTrue(notificationSent.await(2, TimeUnit.SECONDS))
            assertEquals("run-1", sentRuns.single().state.runId)
            assertEquals(NotificationAudience.RUN_IOS_COMPAT, sentNotifications.single().audience)
            assertEquals(listOf("signal-int", "signal-term"), sentNotifications.single().request.actions.map { it.id })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `iOS projection preserves compact legacy actions and omits periodic snapshots`() {
        val blocked = runState("session").copy(
            revision = 2,
            phase = LocalRunPhase.BLOCKED,
            updateReason = LocalRunUpdateReason.BLOCKED,
            blockedReason = LocalRunBlockedReason.TERMINAL_INPUT,
            prompt = LocalRunPromptKind.YES_NO,
            interactionGeneration = 1,
            llmSummary = LocalRunLlmSummary("Summary", "Needs confirmation"),
        )

        val projection = blocked.toIosNotification()!!

        assertEquals("Summary", projection.title)
        assertEquals("Needs confirmation", projection.text)
        assertEquals("Input", projection.shortCriticalText)
        assertEquals(listOf("yes", "no", "signal-int"), projection.actions.map { it.id })
        val hangProjection = blocked.copy(
            prompt = null,
            blockedReason = LocalRunBlockedReason.OUTPUT_AND_CPU_IDLE,
        ).toIosNotification()!!
        assertEquals("Check", hangProjection.shortCriticalText)
        assertEquals(listOf("signal-int", "signal-term"), hangProjection.actions.map { it.id })
        assertNull(
            runState("session").copy(
                revision = 3,
                updateReason = LocalRunUpdateReason.PERIODIC,
            ).toIosNotification(),
        )
    }

    @Test
    fun `iOS outbox coalesces summaries but retains attention and terminal lifecycle edges`() {
        val outbox = InMemoryRunIosNotificationOutbox()
        val initial = pendingIos("initial", runState("session"))
        val blockedState = runState("session").copy(
            revision = 2,
            phase = LocalRunPhase.BLOCKED,
            updateReason = LocalRunUpdateReason.BLOCKED,
            blockedReason = LocalRunBlockedReason.TERMINAL_INPUT,
            prompt = LocalRunPromptKind.YES_NO,
            interactionGeneration = 1,
        )
        val blocked = pendingIos("blocked", blockedState)
        val oldSummary = pendingIos(
            "summary-old",
            blockedState.copy(revision = 3, updateReason = LocalRunUpdateReason.LLM_SUMMARY),
        )
        val newSummary = pendingIos(
            "summary-new",
            blockedState.copy(revision = 4, updateReason = LocalRunUpdateReason.LLM_SUMMARY),
        )
        val completed = pendingIos(
            "completed",
            blockedState.copy(
                revision = 5,
                phase = LocalRunPhase.COMPLETED,
                updateReason = LocalRunUpdateReason.COMPLETED,
                interactionGeneration = 2,
                prompt = null,
                blockedReason = null,
                endedAt = clock.millis(),
                exitCode = 0,
            ),
        )

        listOf(initial, blocked, oldSummary, newSummary, completed).forEach(outbox::enqueueIos)

        val retained = mutableListOf<String>()
        while (outbox.peekIos() != null) {
            val item = outbox.peekIos()!!
            retained += item.id
            outbox.removeIos(item.id)
        }
        assertEquals(listOf("initial", "blocked", "completed"), retained)
    }

    @Test
    fun `file backed Run acceptance atomically commits revision and both delivery lanes`() {
        val layout = DaemonStorageLayout(Files.createTempDirectory("notisync-run-acceptance").toRealPath())
        var failCommit = true
        val database = DaemonDatabaseRepository(
            layout,
            clock,
            beforeRunAcceptanceCommit = {
                if (failCommit) {
                    failCommit = false
                    error("injected Run acceptance failure")
                }
            },
        )
        val peer = currentPeer()
        val registry = LocalSessionRegistry(resolver, clock, database = database)
        val session = registry.create(peer, CreateSessionRequest("nsrun"))
        val dispatcher = dispatcher(registry, database)
        val state = runState(session.sessionId)

        assertThrows(IllegalStateException::class.java) {
            dispatcher.accept(state, session.bearerToken, peer)
        }
        val afterFailure = database.load()
        assertEquals(-1L, afterFailure.sessions.getValue(session.sessionId).latestRunRevision)
        assertTrue(afterFailure.runOutbox.isEmpty())
        assertTrue(afterFailure.runIosOutbox.isEmpty())

        val accepted = dispatcher.accept(state, session.bearerToken, peer)
        val committed = database.load()
        assertEquals(1L, committed.sessions.getValue(session.sessionId).latestRunRevision)
        assertEquals(listOf(accepted.id), committed.runOutbox.map { it.id })
        assertEquals(1, committed.runIosOutbox.size)
        dispatcher.close()

        val restartedDatabase = DaemonDatabaseRepository(layout, clock)
        val restartedRegistry = LocalSessionRegistry(resolver, clock, database = restartedDatabase)
        val restarted = dispatcher(restartedRegistry, restartedDatabase)
        assertEquals(1L, restartedDatabase.load().sessions.getValue(session.sessionId).latestRunRevision)
        assertEquals(1, restartedDatabase.load().runOutbox.size)
        assertEquals(1, restartedDatabase.load().runIosOutbox.size)
        assertThrows(LocalConflictException::class.java) {
            restarted.accept(state, session.bearerToken, peer)
        }
        restarted.close()
    }

    @Test
    fun `Run priority and Android audience policy match lifecycle semantics`() {
        assertEquals(
            setOf(Capability.DISPLAY, Capability.BACKGROUND_WAKE, Capability.PUSH_FILTERING),
            ANDROID_RUN_CAPABILITIES,
        )
        assertEquals(Urgency.HIGH, runUrgency(RunUpdateReason.INITIAL))
        assertEquals(Urgency.HIGH, runUrgency(RunUpdateReason.BLOCKED))
        assertEquals(Urgency.HIGH, runUrgency(RunUpdateReason.COMPLETED))
        assertEquals(Urgency.NORMAL, runUrgency(RunUpdateReason.PERIODIC))
        assertEquals(Urgency.NORMAL, runUrgency(RunUpdateReason.LLM_SUMMARY))
        assertEquals(Urgency.NORMAL, runUrgency(RunUpdateReason.REFRESH))
    }

    @Test
    fun `result worker drains externally queued immediate rejection after going idle`() {
        val outbox = InMemoryRunOutbox()
        val sent = CountDownLatch(1)
        val dispatcher = RunDispatcher(
            sessions = LocalSessionRegistry(resolver, clock),
            runOutbox = outbox,
            resultOutbox = outbox,
            iosOutbox = InMemoryRunIosNotificationOutbox(),
            iosSender = object : NotificationMeshSender {
                override val clientId = ClientId("desktop")
                override suspend fun send(item: PendingNotification) = Unit
            },
            sender = object : RunMeshSender {
                override val clientId = ClientId("desktop")
                override suspend fun sendState(item: PendingRunState) = Unit
                override suspend fun sendControlResult(recipient: ClientId, result: RunControlResult): Int {
                    sent.countDown()
                    return 1
                }
            },
            clock = clock,
        )
        dispatcher.start()
        try {
            Thread.sleep(100)
            outbox.enqueueResult(
                PendingRunControlResult(
                    id = "immediate-result",
                    recipient = ClientId("android"),
                    result = RunControlResult(
                        requestId = "123e4567-e89b-12d3-a456-426614174099",
                        runId = "run-1",
                        status = RunControlResultStatus.STALE,
                        respondedAt = clock.millis(),
                    ),
                    acceptedAt = clock.millis(),
                ),
            )

            assertTrue(sent.await(2, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `Run outbox coalesces replaceable snapshots while retaining lifecycle edges`() {
        val outbox = InMemoryRunOutbox()
        val initial = pending("initial", runState("session"))
        val periodic = pending(
            "periodic",
            runState("session").copy(revision = 2, updateReason = LocalRunUpdateReason.PERIODIC),
        )
        val summary = pending(
            "summary",
            runState("session").copy(revision = 3, updateReason = LocalRunUpdateReason.LLM_SUMMARY),
        )
        val completed = pending(
            "completed",
            runState("session").copy(
                revision = 4,
                phase = LocalRunPhase.COMPLETED,
                updateReason = LocalRunUpdateReason.COMPLETED,
                endedAt = clock.millis(),
                exitCode = 0,
            ),
        )
        outbox.enqueue(initial)
        outbox.enqueue(periodic)
        outbox.enqueue(summary)
        assertEquals("initial", outbox.peekRun()?.id)

        outbox.enqueue(completed)

        assertEquals("initial", outbox.peekRun()?.id)
        outbox.removeRun("initial")
        assertEquals("completed", outbox.peekRun()?.id)
        outbox.removeRun("completed")
        assertNull(outbox.peekRun())
    }

    private fun runState(sessionId: String) = RunStateRequest(
        sessionId = sessionId,
        runId = "run-1",
        revision = 1,
        phase = LocalRunPhase.RUNNING,
        updateReason = LocalRunUpdateReason.INITIAL,
        startedAt = clock.millis(),
        updatedAt = clock.millis(),
        argv = listOf("build", "--all"),
        cwd = "/work",
        usesPty = false,
        terminal = LocalRunTerminalSnapshot("running", false, 7),
    )

    private fun pending(id: String, state: RunStateRequest) = PendingRunState(id, "source", state, clock.millis())

    private fun pendingIos(id: String, state: RunStateRequest): PendingNotification {
        val projection = requireNotNull(state.toIosNotification())
        return PendingNotification(
            id = id,
            sourceKey = "source",
            request = projection,
            postTime = state.revision,
            acceptedAt = clock.millis(),
            audience = NotificationAudience.RUN_IOS_COMPAT,
        )
    }

    private fun dispatcher(
        registry: LocalSessionRegistry,
        database: DaemonDatabaseRepository,
    ) = RunDispatcher(
        sessions = registry,
        runOutbox = database,
        resultOutbox = database,
        iosOutbox = database,
        iosSender = object : NotificationMeshSender {
            override val clientId = ClientId("desktop")
            override suspend fun send(item: PendingNotification) = Unit
        },
        sender = object : RunMeshSender {
            override val clientId = ClientId("desktop")
            override suspend fun sendState(item: PendingRunState) = Unit
            override suspend fun sendControlResult(recipient: ClientId, result: RunControlResult) = 1
        },
        clock = clock,
    )

    private fun currentPeer(): LocalPeer {
        val pid = ProcessHandle.current().pid()
        return LocalPeer(uid = 123, pid = pid, startTime = requireNotNull(resolver.startTime(pid)))
    }
}
