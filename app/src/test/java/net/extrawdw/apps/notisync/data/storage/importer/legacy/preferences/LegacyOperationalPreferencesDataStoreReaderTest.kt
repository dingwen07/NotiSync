package net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyOperationalPreferencesDataStoreReaderTest {
    private val reader = LegacyOperationalPreferencesDataStoreReader()

    @Test
    fun retainedScalarChangesDoNotEnterOwnedSnapshot() = runTest {
        val ownedName = stringPreferencesKey(LegacyOperationalPreferencesContract.DEVICE_NAME)
        val retained = booleanPreferencesKey("advanced_diagnostics")
        val first = mutablePreferencesOf(ownedName to "Pixel")
        val second = mutablePreferencesOf(ownedName to "Pixel", retained to true)

        val left = reader.read(first, setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE))
            .read(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE)
        val right = reader.read(second, setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE))
            .read(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE)

        assertEquals(left.presentKeyCount, right.presentKeyCount)
        assertEquals(left.values, right.values)
        assertFalse(left.toString().contains("Pixel"))
    }

    @Test
    fun coreOwnedTrustCleanupKeyIsCataloguedButNotMaterialized() = runTest {
        val empty = mutablePreferencesOf()
        val withCoreFact = mutablePreferencesOf(
            booleanPreferencesKey(LegacyOperationalPreferencesContract.CORE_OWNED_TRUST_CLEANUP_COMPLETED) to true,
        )

        reader.read(empty, setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE))
        val right = reader.read(withCoreFact, setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE))
            .read(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE)

        assertTrue(
            LegacyOperationalPreferencesContract.CORE_OWNED_TRUST_CLEANUP_COMPLETED in
                LegacyOperationalPreferencesContract.coreOwnedKeyNames,
        )
        assertEquals(LegacyOperationalPreferencesReadStatus.ABSENT, right.status)
        assertEquals(0, right.presentKeyCount)
        assertNull(right.values)
    }

    @Test
    fun everyUpdateSentinelAndGroupWithoutKnownNameRemainExact() = runTest {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.ANDROID_APP_CONFIG) to
                """{"com.example":{"updateIntervalSec":-1}}""",
            stringPreferencesKey(LegacyOperationalPreferencesContract.ANDROID_SEEN_CHANNELS) to
                """{"com.example":[{"channelId":"alerts","groupId":"important"}]}""",
        )

        val read = reader.read(preferences, setOf(LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY))
            .read(LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY)

        assertEquals(LegacyOperationalPreferencesReadStatus.READY, read.status)
        val values = read.values as LegacyAndroidNotificationPreferences
        assertEquals(-1, values.appConfigs.getValue("com.example").updateIntervalSec)
        assertEquals("important", values.seenChannels.getValue("com.example").single().groupId)
        assertNull(values.seenChannels.getValue("com.example").single().groupName)
    }

    @Test
    fun invalidCadenceAndWrongTypedSecurityStateFailClosedWithoutValues() = runTest {
        val invalidCadence = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.ANDROID_APP_CONFIG) to
                """{"com.example":{"updateIntervalSec":-2}}""",
        )
        val cadenceRead = reader.read(
            invalidCadence,
            setOf(LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY),
        ).read(LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY)
        assertEquals(LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED, cadenceRead.status)
        assertNull(cadenceRead.values)
        assertTrue(cadenceRead.issues.any { it.kind == LegacyOperationalPreferencesIssueKind.INVALID_POLICY_VALUE })

        val wrongTypedScreen = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.SCREEN_ENABLED) to "true",
        )
        val screenRead = reader.read(wrongTypedScreen, setOf(LegacyOperationalPreferenceAggregate.SCREEN))
            .read(LegacyOperationalPreferenceAggregate.SCREEN)
        assertEquals(LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED, screenRead.status)
        assertNull(screenRead.values)
        assertEquals(
            setOf(
                LegacyOperationalPreferencesIssue(
                    LegacyOperationalPreferencesIssueKind.WRONG_VALUE_TYPE,
                    LegacyOperationalPreferenceField.SCREEN_ENABLED,
                ),
            ),
            screenRead.issues,
        )
        assertFalse(screenRead.toString().contains("true"))
    }

    @Test
    fun independentIosAllowlistAndSeenSetsDoNotRequirePlaceholders() = runTest {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.IOS_ENABLED_BUNDLES) to
                """["enabled.only"]""",
            stringPreferencesKey(LegacyOperationalPreferencesContract.IOS_DISCOVERED_APPS) to
                """{"seen.only":{"bundleId":"seen.only","displayName":"Seen","lastSeen":42}}""",
        )

        val read = reader.read(preferences, setOf(LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY))
            .read(LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY)

        assertEquals(LegacyOperationalPreferencesReadStatus.READY, read.status)
        val values = read.values as LegacyIosAppPreferences
        assertEquals(setOf("enabled.only"), values.enabledBundleIds)
        assertEquals(setOf("seen.only"), values.discoveredApps.keys)
    }

    @Test
    fun absentRequestedAggregatesAreExplicitAndUnrequestedKeysAreNotParsed() = runTest {
        val malformedFilterOnly = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.INCOMING_FILTERS) to "not-json",
        )

        val snapshot = reader.read(
            malformedFilterOnly,
            setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE),
        )
        val profile = snapshot.read(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE)

        assertEquals(setOf(LegacyOperationalPreferenceAggregate.DEVICE_PROFILE), snapshot.aggregates)
        assertEquals(LegacyOperationalPreferencesReadStatus.ABSENT, profile.status)
        assertEquals(0, profile.presentKeyCount)
    }

    @Test
    fun combinedOperationalAndSealAttemptCollectsExactlyOneDataStoreEmission() = runTest {
        var collections = 0
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(LegacyOperationalPreferencesContract.DEVICE_NAME) to "Pixel",
        )
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                collections++
                emit(preferences)
            }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("legacy reader must not mutate DataStore")
        }

        val snapshot = LegacyOperationalPreferencesAttemptReader(dataStore).read()

        assertEquals(1, collections)
        assertEquals(LegacyOperationalPreferenceAggregate.entries.toSet(), snapshot.operational.aggregates)
        assertEquals(
            net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentStatus.DISABLED,
            snapshot.sealEnrollment.status,
        )
    }
}
