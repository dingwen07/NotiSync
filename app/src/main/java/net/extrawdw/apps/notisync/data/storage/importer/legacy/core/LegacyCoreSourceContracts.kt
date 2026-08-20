package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import net.extrawdw.notisync.protocol.crypto.ClientIds

/** Core-import-local SHA-256 values; no clean storage or sibling legacy adapter type leaks in. */
internal class LegacyCoreSourceDigests(
    contentDigest: ByteArray,
    logicalFingerprint: ByteArray,
) {
    private val contentDigestValue = contentDigest.copyOf()
    private val logicalFingerprintValue = logicalFingerprint.copyOf()

    val contentDigest: ByteArray get() = contentDigestValue.copyOf()
    val logicalFingerprint: ByteArray get() = logicalFingerprintValue.copyOf()

    init {
        require(contentDigestValue.size == SHA256_BYTES && logicalFingerprintValue.size == SHA256_BYTES) {
            "legacy Core digests must be SHA-256"
        }
    }

    fun copyOf(): LegacyCoreSourceDigests =
        LegacyCoreSourceDigests(contentDigestValue, logicalFingerprintValue)

    override fun equals(other: Any?): Boolean = other is LegacyCoreSourceDigests &&
        contentDigestValue.contentEquals(other.contentDigestValue) &&
        logicalFingerprintValue.contentEquals(other.logicalFingerprintValue)

    override fun hashCode(): Int =
        31 * contentDigestValue.contentHashCode() + logicalFingerprintValue.contentHashCode()

    companion object {
        const val SHA256_BYTES = 32
    }
}

/** Fixed-width and length-framed digest input used only inside this Core source boundary. */
internal class LegacyCoreDigestAccumulator {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun text(value: String?) {
        if (value == null) {
            output.writeByte(0)
        } else {
            output.writeByte(1)
            value.encodeToByteArray().let(::bytes)
        }
    }

    fun long(value: Long) = output.writeLong(value)

    fun nullableLong(value: Long?) {
        if (value == null) output.writeByte(0) else {
            output.writeByte(1)
            output.writeLong(value)
        }
    }

    fun int(value: Int) = output.writeInt(value)

    fun boolean(value: Boolean) = output.writeByte(if (value) 1 else 0)

    fun bytes(value: ByteArray?) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            output.writeInt(value.size)
            output.write(value)
        }
    }

    fun digest(): ByteArray {
        output.flush()
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }
}

/** Exact v51 Preferences DataStore keys owned by the Core/profile cutover phases. */
internal object LegacyCorePreferencesSourceContract {
    const val DATASTORE_NAME = "notisync"
    const val DATASTORE_FILE_NAME = "notisync.preferences_pb"
    const val CONTRACT_VERSION = 1

    const val BROKER_URL_KEY = "broker_url"
    const val DEVICE_NAME_KEY = "device_name"
    const val DEVICE_NAME_UPDATED_AT_KEY = "device_name_updated_at"
    const val SELF_PROFILE_FINGERPRINT_KEY = "self_profile_fingerprint"
    const val SELF_PROFILE_UPDATED_AT_KEY = "self_profile_updated_at"
    const val GROUP_ID_KEY = "group_id"
    const val ROUTE_EPOCH_KEY = "route_epoch"
    const val FCM_ROUTE_REF_KEY = "fcm_route_ref"
    const val LAST_SEEN_POST_TIME_KEY = "last_seen_post_time"
    const val SELF_EPOCH_ACTIVATED_AT_KEY = "self_epoch_activated_at"
    const val TRUST_CLEANUP_COMPLETED_KEY = "unverified_device_cleanup_v1_completed"

    const val TRUST_ENTRIES_KEY = "trust_entries_json"
    const val TRUST_CARDS_KEY = "trust_cards_json"
    const val TRUST_OVERLAYS_KEY = "trust_overlays_json"
    const val TRUST_EPOCHS_KEY = "trust_epochs_json"
    const val TRUST_SIGNATURE_KEY = "trust_sig"

    val ownedKeyNames: Set<String> = LegacyCorePreferenceField.entries.mapTo(linkedSetOf()) { it.keyName }
}

