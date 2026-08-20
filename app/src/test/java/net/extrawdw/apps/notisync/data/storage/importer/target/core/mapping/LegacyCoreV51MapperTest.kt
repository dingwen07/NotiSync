package net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping

import java.security.MessageDigest
import java.util.Base64
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreSourceContract
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreSourceDigests
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyHpkeEpochFileSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyIdentityKeySource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyKeystoreSecurityLevel
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyOperationalSignerSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacySignedTrustFormat
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacySignedTrustSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyWrappingKeySource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochLifecycle
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51TrustCommand
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCoreV51MapperTest {
    private val mapper = LegacyCoreV51Mapper(CoreV51MappingDefaults("https://default.example.test"))

    @Test
    fun legacyThreeSectionRemainsPhysicallyAbsentAndUsesExplicitShippedDefaults() {
        val signer = SoftwareIdentitySigner.generate()
        val trust = threeSectionTrust(signer)
        val plan = mapper.map(
            preferences = readyPreferences(trust),
            keystore = readyKeystore(signer, listOf(1), identityBacking = LegacyKeystoreSecurityLevel.UNKNOWN_SECURE),
            files = readyFiles(listOf(1)),
        )
        val foundation = requireNotNull(plan.foundation)
        val mappedTrust = foundation.trust as CoreV51TrustCommand.ThreeSection

        assertNull(mappedTrust.epochsUtf8OrNull())
        assertArrayEquals(ENTRIES.encodeToByteArray(), mappedTrust.entriesUtf8Copy())
        assertArrayEquals(trust.signatureBase64Url.encodeToByteArray(), mappedTrust.signatureBase64UrlUtf8Copy())
        assertEquals(CoreV51IdentityBacking.HARDWARE_SECURE_UNKNOWN, foundation.identity.backing)
        assertEquals("https://default.example.test", foundation.transport.brokerUrl)
        assertNull(foundation.transport.selfEpochActivatedAt)
        assertEquals(0L, foundation.epochs.single().activationAt)
        assertEquals(CoreV51EpochLifecycle.ACTIVE, foundation.epochs.single().lifecycle)
        assertFalse(mappedTrust.toString().contains(ENTRIES))
    }

    @Test
    fun fourSectionPendingRotationMapsExactBytesAndOnlyAuthorizesItsTargetEpoch() {
        val signer = SoftwareIdentitySigner.generate()
        val epochsJson =
            "{\"selfEpoch\":1,\"peers\":{},\"pending\":{\"targetEpoch\":2,\"notBefore\":10," +
                "\"notAfter\":20,\"retiredEpoch\":1,\"retireRetiredAt\":30}}"
        val trust = fourSectionTrust(signer, epochsJson, selfEpoch = 1)
        val plan = mapper.map(
            preferences = readyPreferences(trust, selfEpochActivatedAt = 7),
            keystore = readyKeystore(signer, listOf(1, 2)),
            files = readyFiles(listOf(1, 2)),
        )
        val foundation = requireNotNull(plan.foundation)
        val mappedTrust = foundation.trust as CoreV51TrustCommand.FourSection

        assertArrayEquals(epochsJson.encodeToByteArray(), mappedTrust.epochsUtf8OrNull())
        assertEquals(listOf(CoreV51EpochLifecycle.ACTIVE, CoreV51EpochLifecycle.PROVISIONING),
            foundation.epochs.map { it.lifecycle })
        assertEquals(7L, foundation.epochs.first().activationAt)
        assertNull(foundation.epochs.last().activationAt)
    }

    @Test
    fun allAbsentSourcesProduceAStableOptionalAbsencePlan() {
        val plan = mapper.map(absentPreferences(), absentKeystore(), absentFiles())

        assertTrue(plan.isAbsent)
        assertNull(plan.foundation)
        assertEquals(32, plan.targetContentDigest.copyBytes().size)
        assertFalse(plan.toString().contains("source"))
    }

    @Test
    fun operationalUnknownSecureBackingFailsClosedInsteadOfOverstatingTee() {
        val signer = SoftwareIdentitySigner.generate()
        val failure = assertThrows(CoreV51MappingException::class.java) {
            mapper.map(
                readyPreferences(threeSectionTrust(signer)),
                readyKeystore(
                    signer,
                    listOf(1),
                    operationalBacking = LegacyKeystoreSecurityLevel.UNKNOWN_SECURE,
                ),
                readyFiles(listOf(1)),
            )
        }

        assertEquals(CoreV51MappingIssue.UNSUPPORTED_OPERATIONAL_BACKING, failure.issue)
    }

    @Test
    fun mappingCopiesEverySecretAndKeyBuffer() {
        val signer = SoftwareIdentitySigner.generate()
        val files = readyFiles(listOf(1))
        val sourcePublic = requireNotNull(files.snapshot).hpkeEpochs.single().publicKeysetCopy()
        val plan = mapper.map(
            readyPreferences(threeSectionTrust(signer)),
            readyKeystore(signer, listOf(1)),
            files,
        )
        val mapped = requireNotNull(plan.foundation).epochs.single()
        val first = mapped.hpkePublicKeysetCopy()
        first[0] = (first[0].toInt() xor 0x7f).toByte()

        assertArrayEquals(sourcePublic, mapped.hpkePublicKeysetCopy())
        assertFalse(mapped.toString().contains(sourcePublic.contentToString()))
    }
}

