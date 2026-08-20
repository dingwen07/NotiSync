package net.extrawdw.apps.notisync.composition.bootstrap

import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.composition.storage.StorageClock
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.AndroidOperationalSigner
import net.extrawdw.apps.notisync.crypto.KeyBacking
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationGate
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalBacking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier

/**
 * Read-only v51 activation. Every primitive is loaded through a strict existing-only API and exercised against the
 * exact target-persisted candidates. This class has no generate, delete, wrap, rewrite, publish, or network path.
 */
internal class ProductionCoreV51ActivationGate(
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: StorageClock,
) : CoreV51ActivationGate {
    override suspend fun validate(snapshot: CoreV51ActivationSnapshot): CoreV51ActivationEvidence =
        withContext(ioDispatcher) {
            try {
                validateExisting(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CoreV51ImportFailure) {
                throw failure
            } catch (failure: UnrecoverableKeyException) {
                blocked("activation_existing_key_unrecoverable", failure)
            } catch (failure: KeyStoreException) {
                retryable("activation_keystore_temporarily_unavailable", failure)
            } catch (failure: ProviderException) {
                retryable("activation_provider_temporarily_unavailable", failure)
            } catch (failure: GeneralSecurityException) {
                blocked("activation_cryptographic_self_test_failed", failure)
            } catch (failure: IllegalArgumentException) {
                blocked("activation_candidate_invalid", failure)
            } catch (failure: IllegalStateException) {
                blocked("activation_existing_key_invalid", failure)
            } catch (failure: Exception) {
                blocked("activation_unexpected_failure", failure)
            }
        }

    private suspend fun validateExisting(snapshot: CoreV51ActivationSnapshot): CoreV51ActivationEvidence {
        val timestamps = OrderedEvidenceClock(clock)
        val identityCommand = snapshot.identity
        if (identityCommand.alias != AndroidIdentitySigner.KEY_ALIAS || identityCommand.aliasVersion != 1) {
            blocked("activation_identity_alias_noncanonical")
        }
        val identity = AndroidIdentitySigner.loadExisting(identityCommand.alias)
            ?: blocked("activation_identity_alias_missing")
        val persistedIdentitySpki = identityCommand.publicSpkiCopy()
        if (!MessageDigest.isEqual(identity.publicKeySpki, persistedIdentitySpki) ||
            identity.clientId != ClientIds.derive(persistedIdentitySpki) ||
            !identity.backing.matches(identityCommand.backing)
        ) {
            blocked("activation_identity_binding_mismatch")
        }
        selfTestSignature(identity.publicKeySpki, identity::sign, IDENTITY_CHALLENGE.copyOf())
        val identitySelfTestedAt = timestamps.stamp()

        val wrappingCommand = snapshot.wrappingKey
        if (wrappingCommand.alias != KeyVault.KEY_ALIAS || wrappingCommand.aliasVersion != 1) {
            blocked("activation_wrapping_alias_noncanonical")
        }
        val vault = KeyVault.loadExisting(wrappingCommand.alias)
            ?: blocked("activation_wrapping_alias_missing")
        if (!vault.backing.matches(wrappingCommand.backing)) {
            blocked("activation_wrapping_backing_mismatch")
        }
        val wrappingKeySelfTestedAt = timestamps.stamp()

        val clientId = ClientId(identity.clientId.value)
        val epochEvidence = ArrayList<CoreV51EpochActivationEvidence>(snapshot.epochs.size)
        for (epoch in snapshot.epochs.sortedBy(CoreV51EpochCommand::epoch)) {
            currentCoroutineContext().ensureActive()
            if (epoch.operationalSignerAlias != AndroidOperationalSigner.aliasFor(epoch.epoch) ||
                epoch.operationalSignerAliasVersion != 1
            ) {
                blocked("activation_operational_alias_noncanonical")
            }
            val signer = AndroidOperationalSigner.loadExisting(
                clientId = clientId,
                epoch = epoch.epoch,
                alias = epoch.operationalSignerAlias,
            ) ?: blocked("activation_operational_alias_missing")
            if (!MessageDigest.isEqual(signer.operationalPublicKeySpki, epoch.operationalSignerPublicSpkiCopy()) ||
                signer.signerEpoch != epoch.epoch || signer.clientId != clientId ||
                !signer.backing.matches(epoch.backing)
            ) {
                blocked("activation_operational_binding_mismatch")
            }
            selfTestSignature(
                signer.operationalPublicKeySpki,
                signer::sign,
                operationalChallenge(epoch.epoch),
            )
            val signerSelfTestedAt = timestamps.stamp()

            val privateKeyset = vault.unwrap(epoch.hpkePrivateKeysetWrappedCopy())
            val pairTestedAt = try {
                selfTestHpkePair(epoch.epoch, epoch.hpkePublicKeysetCopy(), privateKeyset)
                timestamps.stamp()
            } finally {
                privateKeyset.fill(0)
            }
            epochEvidence += CoreV51EpochActivationEvidence(
                epoch = epoch.epoch,
                hpkePublicKeysetFingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(epoch.hpkePublicKeysetCopy()),
                operationalSignerSelfTestedAt = signerSelfTestedAt,
                hpkePairSelfTestedAt = pairTestedAt,
            )
        }

        val authTokenSelfTestedAt = snapshot.authToken?.let { token ->
            val plaintext = vault.unwrap(token.wrappedTokenCopy())
            try {
                if (plaintext.isEmpty() || plaintext.size > MAX_AUTH_TOKEN_BYTES) {
                    blocked("activation_auth_token_invalid")
                }
                ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(plaintext.toString(Charsets.UTF_8))
                timestamps.stamp()
            } finally {
                plaintext.fill(0)
            }
        }

        return CoreV51ActivationEvidence(
            planDigest = snapshot.planDigest,
            candidateDigest = snapshot.candidateDigest,
            identityClientId = identity.clientId.value,
            identitySelfTestedAt = identitySelfTestedAt,
            wrappingKeySelfTestedAt = wrappingKeySelfTestedAt,
            epochEvidence = epochEvidence,
            authTokenSelfTestedAt = authTokenSelfTestedAt,
            validatedAt = timestamps.stamp(),
        )
    }

    private fun selfTestSignature(
        publicSpki: ByteArray,
        sign: (ByteArray) -> ByteArray,
        challenge: ByteArray,
    ) {
        val signature = try {
            sign(challenge)
        } catch (failure: Exception) {
            challenge.fill(0)
            throw failure
        }
        try {
            if (!IdentityVerifier.verify(publicSpki, challenge, signature)) {
                blocked("activation_signature_self_test_failed")
            }
        } finally {
            challenge.fill(0)
            signature.fill(0)
        }
    }

    private fun selfTestHpkePair(epoch: Int, publicKeyset: ByteArray, privateKeyset: ByteArray) {
        val plaintext = HPKE_TEST_PLAINTEXT.copyOf()
        val context = "notisync-core-v51-hpke-self-test-v1:$epoch".encodeToByteArray()
        val sealed = Hpke.seal(plaintext, publicKeyset, context)
        val opened = try {
            Hpke.open(sealed, privateKeyset, context)
        } finally {
            sealed.fill(0)
        }
        try {
            if (!MessageDigest.isEqual(plaintext, opened)) blocked("activation_hpke_pair_mismatch")
        } finally {
            plaintext.fill(0)
            opened.fill(0)
            context.fill(0)
        }
    }

    private fun operationalChallenge(epoch: Int): ByteArray =
        "notisync-core-v51-operational-self-test-v1:$epoch".encodeToByteArray()

    private companion object {
        val IDENTITY_CHALLENGE = "notisync-core-v51-identity-self-test-v1".encodeToByteArray()
        val HPKE_TEST_PLAINTEXT = ByteArray(32) { index -> (index * 17 + 11).toByte() }
        const val MAX_AUTH_TOKEN_BYTES = 1_048_576
    }
}

private class OrderedEvidenceClock(private val clock: StorageClock) {
    private var floor = 0L

    fun stamp(): Long {
        val observed = clock.nowMillis()
        if (observed <= 0) blocked("activation_clock_invalid")
        floor = maxOf(floor, observed)
        return floor
    }
}

private fun KeyBacking.matches(expected: CoreV51IdentityBacking): Boolean = when (expected) {
    CoreV51IdentityBacking.HARDWARE_SECURE_UNKNOWN ->
        this == KeyBacking.UNKNOWN_SECURE || this == KeyBacking.TEE || this == KeyBacking.STRONGBOX
    CoreV51IdentityBacking.TRUSTED_ENVIRONMENT -> this == KeyBacking.TEE
    CoreV51IdentityBacking.STRONGBOX -> this == KeyBacking.STRONGBOX
}

private fun KeyBacking.matches(expected: CoreV51OperationalBacking): Boolean = when (expected) {
    CoreV51OperationalBacking.TRUSTED_ENVIRONMENT -> this == KeyBacking.TEE
    CoreV51OperationalBacking.STRONGBOX -> this == KeyBacking.STRONGBOX
}

private fun blocked(code: String, cause: Throwable? = null): Nothing = throw CoreV51ImportFailure(
    CoreV51FailureDisposition.BLOCKED,
    code,
    cause,
)

private fun retryable(code: String, cause: Throwable): Nothing = throw CoreV51ImportFailure(
    CoreV51FailureDisposition.RETRYABLE,
    code,
    cause,
)