internal enum class LegacyCorePreferenceField(val keyName: String) {
    BROKER_URL(LegacyCorePreferencesSourceContract.BROKER_URL_KEY),
    DEVICE_NAME(LegacyCorePreferencesSourceContract.DEVICE_NAME_KEY),
    DEVICE_NAME_UPDATED_AT(LegacyCorePreferencesSourceContract.DEVICE_NAME_UPDATED_AT_KEY),
    SELF_PROFILE_FINGERPRINT(LegacyCorePreferencesSourceContract.SELF_PROFILE_FINGERPRINT_KEY),
    SELF_PROFILE_UPDATED_AT(LegacyCorePreferencesSourceContract.SELF_PROFILE_UPDATED_AT_KEY),
    GROUP_ID(LegacyCorePreferencesSourceContract.GROUP_ID_KEY),
    ROUTE_EPOCH(LegacyCorePreferencesSourceContract.ROUTE_EPOCH_KEY),
    FCM_ROUTE_REF(LegacyCorePreferencesSourceContract.FCM_ROUTE_REF_KEY),
    LAST_SEEN_POST_TIME(LegacyCorePreferencesSourceContract.LAST_SEEN_POST_TIME_KEY),
    SELF_EPOCH_ACTIVATED_AT(LegacyCorePreferencesSourceContract.SELF_EPOCH_ACTIVATED_AT_KEY),
    TRUST_CLEANUP_COMPLETED(LegacyCorePreferencesSourceContract.TRUST_CLEANUP_COMPLETED_KEY),
    TRUST_ENTRIES(LegacyCorePreferencesSourceContract.TRUST_ENTRIES_KEY),
    TRUST_CARDS(LegacyCorePreferencesSourceContract.TRUST_CARDS_KEY),
    TRUST_OVERLAYS(LegacyCorePreferencesSourceContract.TRUST_OVERLAYS_KEY),
    TRUST_EPOCHS(LegacyCorePreferencesSourceContract.TRUST_EPOCHS_KEY),
    TRUST_SIGNATURE(LegacyCorePreferencesSourceContract.TRUST_SIGNATURE_KEY),
}

internal enum class LegacyCoreReadStatus {
    ABSENT,
    READY,
    RECOVERY_REQUIRED,
}

internal enum class LegacyCorePreferencesIssueKind {
    WRONG_VALUE_TYPE,
    INVALID_BROKER_ENDPOINT,
    INVALID_PROFILE_VALUE,
    INVALID_GROUP_ID,
    INVALID_ROUTE_STATE,
    INVALID_TIMESTAMP,
    PARTIAL_SIGNED_TRUST,
    MALFORMED_TRUST_SECTION,
    MALFORMED_TRUST_SIGNATURE,
}

/** A value-free diagnostic. The field enum is safe to retain; source values never enter an error. */
internal data class LegacyCorePreferencesIssue(
    val kind: LegacyCorePreferencesIssueKind,
    val field: LegacyCorePreferenceField? = null,
)

internal enum class LegacySignedTrustFormat {
    /** `trust_epochs_json` was physically absent and the signature covers exactly three sections. */
    LEGACY_THREE_SECTION,

    /** `trust_epochs_json` was physically present and the signature covers exactly four sections. */
    FOUR_SECTION,
}

/**
 * Exact persisted v51 trust material. Strings are intentionally not normalized or re-encoded.
 * In particular, [epochsJson] remains null for a valid three-section snapshot.
 */
internal class LegacySignedTrustSource(
    val format: LegacySignedTrustFormat,
    val entriesJson: String,
    val cardsJson: String,
    val overlaysJson: String,
    val epochsJson: String?,
    signatureBytes: ByteArray,
    internal val signatureBase64Url: String,
    /** Protocol interpretation used only for alias/file consistency; it is not a synthesized section. */
    val effectiveSelfEpoch: Int,
) {
    private val signatureValue = signatureBytes.copyOf()

    init {
        require(entriesJson.isNotEmpty() && cardsJson.isNotEmpty() && overlaysJson.isNotEmpty()) {
            "signed trust sections must not be empty"
        }
        require((format == LegacySignedTrustFormat.FOUR_SECTION) == (epochsJson != null)) {
            "signed trust format must agree with physical epoch-section presence"
        }
        require(signatureValue.isNotEmpty()) { "signed trust signature must not be empty" }
        require(effectiveSelfEpoch > 0) { "effective self epoch must be positive" }
    }

    fun entriesUtf8(): ByteArray = entriesJson.encodeToByteArray()

    fun cardsUtf8(): ByteArray = cardsJson.encodeToByteArray()

    fun overlaysUtf8(): ByteArray = overlaysJson.encodeToByteArray()

    fun epochsUtf8OrNull(): ByteArray? = epochsJson?.encodeToByteArray()

    fun signatureCopy(): ByteArray = signatureValue.copyOf()

    override fun toString(): String =
        "LegacySignedTrustSource(format=$format, effectiveSelfEpoch=$effectiveSelfEpoch, values=<redacted>)"
}

