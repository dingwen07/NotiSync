package net.extrawdw.apps.notisync.data.storage.runtime

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportInitializationResult
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportStateEntity
import net.extrawdw.apps.notisync.data.storage.core.OperationalContinuityOrigin
import net.extrawdw.apps.notisync.data.storage.core.ReplayFenceState
import net.extrawdw.apps.notisync.data.storage.core.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreOperationalContinuityReadinessTest {
    @Test
    fun realRoomTransportRequiresExactOperationalPair() = runBlocking {
        withDatabase { database ->
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.transportStateDao().initialize(transport()),
            )
            suspend fun validate(marker: ObservedOperationalContinuityMarker?) =
                CoreOperationalContinuityValidator(
                    transportSource = { database.transportStateDao().get()?.toSnapshot() },
                    markerSource = OperationalContinuityMarkerSource { marker },
                ).validate()

            assertEquals(OperationalContinuityValidation.VALID, validate(marker()))
            assertEquals(
                OperationalContinuityValidation.GENERATION_MISMATCH,
                validate(marker(generation = 2)),
            )
            assertEquals(
                OperationalContinuityValidation.INCARNATION_MISMATCH,
                validate(marker(incarnation = "replacement")),
            )
            assertEquals(OperationalContinuityValidation.MARKER_MISSING, validate(null))
        }
    }

    private suspend fun withDatabase(block: suspend (CoreDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<CoreDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun marker(
        generation: Long = 1,
        incarnation: String = STORAGE_INCARNATION_ID,
        postCutoverWriteAt: Long? = null,
    ) = ObservedOperationalContinuityMarker(generation, incarnation, postCutoverWriteAt)

    private fun transport() = CoreTransportStateEntity(
        brokerUrl = "https://broker.example.test",
        routeEpoch = 0,
        operationalGeneration = 1,
        operationalIncarnationId = STORAGE_INCARNATION_ID,
        replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
        continuityOrigin = OperationalContinuityOrigin.VERIFIED_V51_CUTOVER,
        updatedAt = 1,
    )

    private companion object {
        const val STORAGE_INCARNATION_ID = "runtime-continuity-test-1"
    }
}
