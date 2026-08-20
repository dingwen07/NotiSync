package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import android.security.keystore.KeyProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCoreKeystoreReaderTest {
    @Test
    fun readySnapshotIsSortedDefensiveAndDoesNotExerciseKeys() = runBlocking {
        val raw = validRawSnapshot(operationalEpochs = listOf(3, 1, 2))
        var snapshotsTaken = 0
        val reader = LegacyCoreKeystoreReader(
            LegacyKeystoreSnapshotPort {
                snapshotsTaken += 1
                raw
            },
        )

        val result = reader.read()

        assertEquals(1, snapshotsTaken)
        assertEquals(LegacyCoreReadStatus.READY, result.status)
        assertEquals(listOf(1, 2, 3), result.snapshot?.operationalSigners?.map { it.epoch })
        assertEquals(5, result.relevantAliasCount)
        assertTrue(result.issues.isEmpty())
        assertFalse(result.toString().contains(LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS))
        assertFalse(result.snapshot.toString().contains("public".repeat(8)))

        val identity = requireNotNull(result.snapshot).identity
        val first = identity.publicSpkiCopy()
        val expected = first.copyOf()
        first.fill(0)
        assertArrayEquals(expected, identity.publicSpkiCopy())

        val digest = requireNotNull(result.digests).contentDigest
        val expectedDigest = digest.copyOf()
        digest.fill(0)
        assertArrayEquals(expectedDigest, requireNotNull(result.digests).contentDigest)
    }

    @Test
    fun missingAndNonCanonicalAliasesRequireRecoveryWithValueFreeIssues() {
        val valid = validRawSnapshot(operationalEpochs = listOf(1))
        val aliases = valid.aliasesBefore.toMutableSet().apply {
            remove(LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS)
            add(LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "01")
        }
        val entries = valid.entries.toMutableMap().apply {
            remove(LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS)
            put(
                LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "01",
                signerEntry(LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "01"),
            )
        }
        val raw = LegacyRawKeystoreSnapshot(aliases, entries, aliases)

        val result = LegacyCoreKeystoreReader().inspect(raw)

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertNull(result.snapshot)
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.MISSING_WRAPPING_KEY })
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.NON_CANONICAL_OPERATIONAL_ALIAS })
        assertFalse(result.toString().contains("epoch01"))
    }

    @Test
    fun invalidPolicySoftwareBackingAndCreationTimeAreFailClosed() {
        val raw = validRawSnapshot(operationalEpochs = listOf(1))
        val alias = LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "1"
        val entries = raw.entries.toMutableMap().apply {
            put(
                alias,
                signerEntry(
                    alias = alias,
                    securityLevel = LegacyKeystoreSecurityLevel.SOFTWARE,
                    createdAt = -1,
                    purposes = setOf(LegacyRawKeyPurpose.SIGN),
                ),
            )
            put(
                LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
                wrappingEntry(randomizedEncryptionRequired = false),
            )
        }

        val result = LegacyCoreKeystoreReader().inspect(
            LegacyRawKeystoreSnapshot(raw.aliasesBefore, entries, raw.aliasesAfter),
        )

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.INVALID_KEY_POLICY })
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.NON_HARDWARE_BACKED_KEY })
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.INVALID_CREATION_TIME })
    }

    @Test
    fun platformUnknownRandomizationFlagIsNotInventedOrRejected() {
        val raw = validRawSnapshot(operationalEpochs = listOf(1))
        val entries = raw.entries.toMutableMap().apply {
            put(
                LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
                wrappingEntry(randomizedEncryptionRequired = null),
            )
        }

        val result = LegacyCoreKeystoreReader().inspect(
            LegacyRawKeystoreSnapshot(raw.aliasesBefore, entries, raw.aliasesAfter),
        )

        assertEquals(LegacyCoreReadStatus.READY, result.status)
    }

    @Test
    fun aliasInventoryChangeAndUnreadableEntryAreRecoveryRequired() {
        val raw = validRawSnapshot(operationalEpochs = listOf(1))
        val after = raw.aliasesAfter + (LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "2")
        val entries = raw.entries.toMutableMap().apply {
            put(
                LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
                LegacyRawKeystoreEntry(
                    alias = LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
                    kind = LegacyRawKeystoreEntryKind.UNREADABLE,
                    algorithm = null,
                    publicSpki = null,
                    keyInfo = null,
                    createdAt = null,
                ),
            )
        }

        val result = LegacyCoreKeystoreReader().inspect(
            LegacyRawKeystoreSnapshot(raw.aliasesBefore, entries, after),
        )

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.SOURCE_CHANGED_DURING_READ })
        assertTrue(result.issues.any { it.kind == LegacyCoreKeystoreIssueKind.UNREADABLE_ENTRY })
    }

    @Test
    fun rawInputsAreDefensivelyCopiedAndStringFormsAreRedacted() {
        val mutablePurposes = mutableSetOf(LegacyRawKeyPurpose.SIGN, LegacyRawKeyPurpose.VERIFY)
        val info = validSignerInfo("private-alias", purposes = mutablePurposes)
        mutablePurposes.clear()
        assertEquals(setOf(LegacyRawKeyPurpose.SIGN, LegacyRawKeyPurpose.VERIFY), info.purposes)
        assertFalse(info.toString().contains("private-alias"))

        val publicBytes = p256Spki()
        val expected = publicBytes.copyOf()
        val entry = LegacyRawKeystoreEntry(
            alias = "private-alias",
            kind = LegacyRawKeystoreEntryKind.PRIVATE_KEY,
            algorithm = "EC",
            publicSpki = publicBytes,
            keyInfo = info,
            createdAt = 1,
        )
        publicBytes.fill(0)
        assertArrayEquals(expected, entry.publicSpkiCopyOrNull())
        assertFalse(entry.toString().contains("private-alias"))
    }

    @Test
    fun cancellationFromReadOnlyPortPropagates() {
        val reader = LegacyCoreKeystoreReader(
            LegacyKeystoreSnapshotPort { throw CancellationException("cancel") },
        )
        assertThrows(CancellationException::class.java) { runBlocking { reader.read() } }
    }

    private fun validRawSnapshot(operationalEpochs: List<Int>): LegacyRawKeystoreSnapshot {
        val aliases = linkedSetOf(
            LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
            LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
        )
        val entries = linkedMapOf<String, LegacyRawKeystoreEntry>()
        entries[LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS] =
            signerEntry(LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS)
        entries[LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS] = wrappingEntry()
        operationalEpochs.forEach { epoch ->
            val alias = LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + epoch
            aliases += alias
            entries[alias] = signerEntry(alias)
        }
        return LegacyRawKeystoreSnapshot(aliases, entries, aliases)
    }

    private fun signerEntry(
        alias: String,
        securityLevel: LegacyKeystoreSecurityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
        createdAt: Long = 1,
        purposes: Set<LegacyRawKeyPurpose> = SIGNER_PURPOSES,
    ): LegacyRawKeystoreEntry = LegacyRawKeystoreEntry(
        alias = alias,
        kind = LegacyRawKeystoreEntryKind.PRIVATE_KEY,
        algorithm = "EC",
        publicSpki = p256Spki(),
        keyInfo = validSignerInfo(alias, purposes, securityLevel),
        createdAt = createdAt,
    )

    private fun validSignerInfo(
        alias: String,
        purposes: Set<LegacyRawKeyPurpose> = SIGNER_PURPOSES,
        securityLevel: LegacyKeystoreSecurityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
    ): LegacyRawKeyInfo = LegacyRawKeyInfo(
        keystoreAlias = alias,
        keySize = 256,
        purposes = purposes,
        digests = setOf(KeyProperties.DIGEST_SHA256),
        blockModes = emptySet(),
        encryptionPaddings = emptySet(),
        randomizedEncryptionRequired = null,
        userAuthenticationRequired = false,
        origin = LegacyRawKeyOrigin.GENERATED,
        securityLevel = securityLevel,
    )

    private fun wrappingEntry(
        randomizedEncryptionRequired: Boolean? = null,
    ): LegacyRawKeystoreEntry {
        val alias = LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS
        return LegacyRawKeystoreEntry(
            alias = alias,
            kind = LegacyRawKeystoreEntryKind.SECRET_KEY,
            algorithm = KeyProperties.KEY_ALGORITHM_AES,
            publicSpki = null,
            keyInfo = LegacyRawKeyInfo(
                keystoreAlias = alias,
                keySize = 256,
                purposes = setOf(LegacyRawKeyPurpose.ENCRYPT, LegacyRawKeyPurpose.DECRYPT),
                digests = emptySet(),
                blockModes = setOf(KeyProperties.BLOCK_MODE_GCM),
                encryptionPaddings = setOf(KeyProperties.ENCRYPTION_PADDING_NONE),
                randomizedEncryptionRequired = randomizedEncryptionRequired,
                userAuthenticationRequired = false,
                origin = LegacyRawKeyOrigin.GENERATED,
                securityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
            ),
            createdAt = 1,
        )
    }

    private fun p256Spki(): ByteArray = SoftwareIdentitySigner.generate().publicKeySpki

    private companion object {
        val SIGNER_PURPOSES = setOf(LegacyRawKeyPurpose.SIGN, LegacyRawKeyPurpose.VERIFY)
    }
}