/** Raw source values only; defaulting/canonical target mapping belongs to a later import adapter. */
internal class LegacyCorePreferencesSnapshot(
    val brokerUrl: String?,
    val deviceName: String?,
    val deviceNameUpdatedAt: Long?,
    val selfProfileFingerprint: String?,
    val selfProfileUpdatedAt: Long?,
    val groupId: String?,
    val routeEpoch: Int?,
    val fcmRouteRef: String?,
    val lastSeenPostTime: Long?,
    val selfEpochActivatedAt: Long?,
    val trustCleanupCompleted: Boolean?,
    val signedTrust: LegacySignedTrustSource?,
) {
    override fun toString(): String =
        "LegacyCorePreferencesSnapshot(trust=${signedTrust?.format ?: "ABSENT"}, values=<redacted>)"
}

internal class LegacyCorePreferencesReadResult(
    val status: LegacyCoreReadStatus,
    val snapshot: LegacyCorePreferencesSnapshot?,
    issues: Set<LegacyCorePreferencesIssue>,
    val presentKeyCount: Int,
    val digests: LegacyCoreSourceDigests,
) {
    val issues: Set<LegacyCorePreferencesIssue> = issues.toSet()

    init {
        require(presentKeyCount >= 0) { "present key count must not be negative" }
        require((status == LegacyCoreReadStatus.READY) == (snapshot != null)) {
            "only a ready preferences read may expose source values"
        }
        require((status == LegacyCoreReadStatus.RECOVERY_REQUIRED) == this.issues.isNotEmpty()) {
            "recovery-required preferences must carry value-free issues"
        }
        require(status != LegacyCoreReadStatus.ABSENT || presentKeyCount == 0) {
            "an absent preferences source cannot contain owned keys"
        }
    }

    override fun toString(): String =
        "LegacyCorePreferencesReadResult(status=$status, presentKeyCount=$presentKeyCount, issues=$issues)"
}

internal object LegacyCoreKeystoreSourceContract {
    const val CONTRACT_VERSION = 1
    const val IDENTITY_ALIAS = "notisync.identity.v1"
    const val IDENTITY_ALIAS_VERSION = 1
    const val OPERATIONAL_ALIAS_PREFIX = "notisync.operational.v1.epoch"
    const val OPERATIONAL_ALIAS_VERSION = 1
    const val WRAPPING_ALIAS = "notisync.kek.v1"
    const val WRAPPING_ALIAS_VERSION = 1
}

internal enum class LegacyKeystoreSecurityLevel {
    UNKNOWN,
    UNKNOWN_SECURE,
    SOFTWARE,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
}

internal class LegacyIdentityKeySource(
    val alias: String,
    val aliasVersion: Int,
    publicSpki: ByteArray,
    val securityLevel: LegacyKeystoreSecurityLevel,
    val createdAt: Long?,
) {
    private val publicSpkiValue = publicSpki.copyOf()
    val clientId: String = ClientIds.derive(publicSpkiValue).value

    init {
        require(alias == LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS) { "unexpected identity alias" }
        require(aliasVersion > 0) { "identity alias version must be positive" }
        require(publicSpkiValue.isNotEmpty()) { "identity SPKI must not be empty" }
        require(createdAt == null || createdAt >= 0) { "identity creation time must not be negative" }
    }

    fun publicSpkiCopy(): ByteArray = publicSpkiValue.copyOf()

    override fun toString(): String =
        "LegacyIdentityKeySource(aliasVersion=$aliasVersion, securityLevel=$securityLevel, public=<redacted>)"
}

