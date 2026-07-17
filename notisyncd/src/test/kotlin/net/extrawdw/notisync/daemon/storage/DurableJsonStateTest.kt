package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableJsonStateTest : StorageTestSupport() {
    @Serializable
    private data class QueueState(
        val outbox: Map<String, String> = emptyMap(),
        val events: Map<String, String> = emptyMap(),
        val deduplication: Set<String> = emptySet(),
        val updates: Int = 0,
    )

    @Test
    fun `missing state loads defaults and initialize persists it`() {
        val path = temporaryDirectory.resolve("data/state/runtime.json")
        val store = store(path)

        assertEquals(QueueState(), store.load())
        assertFalse(store.exists())
        assertEquals(QueueState(), store.initialize())
        assertTrue(store.exists())
        assertEquals(
            SecureFileSystem.FILE_PERMISSIONS,
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun `queue indexes survive a new store instance`() {
        val path = temporaryDirectory.resolve("data/state/runtime.json")
        val expected = QueueState(
            outbox = mapOf("notification-1" to "running"),
            events = mapOf("action-1" to "remote-input"),
            deduplication = setOf("relay-message-1"),
        )
        store(path).save(expected)

        assertEquals(expected, store(path).load())
    }

    @Test
    fun `updates are serialized within a store`() {
        val path = temporaryDirectory.resolve("data/state/runtime.json")
        val store = store(path)
        store.initialize()
        val workers = 8
        val iterations = 20
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        repeat(workers) {
            executor.submit {
                start.await()
                repeat(iterations) {
                    store.update { it.copy(updates = it.updates + 1) }
                }
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(workers * iterations, store.load().updates)
    }

    @Test
    fun `corrupt state is reported instead of reset`() {
        val path = temporaryDirectory.resolve("data/state/runtime.json")
        SecureFileSystem().atomicWrite(path, "not-json".encodeToByteArray())

        val error = assertThrows(IllegalStateException::class.java) { store(path).load() }

        assertTrue(error.message!!.contains(path.toString()))
        assertEquals("not-json", Files.readString(path))
    }

    @Test
    fun `delete removes committed state`() {
        val path = temporaryDirectory.resolve("data/state/runtime.json")
        val store = store(path)
        store.initialize()

        assertTrue(store.delete())
        assertFalse(store.exists())
    }

    private fun store(path: java.nio.file.Path): DurableJsonState<QueueState> = DurableJsonState(
        path = path,
        serializer = QueueState.serializer(),
        defaultValue = ::QueueState,
    )
}
