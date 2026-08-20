package net.extrawdw.apps.notisync.data.iosregistry

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

@RunWith(AndroidJUnit4::class)
class RoomIosAppRegistryRepositoryAndroidTest {
    @Test
    fun allowlistAndSeenRegistryRemainIndependent() = runBlocking {
        withDatabase { database ->
            val repository = RoomIosAppRegistryRepository(database.iosAppDao())
            assertTrue(repository.observeAllowlisted().first().isEmpty())
            assertTrue(repository.observeSeen().first().isEmpty())

            val bundleId = "com.apple.MobileSMS"
            assertTrue(repository.setEnabled(bundleId, true))
            assertFalse(repository.setEnabled(bundleId, true))
            repository.recordSeen(bundleId, "Messages", 1)
            assertEquals(
                listOf(IosSeenApp(bundleId, "Messages", 1)),
                repository.observeSeen().first(),
            )
            assertEquals(IosAllowlistedApp(bundleId), repository.findAllowlisted(bundleId))

            assertTrue(repository.forgetSeen(bundleId))
            assertTrue(repository.observeSeen().first().isEmpty())
            assertEquals(IosAllowlistedApp(bundleId), repository.findAllowlisted(bundleId))

            assertFalse(repository.setEnabled("net.extrawdw.apps.NotiSync", true))
        }
    }

    @Test
    fun malformedPersistedSeenMetadataFailsClosed() = runBlocking {
        withDatabase { database ->
            database.iosAppDao().putSeen(
                net.extrawdw.apps.notisync.data.storage.operational.IosSeenAppEntity(
                    bundleId = "com.example.app",
                    displayName = "Example",
                    lastSeenAt = 1,
                ),
            )
            database.useWriterConnection { connection ->
                connection.executeSQL("UPDATE ios_seen_app SET display_name = '' WHERE bundle_id = 'com.example.app'")
            }
            val repository = RoomIosAppRegistryRepository(database.iosAppDao())
            var failed = false
            try {
                repository.observeSeen().first()
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue("invalid iOS seen metadata must fail closed", failed)
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
