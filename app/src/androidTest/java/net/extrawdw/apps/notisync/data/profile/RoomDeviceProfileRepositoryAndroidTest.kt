package net.extrawdw.apps.notisync.data.profile

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

@RunWith(AndroidJUnit4::class)
class RoomDeviceProfileRepositoryAndroidTest {
    @Test
    fun missingAggregatesAreEmptyAndRoomRowsMapToDomainValues() = runBlocking {
        withDatabase { database ->
            val repository = RoomDeviceProfileRepository(database.profileDao())
            assertNull(repository.readProfile())

            val profile = DeviceProfile(
                deviceName = "Pixel",
                deviceNameUpdatedAt = 2,
                profileFingerprint = "profile-fingerprint",
                profileRevisionAt = 3,
                updatedAt = 4,
            )
            repository.replaceProfile(profile)
            assertEquals(profile, repository.readProfile())
            assertEquals(profile, repository.observeProfile().first())
        }
    }

    @Test
    fun malformedPersistedProfileFailsClosedInsteadOfBecomingAPlaceholder() = runBlocking {
        withDatabase { database ->
            database.profileDao().replaceLocalProfile(
                net.extrawdw.apps.notisync.data.storage.operational.LocalProfileEntity(
                    deviceName = "Valid",
                    deviceNameUpdatedAt = 1,
                    profileFingerprint = null,
                    profileRevisionAt = 1,
                    updatedAt = 1,
                ),
            )
            database.useWriterConnection { connection ->
                connection.executeSQL("UPDATE local_profile SET device_name = '' WHERE singleton_id = 1")
            }
            val repository = RoomDeviceProfileRepository(database.profileDao())
            expectFailure { repository.readProfile() }
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

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("corrupt persisted profile must fail closed", failed)
    }
}