internal class LegacyOperationalSignerSource(
    val epoch: Int,
    val alias: String,
    val aliasVersion: Int,
    publicSpki: ByteArray,
    val securityLevel: LegacyKeystoreSecurityLevel,
    val createdAt: Long?,
) {
    private val publicSpkiValue = publicSpki.copyOf()

    init {
        require(epoch > 0) { "operational signer epoch must be positive" }
        require(alias == LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + epoch) {
            "operational signer alias does not match its epoch"
        }
        require(aliasVersion > 0) { "operational alias version must be positive" }
        require(publicSpkiValue.isNotEmpty()) { "operational signer SPKI must not be empty" }
        require(createdAt == null || createdAt >= 0) { "operational creation time must not be negative" }
    }

    fun publicSpkiCopy(): ByteArray = publicSpkiValue.copyOf()

    override fun toString(): String =
        "LegacyOperationalSignerSource(epoch=$epoch, aliasVersion=$aliasVersion, " +
            "securityLevel=$securityLevel, public=<redacted>)"
}

internal class LegacyWrappingKeySource(
    val alias: String,
    val aliasVersion: Int,
    val securityLevel: LegacyKeystoreSecurityLevel,
    val createdAt: Long?,
) {
    init {
        require(alias == LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS) { "unexpected wrapping alias" }
        require(aliasVersion > 0) { "wrapping alias version must be positive" }
        require(createdAt == null || createdAt >= 0) { "wrapping creation time must not be negative" }
    }
}

internal class LegacyCoreKeystoreSnapshot(
    val identity: LegacyIdentityKeySource,
    operationalSigners: List<LegacyOperationalSignerSource>,
    val wrappingKey: LegacyWrappingKeySource,
    val digests: LegacyCoreSourceDigests,
) {
    val operationalSigners: List<LegacyOperationalSignerSource> = operationalSigners.toList()

    init {
        require(this.operationalSigners.isNotEmpty()) { "v51 source requires an operational signer" }
        require(this.operationalSigners.map { it.epoch }.distinct().size == this.operationalSigners.size) {
            "operational signer epochs must be unique"
        }
        require(this.operationalSigners.zipWithNext().all { (left, right) -> left.epoch < right.epoch }) {
            "operational signer epochs must be sorted"
        }
    }

    override fun toString(): String =
        "LegacyCoreKeystoreSnapshot(operationalEpochs=${operationalSigners.map { it.epoch }}, keyData=<redacted>)"
}

internal enum class LegacyCoreKeystoreRole {
    IDENTITY,
    OPERATIONAL_SIGNER,
    WRAPPING_KEY,
}

internal enum class LegacyCoreKeystoreIssueKind {
    SOURCE_CHANGED_DURING_READ,
    NON_CANONICAL_OPERATIONAL_ALIAS,
    MISSING_IDENTITY,
    MISSING_OPERATIONAL_SIGNER,
    MISSING_WRAPPING_KEY,
    UNREADABLE_ENTRY,
    WRONG_ENTRY_TYPE,
    KEY_INFO_UNAVAILABLE,
    INVALID_KEY_ALGORITHM,
    INVALID_PUBLIC_KEY,
    INVALID_KEY_POLICY,
    INVALID_CREATION_TIME,
    NON_HARDWARE_BACKED_KEY,
}

internal data class LegacyCoreKeystoreIssue(
    val kind: LegacyCoreKeystoreIssueKind,
    val role: LegacyCoreKeystoreRole? = null,
    val epoch: Int? = null,
)

internal class LegacyCoreKeystoreReadResult(
    val status: LegacyCoreReadStatus,
    val snapshot: LegacyCoreKeystoreSnapshot?,
    issues: Set<LegacyCoreKeystoreIssue>,
    val relevantAliasCount: Int,
    val digests: LegacyCoreSourceDigests?,
) {
    val issues: Set<LegacyCoreKeystoreIssue> = issues.toSet()

    init {
        require(relevantAliasCount >= 0) { "alias count must not be negative" }
        require((status == LegacyCoreReadStatus.READY) == (snapshot != null)) {
            "only a ready Keystore read may expose source records"
        }
        require((status == LegacyCoreReadStatus.RECOVERY_REQUIRED) == this.issues.isNotEmpty()) {
            "recovery-required Keystore reads must carry value-free issues"
        }
        require(status != LegacyCoreReadStatus.ABSENT || relevantAliasCount == 0) {
            "an absent Keystore source cannot contain relevant aliases"
        }
    }

    override fun toString(): String =
        "LegacyCoreKeystoreReadResult(status=$status, relevantAliasCount=$relevantAliasCount, issues=$issues)"
}

