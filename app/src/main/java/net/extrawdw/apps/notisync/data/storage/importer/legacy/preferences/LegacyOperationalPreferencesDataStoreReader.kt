package net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentSnapshot

/** One typed view of every Operational-owned value read from a single DataStore emission. */
internal data class LegacyOperationalPreferencesAttemptSnapshot(
    val operational: LegacyOperationalPreferencesSnapshot,
    val sealEnrollment: LegacySealEnrollmentSnapshot,
)

internal fun interface LegacyOperationalPreferencesAttemptSource {
    suspend fun read(): LegacyOperationalPreferencesAttemptSnapshot
}

/** Dedicated legacy reader that is the only owner of the DataStore snapshot operation. */
internal class LegacyOperationalPreferencesAttemptReader(
    private val dataStore: DataStore<Preferences>,
    private val operationalReader: LegacyOperationalPreferencesDataStoreReader =
        LegacyOperationalPreferencesDataStoreReader(),
    private val sealEnrollmentReader: LegacySealEnrollmentDataStoreReader =
        LegacySealEnrollmentDataStoreReader(),
) : LegacyOperationalPreferencesAttemptSource {
    override suspend fun read(): LegacyOperationalPreferencesAttemptSnapshot {
        val preferences = dataStore.data.first()
        return LegacyOperationalPreferencesAttemptSnapshot(
            operational = operationalReader.read(
                preferences,
                LegacyOperationalPreferenceAggregate.entries.toSet(),
            ),
            sealEnrollment = sealEnrollmentReader.read(preferences),
        )
    }
}

/**
 * Takes one snapshot from the application's existing DataStore instance and decodes only requested
 * Operational-owned keys. It never edits the source or fingerprints retained scalar preferences.
 */
internal class LegacyOperationalPreferencesDataStoreReader {
    suspend fun read(
        dataStore: DataStore<Preferences>,
        aggregates: Set<LegacyOperationalPreferenceAggregate>,
    ): LegacyOperationalPreferencesSnapshot = read(dataStore.data.first(), aggregates)

    internal fun read(
        preferences: Preferences,
        aggregates: Set<LegacyOperationalPreferenceAggregate>,
    ): LegacyOperationalPreferencesSnapshot {
        require(aggregates.isNotEmpty()) { "at least one preferences aggregate must be requested" }
        return LegacyOperationalPreferencesSnapshot(
            aggregates.associateWith { aggregate -> readAggregate(preferences, aggregate) },
        )
    }

    private fun readAggregate(
        preferences: Preferences,
        aggregate: LegacyOperationalPreferenceAggregate,
    ): LegacyOperationalPreferenceRead {
        val raw = when (aggregate) {
            LegacyOperationalPreferenceAggregate.DEVICE_PROFILE -> readDeviceProfileRaw(preferences)
            LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY -> readAndroidRaw(preferences)
            LegacyOperationalPreferenceAggregate.INCOMING_FILTERS -> readFiltersRaw(preferences)
            LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY -> readIosRaw(preferences)
            LegacyOperationalPreferenceAggregate.SCREEN -> readScreenRaw(preferences)
        }
        if (raw.presentKeyCount == 0) {
            return LegacyOperationalPreferenceRead(
                aggregate = aggregate,
                status = LegacyOperationalPreferencesReadStatus.ABSENT,
                presentKeyCount = 0,
                values = null,
                issues = emptySet(),
            )
        }
        val issues = linkedSetOf<LegacyOperationalPreferencesIssue>()
        raw.values.filter { it.typeError }.forEach { value ->
            issues += LegacyOperationalPreferencesIssue(
                LegacyOperationalPreferencesIssueKind.WRONG_VALUE_TYPE,
                value.field,
            )
        }
        val values = if (issues.isEmpty()) parse(aggregate, raw, issues) else null
        return LegacyOperationalPreferenceRead(
            aggregate = aggregate,
            status = if (issues.isEmpty()) {
                LegacyOperationalPreferencesReadStatus.READY
            } else {
                LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED
            },
            presentKeyCount = raw.presentKeyCount,
            values = values,
            issues = issues,
        )
    }

