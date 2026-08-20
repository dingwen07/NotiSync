package net.extrawdw.apps.notisync.data.storage.importer.target.preferences

import java.util.Base64
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyAndroidNotificationPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyIncomingFilterPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyIosApp
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyIosAppPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyNotificationFilterRule
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyNotificationOrigin
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceRead
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceAggregate
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceValues
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyPerAppConfig
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyScreenPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacySeenChannel
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyFilterSync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyOperationalPreferencesMapperTest {
    private val mapper = LegacyOperationalPreferencesMapper(LegacyDeviceProfileImportDefaults("Fallback device"))

    @Test
    fun androidCadenceAndBestKnownGroupNameArePreserved() {
        val values = LegacyAndroidNotificationPreferences(
            enabledPackages = setOf("com.example"),
            appConfigs = mapOf("com.example" to LegacyPerAppConfig(updateIntervalSec = -1)),
            seenChannels = mapOf(
                "com.example" to listOf(
                    LegacySeenChannel("a", groupId = "g", groupName = null),
                    LegacySeenChannel("b", groupId = "g", groupName = "Important"),
                ),
            ),
        )

        val command = mapper.map(
            LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY,
            ready(values),
            importStartedAt = 10,
        ).command as OperationalPreferencesImportCommand.AndroidNotificationPolicy

        assertEquals(-1, command.apps.single().updateIntervalSeconds)
        assertEquals("Important", command.groups.single().groupName)
        assertEquals(listOf("a", "b"), command.channels.map { it.channelId })
    }

    @Test
    fun conflictingObservedGroupNamesBlockInsteadOfChoosingOne() {
        val values = LegacyAndroidNotificationPreferences(
            enabledPackages = emptySet(),
            appConfigs = emptyMap(),
            seenChannels = mapOf(
                "com.example" to listOf(
                    LegacySeenChannel("a", groupId = "g", groupName = "One"),
                    LegacySeenChannel("b", groupId = "g", groupName = "Two"),
                ),
            ),
        )

        val failure = assertThrows(OperationalPreferencesImportFailure::class.java) {
            mapper.map(
                LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY,
                ready(values),
                importStartedAt = 10,
            )
        }

        assertEquals("android_seen_group_name_conflict", failure.errorCode)
    }

    @Test
    fun canonicalFiltersArePermutationStableAndCarryTheStorageVersion() {
        val android = LegacyNotificationFilterRule(
            LegacyNotificationOrigin.ANDROID_LOCAL,
            appId = "com.example",
            channelId = "alerts",
        )
        val ios = LegacyNotificationFilterRule(LegacyNotificationOrigin.IOS_ANCS, appId = "com.apple.mail")
        fun command(rules: List<LegacyNotificationFilterRule>) = mapper.map(
            LegacyOperationalPreferenceAggregate.INCOMING_FILTERS,
            ready(LegacyIncomingFilterPreferences(mapOf("desktop" to LegacyFilterSync(rules, updatedAt = 5)))),
            importStartedAt = 10,
        ).command as OperationalPreferencesImportCommand.IncomingFilters

        val first = command(listOf(android, ios, android)).filters.single()
        val second = command(listOf(ios, android)).filters.single()

        assertEquals(1, first.canonicalizationVersion)
        assertEquals(2, first.rules.size)
        assertArrayEquals(first.ruleSetDigest.copyBytes(), second.ruleSetDigest.copyBytes())
        first.rules.zip(second.rules).forEach { (left, right) ->
            assertEquals(left.copy(digest = right.digest), right)
            assertArrayEquals(left.digest.copyBytes(), right.digest.copyBytes())
        }
    }

    @Test
    fun iosAllowlistAndSeenMetadataStayIndependentWithoutPlaceholders() {
        val command = mapper.map(
            LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY,
            ready(
                LegacyIosAppPreferences(
                    enabledBundleIds = setOf("enabled.only", "net.extrawdw.apps.notisync"),
                    discoveredApps = mapOf(
                        "seen.only" to LegacyIosApp("seen.only", "Seen", 7),
                    ),
                ),
            ),
            importStartedAt = 10,
        ).command as OperationalPreferencesImportCommand.IosApps

        assertEquals(listOf("enabled.only"), command.allowlistedBundleIds)
        assertEquals(listOf("seen.only"), command.seenApps.map { it.bundleId })
        assertFalse(command.seenApps.any { it.bundleId == "enabled.only" })
    }

    @Test
    fun screenReplayPairsRequireAdjacentSameExpirySecurityEvidence() {
        val session = digestToken(1)
        val route = digestToken(2)
        val valid = LegacyScreenPreferences(
            enabled = true,
            authorizedPeerIds = setOf("desktop"),
            replayEntries = listOf(session to 100L, route to 100L),
            replayBlocked = null,
            replayQuarantineDigest = null,
            replayQuarantinedAt = null,
            codecPreferences = emptyMap(),
        )

        val command = mapper.map(
            LegacyOperationalPreferenceAggregate.SCREEN,
            ready(valid),
            importStartedAt = 10,
        ).command as OperationalPreferencesImportCommand.Screen
        assertEquals(1, command.replayPairs.size)
        assertEquals(100L, command.replayPairs.single().expiresAt)
        assertNull(command.quarantineDigest)

        val mismatch = valid.copy(replayEntries = listOf(session to 100L, route to 101L))
        val failure = assertThrows(OperationalPreferencesImportFailure::class.java) {
            mapper.map(LegacyOperationalPreferenceAggregate.SCREEN, ready(mismatch), importStartedAt = 10)
        }
        assertEquals("screen_replay_pairing_invalid", failure.errorCode)
    }

    private fun ready(values: LegacyOperationalPreferenceValues) = LegacyOperationalPreferenceRead(
        aggregate = values.aggregate,
        status = LegacyOperationalPreferencesReadStatus.READY,
        presentKeyCount = 1,
        values = values,
        issues = emptySet(),
    )

    private fun digestToken(value: Byte): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { value })
}