internal object LegacyCoreFileSourceContract {
    const val CONTRACT_VERSION = 1
    const val HPKE_PUBLIC_PREFIX = "hpke_public.epoch"
    const val HPKE_PUBLIC_SUFFIX = ".bin"
    const val HPKE_PRIVATE_PREFIX = "hpke_private.epoch"
    const val HPKE_PRIVATE_SUFFIX = ".wrapped"
    const val LEGACY_UNVERSIONED_HPKE_PUBLIC = "hpke_public.bin"
    const val LEGACY_UNVERSIONED_HPKE_PRIVATE = "hpke_private.wrapped"
    const val AUTH_TOKEN_FILE = "auth_token.wrapped"
    const val WRAPPED_IV_BYTES = 12
    const val GCM_TAG_BYTES = 16
    /** Defensive v51 Tink-keyset ceiling; protobuf length varies with the random key ID. */
    const val MAX_V51_HPKE_PUBLIC_KEYSET_BYTES = 4 * 1024
    /** Defensive wrapped-keyset ceiling; the wrapped protobuf length is likewise not fixed. */
    const val MAX_V51_WRAPPED_HPKE_PRIVATE_BYTES = 4 * 1024
    /** Defensive importer ceiling, not a protocol-token length claim. */
    const val MAX_WRAPPED_AUTH_TOKEN_BYTES = 4 * 1024 * 1024
}

internal class LegacyHpkeEpochFileSource(
    val epoch: Int,
    publicKeyset: ByteArray,
    wrappedPrivateKeyset: ByteArray,
) {
    private val publicValue = publicKeyset.copyOf()
    private val wrappedPrivateValue = wrappedPrivateKeyset.copyOf()

    init {
        require(epoch > 0) { "HPKE epoch must be positive" }
        require(publicValue.isNotEmpty() &&
            publicValue.size <= LegacyCoreFileSourceContract.MAX_V51_HPKE_PUBLIC_KEYSET_BYTES
        ) {
            "v51 HPKE public-keyset is outside importer bounds"
        }
        require(wrappedPrivateValue.size > 1 + LegacyCoreFileSourceContract.WRAPPED_IV_BYTES +
            LegacyCoreFileSourceContract.GCM_TAG_BYTES &&
            wrappedPrivateValue.size <= LegacyCoreFileSourceContract.MAX_V51_WRAPPED_HPKE_PRIVATE_BYTES &&
            (wrappedPrivateValue.first().toInt() and 0xff) == LegacyCoreFileSourceContract.WRAPPED_IV_BYTES
        ) {
            "v51 wrapped HPKE private-keyset is outside importer bounds"
        }
    }

    fun publicKeysetCopy(): ByteArray = publicValue.copyOf()

    fun wrappedPrivateKeysetCopy(): ByteArray = wrappedPrivateValue.copyOf()

    override fun toString(): String = "LegacyHpkeEpochFileSource(epoch=$epoch, keyData=<redacted>)"
}

internal class LegacyWrappedAuthTokenSource(wrappedToken: ByteArray) {
    private val wrappedValue = wrappedToken.copyOf()

    init {
        require(wrappedValue.isNotEmpty()) { "wrapped token must not be empty" }
    }

    fun wrappedTokenCopy(): ByteArray = wrappedValue.copyOf()

    override fun toString(): String = "LegacyWrappedAuthTokenSource(value=<redacted>)"
}

