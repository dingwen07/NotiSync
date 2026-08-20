package net.extrawdw.apps.notisync.data.continuity

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOperationalContinuityRepositoryAndroidTest {
    @Test
    fun pristineMarkerInsertAndImmutableReplaySemantics() = runBlocking {
        withDatabase { database ->
            val repository = RoomOperationalContinuityRepository(database.profileDao())
            val initialization = OperationalContinuityInitialization(1, "continuity-test", 10)

            assertEquals(
                OperationalContinuityInitializationResult.INSERTED,
                repository.initializeIfPristine(initialization),
            )
            assertEquals(
                OperationalContinuityInitializationResult.ALREADY_INITIALIZED,
                repository.initializeIfPristine(initialization.copy(initializedAt = 99)),
            )
            assertEquals(
                OperationalContinuityInitializationResult.CONFLICT,
                repository.initializeIfPristine(initialization.copy(operationalGeneration = 2)),
            )
            assertEquals(
                OperationalContinuityInitializationResult.CONFLICT,
                repository.initializeIfPristine(initialization.copy(storageIncarnationId = "other")),
            )
            assertEquals(initialization.operationalGeneration, repository.readMaintenance()?.operationalGeneration)
            assertEquals(initialization.storageIncarnationId, repository.readMaintenance()?.storageIncarnationId)
        }
    }

    @Test
    fun everyOperationalLogicalGroupBlocksPristineInsertWithoutWritingMarker() = runBlocking {
        evidenceRows().forEach { (label, sql) ->
            withDatabase { database ->
                database.useWriterConnection { connection -> connection.executeSQL(sql) }
                val repository = RoomOperationalContinuityRepository(database.profileDao())

                assertEquals(
                    label,
                    OperationalContinuityInitializationResult.CONFLICT,
                    repository.initializeIfPristine(
                        OperationalContinuityInitialization(1, "blocked-$label", 1),
                    ),
                )
                assertNull(database.profileDao().readMaintenance())
            }
        }
    }

    @Test
    fun concurrentSameMarkerInitializationHasOneWinner() = runBlocking {
        withDatabase { database ->
            val repository = RoomOperationalContinuityRepository(database.profileDao())
            val initialization = OperationalContinuityInitialization(1, "concurrent", 1)
            val results = coroutineScope {
                listOf(
                    async(Dispatchers.Default) { repository.initializeIfPristine(initialization) },
                    async(Dispatchers.Default) { repository.initializeIfPristine(initialization) },
                ).map { it.await() }
            }

            assertEquals(
                setOf(
                    OperationalContinuityInitializationResult.INSERTED,
                    OperationalContinuityInitializationResult.ALREADY_INITIALIZED,
                ),
                results.toSet(),
            )
            assertEquals(initialization.storageIncarnationId, database.profileDao().readMaintenance()?.storageIncarnationId)
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

    private fun evidenceRows(): List<Pair<String, String>> = listOf(
        "profile" to "INSERT INTO local_profile(singleton_id, device_name, device_name_updated_at, profile_revision_at, updated_at) VALUES (1, 'Pixel', 1, 1, 1)",
        "policy" to "INSERT INTO android_app_policy(package_name, enabled, mirror_ongoing, update_interval_seconds, mirror_ongoing_to_ios, mirror_media_playback_to_ios, ring_for_calls, updated_at) VALUES ('com.example', 1, 0, 0, 0, 0, 0, 1)",
        "filter" to "INSERT INTO incoming_filter(requester_client_id, canonicalization_version, updated_at, received_at, rule_set_digest) VALUES ('peer', 1, 1, 1, zeroblob(32))",
        "ios" to "INSERT INTO ios_app_allowlist(bundle_id) VALUES ('com.example.ios')",
        "activity" to "INSERT INTO activity_event(event_id, occurred_at, recorded_at, feature, semantic_action, direction, outcome, render_args_version, render_args, coalesced_count) VALUES ('event', 1, 1, 'PROFILE', 'UPDATED', 'INBOUND', 'APPLIED', 1, zeroblob(0), 0)",
        "delivery" to "INSERT INTO message_dedup(message_id, authenticated_fingerprint, evidence_kind, handled_at) VALUES ('message', zeroblob(32), 'AUTHENTICATED', 1)",
        "mirror" to "INSERT INTO mirror_lifecycle(source_client_id, source_key, post_time, dismissed_at, updated_at) VALUES ('source', 'key', 1, NULL, 1)",
        "run" to "INSERT INTO run_state(host_client_id, run_id, revision, phase, presented_revision, active, updated_at, ended_at, received_at, payload, payload_digest) VALUES ('host', 'run', 1, 'ACTIVE', 0, 1, 1, NULL, 1, zeroblob(0), zeroblob(32))",
        "seal" to "INSERT INTO seal_enrollment(singleton_id, state, updated_at) VALUES (1, 'UNENROLLED', 1)",
        "screen" to "INSERT INTO screen_authorized_peer(peer_id, granted_at, updated_at) VALUES ('peer', 1, 1)",
        "ssh" to "INSERT INTO ssh_provider_state(singleton_id, inventory_generation, revision, updated_at) VALUES (1, 'generation', 1, 1)",
    )
}
