package net.extrawdw.apps.notisync.data.storage.importer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.profile.RoomDeviceProfileRepository
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.ImportClock
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacyMessageLedgerSourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacyRunsStagingSourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacySealEnrollmentMapper
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacySealHistorySourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.OperationalCutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.OperationalRebuildResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesAttemptReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.target.RoomOperationalImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.SealImportPayloadMaterializer
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyDeviceProfileImportDefaults
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyOperationalPreferencesMapper
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedCiphertext
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadCipher
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyVault
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rebuilds the clean Operational Room database from the ignored capture pulled from the connected
 * device. The test is skipped when that local capture is not available (for example, on CI).
 *
 * This test intentionally does not construct a RunControl adapter: outgoing controls are ephemeral
 * by policy, while incoming relay messages remain recoverable from the server until acknowledged.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedDeviceMigrationFixtureAndroidTest {
    @Test
    fun connectedDeviceOperationalDataRebuildsWithoutReadingDebugOnlySshDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureContext = InstrumentationRegistry.getInstrumentation().context
        assumeTrue("connected-device fixture archive is not available", hasFixture(fixtureContext))

        val fixtureRoot = File(context.cacheDir, "connected-device-migration-${System.nanoTime()}")
        val sourceRoot = File(fixtureRoot, "source")
        val stagingRoot = File(fixtureRoot, "staging")
        check(sourceRoot.mkdirs() && stagingRoot.mkdirs()) { "unable to create fixture staging directories" }

        try {
            FIXTURE_FILES.forEach { path ->
                copyFixtureAsset(fixtureContext, path, File(sourceRoot, path))
            }
            val before = AUTHORITATIVE_FIXTURE_FILES
                .filterNot { it.endsWith("-shm") }
                .associateWith { path -> sha256(File(sourceRoot, path)) }

            val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore = preferenceDataStore(sourceRoot, preferencesScope)
            val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(context)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            try {
                val maintenanceGate = OperationalStorageMaintenanceGate()
                val materializer = SealImportPayloadMaterializer(
                    protector = OperationalProtectedPayloadProtector(FixtureCipher()),
                    payloadKeyVault = NoOpPayloadKeyVault,
                    maintenanceGate = maintenanceGate,
                    ioDispatcher = Dispatchers.IO,
                )
                val coordinator = OperationalCutoverCoordinator(
                    adapters = listOf(
                        LegacyMessageLedgerSourceAdapter(File(sourceRoot, "databases/message_ledger.db"), stagingRoot),
                        LegacyRunsStagingSourceAdapter(File(sourceRoot, "databases/runs.db"), stagingRoot),
                        LegacySealHistorySourceAdapter(
                            File(sourceRoot, "databases/openpgp_signing.db"),
                            materializer,
                        ),
                    ),
                    preferencesReader = LegacyOperationalPreferencesAttemptReader(
                        dataStore,
                        LegacyOperationalPreferencesDataStoreReader(),
                    ),
                    preferencesMapper = LegacyOperationalPreferencesMapper(
                        LegacyDeviceProfileImportDefaults("Connected device"),
                    ),
                    sealEnrollmentMapper = LegacySealEnrollmentMapper(materializer),
                    target = RoomOperationalImportTarget(database, maintenanceGate, materializer),
                    clock = ImportClock { 1_800_000_000_000L },
                )

                val result = coordinator.rebuild(
                    operationalGeneration = 1,
                    storageIncarnationId = "connected-device-fixture",
                )
                assertTrue("result=$result", result is OperationalRebuildResult.Complete)
                assertEquals(137L, (result as OperationalRebuildResult.Complete).summary.skippedRowCount)
                assertEquals(6_585L, database.count("message_dedup"))
                assertEquals(217L, database.count("mirror_lifecycle"))
                assertEquals(31L, database.count("run_state"))
                assertEquals(42L, database.count("seal_request"))
                assertEquals(0L, database.count("relay_batch_stage"))

                // Hydrate the actual migrated profile, not just its row count. The captured v51 convergence
                // fingerprint uses U+001F tuple separators and must remain readable by the production repository.
                val migratedProfile = RoomDeviceProfileRepository(database.profileDao()).readProfile()
                assertTrue("connected-device profile did not hydrate", migratedProfile != null)
                assertTrue(
                    "connected-device profile fingerprint was not preserved",
                    migratedProfile?.profileFingerprint?.contains('\u001f') == true,
                )

                // The transferable Core source is validated from this same device capture. Keystore-backed
                // activation is intentionally exercised separately because aliases cannot be copied to an AVD.
                val corePreferences = LegacyCorePreferencesDataStoreReader().read(dataStore)
                assertEquals(LegacyCoreReadStatus.READY, corePreferences.status)
                val coreFiles = LegacyCoreFileReader(File(sourceRoot, "files").toPath()).read()
                assertEquals(LegacyCoreReadStatus.READY, coreFiles.status)
                assertEquals(3, coreFiles.relevantFileCount)
                assertEquals(2, coreFiles.skippedUnversionedHpkeFileCount)

                val after = AUTHORITATIVE_FIXTURE_FILES
                    .filterNot { it.endsWith("-shm") }
                    .associateWith { path -> sha256(File(sourceRoot, path)) }
                assertEquals(before, after)

                // The debug SSH database is part of the captured app-private data, but it is not a
                // supported Operational source and must not be opened by this migrator.
                assertTrue(File(sourceRoot, "databases/ssh-key-provider.sqlite3").isFile)
            } finally {
                database.close()
                preferencesScope.cancel()
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    private fun hasFixture(context: Context): Boolean = runCatching {
        context.assets.open("$ASSET_ROOT/files/datastore/notisync.preferences_pb").use { }
        true
    }.getOrDefault(false)

    private fun copyFixtureAsset(context: Context, path: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open("$ASSET_ROOT/$path").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun preferenceDataStore(
        sourceRoot: File,
        scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(sourceRoot, PREFERENCES_FILE).also { it.parentFile?.mkdirs() }
    }

    private suspend fun OperationalDatabase.count(table: String): Long = useReaderConnection { connection ->
        connection.usePrepared("SELECT COUNT(*) FROM `$table`") { statement ->
            check(statement.step()) { "count query returned no row" }
            statement.getLong(0)
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        val hex = "0123456789abcdef"
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }

    private class FixtureCipher : ProtectedPayloadCipher {
        override fun protect(alias: String, plaintext: ByteArray, aad: ByteArray): ProtectedCiphertext {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(alias.encodeToByteArray() + aad)
            return ProtectedCiphertext(
                nonce = digest.copyOf(12),
                ciphertext = plaintext.copyOf() + ByteArray(16),
            )
        }

        override fun open(
            alias: String,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
        ): ByteArray = ciphertext.copyOf(ciphertext.size - 16)
    }

    private object NoOpPayloadKeyVault : OperationalPayloadKeyVault {
        override suspend fun create(generation: Long) = Unit
        override suspend fun selfTest(generation: Long) = Unit
    }

    private companion object {
        const val ASSET_ROOT = "connected-device-v51"
        const val PREFERENCES_FILE = "files/datastore/notisync.preferences_pb"

        val FIXTURE_FILES = listOf(
            "databases/message_ledger.db",
            "databases/message_ledger.db-journal",
            "databases/message_ledger.db-wal",
            "databases/message_ledger.db-shm",
            "databases/runs.db",
            "databases/runs.db-wal",
            "databases/runs.db-shm",
            "databases/openpgp_signing.db",
            "databases/openpgp_signing.db-journal",
            "databases/openpgp_signing.db-wal",
            "databases/openpgp_signing.db-shm",
            "databases/run_control_outbox.db",
            "databases/run_control_outbox.db-journal",
            "databases/ssh-key-provider.sqlite3",
            "databases/ssh-key-provider.sqlite3-wal",
            "databases/ssh-key-provider.sqlite3-shm",
            PREFERENCES_FILE,
            "files/auth_token.wrapped",
            "files/hpke_private.wrapped",
            "files/hpke_public.bin",
            "files/hpke_private.epoch3.wrapped",
            "files/hpke_public.epoch3.bin",
        )

        val AUTHORITATIVE_FIXTURE_FILES = FIXTURE_FILES
    }
}
