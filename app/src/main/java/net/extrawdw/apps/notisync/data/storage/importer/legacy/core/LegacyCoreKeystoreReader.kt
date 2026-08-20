package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class LegacyRawKeystoreEntryKind {
    PRIVATE_KEY,
    SECRET_KEY,
    OTHER,
    UNREADABLE,
}

internal enum class LegacyRawKeyPurpose {
    ENCRYPT,
    DECRYPT,
    SIGN,
    VERIFY,
    WRAP_KEY,
    AGREE_KEY,
    ATTEST_KEY,
}

internal enum class LegacyRawKeyOrigin {
    GENERATED,
    IMPORTED,
    UNKNOWN,
}

/** Portable KeyInfo projection. It contains policy metadata only, never key material. */
internal class LegacyRawKeyInfo(
    val keystoreAlias: String,
    val keySize: Int,
    purposes: Set<LegacyRawKeyPurpose>,
    digests: Set<String>,
    blockModes: Set<String>,
    encryptionPaddings: Set<String>,
    /**
     * Android's immutable [KeyInfo] does not expose the originating
     * `setRandomizedEncryptionRequired` value. Fakes may provide it when the source is known; the
     * platform adapter records null instead of pretending the policy was observable.
     */
    val randomizedEncryptionRequired: Boolean?,
    val userAuthenticationRequired: Boolean,
    val origin: LegacyRawKeyOrigin,
    val securityLevel: LegacyKeystoreSecurityLevel,
) {
    val purposes: Set<LegacyRawKeyPurpose> = purposes.toSet()
    val digests: Set<String> = digests.toSet()
    val blockModes: Set<String> = blockModes.toSet()
    val encryptionPaddings: Set<String> = encryptionPaddings.toSet()

    override fun toString(): String = "LegacyRawKeyInfo(policy=<redacted>)"
}

internal class LegacyRawKeystoreEntry(
    val alias: String,
    val kind: LegacyRawKeystoreEntryKind,
    val algorithm: String?,
    publicSpki: ByteArray?,
    val keyInfo: LegacyRawKeyInfo?,
    val createdAt: Long?,
) {
    private val publicSpkiValue = publicSpki?.copyOf()

    fun publicSpkiCopyOrNull(): ByteArray? = publicSpkiValue?.copyOf()

    override fun toString(): String =
        "LegacyRawKeystoreEntry(kind=$kind, algorithm=$algorithm, keyInfoPresent=${keyInfo != null}, " +
            "public=<redacted>)"
}

internal class LegacyRawKeystoreSnapshot(
    aliasesBefore: Set<String>,
    entries: Map<String, LegacyRawKeystoreEntry>,
    aliasesAfter: Set<String>,
) {
    val aliasesBefore: Set<String> = aliasesBefore.toSet()
    val entries: Map<String, LegacyRawKeystoreEntry> = entries.toMap()
    val aliasesAfter: Set<String> = aliasesAfter.toSet()

    override fun toString(): String =
        "LegacyRawKeystoreSnapshot(aliasCount=${aliasesBefore.size}, changed=${aliasesBefore != aliasesAfter})"
}

/** Read-only acquisition port. Deliberately has no create, delete, rotate, sign, encrypt, or unwrap method. */
internal fun interface LegacyKeystoreSnapshotPort {
    suspend fun snapshot(): LegacyRawKeystoreSnapshot
}

/**
 * AndroidKeyStore implementation used only by the one-time importer.
 *
 * Loading, alias enumeration, `getEntry`, `getCreationDate`, certificate reads, and immutable KeyInfo
 * inspection are the only platform operations. No source key is exercised or modified.
 */
