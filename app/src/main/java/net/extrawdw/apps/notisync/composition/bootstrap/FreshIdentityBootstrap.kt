package net.extrawdw.apps.notisync.composition.bootstrap

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.AndroidOperationalSigner
import net.extrawdw.apps.notisync.crypto.KeyBacking
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochInput
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochSecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochState
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportInitializationResult
import net.extrawdw.apps.notisync.data.storage.core.FreshIdentityTransportInitialization
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataInput
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataSaveResult
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationEnsureResult
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationIntent
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationState
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationTransitionResult
import net.extrawdw.apps.notisync.data.storage.core.OperationalStorageBinding
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier

internal class FreshIdentityKeyMaterial(
    val alias: String,
    publicSpki: ByteArray,
    val clientId: String,
    val backing: KeyBacking,
    private val selfTestBlock: suspend () -> Unit,
) {
    private val storedPublicSpki = publicSpki.copyOf()
    val publicSpki: ByteArray get() = storedPublicSpki.copyOf()

    suspend fun selfTest() = selfTestBlock()

    override fun toString(): String = "FreshIdentityKeyMaterial(backing=$backing,key=<redacted>)"
}

/** The only generation operation is named to make its durable-intent precondition visible at every call site. */
internal interface FreshIdentityCryptoPort {
    suspend fun loadExisting(alias: String): FreshIdentityKeyMaterial?
    suspend fun loadOrCreateAfterIntent(alias: String): FreshIdentityKeyMaterial
    suspend fun provisionFoundationAfterIntent(
        clientId: String,
        createdAt: Long,
    ): CryptoEpochInput
}

internal class AndroidFreshIdentityCryptoPort(
    private val ioDispatcher: CoroutineDispatcher,
) : FreshIdentityCryptoPort {
    override suspend fun loadExisting(alias: String): FreshIdentityKeyMaterial? = cryptoCall("identity_load") {
        AndroidIdentitySigner.loadExisting(alias)?.toFreshMaterial(alias)
    }

    override suspend fun loadOrCreateAfterIntent(alias: String): FreshIdentityKeyMaterial =
        cryptoCall("identity_create") {
            if (alias != StorageBootstrapContract.FRESH_IDENTITY_ALIAS) {
                throw StorageBootstrapFailure(
                    StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
                    "fresh_identity_alias_noncanonical",
                )
            }
            AndroidIdentitySigner.loadOrCreate().toFreshMaterial(alias)
        }

    /**
     * The durable identity CREATE intent authorizes the rest of the deterministic fresh-device key set too.
     * A crash can leave either alias behind, but a retry loads the same alias and self-tests it before the only
     * Room authority transaction. HPKE is generated in memory and is not authoritative until that transaction.
     */
    override suspend fun provisionFoundationAfterIntent(
        clientId: String,
        createdAt: Long,
    ): CryptoEpochInput = cryptoCall("fresh_foundation_create") {
        val protocolClientId = ClientId(clientId)
        val operational = AndroidOperationalSigner.loadOrCreate(
            clientId = protocolClientId,
            epoch = FRESH_EPOCH,
        )
        val backing = operational.backing.toCryptoEpochSecurityLevel()
        val signingChallenge = FRESH_OPERATIONAL_SELF_TEST.copyOf()
        val signature = try {
            operational.sign(signingChallenge)
        } finally {
            signingChallenge.fill(0)
        }
        try {
            check(
                IdentityVerifier.verify(
                    operational.operationalPublicKeySpki,
                    FRESH_OPERATIONAL_SELF_TEST,
                    signature,
                ),
            ) { "Fresh operational signer self-test failed" }
        } finally {
            signature.fill(0)
        }

        val pair = Hpke.generateKeyPair()
        val vault = KeyVault()
        vault.backing.toCryptoEpochSecurityLevel()
        val context = FRESH_HPKE_SELF_TEST_CONTEXT.copyOf()
        val plain = FRESH_HPKE_SELF_TEST_PLAIN.copyOf()
        val sealed = try {
            Hpke.seal(plain, pair.publicKeyset, context)
        } finally {
            plain.fill(0)
        }
        try {
            val opened = Hpke.open(sealed, pair.privateKeyset, context)
            try {
                check(opened.contentEquals(FRESH_HPKE_SELF_TEST_PLAIN)) {
                    "Fresh HPKE keypair self-test failed"
                }
            } finally {
                opened.fill(0)
            }
        } finally {
            context.fill(0)
            sealed.fill(0)
        }
        val privateDigest = MessageDigest.getInstance("SHA-256").digest(pair.privateKeyset)
        val wrappedPrivate = try {
            vault.wrap(pair.privateKeyset)
        } finally {
            pair.privateKeyset.fill(0)
        }
        val unwrapped = vault.unwrap(wrappedPrivate)
        try {
            check(Hpke.rawPublicKey(pair.publicKeyset).isNotEmpty())
            check(MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(unwrapped),
                privateDigest,
            )) { "Fresh wrapping-key self-test failed" }
        } finally {
            unwrapped.fill(0)
            privateDigest.fill(0)
        }
        CryptoEpochInput(
            epoch = FRESH_EPOCH,
            operationalSignerAlias = AndroidOperationalSigner.aliasFor(FRESH_EPOCH),
            operationalSignerPublicSpki = operational.operationalPublicKeySpki,
            hpkePublicKeyset = pair.publicKeyset,
            hpkePrivateKeysetWrapped = wrappedPrivate,
            securityLevel = backing,
            lifecycleState = CryptoEpochState.ACTIVE,
            antiRollbackFloor = FRESH_EPOCH.toLong(),
            activationAt = createdAt,
            createdAt = createdAt,
        )
    }

    private fun AndroidIdentitySigner.toFreshMaterial(alias: String): FreshIdentityKeyMaterial =
        FreshIdentityKeyMaterial(
            alias = alias,
            publicSpki = publicKeySpki,
            clientId = clientId.value,
            backing = backing,
            selfTestBlock = {
                cryptoCall("identity_self_test") {
                    val challenge = FRESH_IDENTITY_SELF_TEST.copyOf()
                    val signature = try {
                        sign(challenge)
                    } finally {
                        // Public domain-separation bytes are wiped as a hygiene invariant shared with secret tests.
                        challenge.fill(0)
                    }
                    try {
                        if (!IdentityVerifier.verify(publicKeySpki, FRESH_IDENTITY_SELF_TEST, signature)) {
                            throw StorageBootstrapFailure(
                                StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
                                "fresh_identity_self_test_failed",
                            )
                        }
                    } finally {
                        signature.fill(0)
                    }
                }
            },
        )

    private suspend fun <T> cryptoCall(code: String, block: () -> T): T = withContext(ioDispatcher) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: StorageBootstrapFailure) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw StorageBootstrapFailure(
                StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
                "${code}_invalid",
                failure,
            )
        } catch (failure: IllegalStateException) {
            throw StorageBootstrapFailure(
                StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
                "${code}_invalid",
                failure,
            )
        } catch (failure: Exception) {
            throw StorageBootstrapFailure(
                StorageBootstrapFailureDisposition.RETRYABLE,
                "${code}_temporarily_unavailable",
                failure,
            )
        }
    }

    private companion object {
        const val FRESH_EPOCH = 1
        val FRESH_IDENTITY_SELF_TEST = "notisync-fresh-identity-self-test-v1".encodeToByteArray()
        val FRESH_OPERATIONAL_SELF_TEST = "notisync-fresh-operational-self-test-v1".encodeToByteArray()
        val FRESH_HPKE_SELF_TEST_CONTEXT = "notisync-fresh-hpke-self-test-v1".encodeToByteArray()
        val FRESH_HPKE_SELF_TEST_PLAIN = "fresh-hpke-self-test".encodeToByteArray()
    }
}

