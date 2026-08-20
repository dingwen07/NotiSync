package net.extrawdw.apps.notisync.composition.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadCipher
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyVault

/** One clock for the complete user-open migration and its persisted transitions. */
internal fun interface StorageClock {
    fun nowMillis(): Long
}

/**
 * The same vault instance owns protected payload encryption and deterministic generation-key reconciliation.
 * This is a concrete crypto capability, not a readiness assertion supplied by the application graph.
 */
internal interface OperationalPayloadVaultPort : ProtectedPayloadCipher, OperationalPayloadKeyVault

/** Non-secret legacy mapping input retained only inside the importer composition. */
internal data class OperationalPreferencesCutoverDefaults(
    val legacyDeviceName: String,
) {
    init {
        require(legacyDeviceName.isNotBlank() && legacyDeviceName.length <= 1_024) {
            "legacy device-name default is invalid"
        }
        require(legacyDeviceName.none(Char::isISOControl)) { "legacy device-name default contains controls" }
    }
}

/** Handles to retained, read-only v51 sources. */
internal data class LegacyV51StorageSources(
    val preferencesDataStore: DataStore<Preferences>,
    val coreFilesDirectory: Path,
    val messageLedgerFile: File,
    val runsFile: File,
    val sealHistoryFile: File,
    val noBackupStagingDirectory: File,
)

internal data class StorageBootstrapDependencies(
    val defaultBrokerUrl: String,
) {
    init {
        require(defaultBrokerUrl.isNotBlank()) { "Default broker URL must not be blank" }
    }
}

/** Concrete platform inputs; no origin, completion, or readiness claim can be injected here. */
internal data class StorageContainerDependencies(
    val ioDispatcher: CoroutineDispatcher,
    val clock: StorageClock,
    val payloadVault: OperationalPayloadVaultPort,
    val preferencesCutoverDefaults: OperationalPreferencesCutoverDefaults,
    val legacyV51Sources: LegacyV51StorageSources,
    val bootstrap: StorageBootstrapDependencies,
)
