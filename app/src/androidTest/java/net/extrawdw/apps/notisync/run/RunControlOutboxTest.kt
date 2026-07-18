package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunControlOutboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun enqueueIsDeduplicatedAndSurvivesReopenUntilRemoval() {
        val original = signal("00000000-0000-4000-8000-000000000010", "INT")
        val conflictingDuplicate = original.copy(signal = "TERM")
        RunControlOutbox(context).also { outbox ->
            outbox.enqueue(original)
            outbox.enqueue(conflictingDuplicate)
            outbox.close()
        }

        RunControlOutbox(context).also { reopened ->
            assertEquals(listOf(original), reopened.pending())
            reopened.remove(original.requestId)
            assertTrue(reopened.pending().isEmpty())
            reopened.close()
        }
    }

    private fun signal(requestId: String, value: String) = RunControl(
        requestId = requestId,
        hostClientId = ClientId("host"),
        runId = "run-1",
        kind = RunControlKind.SIGNAL,
        requestedAt = 1_000,
        signal = value,
    )

    companion object {
        private const val DB_NAME = "run_control_outbox.db"
    }
}
