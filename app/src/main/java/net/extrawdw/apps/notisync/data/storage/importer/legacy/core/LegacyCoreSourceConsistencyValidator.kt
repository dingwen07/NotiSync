package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning

/**
 * Pure cross-source validation for one v51 Core observation.
 *
 * This class verifies only public identity signatures and source inventory relationships. It does
 * not unwrap private bytes, exercise a Keystore key, mutate a source, or map anything into Room.
 */
internal class LegacyCoreSourceConsistencyValidator {
    fun validate(
        preferences: LegacyCorePreferencesReadResult,
        keystore: LegacyCoreKeystoreReadResult,
        files: LegacyCoreFileReadResult,
    ): LegacyCoreConsistencyResult {
        if (listOf(preferences.status, keystore.status, files.status).any {
                it == LegacyCoreReadStatus.RECOVERY_REQUIRED
            }
        ) {
            return recovery(LegacyCoreConsistencyIssue.SOURCE_REQUIRES_RECOVERY)
        }

        val allAbsent = preferences.status == LegacyCoreReadStatus.ABSENT &&
            keystore.status == LegacyCoreReadStatus.ABSENT &&
            files.status == LegacyCoreReadStatus.ABSENT
        if (allAbsent) {
            return LegacyCoreConsistencyResult(LegacyCoreReadStatus.ABSENT, emptySet())
        }

        val issues = linkedSetOf<LegacyCoreConsistencyIssue>()
        val keystoreSnapshot = keystore.snapshot
        val fileSnapshot = files.snapshot
        if (keystoreSnapshot == null || fileSnapshot == null) {
            issues += LegacyCoreConsistencyIssue.PARTIAL_SECURITY_SOURCE
        }

        if (keystoreSnapshot != null && fileSnapshot != null) {
            val operationalEpochs = keystoreSnapshot.operationalSigners.mapTo(sortedSetOf()) { it.epoch }
            val hpkeEpochs = fileSnapshot.hpkeEpochs.mapTo(sortedSetOf()) { it.epoch }
            if ((operationalEpochs - hpkeEpochs).isNotEmpty()) {
                issues += LegacyCoreConsistencyIssue.OPERATIONAL_ALIAS_WITHOUT_HPKE_PAIR
            }
            if ((hpkeEpochs - operationalEpochs).isNotEmpty()) {
                issues += LegacyCoreConsistencyIssue.HPKE_PAIR_WITHOUT_OPERATIONAL_ALIAS
            }

            preferences.snapshot?.signedTrust?.let { trust ->
                if (!trust.verifiesWith(keystoreSnapshot.identity)) {
                    issues += LegacyCoreConsistencyIssue.TRUST_IDENTITY_SIGNATURE_MISMATCH
                }
                if (trust.effectiveSelfEpoch !in operationalEpochs ||
                    trust.effectiveSelfEpoch !in hpkeEpochs
                ) {
                    issues += LegacyCoreConsistencyIssue.CURRENT_TRUST_EPOCH_MISSING
                }
            }
        }

        return if (issues.isEmpty()) {
            LegacyCoreConsistencyResult(LegacyCoreReadStatus.READY, emptySet())
        } else {
            LegacyCoreConsistencyResult(LegacyCoreReadStatus.RECOVERY_REQUIRED, issues)
        }
    }

    private fun LegacySignedTrustSource.verifiesWith(identity: LegacyIdentityKeySource): Boolean =
        when (format) {
            LegacySignedTrustFormat.LEGACY_THREE_SECTION ->
                TrustStoreSigning.verifyLegacyThreeSection(
                    publicKeySpki = identity.publicSpkiCopy(),
                    selfId = ClientId(identity.clientId),
                    entriesJson = entriesJson,
                    cardsJson = cardsJson,
                    overlaysJson = overlaysJson,
                    signatureB64 = signatureBase64Url,
                )

            LegacySignedTrustFormat.FOUR_SECTION ->
                TrustStoreSigning.verify(
                    publicKeySpki = identity.publicSpkiCopy(),
                    selfId = ClientId(identity.clientId),
                    entriesJson = entriesJson,
                    cardsJson = cardsJson,
                    overlaysJson = overlaysJson,
                    epochsJson = requireNotNull(epochsJson),
                    signatureB64 = signatureBase64Url,
                )
        }

    private fun recovery(issue: LegacyCoreConsistencyIssue): LegacyCoreConsistencyResult =
        LegacyCoreConsistencyResult(
            status = LegacyCoreReadStatus.RECOVERY_REQUIRED,
            issues = setOf(issue),
        )
}
