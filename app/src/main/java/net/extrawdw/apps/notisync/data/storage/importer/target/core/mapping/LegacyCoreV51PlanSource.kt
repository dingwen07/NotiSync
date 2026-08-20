package net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PlanSource

/**
 * Read-only production source adapter. The caller supplies the already-created application DataStore and readers;
 * this type never creates a second DataStore, opens Room, or mutates/unwraps any legacy source.
 */
internal class LegacyCoreV51PlanSource(
    private val preferencesDataStore: DataStore<Preferences>,
    private val preferencesReader: LegacyCorePreferencesDataStoreReader,
    private val keystoreReader: LegacyCoreKeystoreReader,
    private val fileReader: LegacyCoreFileReader,
    private val mapper: LegacyCoreV51Mapper,
) : CoreV51PlanSource {
    override suspend fun readPlan(): CoreV51ImportPlan = try {
        coroutineScope {
            val preferences = async { preferencesReader.read(preferencesDataStore) }
            val keystore = async { keystoreReader.read() }
            val files = async { fileReader.read() }
            mapper.map(preferences.await(), keystore.await(), files.await())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreSourceReadException) {
        throw CoreV51ImportFailure(CoreV51FailureDisposition.RETRYABLE, "source_temporarily_unavailable", failure)
    } catch (failure: CoreV51MappingException) {
        throw CoreV51ImportFailure(
            CoreV51FailureDisposition.BLOCKED,
            "source_${failure.issue.name.lowercase()}",
            failure,
        )
    }
}
