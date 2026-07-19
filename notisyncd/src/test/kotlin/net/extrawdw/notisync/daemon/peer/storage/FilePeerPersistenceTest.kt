package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
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
    fun `database persists only application profile and relay deduplication`() {
        val layout = layout()
        val clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC)
        val repository = DaemonDatabaseRepository(layout, clock, maximumDedupEntries = 2)
        repository.record("message-1")

        val recreated = DaemonDatabaseRepository(layout, clock, maximumDedupEntries = 2)
        assertTrue(recreated.seen("message-1"))
        assertEquals(1, recreated.load().schemaVersion)
        assertTrue(recreated.load().applications.isEmpty())
        val encoded = Files.readString(layout.databaseFile)
        listOf(
            "sessions",
            "genericOutbox",
            "submissions",
            "streamSequences",
            "runOutbox",
            "runResultOutbox",
            "runIosOutbox",
        ).forEach {
            assertFalse(encoded.contains("\"$it\""))
        }
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

    private fun layout() = DaemonStorageLayout(temporaryDirectory.resolve(".notisync"))
}
