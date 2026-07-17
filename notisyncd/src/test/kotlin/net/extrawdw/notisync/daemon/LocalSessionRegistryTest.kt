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
