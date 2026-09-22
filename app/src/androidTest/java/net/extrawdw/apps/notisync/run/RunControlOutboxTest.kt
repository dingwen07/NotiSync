package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption
import net.extrawdw.apps.notisync.data.storage.operational.RunControlStorage
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
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
    private val context: Context = RoomStorageTestContext(
        ApplicationProvider.getApplicationContext(),
        "run-control-outbox",
    )

    @Before
    fun clearBefore() {
        context.deleteDatabase(DB_NAME)
        initializeOperationalTestDatabase(context)
    }

    @After
    fun clearAfter() {
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

    @Test
    fun scalarControlsSurviveReopenInTimestampThenRequestIdOrder() {
        val refresh = RunControl(
            requestId = "00000000-0000-4000-8000-000000000001",
            hostClientId = ClientId("host"),
            runId = "run-1",
            kind = RunControlKind.REFRESH,
            requestedAt = 900,
        )
        val emptyInput = refresh.copy(
            requestId = "00000000-0000-4000-8000-000000000002",
            kind = RunControlKind.WRITE_INPUT,
            requestedAt = 1_000,
            interactionGeneration = 0,
            inputText = "",
        )
        val unicodeInput = emptyInput.copy(
            requestId = "00000000-0000-4000-8000-000000000003",
            interactionGeneration = 4_294_967_296L,
            inputText = "  λ 中文 😀\r\nnext line\n",
        )
        val realTimeSignal = signal("00000000-0000-4000-8000-000000000004", "RTMIN+1")
        val numericSignal = signal("00000000-0000-4000-8000-000000000005", "09")
        val ordered = listOf(refresh, emptyInput, unicodeInput, realTimeSignal, numericSignal)
        RunControlOutbox(context).use { outbox -> ordered.reversed().forEach(outbox::enqueue) }

        RunControlOutbox(context).use { reopened ->
            assertEquals(ordered, reopened.pending())
        }
        OperationalDatabaseEncryption.open(context).use { database ->
            val columns = database.rawQuery("PRAGMA table_info(run_controls)", emptyArray()).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            assertEquals(RunControlStorage.columns.toSet(), columns)
            database.rawQuery(
                "SELECT typeof(interaction_generation), typeof(input_text), typeof(signal) " +
                    "FROM run_controls WHERE request_id = ?",
                arrayOf(emptyInput.requestId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("integer", cursor.getString(0))
                assertEquals("text", cursor.getString(1))
                assertEquals("null", cursor.getString(2))
            }
            database.rawQuery(
                "EXPLAIN QUERY PLAN SELECT request_id FROM run_controls ORDER BY requested_at, request_id",
                emptyArray(),
            ).use { cursor ->
                val plan = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                }.joinToString("\n")
                assertTrue(plan, plan.contains("run_controls_order_idx"))
            }
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
        private const val DB_NAME = OperationalDatabase.DATABASE_NAME
    }
}
