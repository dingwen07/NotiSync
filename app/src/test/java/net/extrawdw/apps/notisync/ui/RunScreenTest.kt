package net.extrawdw.apps.notisync.ui

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunScreenTest {
    @Test
    fun failedInputSubmissionPreservesDraft() = runBlocking {
        var submitted = ""

        val result = submitRunInput("retry me\n\n") { terminalLine ->
            submitted = terminalLine
            false
        }

        assertFalse(result.accepted)
        assertEquals("retry me\n\n", result.input)
        assertEquals("retry me\n", submitted)
    }

    @Test
    fun acceptedInputSubmissionClearsDraft() = runBlocking {
        val result = submitRunInput("continue") { true }

        assertTrue(result.accepted)
        assertEquals("", result.input)
    }

    @Test
    fun historyDeletionUsesProvidedIoDispatcher() {
        val callerThread = Thread.currentThread().name
        var clearThread = callerThread
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "run-history-test-io")
        }

        executor.asCoroutineDispatcher().use { dispatcher ->
            val cleared = runBlocking {
                runStorageMutation(
                    mutation = {
                        clearThread = Thread.currentThread().name
                        true
                    },
                    ioDispatcher = dispatcher,
                )
            }

            assertTrue(cleared)
        }
        assertNotEquals(callerThread, clearThread)
        assertTrue(clearThread.startsWith("run-history-test-io"))
    }
}
