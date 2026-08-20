package net.extrawdw.apps.notisync.data.storage.importer.legacy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealEnrollmentDataStoreReaderTest {
    private val files = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach(CoroutineScope::cancel)
        files.forEach { it.delete() }
    }

    @Test
    fun allSixKeysAbsentIsDisabledAndDoesNotNeedAnImportClock() = runBlocking {
        val dataStore = newDataStore()

        val snapshot = LegacySealEnrollmentDataStoreReader().read(dataStore)

        assertEquals(LegacySealEnrollmentStatus.DISABLED, snapshot.status)
        assertNull(snapshot.enrollment)
        assertNull(snapshot.failure)
        assertEquals(0, snapshot.presentKeyCount)
    }

    @Test
    fun validTupleIsReadyAndUnrelatedPreferencesRemainOutsideTheOwnedSnapshot() = runBlocking {
        val dataStore = newDataStore()
        writeValidTuple(dataStore)
        val reader = LegacySealEnrollmentDataStoreReader()

        val first = reader.read(dataStore)
        dataStore.edit { values -> values[stringPreferencesKey("unrelated_key")] = "ignored" }
        val second = reader.read(dataStore)

        assertEquals(LegacySealEnrollmentStatus.READY, first.status)
        assertEquals("OpenKeychain", first.enrollment?.providerId)
        assertEquals("provider-ref", first.enrollment?.providerKeyReference)
        assertEquals("89ABCDEF01234567", first.enrollment?.primaryKeyId)
        assertEquals("Alice <alice@example.test>", first.enrollment?.displayIdentity)
        assertEquals(1234L, first.enrollment?.enrolledAt)
        assertEquals(6, first.presentKeyCount)
        assertEquals(first.status, second.status)
        assertEquals(first.presentKeyCount, second.presentKeyCount)
        assertTrue(first.toString().contains("READY"))
        assertFalse(first.toString().contains("provider-ref"))
    }

    @Test
    fun partialAndWrongTypedTuplesRequireRecoveryWithoutExposingValues() = runBlocking {
        val dataStore = newDataStore()
        val reader = LegacySealEnrollmentDataStoreReader()

        dataStore.edit { values ->
            values[ENABLED] = true
            values[PROVIDER] = "OpenKeychain"
            values[PROVIDER_REFERENCE] = "provider-ref"
            values[PRIMARY_KEY_ID] = "89ABCDEF01234567"
            values[DISPLAY_IDENTITY] = "Alice"
        }
        val missingTime = reader.read(dataStore)
        assertEquals(LegacySealEnrollmentStatus.RECOVERY_REQUIRED, missingTime.status)
        assertEquals(LegacySealEnrollmentFailure.INVALID_ENROLLED_AT, missingTime.failure)

        dataStore.edit { values -> values.clear(); values[WRONG_ENABLED] = "true" }
        val wrongType = reader.read(dataStore)
        assertEquals(LegacySealEnrollmentStatus.RECOVERY_REQUIRED, wrongType.status)
        assertEquals(LegacySealEnrollmentFailure.UNSUPPORTED_KEY_TYPE, wrongType.failure)
        assertFalse(wrongType.toString().contains("true"))
    }

    @Test
    fun malformedTupleValuesAreClassifiedAtTheirBoundaries() = runBlocking {
        val dataStore = newDataStore()
        val reader = LegacySealEnrollmentDataStoreReader()

        dataStore.edit { values ->
            values[ENABLED] = true
            values[PROVIDER] = "x".repeat(257)
            values[PROVIDER_REFERENCE] = "provider-ref"
            values[PRIMARY_KEY_ID] = "89ABCDEF01234567"
            values[DISPLAY_IDENTITY] = "Alice"
            values[ENROLLED_AT] = 1234L
        }
        assertEquals(
            LegacySealEnrollmentFailure.INVALID_PROVIDER,
            reader.read(dataStore).failure,
        )

        dataStore.edit { values -> values[PROVIDER] = "OpenKeychain"; values[DISPLAY_IDENTITY] = "\n" }
        assertEquals(
            LegacySealEnrollmentFailure.INVALID_DISPLAY_IDENTITY,
            reader.read(dataStore).failure,
        )

        dataStore.edit { values -> values[DISPLAY_IDENTITY] = "Alice"; values[PRIMARY_KEY_ID] = "89abcdef01234567" }
        assertEquals(
            LegacySealEnrollmentFailure.INVALID_PRIMARY_KEY_ID,
            reader.read(dataStore).failure,
        )

        dataStore.edit { values -> values[PRIMARY_KEY_ID] = "89ABCDEF01234567"; values[ENROLLED_AT] = 0L }
        assertEquals(
            LegacySealEnrollmentFailure.INVALID_ENROLLED_AT,
            reader.read(dataStore).failure,
        )
    }

    @Test
    fun disabledTupleWithRetainedMaterialIsNotSilentlyImported() = runBlocking {
        val dataStore = newDataStore()
        dataStore.edit { values ->
            values[ENABLED] = false
            values[PROVIDER] = "OpenKeychain"
        }

        val snapshot = LegacySealEnrollmentDataStoreReader().read(dataStore)

        assertEquals(LegacySealEnrollmentStatus.RECOVERY_REQUIRED, snapshot.status)
        assertEquals(LegacySealEnrollmentFailure.PARTIAL_TUPLE, snapshot.failure)
        assertNull(snapshot.enrollment)
    }

    private fun newDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("legacy-seal-enrollment-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        files += file
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private suspend fun writeValidTuple(dataStore: DataStore<Preferences>) {
        dataStore.edit { values ->
            values[ENABLED] = true
            values[PROVIDER] = "OpenKeychain"
            values[PROVIDER_REFERENCE] = "provider-ref"
            values[PRIMARY_KEY_ID] = "89ABCDEF01234567"
            values[DISPLAY_IDENTITY] = "Alice <alice@example.test>"
            values[ENROLLED_AT] = 1234L
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey(LegacySealEnrollmentSourceContract.ENABLED_KEY)
        val WRONG_ENABLED = stringPreferencesKey(LegacySealEnrollmentSourceContract.ENABLED_KEY)
        val PROVIDER = stringPreferencesKey(LegacySealEnrollmentSourceContract.PROVIDER_KEY)
        val PROVIDER_REFERENCE = stringPreferencesKey(LegacySealEnrollmentSourceContract.PROVIDER_REFERENCE_KEY)
        val PRIMARY_KEY_ID = stringPreferencesKey(LegacySealEnrollmentSourceContract.PRIMARY_KEY_ID_KEY)
        val DISPLAY_IDENTITY = stringPreferencesKey(LegacySealEnrollmentSourceContract.DISPLAY_IDENTITY_KEY)
        val ENROLLED_AT = longPreferencesKey(LegacySealEnrollmentSourceContract.ENROLLED_AT_KEY)
    }
}
