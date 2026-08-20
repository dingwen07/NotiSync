package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * Read-only v51 snapshot of the Core/profile keys in the shared Preferences DataStore.
 *
 * The caller supplies the application's already-created singleton. This class never constructs a
 * second DataStore, parses its backing file, invokes a migration, or calls `edit`/`updateData`.
 */
internal class LegacyCorePreferencesDataStoreReader {
    suspend fun read(dataStore: DataStore<Preferences>): LegacyCorePreferencesReadResult {
        val preferences = try {
            dataStore.data.first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw LegacyCoreSourceReadException(
                source = LegacyCoreSourceKind.PREFERENCES,
                kind = LegacyCoreSourceFailureKind.SOURCE_IO,
                cause = failure,
            )
        }
        return read(preferences)
    }

    /** Pure decoder for tests and for a coordinator that already owns one immutable snapshot. */
    internal fun read(preferences: Preferences): LegacyCorePreferencesReadResult {
        val raw = RawPreferences(
            brokerUrl = preferences.readString(LegacyCorePreferenceField.BROKER_URL),
            deviceName = preferences.readString(LegacyCorePreferenceField.DEVICE_NAME),
            deviceNameUpdatedAt = preferences.readLong(LegacyCorePreferenceField.DEVICE_NAME_UPDATED_AT),
            selfProfileFingerprint = preferences.readString(LegacyCorePreferenceField.SELF_PROFILE_FINGERPRINT),
            selfProfileUpdatedAt = preferences.readLong(LegacyCorePreferenceField.SELF_PROFILE_UPDATED_AT),
            groupId = preferences.readString(LegacyCorePreferenceField.GROUP_ID),
            routeEpoch = preferences.readInt(LegacyCorePreferenceField.ROUTE_EPOCH),
            fcmRouteRef = preferences.readString(LegacyCorePreferenceField.FCM_ROUTE_REF),
            lastSeenPostTime = preferences.readLong(LegacyCorePreferenceField.LAST_SEEN_POST_TIME),
            selfEpochActivatedAt = preferences.readLong(LegacyCorePreferenceField.SELF_EPOCH_ACTIVATED_AT),
            trustCleanupCompleted = preferences.readBoolean(LegacyCorePreferenceField.TRUST_CLEANUP_COMPLETED),
            trustEntries = preferences.readString(LegacyCorePreferenceField.TRUST_ENTRIES),
            trustCards = preferences.readString(LegacyCorePreferenceField.TRUST_CARDS),
            trustOverlays = preferences.readString(LegacyCorePreferenceField.TRUST_OVERLAYS),
            trustEpochs = preferences.readString(LegacyCorePreferenceField.TRUST_EPOCHS),
            trustSignature = preferences.readString(LegacyCorePreferenceField.TRUST_SIGNATURE),
        )
        val digests = raw.digests()
        if (raw.presentKeyCount == 0) {
            return LegacyCorePreferencesReadResult(
                status = LegacyCoreReadStatus.ABSENT,
                snapshot = null,
                issues = emptySet(),
                presentKeyCount = 0,
                digests = digests,
            )
        }

        val issues = linkedSetOf<LegacyCorePreferencesIssue>()
        raw.values.forEach { (field, value) ->
            if (value.typeError) {
                issues += LegacyCorePreferencesIssue(
                    kind = LegacyCorePreferencesIssueKind.WRONG_VALUE_TYPE,
                    field = field,
                )
            }
        }

        validateSettings(raw, issues)
        val trustHasTypeError = listOf(
            raw.trustEntries,
            raw.trustCards,
            raw.trustOverlays,
            raw.trustEpochs,
            raw.trustSignature,
        ).any { it.typeError }
        val signedTrust = if (!trustHasTypeError) {
            readSignedTrust(raw, issues)
        } else {
            null
        }

        if (issues.isNotEmpty()) {
            return LegacyCorePreferencesReadResult(
                status = LegacyCoreReadStatus.RECOVERY_REQUIRED,
                snapshot = null,
                issues = issues,
                presentKeyCount = raw.presentKeyCount,
                digests = digests,
            )
        }

        return LegacyCorePreferencesReadResult(
            status = LegacyCoreReadStatus.READY,
            snapshot = LegacyCorePreferencesSnapshot(
                brokerUrl = raw.brokerUrl.value,
                deviceName = raw.deviceName.value,
                deviceNameUpdatedAt = raw.deviceNameUpdatedAt.value,
                selfProfileFingerprint = raw.selfProfileFingerprint.value,
                selfProfileUpdatedAt = raw.selfProfileUpdatedAt.value,
                groupId = raw.groupId.value,
                routeEpoch = raw.routeEpoch.value,
                fcmRouteRef = raw.fcmRouteRef.value,
                lastSeenPostTime = raw.lastSeenPostTime.value,
                selfEpochActivatedAt = raw.selfEpochActivatedAt.value,
                trustCleanupCompleted = raw.trustCleanupCompleted.value,
                signedTrust = signedTrust,
            ),
            issues = emptySet(),
            presentKeyCount = raw.presentKeyCount,
            digests = digests,
        )
    }