private fun readyPreferences(
    trust: LegacySignedTrustSource?,
    selfEpochActivatedAt: Long? = null,
): LegacyCorePreferencesReadResult = LegacyCorePreferencesReadResult(
    status = LegacyCoreReadStatus.READY,
    snapshot = LegacyCorePreferencesSnapshot(
        brokerUrl = null,
        deviceName = null,
        deviceNameUpdatedAt = null,
        selfProfileFingerprint = null,
        selfProfileUpdatedAt = null,
        groupId = "group",
        routeEpoch = 2,
        fcmRouteRef = "route",
        lastSeenPostTime = null,
        selfEpochActivatedAt = selfEpochActivatedAt,
        trustCleanupCompleted = true,
        signedTrust = trust,
    ),
    issues = emptySet(),
    presentKeyCount = if (trust?.epochsJson == null) 4 else 5,
    digests = digests(1),
)

private fun readyKeystore(
    identitySigner: SoftwareIdentitySigner,
    epochs: List<Int>,
    identityBacking: LegacyKeystoreSecurityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
    operationalBacking: LegacyKeystoreSecurityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
): LegacyCoreKeystoreReadResult {
    val snapshot = LegacyCoreKeystoreSnapshot(
        identity = LegacyIdentityKeySource(
            alias = LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
            aliasVersion = 1,
            publicSpki = identitySigner.publicKeySpki,
            securityLevel = identityBacking,
            createdAt = 1,
        ),
        operationalSigners = epochs.map { epoch ->
            LegacyOperationalSignerSource(
                epoch = epoch,
                alias = LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + epoch,
                aliasVersion = 1,
                publicSpki = SoftwareIdentitySigner.generate().publicKeySpki,
                securityLevel = operationalBacking,
                createdAt = epoch.toLong(),
            )
        },
        wrappingKey = LegacyWrappingKeySource(
            alias = LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
            aliasVersion = 1,
            securityLevel = LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
            createdAt = 1,
        ),
        digests = digests(2),
    )
    return LegacyCoreKeystoreReadResult(
        status = LegacyCoreReadStatus.READY,
        snapshot = snapshot,
        issues = emptySet(),
        relevantAliasCount = epochs.size + 2,
        digests = snapshot.digests,
    )
}

private fun readyFiles(epochs: List<Int>): LegacyCoreFileReadResult {
    val snapshot = LegacyCoreFileSnapshot(
        hpkeEpochs = epochs.map { epoch ->
            val pair = Hpke.generateKeyPair()
            LegacyHpkeEpochFileSource(
                epoch = epoch,
                publicKeyset = pair.publicKeyset,
                wrappedPrivateKeyset = byteArrayOf(12) + ByteArray(12) + pair.privateKeyset + ByteArray(16),
            )
        },
        authToken = null,
        skippedUnversionedHpkeFileCount = 0,
        digests = digests(3),
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

private fun absentPreferences() = LegacyCorePreferencesReadResult(
    LegacyCoreReadStatus.ABSENT, null, emptySet(), 0, digests(4),
)

private fun absentKeystore() = LegacyCoreKeystoreReadResult(
    LegacyCoreReadStatus.ABSENT, null, emptySet(), 0, null,
)

private fun absentFiles() = LegacyCoreFileReadResult(
    LegacyCoreReadStatus.ABSENT, null, emptySet(), 0, 0, null,
)

private fun fourSectionTrust(
    signer: SoftwareIdentitySigner,
    epochsJson: String,
    selfEpoch: Int,
): LegacySignedTrustSource {
    val signature = TrustStoreSigning.sign(signer, ENTRIES, CARDS, OVERLAYS, epochsJson)
    return LegacySignedTrustSource(
        LegacySignedTrustFormat.FOUR_SECTION,
        ENTRIES,
        CARDS,
        OVERLAYS,
        epochsJson,
        Base64.getUrlDecoder().decode(signature),
        signature,
        selfEpoch,
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
    val signature = encoder.encodeToString(signer.sign(canonical))
    return LegacySignedTrustSource(
        LegacySignedTrustFormat.LEGACY_THREE_SECTION,
        ENTRIES,
        CARDS,
        OVERLAYS,
        null,
        Base64.getUrlDecoder().decode(signature),
        signature,
        1,
    )
}

private fun digests(seed: Int): LegacyCoreSourceDigests =
    LegacyCoreSourceDigests(ByteArray(32) { seed.toByte() }, ByteArray(32) { (seed + 10).toByte() })

private const val ENTRIES = "[{\"id\":\"source-entry\"}]"
private const val CARDS = "{}"
private const val OVERLAYS = "{}"
