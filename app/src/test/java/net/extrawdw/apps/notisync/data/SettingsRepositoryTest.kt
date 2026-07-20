package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private fun newRepository(initialBrokerUrl: String? = null): SettingsRepository {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("settings-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        if (initialBrokerUrl != null) runBlocking {
            ds.edit { it[stringPreferencesKey("broker_url")] = initialBrokerUrl }
        }
        return SettingsRepository(ds, scope)
    }

    @Test
    fun legacyProductionBroker_isAutomaticallyUpgradedToV2() = runBlocking {
        val settings = newRepository("wss://notisync-api.extrawdw.net")

        assertEquals(SettingsRepository.DEFAULT_BROKER, settings.brokerUrl.value)
        settings.setBrokerUrl("https://notisync-api.extrawdw.net/")
        assertEquals(SettingsRepository.DEFAULT_BROKER, settings.brokerUrl.value)
    }

    @Test
    fun customBroker_isNotChangedByDefaultMigration() = runBlocking {
        val settings = newRepository("https://broker.example.test")

        assertEquals("https://broker.example.test", settings.brokerUrl.value)
        settings.setBrokerUrl("https://notisync-api.extrawdw.net.evil.example")
        assertEquals("https://notisync-api.extrawdw.net.evil.example", settings.brokerUrl.value)
    }

    @Test
    fun unverifiedDeviceCleanup_usesAnIndependentOneTimeMarker() = runBlocking {
        val settings = newRepository("https://broker.example.test")

        assertTrue(settings.needsUnverifiedDeviceCleanupV1())
        settings.markUnverifiedDeviceCleanupV1Completed()
        assertFalse(settings.needsUnverifiedDeviceCleanupV1())

        settings.setBrokerUrl("https://notisync-api.extrawdw.net")
        assertFalse(settings.needsUnverifiedDeviceCleanupV1())
    }

    @Test
    fun callRingerEnabled_defaultsOff() = runBlocking {
        val settings = newRepository()

        assertEquals(false, settings.callRingerEnabled.value)
    }

    @Test
    fun screenMirroring_isExplicitOptInAndHasPersistedColdRead() = runBlocking {
        val settings = newRepository()

        assertFalse(settings.screenMirroringEnabled.value)
        assertFalse(settings.screenMirroringEnabledNow())
        settings.setScreenMirroringEnabled(true)
        assertTrue(settings.screenMirroringEnabledNow())
    }

    @Test
    fun epochForFcmRoute_reusesEpochForSameRouteAndBumpsForNewRoute() = runBlocking {
        val settings = newRepository()

        assertEquals(1, settings.epochForFcmRoute("fid-a"))
        assertEquals(1, settings.epochForFcmRoute("fid-a"))
        assertEquals(2, settings.epochForFcmRoute("fid-b"))
        assertEquals(2, settings.epochForFcmRoute("fid-b"))
    }

    @Test
    fun selfProfileRevision_advancesOnlyWhenCompleteDeclarationChanges() = runBlocking {
        val settings = newRepository()

        assertEquals(100L, settings.ensureSelfProfileRevision("name|android|caps-v1", now = 100L))
        assertNull(settings.ensureSelfProfileRevision("name|android|caps-v1", now = 200L))
        assertEquals(200L, settings.ensureSelfProfileRevision("name|android|caps-v2", now = 200L))
        assertEquals(200L, settings.selfProfileUpdatedAt.value)
    }
}