internal class LegacyCoreFileSnapshot(
    hpkeEpochs: List<LegacyHpkeEpochFileSource>,
    val authToken: LegacyWrappedAuthTokenSource?,
    val skippedUnversionedHpkeFileCount: Int,
    val digests: LegacyCoreSourceDigests,
) {
    val hpkeEpochs: List<LegacyHpkeEpochFileSource> = hpkeEpochs.toList()

    init {
        require(this.hpkeEpochs.map { it.epoch }.distinct().size == this.hpkeEpochs.size) {
            "HPKE epochs must be unique"
        }
        require(this.hpkeEpochs.zipWithNext().all { (left, right) -> left.epoch < right.epoch }) {
            "HPKE epochs must be sorted"
        }
        require(skippedUnversionedHpkeFileCount in 0..2) { "invalid skipped unversioned HPKE count" }
    }

    override fun toString(): String =
        "LegacyCoreFileSnapshot(hpkeEpochs=${hpkeEpochs.map { it.epoch }}, " +
            "authTokenPresent=${authToken != null}, skippedUnversionedHpkeFileCount=$skippedUnversionedHpkeFileCount, " +
            "values=<redacted>)"
}

internal enum class LegacyCoreFileIssueKind {
    SOURCE_CHANGED_DURING_READ,
    SYMBOLIC_LINK,
    NOT_REGULAR_FILE,
    NON_CANONICAL_EPOCH_FILE_NAME,
    MISSING_HPKE_PUBLIC_HALF,
    MISSING_HPKE_PRIVATE_HALF,
    INVALID_HPKE_PUBLIC_KEYSET,
    INVALID_WRAPPED_HPKE_PRIVATE,
    INVALID_WRAPPED_AUTH_TOKEN,
    SOURCE_FILE_TOO_LARGE,
}

internal data class LegacyCoreFileIssue(
    val kind: LegacyCoreFileIssueKind,
    val epoch: Int? = null,
)

internal class LegacyCoreFileReadResult(
    val status: LegacyCoreReadStatus,
    val snapshot: LegacyCoreFileSnapshot?,
    issues: Set<LegacyCoreFileIssue>,
    val relevantFileCount: Int,
    val skippedUnversionedHpkeFileCount: Int,
    val digests: LegacyCoreSourceDigests?,
) {
    val issues: Set<LegacyCoreFileIssue> = issues.toSet()

    init {
        require(relevantFileCount >= 0) { "file count must not be negative" }
        require(skippedUnversionedHpkeFileCount in 0..2) { "invalid skipped unversioned HPKE count" }
        require((status == LegacyCoreReadStatus.READY) == (snapshot != null)) {
            "only a ready file read may expose source bytes"
        }
        require((status == LegacyCoreReadStatus.RECOVERY_REQUIRED) == this.issues.isNotEmpty()) {
            "recovery-required file reads must carry value-free issues"
        }
        require(status != LegacyCoreReadStatus.ABSENT || relevantFileCount == 0) {
            "an absent file source cannot contain relevant files"
        }
    }

    override fun toString(): String =
        "LegacyCoreFileReadResult(status=$status, relevantFileCount=$relevantFileCount, " +
            "skippedUnversionedHpkeFileCount=$skippedUnversionedHpkeFileCount, issues=$issues)"
}

internal enum class LegacyCoreSourceKind {
    PREFERENCES,
    KEYSTORE,
    FILES,
}

internal enum class LegacyCoreSourceFailureKind {
    SOURCE_IO,
    PLATFORM_KEYSTORE_UNAVAILABLE,
}

/** Retryable acquisition failure whose message never contains paths, aliases, values, or provider responses. */
internal class LegacyCoreSourceReadException(
    val source: LegacyCoreSourceKind,
    val kind: LegacyCoreSourceFailureKind,
    @Suppress("UNUSED_PARAMETER") cause: Throwable,
) : IllegalStateException("legacy Core source could not be read")

internal enum class LegacyCoreConsistencyIssue {
    SOURCE_REQUIRES_RECOVERY,
    PARTIAL_SECURITY_SOURCE,
    TRUST_IDENTITY_SIGNATURE_MISMATCH,
    OPERATIONAL_ALIAS_WITHOUT_HPKE_PAIR,
    HPKE_PAIR_WITHOUT_OPERATIONAL_ALIAS,
    CURRENT_TRUST_EPOCH_MISSING,
}

internal data class LegacyCoreConsistencyResult(
    val status: LegacyCoreReadStatus,
    val issues: Set<LegacyCoreConsistencyIssue>,
) {
    init {
        require((status == LegacyCoreReadStatus.RECOVERY_REQUIRED) == issues.isNotEmpty()) {
            "recovery-required consistency results must carry issues"
        }
    }
}
