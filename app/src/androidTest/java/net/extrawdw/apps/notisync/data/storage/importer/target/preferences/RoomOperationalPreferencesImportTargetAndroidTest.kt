package net.extrawdw.apps.notisync.data.storage.importer.target.preferences

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.incomingfilter.CanonicalIncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterCanonicalizer
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleValue
import net.extrawdw.apps.notisync.data.storage.operational.IosSeenAppEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomOperationalPreferencesImportTargetAndroidTest {
    @Test
    fun oneTransactionPreservesCadenceCanonicalFilterAndIndependentIosSets() = runTest {
        withDatabase { database ->
            val canonical = IncomingFilterCanonicalizer.canonicalize(
                listOf(IncomingFilterRuleValue(CanonicalIncomingFilterOrigin.ANDROID_LOCAL, "com.example", "alerts")),
            )
            val commands = mapOf(
                StorageAggregate.ANDROID_NOTIFICATION_POLICY to
                    OperationalPreferencesImportCommand.AndroidNotificationPolicy(
                        apps = listOf(AndroidAppImport("com.example", true, false, -1, false, false, true)),
                        subscopes = emptyList(),
                        groups = emptyList(),
                        channels = emptyList(),
                    ),
                StorageAggregate.INCOMING_FILTERS to OperationalPreferencesImportCommand.IncomingFilters(
                    listOf(
                        IncomingFilterImport(
                            requesterClientId = "desktop",
                            canonicalizationVersion = IncomingFilterCanonicalizer.VERSION,
                            updatedAt = 10,
                            ruleSetDigest = digest(canonical.digestCopy()),
                            rules = canonical.rules.map { rule ->
                                IncomingFilterRuleImport(
                                    ImportNotificationOrigin.ANDROID_LOCAL,
                                    rule.value.appId,
                                    rule.value.channelId,
                                    digest(rule.digestCopy()),
                                )
                            },
                        ),
                    ),
                ),
                StorageAggregate.IOS_APP_REGISTRY to OperationalPreferencesImportCommand.IosApps(
                    allowlistedBundleIds = listOf("enabled.only"),
                    seenApps = listOf(IosSeenAppImport("seen.only", "Seen", 20)),
                ),
            )
            val plan = plan(commands)
            val target = RoomOperationalPreferencesImportTarget(database)

            database.withWriteTransaction { target.applyAll(plan, importStartedAt = 100) }

            assertTrue(target.verifyAll(plan, importStartedAt = 100))
            assertEquals(-1, database.notificationPolicyDao().findApp("com.example")?.updateIntervalSeconds)
            assertEquals(1, database.incomingFilterDao().observeRules("desktop").first().size)
            assertTrue(database.iosAppDao().findAllowlisted("enabled.only") != null)
            assertNull(database.iosAppDao().findSeen("enabled.only"))
            assertNull(database.iosAppDao().findAllowlisted("seen.only"))
            assertEquals("Seen", database.iosAppDao().findSeen("seen.only")?.displayName)

            database.iosAppDao().putSeen(IosSeenAppEntity("enabled.only", "Enabled", 21))
            assertEquals(1, database.iosAppDao().forgetSeen("enabled.only"))
            assertTrue(database.iosAppDao().findAllowlisted("enabled.only") != null)
        }
    }

    @Test
    fun invalidLateAggregateRollsBackEveryEarlierPreferenceProjection() = runTest {
        withDatabase { database ->
            val commands = mapOf(
                StorageAggregate.ANDROID_NOTIFICATION_POLICY to
                    OperationalPreferencesImportCommand.AndroidNotificationPolicy(
                        apps = listOf(AndroidAppImport("com.example", true, false, 0, false, false, true)),
                        subscopes = emptyList(),
                        groups = emptyList(),
                        channels = emptyList(),
                    ),
                StorageAggregate.IOS_APP_REGISTRY to OperationalPreferencesImportCommand.IosApps(
                    allowlistedBundleIds = listOf("enabled.only"),
                    seenApps = listOf(IosSeenAppImport("seen.only", "Seen", lastSeenAt = 0)),
                ),
            )
            val target = RoomOperationalPreferencesImportTarget(database)

            try {
                database.withWriteTransaction { target.applyAll(plan(commands), importStartedAt = 100) }
                error("expected invalid iOS projection")
            } catch (_: IllegalArgumentException) {
                // Expected target invariant rejection.
            }

            assertTrue(database.notificationPolicyDao().observeApps().first().isEmpty())
            assertTrue(database.iosAppDao().observeAllowlist().first().isEmpty())
            assertTrue(database.iosAppDao().observeSeen().first().isEmpty())
        }
    }

    @Test
    fun absentPlanRequiresAnActuallyPristineAggregate() = runTest {
        withDatabase { database ->
            database.iosAppDao().putAllowlisted("existing")
            val target = RoomOperationalPreferencesImportTarget(database)

            try {
                database.withWriteTransaction { target.applyAll(plan(emptyMap()), importStartedAt = 100) }
                error("expected non-pristine conflict")
            } catch (failure: OperationalPreferencesImportFailure) {
                assertEquals("preferences_absent_target_not_pristine", failure.errorCode)
            }

            assertFalse(database.iosAppDao().observeAllowlist().first().isEmpty())
        }
    }

    private fun plan(
        commands: Map<StorageAggregate, OperationalPreferencesImportCommand>,
    ) = OperationalPreferencesRebuildPlan(
        LEGACY_PREFERENCES_AGGREGATES.map { aggregate ->
            val command = commands[aggregate]
            OperationalPreferencesImportPlan(aggregate, command, if (command == null) 0 else 1)
        },
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

    private fun digest(bytes: ByteArray) = OperationalPreferencesImportDigest.from(bytes)
}
