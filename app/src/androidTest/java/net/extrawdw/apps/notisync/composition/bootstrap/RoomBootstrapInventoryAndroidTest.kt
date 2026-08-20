package net.extrawdw.apps.notisync.composition.bootstrap

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.composition.storage.StorageClock
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBootstrapInventoryAndroidTest {
    @Test
    fun newlyCreatedCoreHasExactEmptyBootstrapInventory() = runBlocking {
        withCoreDatabase { database ->
            val snapshot = RoomCoreBootstrapTargetSnapshotSource(database, FIXED_CLOCK).read()

            assertEquals(0L, snapshot.totalApplicationRowCount)
            assertEquals(0L, snapshot.keystoreOperationRowCount)
            assertNull(snapshot.transport)
            assertNull(snapshot.identity)
            assertEquals(CoreBootstrapTargetDecision.InspectLegacy, StorageBootstrapOriginResolver.classifyTarget(snapshot))
        }
    }

    @Test
    fun rebuildIdentityReusesTheExactPreAuthorityMarker() = runBlocking {
        withOperationalDatabase { database ->
            assertEquals(
                net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao.MaintenanceInitializeResult.INITIALIZED,
                database.profileDao().initializeMaintenance(
                    MaintenanceStateEntity(
                        operationalGeneration = 1,
                        storageIncarnationId = "bootstrap-test-incarnation",
                        postCutoverWriteAt = null,
                        lastIntegrityCheckAt = null,
                        updatedAt = 10,
                    ),
                ),
            )
            val identity = RoomOperationalRebuildIdentitySource(database, FIXED_CLOCK)
                .resolve(OperationalRebuildPurpose.VERIFIED_V51)

            assertEquals(1L, identity.operationalGeneration)
            assertEquals("bootstrap-test-incarnation", identity.storageIncarnationId)
            assertEquals(20L, identity.startedAt)
        }
    }

    private suspend fun withCoreDatabase(block: suspend (CoreDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<CoreDatabase>(
            context = ApplicationProvider.getApplicationContext(),
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

    private suspend fun withOperationalDatabase(block: suspend (OperationalDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
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

    private companion object {
        val FIXED_CLOCK = StorageClock { 20L }
    }
}
