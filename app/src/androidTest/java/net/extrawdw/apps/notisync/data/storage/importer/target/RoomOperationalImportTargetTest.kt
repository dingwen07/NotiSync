package net.extrawdw.apps.notisync.data.storage.importer.target

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.AndroidAppImport
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LEGACY_PREFERENCES_AGGREGATES
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LocalProfileImport
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesRebuildPlan
import net.extrawdw.apps.notisync.data.storage.operational.LocalProfileEntity
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.protection.OperationalPayloadKeyAlias
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadFormat
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.StorageAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOperationalImportTargetTest {
    @Test
    fun purgeInventoryCoversEveryOperationalApplicationTableAndNoCoreTable() = runBlocking {
        withDatabase { database ->
            val tables = database.useReaderConnection { connection ->
                connection.usePrepared(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                ) { statement ->
                    buildSet {
                        while (statement.step()) add(requireNotNull(statement.getText(0)))
                    }
                }
            } - setOf("room_master_table", "android_metadata", "maintenance_state")

            assertEquals(tables, RoomOperationalImportTarget.REBUILD_PURGE_TABLES.toSet())
            assertEquals(40, RoomOperationalImportTarget.REBUILD_PURGE_TABLES.size)
            assertTrue(RoomOperationalImportTarget.REBUILD_PURGE_TABLES.none { it.startsWith("core_") })
        }
    }

    @Test
    fun sameIdentityRebuildClearsPartialRowsAndRefreshesOnlyTheAttemptTime() = runBlocking {
        withDatabase { database ->
            val oldMarker = marker(1, "stable-incarnation", 1)
            database.profileDao().initializeMaintenance(oldMarker)
            database.profileDao().replaceLocalProfile(profile("partial", 1))
            val target = target(database)
            val identity = OperationalRebuildIdentity(1, "stable-incarnation", 20)

            target.beginRebuild(identity)

            assertNull(database.profileDao().observeLocalProfile().first())
            assertEquals(
                marker(1, "stable-incarnation", 20),
                database.profileDao().readMaintenance(),
            )
        }
    }

    @Test
    fun changedPreAuthorityIdentityIsRejectedWithoutTouchingRowsMarkerOrProtectedKeyEvidence() = runBlocking {
        withDatabase { database ->
            val target = target(database)
            val original = OperationalRebuildIdentity(1, "stable-incarnation", 10)
            target.beginRebuild(original)
            target.applyBatch(original, listOf(sealHistoryCommand(generation = 1)))
            database.profileDao().replaceLocalProfile(profile("partial", 10))

            val failure = assertThrows(OperationalImportFailure::class.java) {
                runBlocking {
                    target.beginRebuild(OperationalRebuildIdentity(2, "different-incarnation", 20))
                }
            }

            assertEquals("target_rebuild_identity_changed", failure.errorCode)
            assertEquals(marker(1, "stable-incarnation", 10), database.profileDao().readMaintenance())
            assertEquals("partial", database.profileDao().observeLocalProfile().first()?.deviceName)
            assertEquals(
                OperationalPayloadKeyAlias.forGeneration(1),
                database.sealDao().findRequest(REQUEST_ID)?.displayProtectionKeyRef,
            )
        }
    }

    @Test
    fun reopenedProcessReusesThePersistedIdentityAndRestartsFromZero() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "operational-rebuild-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var database = openFileDatabase(context, name)
        val identity = OperationalRebuildIdentity(1, "stable-incarnation", 10)
        try {
            target(database).beginRebuild(identity)
            database.profileDao().replaceLocalProfile(profile("partial", 10))
            database.close()

            database = openFileDatabase(context, name)
            target(database).beginRebuild(identity.copy(startedAt = 20))

            assertNull(database.profileDao().observeLocalProfile().first())
            assertEquals(marker(1, "stable-incarnation", 20), database.profileDao().readMaintenance())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun postCutoverWriteEvidenceRefusesBeforeAnyRowIsCleared() = runBlocking {
        withDatabase { database ->
            val marker = marker(1, "incarnation-a", 1)
            database.profileDao().initializeMaintenance(marker)
            database.profileDao().replaceMaintenance(marker.copy(postCutoverWriteAt = 2, updatedAt = 2))
            database.profileDao().replaceLocalProfile(profile("authoritative", 2))
            val target = target(database)

            val failure = assertThrows(OperationalImportFailure::class.java) {
                runBlocking { target.beginRebuild(OperationalRebuildIdentity(2, "incarnation-b", 3)) }
            }

            assertEquals("target_post_cutover_write_detected", failure.errorCode)
            assertEquals("authoritative", database.profileDao().observeLocalProfile().first()?.deviceName)
            assertEquals(1L, database.profileDao().readMaintenance()?.operationalGeneration)
        }
    }

    @Test
    fun purgeAndMarkerInsertAreOneRollbackCoveredTransaction() = runBlocking {
        withDatabase { database ->
            database.profileDao().initializeMaintenance(marker(2, "new-incarnation", 1))
            database.profileDao().replaceLocalProfile(profile("partial", 1))
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "CREATE TEMP TRIGGER fail_rebuild_marker BEFORE INSERT ON maintenance_state " +
                        "BEGIN SELECT RAISE(ABORT, 'forced marker failure'); END",
                )
            }

            val failure = assertThrows(OperationalImportFailure::class.java) {
                runBlocking {
                    target(database).beginRebuild(OperationalRebuildIdentity(2, "new-incarnation", 2))
                }
            }

            assertEquals("target_database_failure", failure.errorCode)
            assertEquals("partial", database.profileDao().observeLocalProfile().first()?.deviceName)
            assertEquals("new-incarnation", database.profileDao().readMaintenance()?.storageIncarnationId)
        }
    }

    @Test
    fun legacyHandledBatchUsesOnlyModernMaintenanceIdentityAndNoJournal() = runBlocking {
        withDatabase { database ->
            val identity = OperationalRebuildIdentity(1, "incarnation-a", 10)
            val target = target(database)
            target.beginRebuild(identity)

            target.applyBatch(
                identity,
                listOf(OperationalImportCommand.HandledMessageIdOnly("legacy-message", 10)),
            )

            val handled = requireNotNull(database.relayDao().findHandled("legacy-message"))
            assertEquals(MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY, handled.evidenceKind)
            assertNull(handled.authenticatedFingerprint)
        }
    }

    @Test
    fun generationChangeAfterSealMaterializationPreventsTheProtectedRow() = runBlocking {
        withDatabase { database ->
            val target = target(database)
            val identity = OperationalRebuildIdentity(1, "incarnation-a", 10)
            target.beginRebuild(identity)

            // Models reset/corruption racing after materialization but before the fenced commit.
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "UPDATE maintenance_state SET operational_generation = 2, " +
                        "storage_incarnation_id = 'incarnation-b', updated_at = 11",
                )
            }
            val failure = assertThrows(OperationalImportFailure::class.java) {
                runBlocking { target.applyBatch(identity, listOf(sealHistoryCommand(generation = 1))) }
            }

            assertEquals(ImportFailureDisposition.BLOCKED, failure.disposition)
            assertEquals("target_storage_continuity_mismatch", failure.errorCode)
            assertNull(database.sealDao().findRequest(REQUEST_ID))
        }
    }

    @Test
    fun allPreferencesAndEnrollmentRollBackTogetherOnOneInvalidProjection() = runBlocking {
        withDatabase { database ->
            val target = target(database)
            val identity = OperationalRebuildIdentity(1, "incarnation-a", 10)
            target.beginRebuild(identity)
            val profileCommand = OperationalPreferencesImportCommand.DeviceProfile(
                LocalProfileImport("Imported", 1, null, 1),
                lastSeenPostTime = null,
            )
            val invalidAndroid = OperationalPreferencesImportCommand.AndroidNotificationPolicy(
                apps = listOf(AndroidAppImport(" ", true, false, 0, false, false, true)),
                subscopes = emptyList(),
                groups = emptyList(),
                channels = emptyList(),
            )
            val plan = preferencePlan(
                mapOf(
                    StorageAggregate.DEVICE_PROFILE to profileCommand,
                    StorageAggregate.ANDROID_NOTIFICATION_POLICY to invalidAndroid,
                ),
            )

            assertThrows(OperationalImportFailure::class.java) {
                runBlocking {
                    target.applyPreferences(
                        identity,
                        plan,
                        OperationalImportCommand.SealEnrollment(ImportSealEnrollmentState.DISABLED, null, null),
                    )
                }
            }

            assertNull(database.profileDao().observeLocalProfile().first())
            assertNull(database.sealDao().readEnrollment())
        }
    }

    private fun target(database: OperationalDatabase) = RoomOperationalImportTarget(
        database = database,
        maintenanceGate = OperationalStorageMaintenanceGate(),
    )

    private fun preferencePlan(
        commands: Map<StorageAggregate, OperationalPreferencesImportCommand>,
    ) = OperationalPreferencesRebuildPlan(
        LEGACY_PREFERENCES_AGGREGATES.map { aggregate ->
            val command = commands[aggregate]
            OperationalPreferencesImportPlan(aggregate, command, if (command == null) 0 else 1)
        },
    )

    private fun marker(generation: Long, incarnation: String, updatedAt: Long) = MaintenanceStateEntity(
        operationalGeneration = generation,
        storageIncarnationId = incarnation,
        postCutoverWriteAt = null,
        lastIntegrityCheckAt = null,
        updatedAt = updatedAt,
    )

    private fun profile(name: String, time: Long) = LocalProfileEntity(
        deviceName = name,
        deviceNameUpdatedAt = time,
        profileFingerprint = null,
        profileRevisionAt = time,
        updatedAt = time,
    )

    private fun sealHistoryCommand(generation: Long) = OperationalImportCommand.SealTerminalHistory(
        requestId = REQUEST_ID,
        requesterClientId = "desktop-client",
        senderClientId = "desktop-client",
        requestFingerprint = digest(2),
        issuedAt = 1,
        expiresAt = 2,
        payloadSha256 = digest(3),
        state = ImportSealRequestState.SENT,
        outcome = ImportSealRequestOutcome.APPROVED,
        decisionAt = 3,
        createdAt = 1,
        updatedAt = 3,
        protectedDisplay = ProtectedPayload.fromStorage(
            scheme = ProtectedPayloadFormat.SCHEME,
            protectionVersion = 1,
            generation = generation,
            keyRef = OperationalPayloadKeyAlias.forGeneration(generation),
            payloadCodecVersion = 1,
            nonce = ByteArray(12) { 4 },
            ciphertext = ByteArray(16) { 5 },
        ),
        displayPlaintextDigest = digest(4),
        displayTruncated = false,
    )

    private suspend fun withDatabase(block: suspend (OperationalDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
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

    private fun openFileDatabase(context: Context, name: String): OperationalDatabase =
        Room.databaseBuilder<OperationalDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private fun digest(marker: Byte): ImportDigest = ImportDigest.sha256(ByteArray(32) { marker })

    private companion object {
        const val REQUEST_ID = "0123456789abcdef0123456789abcdef"
    }
}
