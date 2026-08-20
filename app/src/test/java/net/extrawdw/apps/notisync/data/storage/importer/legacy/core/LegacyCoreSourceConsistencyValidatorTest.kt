package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import java.security.MessageDigest
import java.util.Base64
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCoreSourceConsistencyValidatorTest {
    private val validator = LegacyCoreSourceConsistencyValidator()

    @Test
    fun exactFourSectionSignatureAndMatchingEpochsAreReady() {
        val signer = SoftwareIdentitySigner.generate()
        val trust = fourSectionTrust(signer, selfEpoch = 2)

        val result = validator.validate(
            preferences = readyPreferences(trust),
            keystore = readyKeystore(signer, setOf(1, 2)),
            files = readyFiles(setOf(1, 2)),
        )

        assertEquals(LegacyCoreReadStatus.READY, result.status)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun exactLegacyThreeSectionSignatureIsVerifiedWithoutSynthesizingEpochBytes() {
        val signer = SoftwareIdentitySigner.generate()
        val trust = threeSectionTrust(signer)

        val result = validator.validate(
            preferences = readyPreferences(trust),
            keystore = readyKeystore(signer, setOf(1)),
            files = readyFiles(setOf(1)),
        )

        assertEquals(LegacyCoreReadStatus.READY, result.status)
        assertEquals(LegacySignedTrustFormat.LEGACY_THREE_SECTION, trust.format)
        assertEquals(null, trust.epochsJson)
    }

    @Test
    fun trustSignedByAnotherIdentityIsSecurityBlocking() {
        val observedIdentity = SoftwareIdentitySigner.generate()
        val otherIdentity = SoftwareIdentitySigner.generate()

        val result = validator.validate(
            preferences = readyPreferences(fourSectionTrust(otherIdentity, selfEpoch = 1)),
            keystore = readyKeystore(observedIdentity, setOf(1)),
            files = readyFiles(setOf(1)),
        )

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(LegacyCoreConsistencyIssue.TRUST_IDENTITY_SIGNATURE_MISMATCH in result.issues)
    }

    @Test
    fun epochInventoryDifferencesAndMissingCurrentEpochAreExplicit() {
        val signer = SoftwareIdentitySigner.generate()

        val result = validator.validate(
            preferences = readyPreferences(fourSectionTrust(signer, selfEpoch = 2)),
            keystore = readyKeystore(signer, setOf(1, 2)),
            files = readyFiles(setOf(1, 3)),
        )

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(LegacyCoreConsistencyIssue.OPERATIONAL_ALIAS_WITHOUT_HPKE_PAIR in result.issues)
        assertTrue(LegacyCoreConsistencyIssue.HPKE_PAIR_WITHOUT_OPERATIONAL_ALIAS in result.issues)
        assertTrue(LegacyCoreConsistencyIssue.CURRENT_TRUST_EPOCH_MISSING in result.issues)
    }

    @Test
    fun sourceRecoveryAndPartialSecurityInventoriesNeverBecomeReady() {
        val signer = SoftwareIdentitySigner.generate()
        val recoveryPreferences = LegacyCorePreferencesReadResult(
            status = LegacyCoreReadStatus.RECOVERY_REQUIRED,
            snapshot = null,
            issues = setOf(
                LegacyCorePreferencesIssue(LegacyCorePreferencesIssueKind.MALFORMED_TRUST_SIGNATURE),
            ),
            presentKeyCount = 1,
            digests = digests(),
        )
        val sourceFailure = validator.validate(
            preferences = recoveryPreferences,
            keystore = absentKeystore(),
            files = absentFiles(),
        )
        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, sourceFailure.status)
        assertEquals(setOf(LegacyCoreConsistencyIssue.SOURCE_REQUIRES_RECOVERY), sourceFailure.issues)

        val partial = validator.validate(
            preferences = absentPreferences(),
            keystore = readyKeystore(signer, setOf(1)),
            files = absentFiles(),
        )
        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, partial.status)
        assertEquals(setOf(LegacyCoreConsistencyIssue.PARTIAL_SECURITY_SOURCE), partial.issues)
    }

    @Test
    fun entirelyAbsentSourceIsDistinguishedFromSettingsWithoutSecurityMaterial() {
        val absent = validator.validate(absentPreferences(), absentKeystore(), absentFiles())
        assertEquals(LegacyCoreReadStatus.ABSENT, absent.status)

        val settingsOnly = readyPreferences(signedTrust = null)
        val partial = validator.validate(settingsOnly, absentKeystore(), absentFiles())
        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, partial.status)
        assertEquals(setOf(LegacyCoreConsistencyIssue.PARTIAL_SECURITY_SOURCE), partial.issues)
    }

    private fun fourSectionTrust(
        signer: SoftwareIdentitySigner,
        selfEpoch: Int,
    ): LegacySignedTrustSource {
        val epochs = "{\"selfEpoch\":$selfEpoch,\"peers\":{}}"
        val signature = TrustStoreSigning.sign(signer, ENTRIES, CARDS, OVERLAYS, epochs)
        return LegacySignedTrustSource(
            format = LegacySignedTrustFormat.FOUR_SECTION,
            entriesJson = ENTRIES,
            cardsJson = CARDS,
            overlaysJson = OVERLAYS,
            epochsJson = epochs,
            signatureBytes = Base64.getUrlDecoder().decode(signature),
            signatureBase64Url = signature,
            effectiveSelfEpoch = selfEpoch,
        )
    }

    private fun threeSectionTrust(signer: SoftwareIdentitySigner): LegacySignedTrustSource {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun digest(value: String): String = encoder.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()),
        )
        val canonical = buildString {
            append(TrustStoreSigning.VERSION).append('\n')
            append(signer.clientId.value).append('\n')
            append(digest(ENTRIES)).append('\n')
            append(digest(CARDS)).append('\n')
            append(digest(OVERLAYS))
        }.encodeToByteArray()
        val signatureBytes = signer.sign(canonical)
        return LegacySignedTrustSource(
            format = LegacySignedTrustFormat.LEGACY_THREE_SECTION,
            entriesJson = ENTRIES,
            cardsJson = CARDS,
            overlaysJson = OVERLAYS,
            epochsJson = null,
            signatureBytes = signatureBytes,
            signatureBase64Url = encoder.encodeToString(signatureBytes),
            effectiveSelfEpoch = 1,
        )
    }

    private fun readyPreferences(
        signedTrust: LegacySignedTrustSource?,
    ): LegacyCorePreferencesReadResult = LegacyCorePreferencesReadResult(
        status = LegacyCoreReadStatus.READY,
        snapshot = LegacyCorePreferencesSnapshot(
            brokerUrl = null,
            deviceName = null,
            deviceNameUpdatedAt = null,
            selfProfileFingerprint = null,
            selfProfileUpdatedAt = null,
            groupId = null,
            routeEpoch = null,
            fcmRouteRef = null,
            lastSeenPostTime = null,
            selfEpochActivatedAt = null,
            trustCleanupCompleted = null,
            signedTrust = signedTrust,
        ),
        issues = emptySet(),
        presentKeyCount = if (signedTrust == null) 1 else if (signedTrust.epochsJson == null) 4 else 5,
        digests = digests(),
    )

    private fun readyKeystore(
        signer: SoftwareIdentitySigner,
        epochs: Set<Int>,
    ): LegacyCoreKeystoreReadResult {
        val snapshot = LegacyCoreKeystoreSnapshot(
            identity = LegacyIdentityKeySource(
                alias = LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
                aliasVersion = 1,
                publicSpki = signer.publicKeySpki,
                securityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                createdAt = 1,
            ),
            operationalSigners = epochs.sorted().map { epoch ->
                LegacyOperationalSignerSource(
                    epoch = epoch,
                    alias = LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + epoch,
                    aliasVersion = 1,
                    publicSpki = SoftwareIdentitySigner.generate().publicKeySpki,
                    securityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                    createdAt = 1,
                )
            },
            wrappingKey = LegacyWrappingKeySource(
                alias = LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
                aliasVersion = 1,
                securityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                createdAt = 1,
            ),
            digests = digests(),
        )
        return LegacyCoreKeystoreReadResult(
            status = LegacyCoreReadStatus.READY,
            snapshot = snapshot,
            issues = emptySet(),
            relevantAliasCount = epochs.size + 2,
            digests = snapshot.digests,
        )
    }

    private fun readyFiles(epochs: Set<Int>): LegacyCoreFileReadResult {
        val sources = epochs.sorted().map { epoch ->
            val pair = Hpke.generateKeyPair()
            LegacyHpkeEpochFileSource(
                epoch = epoch,
                publicKeyset = pair.publicKeyset,
                wrappedPrivateKeyset = wrapForFixture(pair.privateKeyset),
            )
        }
        val snapshot = LegacyCoreFileSnapshot(
            hpkeEpochs = sources,
            authToken = null,
            skippedUnversionedHpkeFileCount = 0,
            digests = digests(),
        )
        return LegacyCoreFileReadResult(
            status = LegacyCoreReadStatus.READY,
            snapshot = snapshot,
            issues = emptySet(),
            relevantFileCount = epochs.size * 2,
            skippedUnversionedHpkeFileCount = 0,
            digests = snapshot.digests,
        )
    }

    private fun absentPreferences(): LegacyCorePreferencesReadResult = LegacyCorePreferencesReadResult(
        status = LegacyCoreReadStatus.ABSENT,
        snapshot = null,
        issues = emptySet(),
        presentKeyCount = 0,
        digests = digests(),
    )

    private fun absentKeystore(): LegacyCoreKeystoreReadResult = LegacyCoreKeystoreReadResult(
        status = LegacyCoreReadStatus.ABSENT,
        snapshot = null,
        issues = emptySet(),
        relevantAliasCount = 0,
        digests = digests(),
    )

    private fun absentFiles(): LegacyCoreFileReadResult = LegacyCoreFileReadResult(
        status = LegacyCoreReadStatus.ABSENT,
        snapshot = null,
        issues = emptySet(),
        relevantFileCount = 0,
        skippedUnversionedHpkeFileCount = 0,
        digests = digests(),
    )

    private fun wrapForFixture(privateKeyset: ByteArray): ByteArray =
        byteArrayOf(12) + ByteArray(12) + privateKeyset + ByteArray(16)

    private fun digests(): LegacyCoreSourceDigests = LegacyCoreSourceDigests(ByteArray(32), ByteArray(32))

    private companion object {
        const val ENTRIES = "[]"
        const val CARDS = "{}"
        const val OVERLAYS = "{}"
    }
}