    private fun parse(
        aggregate: LegacyOperationalPreferenceAggregate,
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyOperationalPreferenceValues? = when (aggregate) {
        LegacyOperationalPreferenceAggregate.DEVICE_PROFILE -> parseDeviceProfile(raw, issues)
        LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY -> parseAndroid(raw, issues)
        LegacyOperationalPreferenceAggregate.INCOMING_FILTERS -> parseFilters(raw, issues)
        LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY -> parseIos(raw, issues)
        LegacyOperationalPreferenceAggregate.SCREEN -> parseScreen(raw, issues)
    }.takeIf { issues.isEmpty() }

    private fun parseDeviceProfile(
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyDeviceProfilePreferences {
        val deviceName = raw.string(LegacyOperationalPreferenceField.DEVICE_NAME)
        if (deviceName != null && !deviceName.isBoundedDisplay()) {
            issues.invalid(LegacyOperationalPreferenceField.DEVICE_NAME, LegacyOperationalPreferencesIssueKind.INVALID_DISPLAY_VALUE)
        }
        val fingerprint = raw.string(LegacyOperationalPreferenceField.SELF_PROFILE_FINGERPRINT)
        if (fingerprint != null && !fingerprint.isBoundedProfileFingerprint()) {
            issues.invalid(
                LegacyOperationalPreferenceField.SELF_PROFILE_FINGERPRINT,
                LegacyOperationalPreferencesIssueKind.INVALID_DISPLAY_VALUE,
            )
        }
        listOf(
            LegacyOperationalPreferenceField.DEVICE_NAME_UPDATED_AT,
            LegacyOperationalPreferenceField.SELF_PROFILE_UPDATED_AT,
            LegacyOperationalPreferenceField.LAST_SEEN_POST_TIME,
        ).forEach { field ->
            if (raw.long(field)?.let { it < 0 } == true) {
                issues.invalid(field, LegacyOperationalPreferencesIssueKind.INVALID_TIMESTAMP)
            }
        }
        return LegacyDeviceProfilePreferences(
            deviceName = deviceName,
            deviceNameUpdatedAt = raw.long(LegacyOperationalPreferenceField.DEVICE_NAME_UPDATED_AT),
            selfProfileFingerprint = fingerprint,
            selfProfileUpdatedAt = raw.long(LegacyOperationalPreferenceField.SELF_PROFILE_UPDATED_AT),
            lastSeenPostTime = raw.long(LegacyOperationalPreferenceField.LAST_SEEN_POST_TIME),
        )
    }

    private fun parseAndroid(
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyAndroidNotificationPreferences {
        val enabled = decodeJson(
            raw,
            LegacyOperationalPreferenceField.ANDROID_ENABLED_PACKAGES,
            SetSerializer(String.serializer()),
            emptySet(),
            issues,
        )
        val configs = decodeJson(
            raw,
            LegacyOperationalPreferenceField.ANDROID_APP_CONFIG,
            MapSerializer(String.serializer(), LegacyPerAppConfig.serializer()),
            emptyMap(),
            issues,
        )
        val seen = decodeJson(
            raw,
            LegacyOperationalPreferenceField.ANDROID_SEEN_CHANNELS,
            MapSerializer(String.serializer(), ListSerializer(LegacySeenChannel.serializer())),
            emptyMap(),
            issues,
        )
        if (enabled.size > MAX_ANDROID_APPS || configs.size > MAX_ANDROID_APPS || seen.size > MAX_ANDROID_APPS) {
            issues.invalid(LegacyOperationalPreferenceField.ANDROID_APP_CONFIG, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
        }
        (enabled + configs.keys + seen.keys).forEach { packageName ->
            if (!packageName.isBoundedPackage()) {
                issues.invalid(LegacyOperationalPreferenceField.ANDROID_APP_CONFIG, LegacyOperationalPreferencesIssueKind.INVALID_IDENTIFIER)
            }
        }
        configs.values.forEach { config ->
            if (config.updateIntervalSec < -1 || config.updateIntervalSec > MAX_UPDATE_INTERVAL_SECONDS) {
                issues.invalid(LegacyOperationalPreferenceField.ANDROID_APP_CONFIG, LegacyOperationalPreferencesIssueKind.INVALID_POLICY_VALUE)
            }
            if (config.disabledChannelIds.size > MAX_SUBSCOPES_PER_APP ||
                config.disabledGroupIds.size > MAX_SUBSCOPES_PER_APP ||
                (config.disabledChannelIds + config.disabledGroupIds).any { !it.isBoundedIdentifier() }
            ) {
                issues.invalid(LegacyOperationalPreferenceField.ANDROID_APP_CONFIG, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
            }
        }
        seen.values.forEach { channels ->
            if (channels.size > MAX_SEEN_CHANNELS_PER_APP) {
                issues.invalid(LegacyOperationalPreferenceField.ANDROID_SEEN_CHANNELS, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
            }
            if (channels.map { it.channelId }.toSet().size != channels.size) {
                issues.invalid(LegacyOperationalPreferenceField.ANDROID_SEEN_CHANNELS, LegacyOperationalPreferencesIssueKind.DUPLICATE_ROW)
            }
            channels.forEach { channel ->
                if (!channel.channelId.isBoundedIdentifier() ||
                    channel.groupId?.isBoundedIdentifier() == false ||
                    channel.channelName?.isBoundedOptionalDisplay() == false ||
                    channel.groupName?.isBoundedOptionalDisplay() == false ||
                    (channel.groupId == null && channel.groupName != null)
                ) {
                    issues.invalid(
                        LegacyOperationalPreferenceField.ANDROID_SEEN_CHANNELS,
                        LegacyOperationalPreferencesIssueKind.INVALID_RELATIONSHIP,
                    )
                }
            }
        }
        return LegacyAndroidNotificationPreferences(enabled, configs, seen)
    }

    private fun parseFilters(
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyIncomingFilterPreferences {
        val filters = decodeJson(
            raw,
            LegacyOperationalPreferenceField.INCOMING_FILTERS,
            MapSerializer(String.serializer(), LegacyFilterSync.serializer()),
            emptyMap(),
            issues,
        )
        if (filters.size > MAX_FILTER_REQUESTERS) {
            issues.invalid(LegacyOperationalPreferenceField.INCOMING_FILTERS, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
        }
        filters.forEach { (requester, filter) ->
            if (!requester.isBoundedIdentifier()) {
                issues.invalid(LegacyOperationalPreferenceField.INCOMING_FILTERS, LegacyOperationalPreferencesIssueKind.INVALID_IDENTIFIER)
            }
            if (filter.updatedAt <= 0) {
                issues.invalid(LegacyOperationalPreferenceField.INCOMING_FILTERS, LegacyOperationalPreferencesIssueKind.INVALID_TIMESTAMP)
            }
            if (filter.rules.size > MAX_FILTER_RULES_PER_REQUESTER) {
                issues.invalid(LegacyOperationalPreferenceField.INCOMING_FILTERS, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
            }
            filter.rules.forEach { rule ->
                if (rule.appId?.isBoundedPackage() == false || rule.channelId?.isBoundedIdentifier() == false ||
                    (rule.appId == null && rule.channelId != null) ||
                    (rule.originPlatform == LegacyNotificationOrigin.IOS_ANCS && rule.channelId != null)
                ) {
                    issues.invalid(LegacyOperationalPreferenceField.INCOMING_FILTERS, LegacyOperationalPreferencesIssueKind.INVALID_RELATIONSHIP)
                }
            }
        }
        return LegacyIncomingFilterPreferences(filters)
    }

    private fun parseIos(
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyIosAppPreferences {
        val enabled = decodeJson(
            raw,
            LegacyOperationalPreferenceField.IOS_ENABLED_BUNDLES,
            SetSerializer(String.serializer()),
            emptySet(),
            issues,
        )
        val discovered = decodeJson(
            raw,
            LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS,
            MapSerializer(String.serializer(), LegacyIosApp.serializer()),
            emptyMap(),
            issues,
        )
        if (enabled.size > MAX_IOS_APPS || discovered.size > MAX_IOS_APPS) {
            issues.invalid(LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
        }
        enabled.forEach { bundleId ->
            if (!bundleId.isBoundedPackage()) {
                issues.invalid(LegacyOperationalPreferenceField.IOS_ENABLED_BUNDLES, LegacyOperationalPreferencesIssueKind.INVALID_IDENTIFIER)
            }
        }
        discovered.forEach { (key, app) ->
            if (key != app.bundleId || !key.isBoundedPackage()) {
                issues.invalid(LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS, LegacyOperationalPreferencesIssueKind.INVALID_RELATIONSHIP)
            }
            if (!app.displayName.isBoundedDisplay()) {
                issues.invalid(LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS, LegacyOperationalPreferencesIssueKind.INVALID_DISPLAY_VALUE)
            }
            if (app.lastSeen <= 0) {
                issues.invalid(LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS, LegacyOperationalPreferencesIssueKind.INVALID_TIMESTAMP)
            }
        }
        return LegacyIosAppPreferences(enabled, discovered)
    }

    private fun parseScreen(
        raw: RawAggregate,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): LegacyScreenPreferences {
        val authorized = decodeJson(
            raw,
            LegacyOperationalPreferenceField.SCREEN_AUTHORIZED_PEERS,
            SetSerializer(String.serializer()),
            emptySet(),
            issues,
        )
        val replay = decodeJson(
            raw,
            LegacyOperationalPreferenceField.SCREEN_REPLAY,
            MapSerializer(String.serializer(), Long.serializer()),
            emptyMap(),
            issues,
        )
        val codecs = decodeJson(
            raw,
            LegacyOperationalPreferenceField.SCREEN_CODEC_PREFERENCES,
            MapSerializer(String.serializer(), String.serializer()),
            emptyMap(),
            issues,
        )
        if (authorized.size > MAX_SCREEN_AUTHORIZATIONS || replay.size > MAX_SCREEN_REPLAY_ROWS ||
            codecs.size > MAX_SCREEN_CODEC_PREFERENCES
        ) {
            issues.invalid(LegacyOperationalPreferenceField.SCREEN_REPLAY, LegacyOperationalPreferencesIssueKind.TOO_MANY_ROWS)
        }
        if ((authorized + codecs.keys).any { !it.isBoundedIdentifier() }) {
            issues.invalid(LegacyOperationalPreferenceField.SCREEN_AUTHORIZED_PEERS, LegacyOperationalPreferencesIssueKind.INVALID_IDENTIFIER)
        }
        replay.forEach { (digest, expiry) ->
            if (!BASE64URL_SHA256.matches(digest) || expiry <= 0) {
                issues.invalid(LegacyOperationalPreferenceField.SCREEN_REPLAY, LegacyOperationalPreferencesIssueKind.MALFORMED_SECURITY_STATE)
            }
        }
        val codecValues = codecs.mapNotNull { (peer, token) ->
            val codec = LegacyScreenCodec.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
            if (codec == null) {
                issues.invalid(LegacyOperationalPreferenceField.SCREEN_CODEC_PREFERENCES, LegacyOperationalPreferencesIssueKind.UNKNOWN_TOKEN)
                null
            } else {
                peer to codec
            }
        }.toMap()
        val blocked = raw.boolean(LegacyOperationalPreferenceField.SCREEN_REPLAY_BLOCKED)
        val quarantineDigest = raw.string(LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINE_DIGEST)
        val quarantinedAt = raw.long(LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINED_AT)
        if (quarantineDigest != null && !BASE64URL_SHA256.matches(quarantineDigest)) {
            issues.invalid(
                LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINE_DIGEST,
                LegacyOperationalPreferencesIssueKind.MALFORMED_SECURITY_STATE,
            )
        }
        if (quarantinedAt != null && quarantinedAt <= 0) {
            issues.invalid(LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINED_AT, LegacyOperationalPreferencesIssueKind.INVALID_TIMESTAMP)
        }
        if (blocked == true) {
            if (replay.isNotEmpty() || quarantineDigest == null || quarantinedAt == null) {
                issues.invalid(LegacyOperationalPreferenceField.SCREEN_REPLAY_BLOCKED, LegacyOperationalPreferencesIssueKind.MALFORMED_SECURITY_STATE)
            }
        } else if (quarantineDigest != null || quarantinedAt != null) {
            issues.invalid(LegacyOperationalPreferenceField.SCREEN_REPLAY_BLOCKED, LegacyOperationalPreferencesIssueKind.MALFORMED_SECURITY_STATE)
        }
        return LegacyScreenPreferences(
            enabled = raw.boolean(LegacyOperationalPreferenceField.SCREEN_ENABLED),
            authorizedPeerIds = authorized,
            replayEntries = replay.entries.map { it.key to it.value },
            replayBlocked = blocked,
            replayQuarantineDigest = quarantineDigest,
            replayQuarantinedAt = quarantinedAt,
            codecPreferences = codecValues,
        )
    }

    private fun readDeviceProfileRaw(preferences: Preferences) = RawAggregate(
        listOf(
            preferences.string(LegacyOperationalPreferenceField.DEVICE_NAME),
            preferences.long(LegacyOperationalPreferenceField.DEVICE_NAME_UPDATED_AT),
            preferences.string(LegacyOperationalPreferenceField.SELF_PROFILE_FINGERPRINT),
            preferences.long(LegacyOperationalPreferenceField.SELF_PROFILE_UPDATED_AT),
            preferences.long(LegacyOperationalPreferenceField.LAST_SEEN_POST_TIME),
        ),
    )

    private fun readAndroidRaw(preferences: Preferences) = RawAggregate(
        listOf(
            preferences.string(LegacyOperationalPreferenceField.ANDROID_ENABLED_PACKAGES),
            preferences.string(LegacyOperationalPreferenceField.ANDROID_APP_CONFIG),
            preferences.string(LegacyOperationalPreferenceField.ANDROID_SEEN_CHANNELS),
        ),
    )

    private fun readFiltersRaw(preferences: Preferences) = RawAggregate(
        listOf(preferences.string(LegacyOperationalPreferenceField.INCOMING_FILTERS)),
    )

    private fun readIosRaw(preferences: Preferences) = RawAggregate(
        listOf(
            preferences.string(LegacyOperationalPreferenceField.IOS_ENABLED_BUNDLES),
            preferences.string(LegacyOperationalPreferenceField.IOS_DISCOVERED_APPS),
        ),
    )

    private fun readScreenRaw(preferences: Preferences) = RawAggregate(
        listOf(
            preferences.string(LegacyOperationalPreferenceField.SCREEN_AUTHORIZED_PEERS),
            preferences.string(LegacyOperationalPreferenceField.SCREEN_REPLAY),
            preferences.boolean(LegacyOperationalPreferenceField.SCREEN_REPLAY_BLOCKED),
            preferences.string(LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINE_DIGEST),
            preferences.long(LegacyOperationalPreferenceField.SCREEN_REPLAY_QUARANTINED_AT),
            preferences.boolean(LegacyOperationalPreferenceField.SCREEN_ENABLED),
            preferences.string(LegacyOperationalPreferenceField.SCREEN_CODEC_PREFERENCES),
        ),
    )

    private fun Preferences.string(field: LegacyOperationalPreferenceField): RawValue =
        typed(field, String::class.java)

    private fun Preferences.long(field: LegacyOperationalPreferenceField): RawValue =
        typed(field, java.lang.Long::class.java)

    private fun Preferences.boolean(field: LegacyOperationalPreferenceField): RawValue =
        typed(field, java.lang.Boolean::class.java)

    /**
     * DataStore keys are generically typed but their runtime equality is name-based. Reading a
     * wrong-typed shipped value through `preferences[key]` can therefore defer the cast until the
     * caller and escape a local `ClassCastException` catch. Inspect the erased snapshot value first
     * so malformed security preferences become privacy-safe recovery evidence instead of crashing.
     */
    private fun Preferences.typed(
        field: LegacyOperationalPreferenceField,
        expectedType: Class<*>,
    ): RawValue {
        val matches = asMap().entries.filter { it.key.name == field.keyName }
        if (matches.isEmpty()) return RawValue(field, present = false, value = null, typeError = false)
        val value = matches.singleOrNull()?.value
        return if (value != null && expectedType.isInstance(value)) {
            RawValue(field, present = true, value = value, typeError = false)
        } else {
            RawValue(field, present = true, value = null, typeError = true)
        }
    }

    private fun RawAggregate.string(field: LegacyOperationalPreferenceField): String? = value(field)
    private fun RawAggregate.long(field: LegacyOperationalPreferenceField): Long? = value(field)
    private fun RawAggregate.boolean(field: LegacyOperationalPreferenceField): Boolean? = value(field)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> RawAggregate.value(field: LegacyOperationalPreferenceField): T? =
        values.first { it.field == field }.value as T?

    private fun <T> decodeJson(
        raw: RawAggregate,
        field: LegacyOperationalPreferenceField,
        serializer: kotlinx.serialization.KSerializer<T>,
        absent: T,
        issues: MutableSet<LegacyOperationalPreferencesIssue>,
    ): T {
        val encoded = raw.string(field) ?: return absent
        if (encoded.encodeToByteArray().size > MAX_JSON_BYTES) {
            issues.invalid(field, LegacyOperationalPreferencesIssueKind.VALUE_TOO_LARGE)
            return absent
        }
        return try {
            STRICT_JSON.decodeFromString(serializer, encoded)
        } catch (_: SerializationException) {
            issues.invalid(field, LegacyOperationalPreferencesIssueKind.MALFORMED_JSON)
            absent
        } catch (_: IllegalArgumentException) {
            issues.invalid(field, LegacyOperationalPreferencesIssueKind.MALFORMED_JSON)
            absent
        }
    }

    private fun MutableSet<LegacyOperationalPreferencesIssue>.invalid(
        field: LegacyOperationalPreferenceField,
        kind: LegacyOperationalPreferencesIssueKind,
    ) {
        add(LegacyOperationalPreferencesIssue(kind, field))
    }

    private fun String.isBoundedPackage(): Boolean =
        isNotBlank() && length <= MAX_PACKAGE_CHARS && hasOnlyPairedSurrogatesAndNoControls()

    private fun String.isBoundedIdentifier(): Boolean =
        isNotBlank() && length <= MAX_ID_CHARS && hasOnlyPairedSurrogatesAndNoControls()

    private fun String.isBoundedDisplay(): Boolean =
        isNotBlank() && length <= MAX_DISPLAY_CHARS && hasOnlyPairedSurrogatesAndNoControls()

    private fun String.isBoundedOptionalDisplay(): Boolean =
        length <= MAX_DISPLAY_CHARS && hasOnlyPairedSurrogatesAndNoControls()

    private fun String.isBoundedProfileFingerprint(): Boolean =
        isNotEmpty() && length <= MAX_PROFILE_FINGERPRINT_CHARS &&
            hasOnlyPairedSurrogatesAndNoControls(allowedControl = '\u001f')

    private fun String.hasOnlyPairedSurrogatesAndNoControls(allowedControl: Char? = null): Boolean {
        var index = 0
        while (index < length) {
            val first = this[index]
            val codePoint = when {
                Character.isHighSurrogate(first) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    Character.toCodePoint(first, this[index + 1]).also { index++ }
                }
                Character.isLowSurrogate(first) -> return false
                else -> first.code
            }
            if (Character.isISOControl(codePoint) && first != allowedControl) return false
            index++
        }
        return true
    }

    private data class RawValue(
        val field: LegacyOperationalPreferenceField,
        val present: Boolean,
        val value: Any?,
        val typeError: Boolean,
    )

    private data class RawAggregate(val values: List<RawValue>) {
        val presentKeyCount: Int get() = values.count { it.present }
    }

    private companion object {
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            isLenient = false
            coerceInputValues = false
        }
        val BASE64URL_SHA256 = Regex("[A-Za-z0-9_-]{43}")
        const val MAX_JSON_BYTES = 2 * 1_024 * 1_024
        const val MAX_PACKAGE_CHARS = 512
        const val MAX_ID_CHARS = 256
        const val MAX_DISPLAY_CHARS = 1_024
        const val MAX_PROFILE_FINGERPRINT_CHARS = 16_384
        const val MAX_ANDROID_APPS = 2_048
        const val MAX_SUBSCOPES_PER_APP = 2_048
        const val MAX_SEEN_CHANNELS_PER_APP = 2_048
        const val MAX_UPDATE_INTERVAL_SECONDS = 86_400
        const val MAX_FILTER_REQUESTERS = 512
        const val MAX_FILTER_RULES_PER_REQUESTER = 512
        const val MAX_IOS_APPS = 4_096
        const val MAX_SCREEN_AUTHORIZATIONS = 256
        const val MAX_SCREEN_REPLAY_ROWS = 512
        const val MAX_SCREEN_CODEC_PREFERENCES = 256
    }
}
