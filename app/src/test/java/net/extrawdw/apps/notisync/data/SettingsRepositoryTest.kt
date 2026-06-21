package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private fun newRepository(): SettingsRepository {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("settings-${System.nanoTime()}", ".preferences_pb").also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return SettingsRepository(ds, scope)
    }

    @Test
    fun epochForFcmRoute_reusesEpochForSameRouteAndBumpsForNewRoute() = runBlocking {
        val settings = newRepository()

        assertEquals(1, settings.epochForFcmRoute("fid-a"))
        assertEquals(1, settings.epochForFcmRoute("fid-a"))
        assertEquals(2, settings.epochForFcmRoute("fid-b"))
        assertEquals(2, settings.epochForFcmRoute("fid-b"))
    }
}
