package net.extrawdw.notisync.daemon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.LocalRunPhase
import net.extrawdw.notisync.localapi.LocalRunBlockedReason
import net.extrawdw.notisync.localapi.LocalRunPromptKind
import net.extrawdw.notisync.localapi.LocalRunTerminalSnapshot
import net.extrawdw.notisync.localapi.LocalRunUpdateReason
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionRegistryTest {
    private val resolver = ProcessIdentityResolver()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)

    @Test
    fun `session is bearer and process identity bound`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))

        assertEquals(session.sourceKey, registry.authorize(session.sessionId, session.bearerToken, peer).sourceKey)
        assertThrows(LocalAuthorizationException::class.java) {
            registry.authorize(session.sessionId, "wrong", peer)
        }
        assertThrows(LocalAuthorizationException::class.java) {
            registry.authorize(session.sessionId, session.bearerToken, peer.copy(startTime = "reused"))
        }
    }

    @Test
    fun `stale or mismatched wire action is not delivered`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val registration = registry.registerNotification(
            notification(session.sessionId, generation = 4),
            session.bearerToken,
            peer,
        )

        val action = registration.actions.single()
        assertFalse(deliver(registry, session.sourceKey, action, "wrong-title", title = "Wrong"))
        assertFalse(deliver(registry, session.sourceKey, action, "untrusted", input = "secret", trusted = false))
        assertFalse(deliver(registry, session.sourceKey, action, "wrong-generation", generation = 3))
        assertFalse(deliver(registry, session.sourceKey, action, "wrong-token", token = "forged"))
        assertTrue(deliver(registry, session.sourceKey, action, "valid", input = "hello"))

        val event = registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals(LocalEventType.ACTION, event.type)
        assertEquals(4L, event.generation)
        assertEquals("input", event.actionId)
        assertEquals("hello", event.inputText)
        assertTrue(registry.acknowledge(session.sessionId, event.id, session.bearerToken, peer))
        assertEquals(null, registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0))
    }

    @Test
    fun `older notification generation is rejected`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        registry.registerNotification(notification(session.sessionId, 8), session.bearerToken, peer)
        assertThrows(LocalConflictException::class.java) {
            registry.registerNotification(notification(session.sessionId, 7), session.bearerToken, peer)
        }
    }

    @Test
    fun `wire action index changes with generation`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val old = registry.registerNotification(notification(session.sessionId, 1), session.bearerToken, peer)
        val current = registry.registerNotification(notification(session.sessionId, 2), session.bearerToken, peer)

        assertFalse(deliver(registry, session.sourceKey, old.actions.single(), "stale", input = "stale"))
        assertTrue(deliver(registry, session.sourceKey, current.actions.single(), "fresh", input = "fresh"))
    }

    @Test
    fun `same contextual generation reuses action capabilities`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val first = registry.registerNotification(notification(session.sessionId, 3), session.bearerToken, peer)
        val summaryReplacement = registry.registerNotification(
            notification(session.sessionId, 3).copy(text = "LLM summary"),
            session.bearerToken,
            peer,
        )

        assertEquals(first.actions, summaryReplacement.actions)
    }

    @Test
    fun `session process actions survive replacements while contextual actions expire`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val first = registry.registerNotification(
            notification(session.sessionId, 1).copy(
                actions = listOf(
                    signalAction("signal-int", "Interrupt", "SIGINT", 1),
                    signalAction("signal-term", "Terminate", "SIGTERM", 1),
                    inputAction(1),
                ),
            ),
            session.bearerToken,
            peer,
        )
        val oldInterrupt = first.actions.single { it.id == "signal-int" }
        val oldTerminate = first.actions.single { it.id == "signal-term" }
        val oldInput = first.actions.single { it.id == "input" }

        val replacement = registry.registerNotification(
            notification(session.sessionId, 2).copy(
                actions = listOf(
                    LocalNotificationAction(
                        id = "yes",
                        title = "Yes",
                        kind = NotificationActionKind.WRITE_INPUT,
                        generation = 2,
                        inputText = "y\n",
                    ),
                    LocalNotificationAction(
                        id = "no",
                        title = "No",
                        kind = NotificationActionKind.WRITE_INPUT,
                        generation = 2,
                        inputText = "n\n",
                    ),
                    signalAction("signal-int", "Interrupt", "SIGINT", 2),
                ),
            ),
            session.bearerToken,
            peer,
        )

        val currentInterrupt = replacement.actions.single { it.id == "signal-int" }
        assertEquals(oldInterrupt, currentInterrupt)
        assertFalse(deliver(registry, session.sourceKey, oldInput, "stale-input", input = "stale"))
        assertFalse(deliver(registry, session.sourceKey, oldTerminate, "forged-signal", token = "forged"))
        assertTrue(deliver(registry, session.sourceKey, oldTerminate, "old-terminate"))

        val event = registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals("signal-term", event.actionId)
        assertEquals(1L, event.generation)
    }

    @Test
    fun `outbound dismissal authorization rejects a stale generation`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        registry.registerNotification(notification(session.sessionId, 3), session.bearerToken, peer)

        assertThrows(LocalConflictException::class.java) {
            registry.authorizeNotificationGeneration(session.sessionId, 2, session.bearerToken, peer)
        }
        assertEquals(
            session.sourceKey,
            registry.authorizeNotificationGeneration(session.sessionId, 3, session.bearerToken, peer).sourceKey,
        )
    }

    @Test
    fun `full event queue applies relay backpressure without evicting unacknowledged data`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val action = registry.registerNotification(
            notification(session.sessionId, 1),
            session.bearerToken,
            peer,
        ).actions.single()

        repeat(1_024) { sequence ->
            assertTrue(
                deliver(registry, session.sourceKey, action, "event-$sequence", input = "event-$sequence"),
            )
        }
        val oldest = registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals("event-0", oldest.inputText)
        assertThrows(LocalEventQueueFullException::class.java) {
            deliver(registry, session.sourceKey, action, "must-retry", input = "must-retry")
        }
        assertEquals(oldest.id, registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)?.id)
    }

    @Test
    fun `unacknowledged event and action generation survive daemon restart`() {
        val database = DaemonDatabaseRepository(
            DaemonStorageLayout(Files.createTempDirectory("notisync-session-db").toRealPath()),
            clock,
        )
        val peer = currentPeer()
        val first = LocalSessionRegistry(resolver, clock, database = database)
        val session = first.create(peer, CreateSessionRequest("test"))
        val registration = first.registerNotification(notification(session.sessionId, 11), session.bearerToken, peer)
        assertTrue(deliver(first, session.sourceKey, registration.actions.single(), "relay-durable", input = "durable"))
        assertTrue(database.seen("relay-durable"))

        val restarted = LocalSessionRegistry(resolver, clock, database = database)
        val afterRestart = restarted.registerNotification(
            notification(session.sessionId, 12),
            session.bearerToken,
            peer,
        )
        assertTrue(afterRestart.postTime > registration.postTime)
        val event = restarted.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals(11L, event.generation)
        assertEquals("durable", event.inputText)
        assertTrue(restarted.acknowledge(session.sessionId, event.id, session.bearerToken, peer))
        assertTrue(database.load().sessions.getValue(session.sessionId).events.isEmpty())
    }

    @Test
    fun `session process action survives daemon restart and later omission`() {
        val database = DaemonDatabaseRepository(
            DaemonStorageLayout(Files.createTempDirectory("notisync-session-action").toRealPath()),
            clock,
        )
        val peer = currentPeer()
        val first = LocalSessionRegistry(resolver, clock, database = database)
        val session = first.create(peer, CreateSessionRequest("test"))
        val terminate = first.registerNotification(
            notification(session.sessionId, 1).copy(
                actions = listOf(signalAction("signal-term", "Terminate", "SIGTERM", 1)),
            ),
            session.bearerToken,
            peer,
        ).actions.single()

        val restarted = LocalSessionRegistry(resolver, clock, database = database)
        restarted.registerNotification(
            notification(session.sessionId, 2).copy(actions = emptyList()),
            session.bearerToken,
            peer,
        )

        assertTrue(deliver(restarted, session.sourceKey, terminate, "old-terminate-after-restart"))
        assertEquals(
            "signal-term",
            restarted.awaitEvent(session.sessionId, session.bearerToken, peer, 0)?.actionId,
        )
    }

    @Test
    fun `relay message id and local event commit atomically and suppress a duplicate`() {
        val database = DaemonDatabaseRepository(
            DaemonStorageLayout(Files.createTempDirectory("notisync-session-atomic").toRealPath()),
            clock,
        )
        val peer = currentPeer()
        val registry = LocalSessionRegistry(resolver, clock, database = database)
        val session = registry.create(peer, CreateSessionRequest("test"))
        val action = registry.registerNotification(
            notification(session.sessionId, 5), session.bearerToken, peer,
        ).actions.single()

        assertTrue(deliver(registry, session.sourceKey, action, "relay-once", input = "once"))
        assertTrue(deliver(registry, session.sourceKey, action, "relay-once", input = "duplicate"))

        val stored = database.load()
        assertTrue("relay-once" in stored.deduplication)
        assertEquals(1, stored.sessions.getValue(session.sessionId).events.size)
        val actionEvent = registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals("once", actionEvent.inputText)
        assertTrue(registry.acknowledge(session.sessionId, actionEvent.id, session.bearerToken, peer))

        assertTrue(registry.deliverDismissal(session.sourceKey, "sender", true, "dismissal-once"))
        assertTrue(registry.deliverDismissal(session.sourceKey, "sender", true, "dismissal-once"))
        val afterDismissal = database.load()
        assertTrue("dismissal-once" in afterDismissal.deduplication)
        assertEquals(1, afterDismissal.sessions.getValue(session.sessionId).events.size)
        assertEquals(
            LocalEventType.DISMISSAL,
            registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)?.type,
        )
    }

    @Test
    fun `Run controls reject stale input and durably deduplicate an applied signal`() {
        val database = DaemonDatabaseRepository(
            DaemonStorageLayout(Files.createTempDirectory("notisync-run-controls").toRealPath()),
            clock,
        )
        val peer = currentPeer()
        val registry = LocalSessionRegistry(resolver, clock, database = database)
        val session = registry.create(peer, CreateSessionRequest("nsrun"))
        registry.registerRunState(
            runState(session.sessionId, interactionGeneration = 2).copy(
                phase = LocalRunPhase.BLOCKED,
                updateReason = LocalRunUpdateReason.BLOCKED,
                blockedReason = LocalRunBlockedReason.TERMINAL_INPUT,
                prompt = LocalRunPromptKind.TEXT,
            ),
            null,
            session.bearerToken,
            peer,
        )

        val stale = runControl(
            requestId = "123e4567-e89b-12d3-a456-426614174001",
            kind = RunControlKind.WRITE_INPUT,
            interactionGeneration = 1,
            inputText = "stale\n",
        )
        assertEquals(
            RunControlDelivery.STALE,
            registry.deliverRunControl(stale, "android", true, "relay-stale"),
        )
        assertEquals(null, registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0))

        val signal = runControl(
            requestId = "123e4567-e89b-12d3-a456-426614174002",
            kind = RunControlKind.SIGNAL,
            signal = "KILL",
        )
        assertEquals(
            RunControlDelivery.ENQUEUED,
            registry.deliverRunControl(signal, "android", true, "relay-signal"),
        )
        assertEquals(
            RunControlDelivery.DUPLICATE,
            registry.deliverRunControl(signal, "android", true, "relay-signal-retry"),
        )
        val event = registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)!!
        assertEquals(LocalEventType.RUN_CONTROL, event.type)
        assertEquals("KILL", event.signal)

        val pendingResult = PendingRunControlResult(
            id = "result-1",
            recipient = ClientId("android"),
            result = RunControlResult(
                signal.requestId,
                signal.runId,
                RunControlResultStatus.APPLIED,
                clock.millis(),
            ),
            acceptedAt = clock.millis(),
        )
        registry.completeRunControl(
            session.sessionId,
            event.id,
            session.bearerToken,
            peer,
            pendingResult,
            InMemoryRunOutbox(),
        )

        assertEquals(null, registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0))
        assertEquals(listOf(pendingResult), database.load().runResultOutbox)
        assertEquals(
            RunControlDelivery.DUPLICATE,
            registry.deliverRunControl(signal, "android", true, "relay-signal-after-complete"),
        )
    }

    @Test
    fun `Run input requires an authoritative prompt while signals remain generation independent`() {
        val peer = currentPeer()
        val registry = LocalSessionRegistry(resolver, clock)
        val session = registry.create(peer, CreateSessionRequest("nsrun"))
        val initial = runState(session.sessionId, interactionGeneration = 0)
        registry.registerRunState(initial, null, session.bearerToken, peer)

        assertEquals(
            RunControlDelivery.REJECTED,
            registry.deliverRunControl(
                runControl(
                    requestId = "123e4567-e89b-12d3-a456-426614174010",
                    kind = RunControlKind.WRITE_INPUT,
                    interactionGeneration = 0,
                    inputText = "not requested\n",
                ),
                "android", true, "relay-running-input",
            ),
        )

        registry.registerRunState(
            initial.copy(
                revision = 2,
                phase = LocalRunPhase.BLOCKED,
                updateReason = LocalRunUpdateReason.BLOCKED,
                blockedReason = LocalRunBlockedReason.OUTPUT_AND_CPU_IDLE,
            ),
            null,
            session.bearerToken,
            peer,
        )
        assertEquals(
            RunControlDelivery.REJECTED,
            registry.deliverRunControl(
                runControl(
                    requestId = "123e4567-e89b-12d3-a456-426614174011",
                    kind = RunControlKind.WRITE_INPUT,
                    interactionGeneration = 0,
                    inputText = "still not requested\n",
                ),
                "android", true, "relay-hang-input",
            ),
        )

        registry.registerRunState(
            initial.copy(
                revision = 3,
                phase = LocalRunPhase.BLOCKED,
                updateReason = LocalRunUpdateReason.BLOCKED,
                blockedReason = LocalRunBlockedReason.TERMINAL_INPUT,
                prompt = LocalRunPromptKind.TEXT,
                interactionGeneration = 1,
            ),
            null,
            session.bearerToken,
            peer,
        )
        assertEquals(
            RunControlDelivery.STALE,
            registry.deliverRunControl(
                runControl(
                    requestId = "123e4567-e89b-12d3-a456-426614174012",
                    kind = RunControlKind.WRITE_INPUT,
                    interactionGeneration = 0,
                    inputText = "old context\n",
                ),
                "android", true, "relay-stale-prompt-input",
            ),
        )
        assertEquals(
            RunControlDelivery.ENQUEUED,
            registry.deliverRunControl(
                runControl(
                    requestId = "123e4567-e89b-12d3-a456-426614174013",
                    kind = RunControlKind.WRITE_INPUT,
                    interactionGeneration = 1,
                    inputText = "current context\n",
                ),
                "android", true, "relay-current-prompt-input",
            ),
        )
        assertEquals(
            LocalEventType.RUN_CONTROL,
            registry.awaitEvent(session.sessionId, session.bearerToken, peer, 0)?.type,
        )
    }

    private fun deliver(
        registry: LocalSessionRegistry,
        sourceKey: String,
        action: WireAction,
        relayMessageId: String,
        input: String? = null,
        title: String? = action.title,
        generation: Long? = action.generation,
        token: String? = action.actionToken,
        trusted: Boolean = true,
    ): Boolean = registry.deliverWireAction(
        sourceKey = sourceKey,
        actionIndex = action.index,
        actionTitle = title,
        inputText = input,
        senderClientId = "sender",
        senderIsTrustedOwnDevice = trusted,
        actionGeneration = generation,
        actionToken = token,
        relayMessageId = relayMessageId,
    )

    private fun currentPeer(): LocalPeer {
        val pid = ProcessHandle.current().pid()
        return LocalPeer(uid = 123, pid = pid, startTime = requireNotNull(resolver.startTime(pid)))
    }

    private fun notification(sessionId: String, generation: Long) = NotificationRequest(
        sessionId = sessionId,
        generation = generation,
        phase = NotificationPhase.BLOCKED,
        title = "Waiting",
        text = "Input needed",
        silent = false,
        ongoing = true,
        clearable = false,
        actions = listOf(
            inputAction(generation),
        ),
    )

    private fun runState(sessionId: String, interactionGeneration: Long) = RunStateRequest(
        sessionId = sessionId,
        runId = "run-1",
        revision = 1,
        phase = LocalRunPhase.RUNNING,
        updateReason = LocalRunUpdateReason.INITIAL,
        startedAt = clock.millis(),
        updatedAt = clock.millis(),
        argv = listOf("build"),
        cwd = "/work",
        usesPty = false,
        terminal = LocalRunTerminalSnapshot("running", false, 7),
        interactionGeneration = interactionGeneration,
    )

    private fun runControl(
        requestId: String,
        kind: RunControlKind,
        interactionGeneration: Long? = null,
        inputText: String? = null,
        signal: String? = null,
    ) = RunControl(
        requestId = requestId,
        hostClientId = ClientId("desktop"),
        runId = "run-1",
        kind = kind,
        requestedAt = clock.millis(),
        interactionGeneration = interactionGeneration,
        inputText = inputText,
        signal = signal,
    )

    private fun inputAction(generation: Long) = LocalNotificationAction(
        id = "input",
        title = "Input",
        kind = NotificationActionKind.REMOTE_INPUT,
        generation = generation,
    )

    private fun signalAction(id: String, title: String, signal: String, generation: Long) =
        LocalNotificationAction(
            id = id,
            title = title,
            kind = NotificationActionKind.SIGNAL,
            generation = generation,
            signal = signal,
            lifetime = NotificationActionLifetime.SESSION,
        )
}
