package net.extrawdw.notisync.screen

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayProtectorPersistenceTest {
    @Test
    fun `durable cache stores only independent hashes and rejects either replay after restart`() {
        val directory = Files.createTempDirectory("notisync-screen-replay-")
        val path = directory.resolve("requests.cache")
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val token = ByteArray(ROUTING_TOKEN_BYTES) { 0x37 }
        try {
            assertTrue(FileReplayProtector(path, clock).record("private-session-id", token, 20_000))
            val persisted = Files.readString(path)
            assertFalse(persisted.contains("private-session-id"))
            assertFalse(persisted.contains(HexFormat.of().formatHex(token)))
            assertTrue(persisted.lineSequence().filter(String::isNotBlank).count() == 2)

            val restarted = FileReplayProtector(path, clock)
            assertFalse(restarted.record("private-session-id", ByteArray(ROUTING_TOKEN_BYTES) { 0x22 }, 20_000))
            assertFalse(restarted.record("different-session", token, 20_000))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