internal interface FreshIdentityPersistencePort {
    suspend fun ensureIdentityCreation(intent: KeystoreOperationIntent): KeystoreOperationEnsureResult
    suspend fun saveIdentity(metadata: IdentityMetadataInput): IdentityMetadataSaveResult
    suspend fun markIdentityCreationApplied(
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        completedAt: Long,
    ): KeystoreOperationTransitionResult

    suspend fun markIdentityCreationFailure(
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        targetState: KeystoreOperationState,
        errorCode: String,
    ): KeystoreOperationTransitionResult

    suspend fun initializeAuthority(
        defaultBrokerUrl: String,
        operationalStorage: OperationalStorageBinding,
        cryptoEpoch: CryptoEpochInput,
    ): CoreTransportInitializationResult
}

internal class RepositoryFreshIdentityPersistencePort(
    private val repository: CoreFoundationRepository,
) : FreshIdentityPersistencePort {
    override suspend fun ensureIdentityCreation(intent: KeystoreOperationIntent): KeystoreOperationEnsureResult =
        repository.ensureKeystoreOperation(intent)

    override suspend fun saveIdentity(metadata: IdentityMetadataInput): IdentityMetadataSaveResult =
        repository.saveIdentityMetadata(metadata)

    override suspend fun markIdentityCreationApplied(
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        completedAt: Long,
    ): KeystoreOperationTransitionResult = repository.transitionKeystoreOperation(
        operationId = StorageBootstrapContract.FRESH_IDENTITY_OPERATION_ID,
        expectedState = expectedState,
        expectedAttempts = expectedAttempts,
        targetState = KeystoreOperationState.APPLIED,
        completedAt = completedAt,
        errorCode = null,
    )

    override suspend fun markIdentityCreationFailure(
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        targetState: KeystoreOperationState,
        errorCode: String,
    ): KeystoreOperationTransitionResult = repository.transitionKeystoreOperation(
        operationId = StorageBootstrapContract.FRESH_IDENTITY_OPERATION_ID,
        expectedState = expectedState,
        expectedAttempts = expectedAttempts,
        targetState = targetState,
        completedAt = null,
        errorCode = errorCode,
    )

    override suspend fun initializeAuthority(
        defaultBrokerUrl: String,
        operationalStorage: OperationalStorageBinding,
        cryptoEpoch: CryptoEpochInput,
    ): CoreTransportInitializationResult = repository.initializeFreshAuthority(
        initialization = FreshIdentityTransportInitialization(defaultBrokerUrl),
        operationalStorage = operationalStorage,
        cryptoEpoch = cryptoEpoch,
    )
}

private fun KeyBacking.toCryptoEpochSecurityLevel(): CryptoEpochSecurityLevel = when (this) {
    KeyBacking.STRONGBOX -> CryptoEpochSecurityLevel.STRONGBOX
    KeyBacking.TEE -> CryptoEpochSecurityLevel.TRUSTED_ENVIRONMENT
    KeyBacking.SOFTWARE,
    KeyBacking.UNKNOWN,
    KeyBacking.UNKNOWN_SECURE,
    -> throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        "fresh_operational_key_not_hardware_backed",
    )
}
