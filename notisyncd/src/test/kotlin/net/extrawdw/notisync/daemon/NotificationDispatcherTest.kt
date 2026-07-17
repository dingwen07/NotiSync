package net.extrawdw.notisync.daemon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDispatcherTest {
    private val resolver = ProcessIdentityResolver()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC)

    @Test
    fun `invalid replacement does not invalidate last accepted actions`() {
        val registry = LocalSessionRegistry(resolver, clock)
        val peer = currentPeer()
        val session = registry.create(peer, CreateSessionRequest("test"))
        val current = notification(session.sessionId, generation = 1)
        val currentRegistration = registry.registerNotification(current, session.bearerToken, peer)
        val dispatcher = NotificationDispatcher(
            sessions = registry,
            outbox = InMemoryNotificationOutbox(),
            sender = object : NotificationMeshSender {
                override val clientId = ClientId("a".repeat(52))
                override suspend fun send(item: PendingNotification) = Unit
            },
            clock = clock,
        )

        assertThrows(IllegalArgumentException::class.java) {
            dispatcher.accept(
                notification(session.sessionId, generation = 2).copy(shortCriticalText = "12345678"),
                session.bearerToken,
                peer,
            )
        }

        assertTrue(
            registry.deliverWireAction(
                sourceKey = session.sourceKey,
                actionIndex = currentRegistration.actions.single().index,
                actionTitle = "Input",
                inputText = "still current",
                senderClientId = "trusted-own-peer",
                senderIsTrustedOwnDevice = true,
                actionGeneration = currentRegistration.actions.single().generation,
                actionToken = currentRegistration.actions.single().actionToken,
                relayMessageId = "notification-dispatcher-current-action",
            ),
        )
    }

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
        shortCriticalText = "Input",
        silent = false,
        ongoing = true,
        clearable = false,
        actions = listOf(
            LocalNotificationAction(
                id = "input",
                title = "Input",
                kind = NotificationActionKind.REMOTE_INPUT,
                generation = generation,
            ),
        ),
    )
}