    private fun validateSettings(
        raw: RawPreferences,
        issues: MutableSet<LegacyCorePreferencesIssue>,
    ) {
        raw.brokerUrl.validValueOrNull()?.let { value ->
            if (!value.isLegacyBrokerEndpoint()) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_BROKER_ENDPOINT,
                    LegacyCorePreferenceField.BROKER_URL,
                )
            }
        }
        raw.deviceName.validValueOrNull()?.let { value ->
            if (!value.isBoundedOpaqueProfileValue()) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_PROFILE_VALUE,
                    LegacyCorePreferenceField.DEVICE_NAME,
                )
            }
        }
        raw.selfProfileFingerprint.validValueOrNull()?.let { value ->
            if (!value.isBoundedOpaqueProfileValue()) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_PROFILE_VALUE,
                    LegacyCorePreferenceField.SELF_PROFILE_FINGERPRINT,
                )
            }
        }
        raw.groupId.validValueOrNull()?.let { value ->
            if (value.isBlank() || value.length > MAX_GROUP_ID_CHARS || value.any(Char::isISOControl)) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_GROUP_ID,
                    LegacyCorePreferenceField.GROUP_ID,
                )
            }
        }
        raw.fcmRouteRef.validValueOrNull()?.let { value ->
            if (value.isBlank() || value.length > MAX_ROUTE_REFERENCE_CHARS || value.any(Char::isISOControl)) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_ROUTE_STATE,
                    LegacyCorePreferenceField.FCM_ROUTE_REF,
                )
            }
        }

        listOf(
            LegacyCorePreferenceField.DEVICE_NAME_UPDATED_AT to raw.deviceNameUpdatedAt,
            LegacyCorePreferenceField.SELF_PROFILE_UPDATED_AT to raw.selfProfileUpdatedAt,
            LegacyCorePreferenceField.LAST_SEEN_POST_TIME to raw.lastSeenPostTime,
            LegacyCorePreferenceField.SELF_EPOCH_ACTIVATED_AT to raw.selfEpochActivatedAt,
        ).forEach { (field, value) ->
            if (value.validValueOrNull()?.let { it < 0 } == true) {
                issues += LegacyCorePreferencesIssue(LegacyCorePreferencesIssueKind.INVALID_TIMESTAMP, field)
            }
        }

        raw.routeEpoch.validValueOrNull()?.let { epoch ->
            if (epoch < 0 || (raw.fcmRouteRef.validValueOrNull() != null && epoch == 0)) {
                issues += LegacyCorePreferencesIssue(
                    LegacyCorePreferencesIssueKind.INVALID_ROUTE_STATE,
                    LegacyCorePreferenceField.ROUTE_EPOCH,
                )
            }
        }
        if (raw.fcmRouteRef.validValueOrNull() != null && raw.routeEpoch.validValueOrNull() == null) {
            issues += LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.INVALID_ROUTE_STATE,
                LegacyCorePreferenceField.ROUTE_EPOCH,
            )
        }
    }

    private fun readSignedTrust(
        raw: RawPreferences,
        issues: MutableSet<LegacyCorePreferencesIssue>,
    ): LegacySignedTrustSource? {
        val required = listOf(raw.trustEntries, raw.trustCards, raw.trustOverlays, raw.trustSignature)
        val anyTrustValue = required.any { it.present } || raw.trustEpochs.present
        if (!anyTrustValue) return null
        if (required.any { !it.present }) {
            issues += LegacyCorePreferencesIssue(LegacyCorePreferencesIssueKind.PARTIAL_SIGNED_TRUST)
            return null
        }

        val entries = requireNotNull(raw.trustEntries.value)
        val cards = requireNotNull(raw.trustCards.value)
        val overlays = requireNotNull(raw.trustOverlays.value)
        val epochs = raw.trustEpochs.value
        val signature = requireNotNull(raw.trustSignature.value)

        if (!isValidEntriesSection(entries)) {
            issues += LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION,
                LegacyCorePreferenceField.TRUST_ENTRIES,
            )
        }
        if (!isValidCardsSection(cards)) {
            issues += LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION,
                LegacyCorePreferenceField.TRUST_CARDS,
            )
        }
        if (!isValidOverlaysSection(overlays)) {
            issues += LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION,
                LegacyCorePreferenceField.TRUST_OVERLAYS,
            )
        }

        val selfEpoch = if (epochs == null) {
            LEGACY_THREE_SECTION_SELF_EPOCH
        } else {
            readSelfEpoch(epochs).also { parsed ->
                if (parsed == null) {
                    issues += LegacyCorePreferencesIssue(
                        LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION,
                        LegacyCorePreferenceField.TRUST_EPOCHS,
                    )
                }
            }
        }

        val signatureBytes = signature
            .takeIf { it.length <= MAX_ECDSA_SIGNATURE_BASE64URL_CHARS && BASE64URL_PATTERN.matches(it) }
            ?.let { encoded -> runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_ECDSA_DER_SIGNATURE_BYTES }
        if (signatureBytes == null) {
            issues += LegacyCorePreferencesIssue(
                LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SIGNATURE,
                LegacyCorePreferenceField.TRUST_SIGNATURE,
            )
        }
        if (issues.any { it.kind in TRUST_STRUCTURE_ISSUES }) return null

        return LegacySignedTrustSource(
            format = if (epochs == null) {
                LegacySignedTrustFormat.LEGACY_THREE_SECTION
            } else {
                LegacySignedTrustFormat.FOUR_SECTION
            },
            entriesJson = entries,
            cardsJson = cards,
            overlaysJson = overlays,
            epochsJson = epochs,
            signatureBytes = requireNotNull(signatureBytes),
            signatureBase64Url = signature,
            effectiveSelfEpoch = requireNotNull(selfEpoch),
        )
    }

    private fun isValidEntriesSection(encoded: String): Boolean {
        val rows = parseJson(encoded) as? JsonArray ?: return false
        val clientIds = hashSetOf<String>()
        return rows.all { row ->
            val objectValue = row as? JsonObject ?: return@all false
            if (!objectValue.keys.all { it in TRUST_ENTRY_KEYS }) return@all false
            val clientId = objectValue.string("clientId") ?: return@all false
            val status = objectValue.string("status") ?: return@all false
            val updatedAt = objectValue.long("updatedAt") ?: return@all false
            val introducedBy = objectValue.optionalString("introducedBy") ?: return@all false
            if (objectValue.optionalBoolean("ownDevice") == null) return@all false
            clientId.isLegacyClientId() && clientIds.add(clientId) &&
                status in TRUST_STATUS_TOKENS && updatedAt >= 0 &&
                (introducedBy.value == null || introducedBy.value.isLegacyClientId())
        }
    }

    private fun isValidCardsSection(encoded: String): Boolean {
        val cards = parseJson(encoded) as? JsonObject ?: return false
        return cards.all { (clientId, value) ->
            clientId.isLegacyClientId() && value is JsonPrimitive && value.isString &&
                runCatching { Base64.getDecoder().decode(value.content) }.getOrNull()?.isNotEmpty() == true
        }
    }

    private fun isValidOverlaysSection(encoded: String): Boolean {
        val overlays = parseJson(encoded) as? JsonObject ?: return false
        return overlays.all { (clientId, value) ->
            val overlay = value as? JsonObject ?: return@all false
            if (!clientId.isLegacyClientId() || !overlay.keys.all { it in PROFILE_OVERLAY_KEYS }) return@all false
            val capabilities = overlay["capabilities"] as? JsonArray ?: return@all false
            overlay.string("displayName") != null &&
                overlay.string("platform") != null &&
                overlay.long("updatedAt")?.let { it >= 0 } == true &&
                capabilities.all { item -> item is JsonPrimitive && item.isString }
        }
    }

    private fun readSelfEpoch(encoded: String): Int? {
        val section = parseJson(encoded) as? JsonObject ?: return null
        if (!section.keys.all { it in EPOCH_SECTION_KEYS }) return null
        val selfEpoch = section["selfEpoch"]?.jsonPrimitive?.intOrNull ?: LEGACY_THREE_SECTION_SELF_EPOCH
        if (selfEpoch <= 0) return null
        val peers = section["peers"] as? JsonObject ?: return null
        if (!peers.all { (clientId, value) -> clientId.isLegacyClientId() && value.isValidPeerEpochs() }) return null
        val pending = section["pending"]
        if (pending != null && pending !is JsonNull && !pending.isValidPendingRotation(selfEpoch)) return null
        return selfEpoch
    }

    private fun JsonElement.isValidPeerEpochs(): Boolean {
        val peer = this as? JsonObject ?: return false
        if (!peer.keys.all { it in PEER_EPOCH_KEYS }) return false
        val ring = peer["ringB64"] as? JsonArray ?: return false
        val floor = peer["floor"]?.jsonPrimitive?.intOrNull ?: return false
        return floor >= 0 && ring.size <= MAX_EPOCH_RING_SIZE && ring.all { value ->
            value is JsonPrimitive && value.isString &&
                runCatching { Base64.getDecoder().decode(value.content) }.getOrNull()?.isNotEmpty() == true
        }
    }

    private fun JsonElement.isValidPendingRotation(currentSelfEpoch: Int): Boolean {
        val pending = this as? JsonObject ?: return false
        if (pending.keys != PENDING_ROTATION_KEYS) return false
        val targetEpoch = pending["targetEpoch"]?.jsonPrimitive?.intOrNull ?: return false
        val notBefore = pending["notBefore"]?.jsonPrimitive?.longOrNull ?: return false
        val notAfter = pending["notAfter"]?.jsonPrimitive?.longOrNull ?: return false
        val retiredEpoch = pending["retiredEpoch"]?.jsonPrimitive?.intOrNull ?: return false
        val retireRetiredAt = pending["retireRetiredAt"]?.jsonPrimitive?.longOrNull ?: return false
        return targetEpoch > currentSelfEpoch && retiredEpoch == currentSelfEpoch &&
            notBefore >= 0 && notAfter > notBefore && retireRetiredAt >= notAfter
    }

    private fun parseJson(encoded: String): JsonElement? = encoded
        .takeIf { it.length <= MAX_TRUST_SECTION_CHARS }
        ?.let { bounded -> runCatching { ProtocolCodec.json.parseToJsonElement(bounded) }.getOrNull() }

    private fun String.isLegacyBrokerEndpoint(): Boolean {
        if (isBlank() || length > MAX_BROKER_ENDPOINT_CHARS || any(Char::isISOControl)) return false
        val uri = runCatching { URI(trim()) }.getOrNull() ?: return false
        return !uri.isOpaque && uri.scheme?.lowercase() in BROKER_SCHEMES &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }

    private fun String.isBoundedOpaqueProfileValue(): Boolean =
        isNotEmpty() && length <= MAX_OPAQUE_PROFILE_CHARS && '\u0000' !in this

    private fun String.isLegacyClientId(): Boolean = CLIENT_ID_PATTERN.matches(this)

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(name: String): Long? =
        (get(name) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.optionalString(name: String): OptionalJsonValue<String>? {
        val value = get(name) ?: return OptionalJsonValue(null)
        if (value is JsonNull) return OptionalJsonValue(null)
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::OptionalJsonValue)
    }

    private fun JsonObject.optionalBoolean(name: String): OptionalJsonValue<Boolean>? {
        val value = get(name) ?: return OptionalJsonValue(null)
        if (value is JsonNull) return OptionalJsonValue(null)
        return (value as? JsonPrimitive)?.booleanOrNull?.let(::OptionalJsonValue)
    }

    private data class OptionalJsonValue<T>(val value: T?)

    private data class RawPreferences(
        val brokerUrl: RawValue<String>,
        val deviceName: RawValue<String>,
        val deviceNameUpdatedAt: RawValue<Long>,
        val selfProfileFingerprint: RawValue<String>,
        val selfProfileUpdatedAt: RawValue<Long>,
        val groupId: RawValue<String>,
        val routeEpoch: RawValue<Int>,
        val fcmRouteRef: RawValue<String>,
        val lastSeenPostTime: RawValue<Long>,
        val selfEpochActivatedAt: RawValue<Long>,
        val trustCleanupCompleted: RawValue<Boolean>,
        val trustEntries: RawValue<String>,
        val trustCards: RawValue<String>,
        val trustOverlays: RawValue<String>,
        val trustEpochs: RawValue<String>,
        val trustSignature: RawValue<String>,
    ) {
        val values: List<Pair<LegacyCorePreferenceField, RawValue<*>>>
            get() = listOf(
                LegacyCorePreferenceField.BROKER_URL to brokerUrl,
                LegacyCorePreferenceField.DEVICE_NAME to deviceName,
                LegacyCorePreferenceField.DEVICE_NAME_UPDATED_AT to deviceNameUpdatedAt,
                LegacyCorePreferenceField.SELF_PROFILE_FINGERPRINT to selfProfileFingerprint,
                LegacyCorePreferenceField.SELF_PROFILE_UPDATED_AT to selfProfileUpdatedAt,
                LegacyCorePreferenceField.GROUP_ID to groupId,
                LegacyCorePreferenceField.ROUTE_EPOCH to routeEpoch,
                LegacyCorePreferenceField.FCM_ROUTE_REF to fcmRouteRef,
                LegacyCorePreferenceField.LAST_SEEN_POST_TIME to lastSeenPostTime,
                LegacyCorePreferenceField.SELF_EPOCH_ACTIVATED_AT to selfEpochActivatedAt,
                LegacyCorePreferenceField.TRUST_CLEANUP_COMPLETED to trustCleanupCompleted,
                LegacyCorePreferenceField.TRUST_ENTRIES to trustEntries,
                LegacyCorePreferenceField.TRUST_CARDS to trustCards,
                LegacyCorePreferenceField.TRUST_OVERLAYS to trustOverlays,
                LegacyCorePreferenceField.TRUST_EPOCHS to trustEpochs,
                LegacyCorePreferenceField.TRUST_SIGNATURE to trustSignature,
            )

        val presentKeyCount: Int get() = values.count { it.second.present }

        fun digests(): LegacyCoreSourceDigests {
            val content = LegacyCoreDigestAccumulator().apply {
                text("NotiSync/core-preferences/v51")
                values.forEach { (field, value) ->
                    text(field.keyName)
                    boolean(value.present)
                    when {
                        !value.present -> Unit
                        value.typeError -> text("type-error")
                        value.value is String -> {
                            text("string-sha256")
                            int(value.value.length)
                            bytes(value.value.legacyCoreSha256Utf8())
                        }
                        value.value is Long -> {
                            text("long")
                            long(value.value)
                        }
                        value.value is Int -> {
                            text("int")
                            int(value.value)
                        }
                        value.value is Boolean -> {
                            text("boolean")
                            boolean(value.value)
                        }
                        else -> text("unsupported")
                    }
                }
            }.digest()
            val logical = LegacyCoreDigestAccumulator().apply {
                text("NotiSync/core-preferences-logical-fingerprint/v1")
                text(LegacyCorePreferencesSourceContract.DATASTORE_FILE_NAME)
                int(LegacyCorePreferencesSourceContract.CONTRACT_VERSION)
                bytes(content)
            }.digest()
            return LegacyCoreSourceDigests(
                contentDigest = content,
                logicalFingerprint = logical,
            )
        }
    }

    private data class RawValue<T>(
        val present: Boolean,
        val value: T?,
        val typeError: Boolean,
    ) {
        fun validValueOrNull(): T? = value.takeUnless { typeError }
    }

    private fun Preferences.readString(field: LegacyCorePreferenceField): RawValue<String> =
        readTyped(field) { this[stringPreferencesKey(field.keyName)] }

    private fun Preferences.readLong(field: LegacyCorePreferenceField): RawValue<Long> =
        readTyped(field) { this[longPreferencesKey(field.keyName)] }

    private fun Preferences.readInt(field: LegacyCorePreferenceField): RawValue<Int> =
        readTyped(field) { this[intPreferencesKey(field.keyName)] }

    private fun Preferences.readBoolean(field: LegacyCorePreferenceField): RawValue<Boolean> =
        readTyped(field) { this[booleanPreferencesKey(field.keyName)] }

    private inline fun <T : Any> Preferences.readTyped(
        field: LegacyCorePreferenceField,
        read: () -> T?,
    ): RawValue<T> {
        val physicallyPresent = asMap().keys.any { it.name == field.keyName }
        return try {
            RawValue(present = physicallyPresent, value = read(), typeError = false)
        } catch (_: ClassCastException) {
            RawValue(present = true, value = null, typeError = true)
        }
    }

    private companion object {
        const val MAX_GROUP_ID_CHARS = 256
        const val MAX_ROUTE_REFERENCE_CHARS = 1_024
        const val MAX_BROKER_ENDPOINT_CHARS = 4_096
        const val MAX_OPAQUE_PROFILE_CHARS = 65_536
        const val MAX_ECDSA_DER_SIGNATURE_BYTES = 144
        const val MAX_ECDSA_SIGNATURE_BASE64URL_CHARS = 192
        /** Defensive importer parsing ceiling per exact signed section; not a protocol-size claim. */
        const val MAX_TRUST_SECTION_CHARS = 4 * 1024 * 1024
        const val LEGACY_THREE_SECTION_SELF_EPOCH = 1
        const val MAX_EPOCH_RING_SIZE = 3

        val BROKER_SCHEMES = setOf("http", "https", "ws", "wss")
        val CLIENT_ID_PATTERN = Regex("[a-z2-7]{32}")
        val BASE64URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        val TRUST_STATUS_TOKENS = setOf("PENDING_TRUST", "TRUSTED", "PENDING_REVOKE", "REVOKED")
        val TRUST_ENTRY_KEYS = setOf("clientId", "status", "updatedAt", "introducedBy", "ownDevice")
        val PROFILE_OVERLAY_KEYS = setOf("displayName", "platform", "capabilities", "updatedAt")
        val EPOCH_SECTION_KEYS = setOf("selfEpoch", "peers", "pending")
        val PEER_EPOCH_KEYS = setOf("ringB64", "floor")
        val PENDING_ROTATION_KEYS = setOf(
            "targetEpoch",
            "notBefore",
            "notAfter",
            "retiredEpoch",
            "retireRetiredAt",
        )
        val TRUST_STRUCTURE_ISSUES = setOf(
            LegacyCorePreferencesIssueKind.PARTIAL_SIGNED_TRUST,
            LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SECTION,
            LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SIGNATURE,
        )
    }
}

/** Hashes an already-materialized DataStore string without allocating another full UTF-8 copy. */
private fun String.legacyCoreSha256Utf8(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() {
        override fun write(value: Int) = digest.update(value.toByte())

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
        }
    }
    sink.writer(Charsets.UTF_8).use { writer -> writer.write(this) }
    return digest.digest()
}
