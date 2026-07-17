package net.extrawdw.notisync.daemon.peer.storage

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.extrawdw.notisync.daemon.PendingNotification
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePeerPersistenceTest : StorageTestSupport() {
    @Test
    fun `trust batches merge and null deletes without losing other stable keys`() {
        val layout = layout()
        val trust = FileTrustPersistence(layout)
        trust.write(mapOf("entries" to "one", "cards" to "two"))
        trust.write(mapOf("entries" to "updated", "cards" to null, "signature" to "sig"))

        val recreated = FileTrustPersistence(layout)
        assertEquals("updated", recreated.read("entries"))
        assertNull(recreated.read("cards"))
        assertEquals("sig", recreated.read("signature"))
    }

    @Test
    fun `auth token save replace and clear are durable`() {
        val layout = layout()
        val repository = FileAuthTokenRepository(layout)
        val token = IntegrityVerificationResponse(
            token = "broker-bearer",
            clientId = ClientId("desktop"),
            expiresAt = 123_456L,
        )
        repository.save(token)
        assertEquals(token, FileAuthTokenRepository(layout).load())

        FileAuthTokenRepository(layout).save(null)
        assertNull(repository.load())
    }

    @Test
    fun `database keeps sessions while dedup and outbox update across recreation`() {
        val layout = layout()
        val clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC)
        val repository = DaemonDatabaseRepository(layout, clock, maximumDedupEntries = 2)
        val session = StoredLocalSession(
            id = "session-1",
            sourceKey = "local:source",
            bearerHash = ByteArray(32) { it.toByte() },
            uid = 501,
            pid = 10,
            startTime = "boot:123",
            clientName = "test",
            createdAt = 4_000,
            actions = listOf(StoredWireAction(7, "interrupt", "Interrupt", 1, false)),
            events = linkedMapOf(
                "event-1" to LocalEvent(
                    id = "event-1",
                    type = LocalEventType.ACTION,
                    sessionId = "session-1",
                    createdAtEpochMillis = 4_500,
                ),
            ),
        )
        repository.update { it.copy(sessions = linkedMapOf(session.id to session)) }
        repository.record("message-1")
        repository.enqueue(notification("running", NotificationPhase.INITIAL, "local:source"))

        val recreated = DaemonDatabaseRepository(layout, clock, maximumDedupEntries = 2)
        assertTrue(recreated.seen("message-1"))
        val stored = recreated.load().sessions.getValue(session.id)
        assertEquals(session.id, stored.id)
        assertEquals(session.sourceKey, stored.sourceKey)
        assertTrue(session.bearerHash.contentEquals(stored.bearerHash))
        assertEquals(session.actions, stored.actions)
        assertEquals(session.events, stored.events)
        assertEquals("running", recreated.peek()!!.id)
    }

    @Test
    fun `outbox coalesces periodic state and completion supersedes all unsent source states`() {
        val repository = DaemonDatabaseRepository(layout())
        repository.enqueue(notification("initial-a", NotificationPhase.INITIAL, "source-a"))
        repository.enqueue(notification("periodic-a-1", NotificationPhase.PERIODIC, "source-a"))
        repository.enqueue(notification("periodic-b", NotificationPhase.PERIODIC, "source-b"))
        repository.enqueue(notification("periodic-a-2", NotificationPhase.PERIODIC, "source-a"))

        assertEquals(3, repository.pendingCount())
        repository.enqueue(notification("complete-a", NotificationPhase.COMPLETED, "source-a"))
        assertEquals(2, repository.pendingCount())
        assertEquals(listOf("periodic-b", "complete-a"), repository.load().outbox.map { it.id })

        repository.retryLater("periodic-b")
        assertEquals("complete-a", repository.peek()!!.id)
        repository.remove("complete-a")
        assertEquals("periodic-b", repository.peek()!!.id)
    }

    @Test
    fun `dedup repository evicts oldest bounded entry`() {
        var now = 1L
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant = Instant.ofEpochMilli(now++)
        }
        val repository = DaemonDatabaseRepository(layout(), clock, maximumDedupEntries = 2)
        repository.record("one")
        repository.record("two")
        repository.record("three")

        assertFalse(repository.seen("one"))
        assertTrue(repository.seen("two"))
        assertTrue(repository.seen("three"))
    }

    private fun notification(id: String, phase: NotificationPhase, sourceKey: String) = PendingNotification(
        id = id,
        sourceKey = sourceKey,
        request = NotificationRequest(
            sessionId = "session",
            generation = 1,
            phase = phase,
            title = "title",
            text = "text",
            silent = phase == NotificationPhase.PERIODIC,
            ongoing = phase != NotificationPhase.COMPLETED,
            clearable = phase == NotificationPhase.COMPLETED,
        ),
        postTime = 1,
        acceptedAt = 1,
    )

    private fun layout() = DaemonStorageLayout(temporaryDirectory.resolve(".notisync"))
}
