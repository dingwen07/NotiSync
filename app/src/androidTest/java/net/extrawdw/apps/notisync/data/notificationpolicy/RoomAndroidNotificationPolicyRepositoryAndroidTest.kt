package net.extrawdw.apps.notisync.data.notificationpolicy

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
class RoomAndroidNotificationPolicyRepositoryAndroidTest {
    @Test
    fun appSubscopeAndObservedMetadataUseOneDomainBoundary() = runBlocking {
        withDatabase { database ->
            val repository = RoomAndroidNotificationPolicyRepository(database.notificationPolicyDao())
            assertTrue(repository.observeApps().first().isEmpty())

            repository.replaceApp(
                AndroidAppPolicy(
                    packageName = "net.example",
                    enabled = true,
                    mirrorOngoing = false,
                    updateIntervalSeconds = 0,
                    mirrorOngoingToIos = false,
                    mirrorMediaPlaybackToIos = false,
                    ringForCalls = true,
                    lastSeenAt = null,
                    updatedAt = 1,
                ),
            )
            repository.replaceSubscopePolicy(
                AndroidSubscopePolicy(
                    packageName = "net.example",
                    scope = NotificationPolicyScope.CHANNEL,
                    scopeId = "messages",
                    enabled = false,
                    updatedAt = 2,
                ),
            )
            val group = AndroidObservedGroup("net.example", "people", "People", 3, 4)
            val channel = AndroidObservedChannel("net.example", "messages", "Messages", "people", 3, 4)
            repository.recordSeenChannel(group, channel)

            assertEquals(true, repository.findApp("net.example")?.enabled)
            assertEquals(
                listOf("messages"),
                repository.observeSubscopePolicies("net.example", NotificationPolicyScope.CHANNEL)
                    .first().map { it.scopeId },
            )
            assertEquals(listOf(channel), repository.observeSeenChannels("net.example").first())
            assertEquals(listOf(group), repository.observeSeenGroups("net.example").first())

            assertFalse(repository.removeSubscopePolicy("net.example", NotificationPolicyScope.GROUP, "missing"))
            assertTrue(repository.removeSubscopePolicy("net.example", NotificationPolicyScope.CHANNEL, "messages"))
            assertTrue(repository.forgetApp("net.example"))
            assertTrue(repository.observeSeenChannels("net.example").first().isEmpty())
            assertTrue(repository.observeSeenGroups("net.example").first().isEmpty())
        }
    }

    @Test
    fun malformedPersistedPolicyFailsClosedAndGroupOwnershipIsChecked() = runBlocking {
        withDatabase { database ->
            database.notificationPolicyDao().upsertApp(
                net.extrawdw.apps.notisync.data.storage.operational.AndroidAppPolicyEntity(
                    packageName = "net.example",
                    enabled = true,
                    mirrorOngoing = false,
                    updateIntervalSeconds = 0,
                    mirrorOngoingToIos = false,
                    mirrorMediaPlaybackToIos = false,
                    ringForCalls = false,
                    lastSeenAt = null,
                    updatedAt = 1,
                ),
            )
            val repository = RoomAndroidNotificationPolicyRepository(database.notificationPolicyDao())
            expectFailure {
                repository.recordSeenChannel(
                    group = AndroidObservedGroup("net.other", "group", null, 1, 1),
                    channel = AndroidObservedChannel("net.example", "channel", null, "group", 1, 1),
                )
            }
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "UPDATE android_app_policy SET update_interval_seconds = -2 WHERE package_name = 'net.example'",
                )
            }
            expectFailure { repository.findApp("net.example") }
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
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("invalid policy state must fail closed", failed)
    }
}
