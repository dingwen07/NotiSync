package net.extrawdw.apps.notisync.data.profile

import android.content.Context
import android.os.Build
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

@RunWith(AndroidJUnit4::class)
class RoomProfileCaptureFacadeAndroidTest {
    @Test
    fun profileRevisionIsIdempotentAndCaptureWatermarkIsMonotonic() = runBlocking {
        withDatabase { database ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val facade = RoomProfileCaptureFacade(database, scope)

            assertEquals(Build.MODEL, facade.deviceName.value)
            assertEquals(100L, facade.ensureSelfProfileRevision("fingerprint-v1", now = 100L))
            assertNull(facade.ensureSelfProfileRevision("fingerprint-v1", now = 200L))

            facade.setDeviceName("Pixel", now = 300L)
            assertEquals("Pixel", facade.readProfile()?.deviceName)
            assertEquals(300L, facade.readProfile()?.deviceNameUpdatedAt)
            assertEquals(100L, facade.readProfile()?.profileRevisionAt)
            assertEquals(400L, facade.ensureSelfProfileRevision("fingerprint-v2", now = 400L))

            facade.recordLastSeenPostTime(50L)
            assertEquals(50L, facade.lastSeenPostTime())
            facade.recordLastSeenPostTime(20L)
            assertEquals(50L, facade.lastSeenPostTime())

            scope.cancel()
        }
    }

    private suspend fun withDatabase(block: suspend (OperationalDatabase) -> Unit) {
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
}