internal class AndroidLegacyKeystoreSnapshotPort(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LegacyKeystoreSnapshotPort {
    override suspend fun snapshot(): LegacyRawKeystoreSnapshot = withContext(ioDispatcher) {
        try {
            currentCoroutineContext().ensureActive()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val aliasesBefore = keyStore.relevantAliases()
            val entries = aliasesBefore.associateWith { alias ->
                currentCoroutineContext().ensureActive()
                keyStore.readEntry(alias)
            }
            val aliasesAfter = keyStore.relevantAliases()
            LegacyRawKeystoreSnapshot(aliasesBefore, entries, aliasesAfter)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw LegacyCoreSourceReadException(
                source = LegacyCoreSourceKind.KEYSTORE,
                kind = LegacyCoreSourceFailureKind.PLATFORM_KEYSTORE_UNAVAILABLE,
                cause = failure,
            )
        }
    }

    private fun KeyStore.relevantAliases(): Set<String> {
        val result = sortedSetOf<String>()
        val aliases = aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (alias == LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS ||
                alias == LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS ||
                alias.startsWith(LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX)
            ) {
                result += alias
            }
        }
        return result
    }

    private fun KeyStore.readEntry(alias: String): LegacyRawKeystoreEntry = try {
        val createdAt = getCreationDate(alias)?.time
        when (val entry = getEntry(alias, null)) {
            is KeyStore.PrivateKeyEntry -> LegacyRawKeystoreEntry(
                alias = alias,
                kind = LegacyRawKeystoreEntryKind.PRIVATE_KEY,
                algorithm = entry.privateKey.algorithm,
                publicSpki = entry.certificate.publicKey.encoded,
                keyInfo = runCatching {
                    (KeyFactory.getInstance(entry.privateKey.algorithm, ANDROID_KEYSTORE)
                        .getKeySpec(entry.privateKey, KeyInfo::class.java) as KeyInfo)
                        .toRaw()
                }.getOrNull(),
                createdAt = createdAt,
            )

            is KeyStore.SecretKeyEntry -> LegacyRawKeystoreEntry(
                alias = alias,
                kind = LegacyRawKeystoreEntryKind.SECRET_KEY,
                algorithm = entry.secretKey.algorithm,
                publicSpki = null,
                keyInfo = runCatching {
                    (SecretKeyFactory.getInstance(entry.secretKey.algorithm, ANDROID_KEYSTORE)
                        .getKeySpec(entry.secretKey, KeyInfo::class.java) as KeyInfo)
                        .toRaw()
                }.getOrNull(),
                createdAt = createdAt,
            )

            null -> LegacyRawKeystoreEntry(
                alias = alias,
                kind = LegacyRawKeystoreEntryKind.UNREADABLE,
                algorithm = null,
                publicSpki = null,
                keyInfo = null,
                createdAt = createdAt,
            )

            else -> LegacyRawKeystoreEntry(
                alias = alias,
                kind = LegacyRawKeystoreEntryKind.OTHER,
                algorithm = null,
                publicSpki = null,
                keyInfo = null,
                createdAt = createdAt,
            )
        }
    } catch (_: Exception) {
        // The value-free result lets the caller distinguish one unreadable entry from a provider-wide
        // acquisition failure without retaining an exception that may contain vendor details.
        LegacyRawKeystoreEntry(
            alias = alias,
            kind = LegacyRawKeystoreEntryKind.UNREADABLE,
            algorithm = null,
            publicSpki = null,
            keyInfo = null,
            createdAt = null,
        )
    }

    private fun KeyInfo.toRaw(): LegacyRawKeyInfo = LegacyRawKeyInfo(
        keystoreAlias = keystoreAlias,
        keySize = keySize,
        purposes = buildSet {
            if (purposes and KeyProperties.PURPOSE_ENCRYPT != 0) add(LegacyRawKeyPurpose.ENCRYPT)
            if (purposes and KeyProperties.PURPOSE_DECRYPT != 0) add(LegacyRawKeyPurpose.DECRYPT)
            if (purposes and KeyProperties.PURPOSE_SIGN != 0) add(LegacyRawKeyPurpose.SIGN)
            if (purposes and KeyProperties.PURPOSE_VERIFY != 0) add(LegacyRawKeyPurpose.VERIFY)
            if (purposes and KeyProperties.PURPOSE_WRAP_KEY != 0) add(LegacyRawKeyPurpose.WRAP_KEY)
            if (purposes and KeyProperties.PURPOSE_AGREE_KEY != 0) add(LegacyRawKeyPurpose.AGREE_KEY)
            if (purposes and KeyProperties.PURPOSE_ATTEST_KEY != 0) add(LegacyRawKeyPurpose.ATTEST_KEY)
        },
        digests = runCatching { digests.toSet() }.getOrDefault(emptySet()),
        blockModes = runCatching { blockModes.toSet() }.getOrDefault(emptySet()),
        encryptionPaddings = runCatching { encryptionPaddings.toSet() }.getOrDefault(emptySet()),
        // KeyInfo has no accessor for this KeyGenParameterSpec setting.
        randomizedEncryptionRequired = null,
        userAuthenticationRequired = isUserAuthenticationRequired,
        origin = when (origin) {
            KeyProperties.ORIGIN_GENERATED -> LegacyRawKeyOrigin.GENERATED
            KeyProperties.ORIGIN_IMPORTED -> LegacyRawKeyOrigin.IMPORTED
            else -> LegacyRawKeyOrigin.UNKNOWN
        },
        securityLevel = when (securityLevel) {
            KeyProperties.SECURITY_LEVEL_UNKNOWN -> LegacyKeystoreSecurityLevel.UNKNOWN
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> LegacyKeystoreSecurityLevel.UNKNOWN_SECURE
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> LegacyKeystoreSecurityLevel.SOFTWARE
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> LegacyKeystoreSecurityLevel.STRONGBOX
            else -> LegacyKeystoreSecurityLevel.UNKNOWN
        },
    )

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** Structural, policy, and public-key validator over one immutable Keystore observation. */
internal class LegacyCoreKeystoreReader(
    private val source: LegacyKeystoreSnapshotPort = AndroidLegacyKeystoreSnapshotPort(),
) {
    suspend fun read(): LegacyCoreKeystoreReadResult = inspect(source.snapshot())

    internal fun inspect(raw: LegacyRawKeystoreSnapshot): LegacyCoreKeystoreReadResult {
        val relevantAliasCount = raw.aliasesBefore.size
        val digests = raw.digests()
        if (relevantAliasCount == 0 && raw.aliasesAfter.isEmpty()) {
            return LegacyCoreKeystoreReadResult(
                status = LegacyCoreReadStatus.ABSENT,
                snapshot = null,
                issues = emptySet(),
                relevantAliasCount = 0,
                digests = digests,
            )
        }

        val issues = linkedSetOf<LegacyCoreKeystoreIssue>()
        if (raw.aliasesBefore != raw.aliasesAfter) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.SOURCE_CHANGED_DURING_READ)
        }

        val identityEntry = raw.entries[LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS]
        val wrappingEntry = raw.entries[LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS]
        if (identityEntry == null) {
            issues += LegacyCoreKeystoreIssue(
                LegacyCoreKeystoreIssueKind.MISSING_IDENTITY,
                LegacyCoreKeystoreRole.IDENTITY,
            )
        }
        if (wrappingEntry == null) {
            issues += LegacyCoreKeystoreIssue(
                LegacyCoreKeystoreIssueKind.MISSING_WRAPPING_KEY,
                LegacyCoreKeystoreRole.WRAPPING_KEY,
            )
        }

        val operationalEntries = mutableListOf<Pair<Int, LegacyRawKeystoreEntry>>()
        raw.aliasesBefore.filter { it.startsWith(LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX) }
            .sorted()
            .forEach { alias ->
                val epoch = alias.removePrefix(LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX)
                    .takeIf { CANONICAL_EPOCH_SUFFIX.matches(it) }
                    ?.toIntOrNull()
                if (epoch == null || epoch <= 0 || alias != LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + epoch) {
                    issues += LegacyCoreKeystoreIssue(
                        LegacyCoreKeystoreIssueKind.NON_CANONICAL_OPERATIONAL_ALIAS,
                        LegacyCoreKeystoreRole.OPERATIONAL_SIGNER,
                    )
                } else {
                    raw.entries[alias]?.let { operationalEntries += epoch to it }
                }
            }
        operationalEntries.sortBy { it.first }
        if (operationalEntries.isEmpty()) {
            issues += LegacyCoreKeystoreIssue(
                LegacyCoreKeystoreIssueKind.MISSING_OPERATIONAL_SIGNER,
                LegacyCoreKeystoreRole.OPERATIONAL_SIGNER,
            )
        }

        identityEntry?.let {
            validateP256Signer(it, LegacyCoreKeystoreRole.IDENTITY, epoch = null, issues)
        }
        operationalEntries.forEach { (epoch, entry) ->
            validateP256Signer(entry, LegacyCoreKeystoreRole.OPERATIONAL_SIGNER, epoch, issues)
        }
        wrappingEntry?.let { validateWrappingKey(it, issues) }

        if (issues.isNotEmpty()) {
            return LegacyCoreKeystoreReadResult(
                status = LegacyCoreReadStatus.RECOVERY_REQUIRED,
                snapshot = null,
                issues = issues,
                relevantAliasCount = relevantAliasCount,
                digests = digests,
            )
        }

        val identity = requireNotNull(identityEntry)
        val identityInfo = requireNotNull(identity.keyInfo)
        val wrapping = requireNotNull(wrappingEntry)
        val wrappingInfo = requireNotNull(wrapping.keyInfo)
        val snapshot = LegacyCoreKeystoreSnapshot(
            identity = LegacyIdentityKeySource(
                alias = identity.alias,
                aliasVersion = LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS_VERSION,
                publicSpki = requireNotNull(identity.publicSpkiCopyOrNull()),
                securityLevel = identityInfo.securityLevel,
                createdAt = identity.createdAt,
            ),
            operationalSigners = operationalEntries.map { (epoch, entry) ->
                LegacyOperationalSignerSource(
                    epoch = epoch,
                    alias = entry.alias,
                    aliasVersion = LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_VERSION,
                    publicSpki = requireNotNull(entry.publicSpkiCopyOrNull()),
                    securityLevel = requireNotNull(entry.keyInfo).securityLevel,
                    createdAt = entry.createdAt,
                )
            },
            wrappingKey = LegacyWrappingKeySource(
                alias = wrapping.alias,
                aliasVersion = LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS_VERSION,
                securityLevel = wrappingInfo.securityLevel,
                createdAt = wrapping.createdAt,
            ),
            digests = requireNotNull(digests),
        )
        return LegacyCoreKeystoreReadResult(
            status = LegacyCoreReadStatus.READY,
            snapshot = snapshot,
            issues = emptySet(),
            relevantAliasCount = relevantAliasCount,
            digests = digests,
        )
    }

    private fun validateP256Signer(
        entry: LegacyRawKeystoreEntry,
        role: LegacyCoreKeystoreRole,
        epoch: Int?,
        issues: MutableSet<LegacyCoreKeystoreIssue>,
    ) {
        if (entry.createdAt?.let { it < 0 } == true) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_CREATION_TIME, role, epoch)
        }
        if (!entry.requireKind(LegacyRawKeystoreEntryKind.PRIVATE_KEY, role, epoch, issues)) return
        if (!entry.algorithm.equals("EC", ignoreCase = true)) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_KEY_ALGORITHM, role, epoch)
        }
        val publicSpki = entry.publicSpkiCopyOrNull()
        if (publicSpki == null || !publicSpki.isP256Spki()) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_PUBLIC_KEY, role, epoch)
        }
        val info = entry.keyInfo
        if (info == null) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.KEY_INFO_UNAVAILABLE, role, epoch)
            return
        }
        if (info.keystoreAlias != entry.alias || info.keySize != P256_BITS ||
            info.purposes != SIGNER_PURPOSES || info.digests != setOf(KeyProperties.DIGEST_SHA256) ||
            info.userAuthenticationRequired || info.origin != LegacyRawKeyOrigin.GENERATED
        ) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_KEY_POLICY, role, epoch)
        }
        if (!info.securityLevel.isHardwareBacked()) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.NON_HARDWARE_BACKED_KEY, role, epoch)
        }
    }

    private fun validateWrappingKey(
        entry: LegacyRawKeystoreEntry,
        issues: MutableSet<LegacyCoreKeystoreIssue>,
    ) {
        val role = LegacyCoreKeystoreRole.WRAPPING_KEY
        if (entry.createdAt?.let { it < 0 } == true) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_CREATION_TIME, role)
        }
        if (!entry.requireKind(LegacyRawKeystoreEntryKind.SECRET_KEY, role, epoch = null, issues)) return
        if (!entry.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_KEY_ALGORITHM, role)
        }
        val info = entry.keyInfo
        if (info == null) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.KEY_INFO_UNAVAILABLE, role)
            return
        }
        if (info.keystoreAlias != entry.alias || info.keySize != AES_BITS ||
            info.purposes != WRAPPING_PURPOSES || info.blockModes != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            info.encryptionPaddings != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) ||
            info.randomizedEncryptionRequired == false || info.userAuthenticationRequired ||
            info.origin != LegacyRawKeyOrigin.GENERATED
        ) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.INVALID_KEY_POLICY, role)
        }
        if (!info.securityLevel.isHardwareBacked()) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.NON_HARDWARE_BACKED_KEY, role)
        }
    }

    private fun LegacyRawKeystoreEntry.requireKind(
        expected: LegacyRawKeystoreEntryKind,
        role: LegacyCoreKeystoreRole,
        epoch: Int?,
        issues: MutableSet<LegacyCoreKeystoreIssue>,
    ): Boolean {
        if (kind == LegacyRawKeystoreEntryKind.UNREADABLE) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.UNREADABLE_ENTRY, role, epoch)
            return false
        }
        if (kind != expected) {
            issues += LegacyCoreKeystoreIssue(LegacyCoreKeystoreIssueKind.WRONG_ENTRY_TYPE, role, epoch)
            return false
        }
        return true
    }

    private fun ByteArray.isP256Spki(): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(this)) as? ECPublicKey
            ?: return@runCatching false
        key.params.sameCurve(P256_PARAMETERS)
    }.getOrDefault(false)

    private fun ECParameterSpec.sameCurve(other: ECParameterSpec): Boolean =
        curve.field.fieldSize == other.curve.field.fieldSize &&
            curve.a == other.curve.a && curve.b == other.curve.b &&
            generator == other.generator && order == other.order && cofactor == other.cofactor

    private fun LegacyKeystoreSecurityLevel.isHardwareBacked(): Boolean =
        this == LegacyKeystoreSecurityLevel.UNKNOWN_SECURE ||
            this == LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT ||
            this == LegacyKeystoreSecurityLevel.STRONGBOX

    private fun LegacyRawKeystoreSnapshot.digests(): LegacyCoreSourceDigests {
        val content = LegacyCoreDigestAccumulator().apply {
            text("NotiSync/core-keystore/v51")
            aliasesBefore.sorted().forEach { alias ->
                text(alias)
                val entry = entries[alias]
                text(entry?.kind?.name)
                text(entry?.algorithm)
                bytes(entry?.publicSpkiCopyOrNull())
                nullableLong(entry?.createdAt)
                entry?.keyInfo?.let { info ->
                    text(info.keystoreAlias)
                    int(info.keySize)
                    info.purposes.map { it.name }.sorted().forEach(::text)
                    info.digests.sorted().forEach(::text)
                    info.blockModes.sorted().forEach(::text)
                    info.encryptionPaddings.sorted().forEach(::text)
                    text(info.randomizedEncryptionRequired?.toString())
                    boolean(info.userAuthenticationRequired)
                    text(info.origin.name)
                    text(info.securityLevel.name)
                }
            }
            text("aliases-after")
            aliasesAfter.sorted().forEach(::text)
        }.digest()
        val fingerprint = LegacyCoreDigestAccumulator().apply {
            text("NotiSync/core-keystore-logical-fingerprint/v1")
            int(LegacyCoreKeystoreSourceContract.CONTRACT_VERSION)
            bytes(content)
        }.digest()
        return LegacyCoreSourceDigests(content, fingerprint)
    }

    private companion object {
        const val P256_BITS = 256
        const val AES_BITS = 256
        val CANONICAL_EPOCH_SUFFIX = Regex("[1-9][0-9]*")
        val SIGNER_PURPOSES = setOf(LegacyRawKeyPurpose.SIGN, LegacyRawKeyPurpose.VERIFY)
        val WRAPPING_PURPOSES = setOf(LegacyRawKeyPurpose.ENCRYPT, LegacyRawKeyPurpose.DECRYPT)
        val P256_PARAMETERS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }
}
