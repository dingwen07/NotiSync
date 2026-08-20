package net.extrawdw.apps.notisync.composition.bootstrap

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.composition.storage.LegacyV51StorageSources
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreIssueKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceAggregate
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PlanSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventory
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventorySource

internal fun interface FreshLegacyInventorySource {
    suspend fun requireAllAbsent()
}

/**
 * Attempt-local, read-only origin classification. It deliberately persists neither bit positions nor fingerprints.
 */
internal class ProductionV51LegacySourceInventorySource(
    private val coreSource: CoreV51PlanSource,
    private val allAbsentSource: FreshLegacyInventorySource,
) : V51LegacySourceInventorySource {
    override suspend fun capture(): V51LegacySourceInventory {
        val corePlan = try {
            coreSource.readPlan()
        } catch (failure: CoreV51ImportFailure) {
            return when (failure.disposition) {
                CoreV51FailureDisposition.RETRYABLE -> throw failure
                CoreV51FailureDisposition.BLOCKED -> V51LegacySourceInventory.RECOVERY_REQUIRED
            }
        }
        if (!corePlan.isAbsent) return V51LegacySourceInventory.CORE_FOUNDATION_PRESENT
        return try {
            allAbsentSource.requireAllAbsent()
            V51LegacySourceInventory.ALL_ABSENT
        } catch (failure: StorageBootstrapFailure) {
            when (failure.disposition) {
                StorageBootstrapFailureDisposition.RETRYABLE,
                StorageBootstrapFailureDisposition.USER_RECOVERABLE,
                -> throw failure
                StorageBootstrapFailureDisposition.SECURITY_BLOCKING ->
                    V51LegacySourceInventory.RECOVERY_REQUIRED
            }
        }
    }
}

/**
 * Fresh-only source inventory. It contains no decoder of its own: typed legacy DataStore interpretation remains in
 * the importer package, while this composition adapter merely requires every owned aggregate to be absent.
 */
internal class ProductionFreshLegacyInventorySource(
    private val sources: LegacyV51StorageSources,
    private val preferencesReader: LegacyOperationalPreferencesDataStoreReader,
    private val corePreferencesReader: LegacyCorePreferencesDataStoreReader,
    private val coreKeystoreReader: LegacyCoreKeystoreReader,
    private val coreFileReader: LegacyCoreFileReader,
    private val ioDispatcher: CoroutineDispatcher,
    private val sealReader: LegacySealEnrollmentDataStoreReader = LegacySealEnrollmentDataStoreReader(),
) : FreshLegacyInventorySource {
    override suspend fun requireAllAbsent(): Unit = try {
        val filesAbsent = withContext(ioDispatcher) {
            LEGACY_DATABASES.all(::databaseAndSidecarsAbsent)
        }
        if (!filesAbsent) blocked("fresh_legacy_database_present")

        val corePreferences = corePreferencesReader.read(sources.preferencesDataStore)
        if (corePreferences.status != LegacyCoreReadStatus.ABSENT || corePreferences.presentKeyCount != 0) {
            blocked("fresh_legacy_core_preferences_present")
        }
        val coreFiles = coreFileReader.read()
        if (coreFiles.status != LegacyCoreReadStatus.ABSENT || coreFiles.relevantFileCount != 0 ||
            coreFiles.skippedUnversionedHpkeFileCount != 0
        ) {
            blocked("fresh_legacy_core_files_present")
        }
        val coreKeystore = coreKeystoreReader.read()
        val keystoreCompatible = when {
            coreKeystore.status == LegacyCoreReadStatus.ABSENT -> coreKeystore.relevantAliasCount == 0
            coreKeystore.status == LegacyCoreReadStatus.RECOVERY_REQUIRED &&
                coreKeystore.relevantAliasCount == 1 -> {
                val issueKinds = coreKeystore.issues.mapTo(mutableSetOf()) { it.kind }
                issueKinds == setOf(
                    LegacyCoreKeystoreIssueKind.MISSING_OPERATIONAL_SIGNER,
                    LegacyCoreKeystoreIssueKind.MISSING_WRAPPING_KEY,
                )
            }
            else -> false
        }
        if (!keystoreCompatible) blocked("fresh_legacy_core_keystore_conflict")

        val preferenceSnapshot = preferencesReader.read(
            sources.preferencesDataStore,
            LegacyOperationalPreferenceAggregate.entries.toSet(),
        )
        if (LegacyOperationalPreferenceAggregate.entries.any { aggregate ->
                val read = preferenceSnapshot.read(aggregate)
                read.status != LegacyOperationalPreferencesReadStatus.ABSENT || read.presentKeyCount != 0
            }
        ) {
            blocked("fresh_legacy_preferences_present")
        }

        val seal = sealReader.read(sources.preferencesDataStore)
        if (seal.status != LegacySealEnrollmentStatus.DISABLED || seal.presentKeyCount != 0 ||
            seal.enrollment != null || seal.failure != null
        ) {
            blocked("fresh_legacy_seal_enrollment_present")
        }

        Unit
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: StorageBootstrapFailure) {
        throw failure
    } catch (failure: Exception) {
        throw StorageBootstrapFailure(
            StorageBootstrapFailureDisposition.RETRYABLE,
            "fresh_legacy_inventory_unavailable",
            failure,
        )
    }

    private fun databaseAndSidecarsAbsent(file: File): Boolean =
        !file.exists() && LEGACY_SQLITE_SUFFIXES.none { suffix -> File(file.path + suffix).exists() }

    private val LEGACY_DATABASES: List<File>
        get() = listOf(sources.messageLedgerFile, sources.runsFile, sources.sealHistoryFile)

    private companion object {
        val LEGACY_SQLITE_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}

private fun blocked(code: String): Nothing = throw StorageBootstrapFailure(
    StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
    code,
)
