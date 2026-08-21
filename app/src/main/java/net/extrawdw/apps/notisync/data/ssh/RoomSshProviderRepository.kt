package net.extrawdw.apps.notisync.data.ssh

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalFeatureCommitResult
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSingletons
import net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition
import net.extrawdw.apps.notisync.data.storage.operational.PreparedOperationalReceipt
import net.extrawdw.apps.notisync.data.storage.operational.SshApprovalPolicyToken
import net.extrawdw.apps.notisync.data.storage.operational.SshAuthorizationFloorEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshExportAuthenticationToken
import net.extrawdw.apps.notisync.data.storage.operational.SshExportBackendToken
import net.extrawdw.apps.notisync.data.storage.operational.SshExportCopyEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyAlgorithmToken
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyLifecycleCandidateEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyLifecycleState
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyLifecycleEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshKeyOriginToken
import net.extrawdw.apps.notisync.data.storage.operational.SshLifecycleCandidatePurpose
import net.extrawdw.apps.notisync.data.storage.operational.SshOperationalKeyEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderPendingPayloadEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderRequestEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderRequestKind as StorageSshProviderRequestKind
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderRequestOutcome as StorageSshProviderRequestOutcome
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderRequestState as StorageSshProviderRequestState
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderResponseCustodyEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderResponsePayloadFormat
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshSecurityLevelToken
import net.extrawdw.apps.notisync.data.storage.operational.SshStorageKind
import net.extrawdw.apps.notisync.data.storage.operational.SshUserVerificationToken
import net.extrawdw.apps.notisync.data.storage.operational.SshWrappedOperationalMaterialEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshKnownHostEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshPeerAuthorizationEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshHostAuthorizationEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderOutcomeTransition
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderResponsePrepareResult
import net.extrawdw.apps.notisync.data.storage.operational.SshProviderResponseCompleteResult
import net.extrawdw.apps.notisync.data.storage.operational.SshResetAliasEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshResetAliasKind
import net.extrawdw.apps.notisync.data.storage.operational.SshResetAliasState
import net.extrawdw.apps.notisync.data.storage.operational.SshResetJournalEntity
import net.extrawdw.apps.notisync.data.storage.operational.SshResetState
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadBinding
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyAvailability
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyEnsurer
import net.extrawdw.apps.notisync.sshagent.PreparedSshImportStorage
import net.extrawdw.apps.notisync.sshagent.PreparedSshKeyExport
import net.extrawdw.apps.notisync.sshagent.PreparedSshKeyStorage
import net.extrawdw.apps.notisync.sshagent.PreparedSshSignature
import net.extrawdw.apps.notisync.sshagent.PreparedSignatureOperation
import net.extrawdw.apps.notisync.sshagent.PreparedStorageStage
import net.extrawdw.apps.notisync.sshagent.PendingSshKeyProvisioning
import net.extrawdw.apps.notisync.sshagent.PendingSshKeyRecord
import net.extrawdw.apps.notisync.sshagent.ProtectedSshKeyMaterial
import net.extrawdw.apps.notisync.sshagent.SshImportApprovalOutcome
import net.extrawdw.apps.notisync.sshagent.SshExportCandidateException
import net.extrawdw.apps.notisync.sshagent.SshExportOperationException
import net.extrawdw.apps.notisync.sshagent.SshKeyStoragePolicy
import net.extrawdw.apps.notisync.sshagent.SshKeyStorageResult
import net.extrawdw.apps.notisync.sshagent.SshOperationalCandidateException
import net.extrawdw.apps.notisync.sshagent.SshOperationalCandidateStage
import net.extrawdw.apps.notisync.sshagent.SshOperationalOperationException
import net.extrawdw.apps.notisync.sshagent.PreparedSshResponse
import net.extrawdw.apps.notisync.sshagent.SshProviderAcceptResult
import net.extrawdw.apps.notisync.sshagent.SshProviderRequestKind
import net.extrawdw.apps.notisync.sshagent.SshProviderRequestOutcome
import net.extrawdw.apps.notisync.sshagent.SshProviderRequestState
import net.extrawdw.apps.notisync.sshagent.SshProviderStoreOwner
import net.extrawdw.apps.notisync.sshagent.SshRememberedAuthorization
import net.extrawdw.apps.notisync.sshagent.SshKnownHost
import net.extrawdw.apps.notisync.sshagent.SshKeyStoreResetResult
import net.extrawdw.apps.notisync.sshagent.SshRequestApprovalKind
import net.extrawdw.apps.notisync.sshagent.SshRequestHistorySnapshot
import net.extrawdw.apps.notisync.sshagent.StoredSshProviderRequest
import net.extrawdw.apps.notisync.sshagent.SshAgentProviderRepository
import net.extrawdw.apps.notisync.sshagent.SshPrivateKeyFileParser
import net.extrawdw.apps.notisync.sshagent.SshWrappedOperationalKeyVault
import net.extrawdw.apps.notisync.sshagent.SshWrappedOperationalOperationException
import net.extrawdw.apps.notisync.sshagent.SshExportKeyVault
import net.extrawdw.apps.notisync.sshagent.SensitiveBytes
import net.extrawdw.apps.notisync.sshagent.SshKeystoreJca
import net.extrawdw.apps.notisync.sshagent.SshAuthenticationPolicy
import net.extrawdw.apps.notisync.sshagent.SshRememberAuthorizationPolicy
import net.extrawdw.apps.notisync.sshagent.SshRememberAuthorizationStorage
import net.extrawdw.apps.notisync.sshagent.authorizationStorage
import net.extrawdw.apps.notisync.sshagent.toSshStorageSecurityLevel
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.EcdsaSignatureTranscoder
import net.extrawdw.notisync.ssh.core.SshSignatureCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import net.extrawdw.notisync.ssh.core.AgentAddIdentityParser
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshExportCopyAuthentication
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshExportCopyProtection
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshImportResultKind
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
import net.extrawdw.notisync.protocol.SshOperationalKeyProtection
import net.extrawdw.notisync.protocol.SshOperationalKeyProvider
import net.extrawdw.notisync.protocol.SshKeysSnapshot
import net.extrawdw.notisync.protocol.SshProviderFailure
import net.extrawdw.notisync.protocol.SshProviderFailureCode
import net.extrawdw.notisync.protocol.SshProviderHealth
import net.extrawdw.notisync.protocol.SshRememberDisposition
import net.extrawdw.notisync.protocol.SshRememberedNamespace
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.SshSignatureResult
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Room authority for SSH provider state. All durable reads/writes are suspend operations so Android UI and
 * notification components never block on SQLite or Android Keystore. The legacy SQLite store is intentionally not
 * consulted: the debug-only v51 SSH database is not part of the supported migration contract.
 */
internal class RoomSshProviderRepository(
    private val context: Context,
    private val database: OperationalDatabase,
    private val protector: OperationalProtectedPayloadProtector,
    private val payloadKeyEnsurer: OperationalPayloadKeyEnsurer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : SshProviderStoreOwner, SshAgentProviderRepository {
    private val keyDao = database.sshKeyDao()
    private val authorizationDao = database.sshAuthorizationDao()
    private val requestDao = database.sshRequestDao()
    private val resetDao = database.sshResetDao()
    private val writeMutex = Mutex()
    private val _changeVersion = MutableStateFlow(0L)
    override val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()
    private val strongBoxAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val wrappedVault = SshWrappedOperationalKeyVault(strongBoxAvailable)
    private val exportVault = SshExportKeyVault(strongBoxAvailable)
    private val transientResponses = LinkedHashMap<String, ByteArray>()
    private val transientRequestBodies = LinkedHashMap<String, ByteArray>()

    override suspend fun snapshot(
        provider: ClientId,
        respondingToRequestId: String?,
        now: Long,
    ): SshKeysSnapshot = withContext(ioDispatcher) {
        pruneExpiredKeys(now)
        val state = ensureProviderState(now)
        val remembered = rememberedNamespaces()
        val keys = buildList {
            keyDao.observeKeys().first().forEach { key ->
                descriptor(key, remembered[key.providerKeyId].orEmpty())?.let(::add)
            }
        }
        SshKeysSnapshot(
            providerClientId = provider,
            inventoryGeneration = state.inventoryGeneration,
            revision = state.revision,
            generatedAt = now,
            respondingToRequestId = respondingToRequestId,
            keys = keys,
            providerHealth = SshProviderHealth.HEALTHY,
        )
    }

    override suspend fun knownHosts(): List<SshKnownHost> = withContext(ioDispatcher) {
        authorizationDao.observeKnownHosts().first().map { it.toDomain() }
    }

    /** The v1 Room SSH schema intentionally stores host-key identity/timestamps only. */
    override suspend fun knownHostHostname(hostKeySha256: ByteArray): String? = null

    override suspend fun updateKnownHostHostname(hostKeySha256: ByteArray, hostname: String): Boolean = false

    override suspend fun deleteKnownHost(hostKeySha256: ByteArray): Boolean = mutate {
        authorizationDao.deleteKnownHost(hostKeySha256) == 1
    }

    override suspend fun rememberedAuthorizations(): List<SshRememberedAuthorization> = withContext(ioDispatcher) {
        val hosts = authorizationDao.observeKnownHosts().first().associateBy { it.hostKeySha256.contentKey() }
        val hostRows = authorizationDao.observeHostAuthorizations().first()
        val peerRows = authorizationDao.observePeerAuthorizations().first()
        buildList {
            peerRows.forEach { row -> add(row.toDomain(scope = SshRememberScope.PEER, host = null)) }
            hostRows.forEach { row -> add(row.toDomain(SshRememberScope.PEER_HOST_KEY, hosts[row.hostKeySha256.contentKey()])) }
        }.sortedWith(compareBy<SshRememberedAuthorization> { it.providerKeyId }.thenByDescending { it.createdAt })
    }

    override suspend fun deleteRememberedAuthorization(authorizationId: String): Boolean = mutate {
        authorizationDao.forget(authorizationId)
    }

    override suspend fun generateKey(
        algorithm: SshKeyAlgorithm,
        displayName: String,
        now: Long,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        rsaKeySizeBits: Int,
    ): SshKeyStorageResult = withContext(ioDispatcher) {
        require(now > 0)
        val name = validateName(displayName)
        if (!allowExport) generateDirect(algorithm, name, now, userVerificationPolicy, rsaKeySizeBits)
        else {
            val pair = generateSoftwarePair(algorithm, rsaKeySizeBits)
            storeSoftware(
                pair = pair,
                algorithm = algorithm,
                publicBlob = SshPublicKeyCodec.encode(pair.public, algorithm.toCoreType()),
                displayName = name,
                origin = SshKeyOrigin.GENERATED,
                now = now,
                exportPolicy = exportCopyBackendPolicy,
                userVerificationPolicy = userVerificationPolicy,
            )
        }
    }

    override suspend fun importPrivateKeyFile(
        fileBytes: ByteArray,
        passphrase: CharArray?,
        displayName: String,
        now: Long,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyStorageResult = withContext(ioDispatcher) {
        val parsed = SshPrivateKeyFileParser.parse(fileBytes, passphrase)
        try {
            val publicKey = SshPublicKeyCodec.decode(parsed.publicKeyBlob).publicKey
            val privateKey = softwarePrivateKey(parsed.algorithm, parsed.pkcs8PrivateKey)
            storeSoftware(
                pair = KeyPair(publicKey, privateKey),
                algorithm = parsed.algorithm,
                publicBlob = parsed.publicKeyBlob,
                displayName = validateName(displayName),
                origin = SshKeyOrigin.SAF_IMPORT,
                now = now,
                exportPolicy = exportCopyBackendPolicy.takeIf { allowExport },
                userVerificationPolicy = userVerificationPolicy,
            )
        } finally {
            parsed.pkcs8PrivateKey.fill(0)
        }
    }

    override suspend fun prepareExport(providerKeyId: String): PreparedSshKeyExport? = withContext(ioDispatcher) {
        val key = keyDao.findKey(providerKeyId) ?: return@withContext null
        val copy = keyDao.findExportCopy(providerKeyId) ?: return@withContext null
        val prepared = exportVault.prepareUnwrap(
            providerKeyId,
            copy.privateKeyCiphertext,
            copy.privateKeyNonce,
            key.algorithm.toProtocol(),
            key.publicHash,
            copy.securityLevel.toProtocol(),
            strongBoxCandidate = copy.keyAlias == exportVault.alias(providerKeyId, true),
        )
        PreparedSshKeyExport(providerKeyId, prepared.cipher, key.publicHash.copyOf(), prepared, copy.securityLevel.toProtocol())
    }

    override suspend fun completeExport(prepared: PreparedSshKeyExport, authenticatedCipher: Cipher): ByteArray? =
        withContext(ioDispatcher) {
            val key = keyDao.findKey(prepared.providerKeyId)
            val copy = keyDao.findExportCopy(prepared.providerKeyId)
            if (key == null || copy == null || authenticatedCipher !== prepared.cipher ||
                !MessageDigest.isEqual(key.publicHash, prepared.publicHash)
            ) {
                cancelExport(prepared)
                return@withContext null
            }
            try {
                exportVault.completeUnwrap(prepared.unwrap, authenticatedCipher).take()
            } finally {
                prepared.publicHash.fill(0)
            }
        }

    override suspend fun cancelExport(prepared: PreparedSshKeyExport) = withContext(ioDispatcher) {
        prepared.unwrap.close()
        prepared.publicHash.fill(0)
    }

    override suspend fun completePreparedKeyStorage(
        prepared: PreparedSshKeyStorage,
        authenticatedCipher: Cipher?,
        authenticatedSignature: Signature?,
    ): SshKeyStorageResult = withContext(ioDispatcher) {
        require(prepared.owner === this@RoomSshProviderRepository)
        if (prepared.provisioning.finished) error("SSH key provisioning has already finished")
        try {
            when (val stage = prepared.stage) {
                is PreparedStorageStage.OperationalWrapEncrypt -> {
                    completeWrappedOperationalEncryption(
                        prepared.provisioning,
                        stage,
                        requireNotNull(authenticatedCipher),
                    )
                }
                is PreparedStorageStage.ExportEncrypt -> {
                    completeExportEncryption(
                        prepared.provisioning,
                        stage,
                        requireNotNull(authenticatedCipher),
                    )
                }
                is PreparedStorageStage.OperationalWrapDecrypt -> {
                    completeWrappedOperationalValidation(
                        prepared.provisioning,
                        stage,
                        requireNotNull(authenticatedCipher),
                    )
                }
                is PreparedStorageStage.OperationalSelfTest -> {
                    completeOperationalSelfTest(
                        prepared.provisioning,
                        stage,
                        requireNotNull(authenticatedSignature),
                    )
                }
                is PreparedStorageStage.ExportDecrypt -> {
                    completeExportValidation(
                        prepared.provisioning,
                        stage,
                        requireNotNull(authenticatedCipher),
                    )
                }
            }
        } catch (failure: CancellationException) {
            closePreparedStorageStage(prepared.stage)
            abortProvisioning(prepared.provisioning)
            throw failure
        } catch (failure: Exception) {
            closePreparedStorageStage(prepared.stage)
            abortProvisioning(prepared.provisioning)
            throw failure
        }
    }

    override suspend fun cancelPreparedKeyStorage(prepared: PreparedSshKeyStorage) = withContext(NonCancellable + ioDispatcher) {
        if (prepared.owner !== this@RoomSshProviderRepository || prepared.provisioning.finished) return@withContext
        closePreparedStorageStage(prepared.stage)
        abortProvisioning(prepared.provisioning)
    }

    override suspend fun deleteKey(providerKeyId: String): Boolean = withContext(ioDispatcher) {
        writeMutex.withLock {
            val key = keyDao.findKey(providerKeyId) ?: return@withLock false
            val operational = keyDao.findOperationalKey(providerKeyId) ?: return@withLock false
            val wrapped = keyDao.findWrappedOperationalMaterial(providerKeyId)
            val lifecycle = SshKeyLifecycleEntity(
                providerKeyId = providerKeyId,
                operationalAlias = operational.keyAlias,
                storageKind = if (wrapped == null) SshStorageKind.DIRECT else SshStorageKind.WRAPPED,
                state = SshKeyLifecycleState.DELETING,
                createdAt = now().coerceAtLeast(1),
                updatedAt = now().coerceAtLeast(1),
            )
            keyDao.beginLifecycle(lifecycle, emptyList())
            deleteAlias(operational.keyAlias)
            keyDao.findExportCopy(providerKeyId)?.let { copy -> deleteAlias(copy.keyAlias) }
            keyDao.finalizeDeletion(providerKeyId, nextProviderState(now()))
            bumpChange()
            true
        }
    }

    override suspend fun updateKeyMetadata(
        providerKeyId: String,
        displayName: String,
        approvalPolicy: SshApprovalPolicy,
        expiresAt: Long?,
        updatedAt: Long,
    ): Boolean = mutate {
        keyDao.updateKeyMetadata(
            providerKeyId,
            validateName(displayName),
            approvalPolicy.toToken(),
            expiresAt,
            updatedAt,
        ) == 1
    }

    override suspend fun owns(publicKeyBlob: ByteArray, now: Long): Boolean = withContext(ioDispatcher) {
        val key = keyDao.findKeyByPublicHash(sha256(publicKeyBlob))
        key != null && (key.expiresAt == null || key.expiresAt >= now)
    }

    override suspend fun acceptSign(request: SshSignRequest, now: Long): SshProviderAcceptResult =
        accept(request, SshProviderRequestKind.SIGN, request.historySnapshot(keyDisplayName(request.publicKeyBlob)), now)

    override suspend fun acceptImport(request: SshImportRequest, now: Long): SshProviderAcceptResult =
        accept(request, SshProviderRequestKind.IMPORT, request.historySnapshot(), now)

    /**
     * Owner transaction used by authenticated broker delivery. SIGN and IMPORT are the only SSH
     * commands represented by the provider-request aggregate; their protected request/history and
     * broker receipt are committed by the DAO as one transaction. KEYS is reconstructable and
     * FORGET is an idempotent authorization-floor mutation, so neither gets a synthetic request
     * row or protected payload solely to manufacture durability.
     */
    internal suspend fun acceptWithReceipt(
        request: SshAgentSync,
        senderClientId: ClientId,
        now: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        require(request.validationError(::sha256) == null) { "invalid SSH Agent request" }
        return when (request.kind) {
            SshAgentSyncKind.SIGN_REQUEST -> {
                val sign = requireNotNull(request.signRequest)
                require(sign.requesterClientId == senderClientId) { "SSH sign sender mismatch" }
                withPreparedProviderRequest(
                    requestId = sign.requestId,
                    requester = sign.requesterClientId,
                    kind = SshProviderRequestKind.SIGN,
                    expiresAt = sign.expiresAt,
                    history = sign.historySnapshot(keyDisplayName(sign.publicKeyBlob)),
                    encoded = ProtocolCodec.encodeToCbor(sign),
                ) { entity, pending -> requestDao.acceptProviderRequestWithReceipt(entity, pending, receipt, now) }
            }
            SshAgentSyncKind.IMPORT_REQUEST -> {
                val import = requireNotNull(request.importRequest)
                require(import.requesterClientId == senderClientId) { "SSH import sender mismatch" }
                withPreparedProviderRequest(
                    requestId = import.requestId,
                    requester = import.requesterClientId,
                    kind = SshProviderRequestKind.IMPORT,
                    expiresAt = import.expiresAt,
                    history = import.historySnapshot(),
                    encoded = ProtocolCodec.encodeToCbor(import),
                ) { entity, pending -> requestDao.acceptProviderRequestWithReceipt(entity, pending, receipt, now) }
            }
            SshAgentSyncKind.KEYS_REQUEST -> {
                val keys = requireNotNull(request.keysRequest)
                require(keys.requesterClientId == senderClientId) { "SSH keys sender mismatch" }
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED)
            }
            SshAgentSyncKind.FORGET_AUTHORIZATION -> {
                val forget = requireNotNull(request.forgetAuthorization)
                require(forget.requesterClientId == senderClientId) { "SSH forget sender mismatch" }
                forgetAuthorization(
                    requester = forget.requesterClientId,
                    generation = forget.authorizationGeneration,
                    invalidatedThroughEpoch = forget.invalidatedThroughEpoch,
                    now = now,
                )
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED)
            }
            else -> OperationalFeatureCommitResult.SecurityBlocked("ssh_owner_unavailable")
        }
    }

    override suspend fun find(requestId: String): StoredSshProviderRequest? = withContext(ioDispatcher) {
        val entity = requestDao.findProviderRequest(requestId) ?: return@withContext null
        decodeRequest(entity)
    }

    override suspend fun pendingReview(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.PENDING_REVIEW)

    override suspend fun pendingResponses(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.RESPONSE_PENDING_SEND)

    override suspend fun requests(): List<StoredSshProviderRequest> = withContext(ioDispatcher) {
        buildList {
            requestDao.observeProviderHistory().first().forEach { entity ->
                decodeRequest(entity)?.let(::add)
            }
        }
    }

    override suspend fun recordImportPreview(requestId: String, publicKeyBlob: ByteArray): Boolean = withContext(ioDispatcher) {
        val current = requestDao.findProviderRequest(requestId) ?: return@withContext false
        val decoded = decodeRequest(current) ?: return@withContext false
        val updated = decoded.history.copy(publicKeyBlob = publicKeyBlob.copyOf(), keyName = keyDisplayName(publicKeyBlob))
        updateHistory(current, updated)
        bumpChange()
        true
    }

    override suspend fun keyDisplayName(publicKeyBlob: ByteArray): String? = withContext(ioDispatcher) {
        keyDao.findKeyByPublicHash(sha256(publicKeyBlob))?.displayName
    }

    override suspend fun availableRememberScopes(requestId: String): Set<SshRememberScope> = withContext(ioDispatcher) {
        val request = decodeRequest(requestDao.findProviderRequest(requestId) ?: return@withContext emptySet())
        if (request?.signRequest == null) emptySet() else SshRememberScope.entries.toSet()
    }

    override suspend fun requiresPerUseUserVerification(requestId: String): Boolean = withContext(ioDispatcher) {
        val stored = decodeRequest(requestDao.findProviderRequest(requestId) ?: return@withContext false)
        val publicBlob = stored?.signRequest?.publicKeyBlob ?: return@withContext false
        keyDao.findKeyByPublicHash(sha256(publicBlob))?.let { keyDao.findOperationalKey(it.providerKeyId) }
            ?.userVerificationPolicy == SshUserVerificationToken.PER_USE
    }

    override suspend fun approve(requestId: String, provider: ClientId, now: Long): SshSignResult? =
        approveSign(requestId, provider, now, SshRememberDisposition.NONE, null)

    override suspend fun approveAndRemember(
        requestId: String,
        provider: ClientId,
        scope: SshRememberScope,
        now: Long,
    ): SshSignResult? = approveSign(requestId, provider, now, scope.createdDisposition(), scope)

    override suspend fun prepareUserVerifiedSignature(
        requestId: String,
        provider: ClientId,
        now: Long,
    ): PreparedSshSignature? = withContext(ioDispatcher) {
        val stored = decodeRequest(requestDao.findProviderRequest(requestId) ?: return@withContext null) ?: return@withContext null
        val request = stored.signRequest ?: return@withContext null
        val key = keyDao.findKeyByPublicHash(sha256(request.publicKeyBlob)) ?: return@withContext null
        val operational = keyDao.findOperationalKey(key.providerKeyId) ?: return@withContext null
        val method = signatureMethod(request)
        if (operational.userVerificationPolicy != SshUserVerificationToken.PER_USE) return@withContext null
        if (keyDao.findWrappedOperationalMaterial(key.providerKeyId) == null) {
            val privateKey = loadPrivateKey(operational.keyAlias)
            return@withContext PreparedSshSignature(
                requestId,
                stored.requestFingerprint.copyOf(),
                SshKeystoreJca.signature(method.jcaName).apply { initSign(privateKey) },
                null,
                method,
                PreparedSignatureOperation.Direct,
            )
        }
        val material = keyDao.findWrappedOperationalMaterial(key.providerKeyId) ?: return@withContext null
        val unwrap = wrappedVault.prepareUnwrap(
            operational.keyAlias,
            key.providerKeyId,
            material.privateKeyCiphertext,
            material.privateKeyNonce,
            key.algorithm.toProtocol(),
            key.publicHash,
            operational.securityLevel.toProtocol(),
            operational.userVerificationPolicy.toProtocol(),
        )
        PreparedSshSignature(
            requestId,
            stored.requestFingerprint.copyOf(),
            null,
            unwrap.cipher,
            method,
            PreparedSignatureOperation.Wrapped(unwrap),
        )
    }

    override suspend fun completeUserVerifiedSignature(
        prepared: PreparedSshSignature,
        signature: Signature?,
        cipher: Cipher?,
        provider: ClientId,
        now: Long,
    ): SshSignResult? = withContext(ioDispatcher) {
        val stored = decodeRequest(requestDao.findProviderRequest(prepared.requestId) ?: return@withContext null)
            ?: return@withContext null
        val request = stored.signRequest ?: return@withContext null
        val bytes = try {
            when (val operation = prepared.operation) {
                PreparedSignatureOperation.Direct -> {
                    require(signature === prepared.signature && cipher == null)
                    requireNotNull(signature).apply { update(request.data) }.sign()
                }
                is PreparedSignatureOperation.Wrapped -> {
                    require(cipher === prepared.cipher && signature == null)
                    wrappedVault.completeUnwrap(operation.unwrap, requireNotNull(cipher)).use { privateBytes ->
                        signSoftwareRaw(prepared.method, softwarePrivateKey(request.requestedSignatureAlgorithm.toKeyAlgorithm(), privateBytes.bytes), request.data)
                    }
                }
            }
        } finally {
            prepared.close()
        }
        val result = signedResult(request, provider, now, SshRememberDisposition.NONE, prepared.method, bytes)
        storeResponse(stored, result, now, null)
        result
    }

    override suspend fun cancelPreparedSignature(prepared: PreparedSshSignature) { withContext(ioDispatcher) { prepared.close() } }

    override suspend fun failUserVerification(
        requestId: String,
        provider: ClientId,
        now: Long,
        code: SshProviderFailureCode,
    ): Boolean {
        val stored = find(requestId) ?: return false
        val request = stored.signRequest ?: return false
        return storeResponse(stored, signFailure(request, provider, now, code), now, null)
    }

    override suspend fun approveImport(
        requestId: String,
        provider: ClientId,
        now: Long,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray?,
    ): SshImportApprovalOutcome? {
        val stored = find(requestId) ?: return null
        val request = stored.importRequest ?: return null
        val storage = try {
            importRequest(
                request,
                provider,
                now,
                allowExport,
                exportCopyBackendPolicy,
                userVerificationPolicy,
                passphrase,
            )
        } catch (_: Exception) {
            null
        }
        if (storage == null) return null
        return when (storage) {
            is SshImportAttempt.Complete -> {
                storeResponse(stored, storage.response, now, null)
                SshImportApprovalOutcome.Completed
            }
            is SshImportAttempt.AuthenticationRequired -> SshImportApprovalOutcome.AuthenticationRequired(
                PreparedSshImportStorage(
                    storage.keyStorage,
                    requestId,
                    stored.requestFingerprint.copyOf(),
                    request.requesterClientId,
                    storage.publicKeyBlob.copyOf(),
                ),
            )
        }
    }

    override suspend fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: Cipher?,
        authenticatedSignature: Signature?,
        provider: ClientId,
        now: Long,
    ): SshImportApprovalOutcome? {
        val stored = find(prepared.requestId) ?: return null
        if (
            stored.requestFingerprint.contentEquals(prepared.requestFingerprint).not() ||
            stored.requesterClientId != prepared.requesterClientId ||
            stored.state != SshProviderRequestState.PENDING_REVIEW
        ) {
            cancelPreparedImport(prepared)
            return null
        }
        val result = try {
            completePreparedKeyStorage(prepared.keyStorage, authenticatedCipher, authenticatedSignature)
        } catch (failure: Exception) {
            val response = SshImportResult(
                requestId = prepared.requestId,
                requesterClientId = prepared.requesterClientId,
                providerClientId = provider,
                resultAt = now,
                kind = SshImportResultKind.FAILED,
                message = "SSH identity import failed: ${failure.message.orEmpty()}".take(512),
            )
            storeResponse(stored, response, now, null)
            return SshImportApprovalOutcome.Completed
        }
        return when (result) {
            is SshKeyStorageResult.AuthenticationRequired -> SshImportApprovalOutcome.AuthenticationRequired(
                PreparedSshImportStorage(
                    result.prepared,
                    prepared.requestId,
                    prepared.requestFingerprint.copyOf(),
                    prepared.requesterClientId,
                    prepared.publicKeyBlob.copyOf(),
                ),
            )
            is SshKeyStorageResult.Stored -> {
                val response = SshImportResult(
                    requestId = prepared.requestId,
                    requesterClientId = prepared.requesterClientId,
                    providerClientId = provider,
                    resultAt = now,
                    kind = SshImportResultKind.IMPORTED,
                    providerKeyId = result.descriptor.providerKeyId,
                    publicKeyBlob = prepared.publicKeyBlob,
                )
                storeResponse(stored, response, now, null)
                SshImportApprovalOutcome.Completed
            }
        }
    }

    override suspend fun cancelPreparedImport(prepared: PreparedSshImportStorage) = cancelPreparedKeyStorage(prepared.keyStorage)

    override suspend fun autoApproveRemembered(requestId: String, provider: ClientId, now: Long): StoredSshProviderRequest? {
        val stored = find(requestId) ?: return null
        val request = stored.signRequest ?: return null
        val match = matchingRememberedAuthorization(request) ?: return null
        val result = approveSign(requestId, provider, now, match.scope.matchedDisposition(), match.scope)
        return result?.let { find(requestId) }
    }

    override suspend fun forgetAuthorization(
        requester: ClientId,
        generation: String,
        invalidatedThroughEpoch: Long,
        now: Long,
    ): net.extrawdw.apps.notisync.sshagent.SshAuthorizationForgetOutcome = withContext(ioDispatcher) {
        val changed = authorizationDao.advanceFloor(
            SshAuthorizationFloorEntity(
                requesterClientId = requester.value,
                authorizationGeneration = generation,
                invalidatedThroughEpoch = invalidatedThroughEpoch,
                updatedAt = now,
            ),
        )
        val cancelled = if (changed) {
            buildList {
                requestDao.observeProviderHistory().first()
                    .filter {
                        it.state == StorageSshProviderRequestState.PENDING_REVIEW &&
                            it.requesterClientId == requester.value
                    }
                    .forEach { entity ->
                        val stored = decodeRequest(entity) ?: return@forEach
                        val request = stored.signRequest ?: return@forEach
                        if (
                            request.authorizationGeneration == generation &&
                            request.authorizationEpoch <= invalidatedThroughEpoch &&
                            requestDao.terminalProviderRequest(
                                entity.requestId,
                                StorageSshProviderRequestState.PENDING_REVIEW,
                                StorageSshProviderRequestState.CANCELLED,
                                StorageSshProviderRequestOutcome.CANCELLED,
                                now,
                            ) == 1
                        ) add(entity.requestId)
                    }
            }
        } else emptyList()
        if (changed) bumpChange()
        net.extrawdw.apps.notisync.sshagent.SshAuthorizationForgetOutcome(changed, cancelled)
    }

    override suspend fun reject(requestId: String, provider: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        val request = stored.signRequest ?: return false
        return storeResponse(stored, SshSignResult(
            requestId,
            request.requesterClientId,
            sha256(request.publicKeyBlob),
            SshSignResultKind.REJECTED_BY_USER,
            now,
            provider,
        ), now, null)
    }

    override suspend fun cancelSign(requestId: String, requester: ClientId, now: Long): Boolean = withContext(ioDispatcher) {
        val stored = find(requestId) ?: return@withContext false
        if (stored.requesterClientId != requester || stored.state != SshProviderRequestState.PENDING_REVIEW) return@withContext false
        val changed = requestDao.terminalProviderRequest(requestId, StorageSshProviderRequestState.PENDING_REVIEW, StorageSshProviderRequestState.CANCELLED, StorageSshProviderRequestOutcome.CANCELLED, now) == 1
        if (changed) bumpChange()
        changed
    }

    override suspend fun markSent(requestId: String, now: Long): Boolean = withContext(ioDispatcher) {
        val custody = requestDao.findProviderResponseCustody(requestId)
        if (custody != null) {
            requestDao.completeProviderResponse(custody, now) in setOf(
                SshProviderResponseCompleteResult.SENT,
                SshProviderResponseCompleteResult.ALREADY_SENT,
            )
        } else {
            requestDao.terminalProviderRequest(requestId, StorageSshProviderRequestState.RESPONSE_QUEUED, StorageSshProviderRequestState.SENT, StorageSshProviderRequestOutcome.FAILED, now) == 1
        }
    }

    override suspend fun expireDue(now: Long): List<String> = withContext(ioDispatcher) {
        requestDao.observeProviderHistory().first().filter {
            it.state == StorageSshProviderRequestState.PENDING_REVIEW && it.expiresAt < now
        }.mapNotNull { entity ->
            entity.requestId.takeIf {
                requestDao.terminalProviderRequest(it, StorageSshProviderRequestState.PENDING_REVIEW, StorageSshProviderRequestState.EXPIRED, StorageSshProviderRequestOutcome.EXPIRED, now) == 1
            }
        }.also { if (it.isNotEmpty()) bumpChange() }
    }

    override suspend fun cancelInvalidatedPending(now: Long): List<String> = withContext(ioDispatcher) {
        val rows = requestDao.observeProviderHistory().first()
        val ids = buildList {
            for (entity in rows.filter { it.state == StorageSshProviderRequestState.PENDING_REVIEW }) {
                val stored = decodeRequest(entity) ?: continue
                val request = stored.signRequest ?: continue
                val floor = authorizationDao.findFloor(request.requesterClientId.value, request.authorizationGeneration)
                    ?.invalidatedThroughEpoch ?: Long.MIN_VALUE
                if (
                    request.authorizationEpoch <= floor &&
                    requestDao.terminalProviderRequest(
                        entity.requestId,
                        StorageSshProviderRequestState.PENDING_REVIEW,
                        StorageSshProviderRequestState.CANCELLED,
                        StorageSshProviderRequestOutcome.CANCELLED,
                        now,
                    ) == 1
                ) add(entity.requestId)
            }
        }
        if (ids.isNotEmpty()) bumpChange()
        ids
    }

    override suspend fun prepareResponse(requestId: String, now: Long): PreparedSshResponse? = withContext(ioDispatcher) {
        val stored = find(requestId) ?: return@withContext null
        if (stored.state != SshProviderRequestState.RESPONSE_PENDING_SEND) return@withContext null
        val custody = requestDao.findProviderResponseCustody(requestId)
        if (custody == null) {
            val transient = transientResponses[requestId]?.copyOf() ?: return@withContext null
            return@withContext PreparedSshResponse(requestId, stored.kind, transient, durableCustody = false)
        }
        val prepared = if (custody.payloadFormat == SshProviderResponsePayloadFormat.BODY) {
            val next = custody.copy(payloadFormat = SshProviderResponsePayloadFormat.PREPARED_ENVELOPE, updatedAt = maxOf(now, custody.updatedAt + 1))
            when (requestDao.prepareProviderResponse(custody, next)) {
                SshProviderResponsePrepareResult.UPDATED,
                SshProviderResponsePrepareResult.ALREADY_PREPARED,
                -> requestDao.findProviderResponseCustody(requestId)
                else -> null
            }
        } else custody
        val current = prepared ?: return@withContext null
        val bytes = openAtGeneration(current.toProtectedPayload(), ProtectedPayloadBinding.sshProviderResponse(requestId))
        PreparedSshResponse(requestId, stored.kind, bytes, durableCustody = true)
    }

    override suspend fun completeResponse(prepared: PreparedSshResponse, sentAt: Long): Boolean = withContext(ioDispatcher) {
        try {
            if (!prepared.durableCustody) {
                val current = requestDao.findProviderRequest(prepared.requestId) ?: return@withContext false
                if (current.state == StorageSshProviderRequestState.SENT) {
                    transientResponses.remove(prepared.requestId)?.fill(0)
                    return@withContext true
                }
                val outcome = current.outcome ?: return@withContext false
                if (
                    requestDao.terminalProviderRequest(
                        prepared.requestId,
                        StorageSshProviderRequestState.RESPONSE_QUEUED,
                        StorageSshProviderRequestState.SENT,
                        outcome,
                        sentAt,
                    ) != 1
                ) return@withContext false
                transientResponses.remove(prepared.requestId)?.fill(0)
                bumpChange()
                return@withContext true
            }
            val custody = requestDao.findProviderResponseCustody(prepared.requestId) ?: return@withContext false
            requestDao.completeProviderResponse(custody, sentAt) in setOf(
                SshProviderResponseCompleteResult.SENT,
                SshProviderResponseCompleteResult.ALREADY_SENT,
            )
        } finally {
            prepared.encodedBody.fill(0)
        }
    }

    override suspend fun requestExpiresAt(request: StoredSshProviderRequest): Long =
        request.signRequest?.expiresAt ?: request.importRequest?.expiresAt ?: request.history.expiresAt

    override suspend fun resetAllSshStorage(): SshKeyStoreResetResult = withContext(ioDispatcher) {
        writeMutex.withLock {
            val at = now().coerceAtLeast(1)
            val state = keyDao.readProviderState()
            val keys = keyDao.observeKeys().first()
            val requestIds = requestDao.observeProviderHistory().first().map { it.requestId }
            val aliases = buildMap<String, SshResetAliasKind> {
                keyDao.pendingLifecycles().forEach { lifecycle ->
                    put(lifecycle.operationalAlias, SshResetAliasKind.OPERATIONAL)
                }
                keys.forEach { key ->
                    keyDao.findOperationalKey(key.providerKeyId)?.let {
                        put(it.keyAlias, SshResetAliasKind.OPERATIONAL)
                    }
                    keyDao.findExportCopy(key.providerKeyId)?.let {
                        put(it.keyAlias, SshResetAliasKind.EXPORT_COPY)
                    }
                    keyDao.findCandidates(key.providerKeyId).forEach { candidate ->
                        put(
                            candidate.keyAlias,
                            if (candidate.purpose == SshLifecycleCandidatePurpose.EXPORT) {
                                SshResetAliasKind.EXPORT_COPY
                            } else {
                                SshResetAliasKind.OPERATIONAL
                            },
                        )
                    }
                }
            }
            val resetId = randomId()
            val newGeneration = randomId()
            val journal = SshResetJournalEntity(
                resetId = resetId,
                state = SshResetState.JOURNALED,
                oldInventoryGeneration = state?.inventoryGeneration,
                newInventoryGeneration = newGeneration,
                startedAt = at,
                updatedAt = at,
                lastErrorCode = null,
            )
            val aliasRows = aliases.map { (alias, kind) ->
                SshResetAliasEntity(
                    keyAlias = alias,
                    aliasKind = kind,
                    state = SshResetAliasState.PENDING,
                    attemptCount = 0,
                    updatedAt = at,
                    lastErrorCode = null,
                )
            }
            resetDao.beginReset(journal, aliasRows)
            check(resetDao.advanceJournal(resetId, SshResetState.JOURNALED, at, SshResetState.DELETING_ALIASES, at, null))
            aliases.keys.forEach { alias ->
                val removed = deleteAlias(alias)
                check(
                    resetDao.recordAliasAttempt(
                        resetId,
                        alias,
                        SshResetAliasState.PENDING,
                        0,
                        if (removed) SshResetAliasState.DELETED else SshResetAliasState.NOT_FOUND,
                        at,
                        null,
                    ),
                )
            }
            check(resetDao.advanceJournal(resetId, SshResetState.DELETING_ALIASES, at, SshResetState.FINALIZING, at, null))
            check(resetDao.finalizeReset(resetId, at, at))
            bumpChange()
            SshKeyStoreResetResult(keys.size, requestIds)
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = withContext(ioDispatcher) {
        writeMutex.withLock {
            block().also { if (it is Boolean && it) bumpChange() }
        }
    }

    private suspend fun ensureProviderState(at: Long): SshProviderStateEntity =
        keyDao.ensureProviderState(randomId(), at.coerceAtLeast(1))

    private suspend fun nextProviderState(at: Long): SshProviderStateEntity {
        val current = keyDao.readProviderState()
        return if (current == null) {
            keyDao.ensureProviderState(randomId(), at.coerceAtLeast(1))
        } else current.copy(revision = current.revision + 1, updatedAt = at.coerceAtLeast(1))
    }

    private fun bumpChange() { _changeVersion.value = if (_changeVersion.value == Long.MAX_VALUE) 0 else _changeVersion.value + 1 }

    private suspend fun generateDirect(
        algorithm: SshKeyAlgorithm,
        name: String,
        at: Long,
        verification: SshUserVerificationPolicy,
        rsaBits: Int,
    ): SshKeyStorageResult {
        val id = randomId()
        val alias = "notisync_ssh_identity_$id"
        var provisioning: PendingSshKeyProvisioning? = null
        keyDao.beginProvisioningHeader(lifecycleHeader(id, alias, SshStorageKind.DIRECT, at))
        try {
            val attemptedStrongBox = SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(
                strongBoxAvailable,
                algorithm,
            )
            var strongBoxFallback = false
            val candidate = try {
                generateOperationalKeyPair(algorithm, alias, attemptedStrongBox, verification, rsaBits)
            } catch (failure: SshOperationalCandidateException) {
                if (!attemptedStrongBox || !failure.strongBox) throw failure
                deleteAliasOrThrow(alias)
                strongBoxFallback = true
                generateOperationalKeyPair(algorithm, alias, false, verification, rsaBits)
            }
            val publicBlob = SshPublicKeyCodec.encode(candidate.pair.public, algorithm.toCoreType())
            val hash = sha256(publicBlob)
            provisioning = PendingSshKeyProvisioning(
                record = PendingSshKeyRecord(
                    providerKeyId = id,
                    publicBlob = publicBlob,
                    publicHash = hash,
                    algorithm = algorithm,
                    displayName = name,
                    origin = SshKeyOrigin.GENERATED,
                    operationalProvider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                    operationalSecurityLevel = candidate.securityLevel,
                    operationalStrongBoxAttempted = attemptedStrongBox,
                    operationalStrongBoxFallback = strongBoxFallback,
                    userVerificationPolicy = verification,
                    keyAlias = alias,
                    createdAt = at,
                    expiresAt = null,
                ),
                privateKeyPkcs8 = null,
                sourcePublicKey = null,
                exportCopyBackendPolicy = null,
                rsaKeySizeBits = rsaBits,
            )
            return validateOperationalOrContinue(provisioning, candidate.pair.public)
        } catch (failure: Exception) {
            val active = provisioning ?: PendingSshKeyProvisioning(
                PendingSshKeyRecord(
                    id,
                    ByteArray(0),
                    ByteArray(0),
                    algorithm,
                    name,
                    SshKeyOrigin.GENERATED,
                    SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                    SshStorageSecurityLevel.UNKNOWN,
                    false,
                    false,
                    verification,
                    alias,
                    at,
                    null,
                ),
                null,
                null,
                null,
                rsaBits,
            )
            abortProvisioning(active)
            throw failure
        }
    }

    private suspend fun storeSoftware(
        pair: KeyPair,
        algorithm: SshKeyAlgorithm,
        publicBlob: ByteArray,
        displayName: String,
        origin: SshKeyOrigin,
        now: Long,
        exportPolicy: SshExportCopyBackendPolicy?,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyStorageResult {
        val id = randomId()
        val alias = "notisync_ssh_identity_$id"
        val privateBytes = SensitiveBytes.takeOwnership(requireNotNull(pair.private.encoded).copyOf())
        val hash = sha256(publicBlob)
        // The self-test and all subsequent operations are inside the cleanup scope below. This
        // closes the sensitive source bytes even when validation itself is interrupted.
        val attemptStrongBox = SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(
            strongBoxAvailable,
            algorithm,
        )
        val record = PendingSshKeyRecord(
            id,
            publicBlob.copyOf(),
            hash,
            algorithm,
            displayName,
            origin,
            SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
            if (attemptStrongBox) SshStorageSecurityLevel.STRONGBOX else SshStorageSecurityLevel.UNKNOWN,
            attemptStrongBox,
            false,
            userVerificationPolicy,
            alias,
            now,
            null,
        )
        val provisioning = PendingSshKeyProvisioning(
            record,
            privateBytes,
            pair.public,
            exportPolicy,
            (pair.public as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 3072,
        )
        var ownershipTransferred = false
        try {
            selfTestSoftware(pair.private, pair.public, algorithm)
            keyDao.beginProvisioningHeader(lifecycleHeader(id, alias, SshStorageKind.DIRECT, now))
            var fallback = false
            val securityLevel = try {
                installOperationalKey(
                    alias,
                    privateBytes.bytes,
                    pair.public,
                    algorithm,
                    now,
                    attemptStrongBox,
                    userVerificationPolicy,
                )
            } catch (failure: SshOperationalCandidateException) {
                if (attemptStrongBox && failure.strongBox) {
                    deleteAliasOrThrow(alias)
                    fallback = true
                    installOperationalKey(
                        alias,
                        privateBytes.bytes,
                        pair.public,
                        algorithm,
                        now,
                        false,
                        userVerificationPolicy,
                    )
                } else if (
                    failure.stage == SshOperationalCandidateStage.DIRECT_PRIVATE_KEY_IMPORT &&
                    SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(algorithm)
                ) {
                    deleteAliasOrThrow(alias)
                    keyDao.transitionProvisioningStorageKind(
                        id,
                        SshStorageKind.DIRECT,
                        SshStorageKind.WRAPPED,
                        now,
                    )
                    val wrappedStrongBox = wrappedVault.shouldAttemptStrongBox()
                    provisioning.record = provisioning.record.copy(
                        operationalProvider = SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED,
                        operationalSecurityLevel = if (wrappedStrongBox) {
                            SshStorageSecurityLevel.STRONGBOX
                        } else {
                            SshStorageSecurityLevel.UNKNOWN
                        },
                        operationalStrongBoxAttempted = wrappedStrongBox,
                        operationalStrongBoxFallback = false,
                    )
                    val result = prepareWrappedOperationalEncryption(provisioning, wrappedStrongBox)
                    if (result is SshKeyStorageResult.AuthenticationRequired) ownershipTransferred = true
                    return result
                } else {
                    throw failure
                }
            }
            provisioning.record = provisioning.record.copy(
                operationalSecurityLevel = securityLevel,
                operationalStrongBoxFallback = fallback,
            )
            val result = validateOperationalOrContinue(provisioning, pair.public)
            if (result is SshKeyStorageResult.AuthenticationRequired) ownershipTransferred = true
            return result
        } finally {
            if (!ownershipTransferred && !provisioning.finished) abortProvisioning(provisioning)
        }
    }

    private suspend fun completeWrappedOperationalEncryption(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalWrapEncrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        provisioning.wrappedOperationalMaterial = try {
            wrappedVault.completeProtect(stage.protection, authenticatedCipher)
        } catch (failure: SshWrappedOperationalOperationException) {
            if (stage.strongBox && failure.strongBox) return retryWrappedOperationalWithDefault(provisioning)
            throw failure
        }
        return persistAndValidateWrappedOperational(provisioning, stage.strongBox)
    }

    private suspend fun prepareWrappedOperationalEncryption(
        provisioning: PendingSshKeyProvisioning,
        strongBox: Boolean,
    ): SshKeyStorageResult {
        val record = provisioning.record
        val prepared = try {
            wrappedVault.prepareProtect(
                record.keyAlias,
                record.providerKeyId,
                requireNotNull(provisioning.privateKeyPkcs8),
                record.algorithm,
                record.publicHash,
                strongBox,
                record.userVerificationPolicy,
            )
        } catch (failure: SshOperationalCandidateException) {
            if (strongBox && failure.strongBox) return retryWrappedOperationalWithDefault(provisioning)
            throw failure
        }
        provisioning.record = record.copy(operationalSecurityLevel = prepared.securityLevel)
        val stage = PreparedStorageStage.OperationalWrapEncrypt(prepared, strongBox)
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            authenticationRequired(
                provisioning,
                stage,
                prepared.cipher,
                SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            )
        } else {
            completeWrappedOperationalEncryption(provisioning, stage, prepared.cipher)
        }
    }

    private suspend fun persistAndValidateWrappedOperational(
        provisioning: PendingSshKeyProvisioning,
        strongBox: Boolean,
    ): SshKeyStorageResult {
        val record = provisioning.record
        val material = requireNotNull(provisioning.wrappedOperationalMaterial)
        keyDao.addLifecycleCandidate(
            SshKeyLifecycleCandidateEntity(
                record.providerKeyId,
                SshLifecycleCandidatePurpose.OPERATIONAL,
                record.keyAlias,
                material.ciphertext.copyOf(),
                material.nonce.copyOf(),
                material.securityLevel.toToken(),
            ),
        )
        val opened = try {
            wrappedVault.prepareUnwrap(
                record.keyAlias,
                record.providerKeyId,
                material.ciphertext,
                material.nonce,
                record.algorithm,
                record.publicHash,
                material.securityLevel,
                record.userVerificationPolicy,
            )
        } catch (failure: SshOperationalCandidateException) {
            if (strongBox && failure.strongBox) return retryWrappedOperationalWithDefault(provisioning)
            throw failure
        }
        val stage = PreparedStorageStage.OperationalWrapDecrypt(opened, material, strongBox)
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            authenticationRequired(
                provisioning,
                stage,
                opened.cipher,
                SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            )
        } else {
            completeWrappedOperationalValidation(provisioning, stage, opened.cipher)
        }
    }

    private suspend fun completeWrappedOperationalValidation(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalWrapDecrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val decrypted = try {
            wrappedVault.completeUnwrap(stage.unwrap, authenticatedCipher)
        } catch (failure: SshWrappedOperationalOperationException) {
            if (stage.strongBox && failure.strongBox) return retryWrappedOperationalWithDefault(provisioning)
            throw failure
        }
        decrypted.use { candidate ->
            val original = requireNotNull(provisioning.privateKeyPkcs8).bytes
            check(MessageDigest.isEqual(candidate.bytes, original)) {
                "Wrapped SSH operational key did not reproduce the original private key"
            }
            selfTestSoftware(
                softwarePrivateKey(provisioning.record.algorithm, candidate.bytes),
                requireNotNull(provisioning.sourcePublicKey),
                provisioning.record.algorithm,
            )
        }
        return continueAfterOperationalValidation(provisioning)
    }

    private suspend fun retryWrappedOperationalWithDefault(
        provisioning: PendingSshKeyProvisioning,
    ): SshKeyStorageResult {
        provisioning.wrappedOperationalMaterial?.ciphertext?.fill(0)
        provisioning.wrappedOperationalMaterial?.nonce?.fill(0)
        provisioning.wrappedOperationalMaterial = null
        wrappedVault.delete(provisioning.record.keyAlias)
        keyDao.deleteLifecycleCandidate(
            provisioning.record.providerKeyId,
            SshLifecycleCandidatePurpose.OPERATIONAL,
        )
        provisioning.record = provisioning.record.copy(
            operationalSecurityLevel = SshStorageSecurityLevel.UNKNOWN,
            operationalStrongBoxFallback = true,
        )
        return prepareWrappedOperationalEncryption(provisioning, false)
    }

    private suspend fun validateOperationalOrContinue(
        provisioning: PendingSshKeyProvisioning,
        publicKey: PublicKey,
    ): SshKeyStorageResult {
        val record = provisioning.record
        val privateKey = try {
            loadPrivateKey(record.keyAlias)
        } catch (failure: Exception) {
            if (record.isActiveStrongBoxCandidate()) return retryOperationalWithDefault(provisioning)
            throw SshOperationalOperationException(failure)
        }
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            val challenge = ByteArray(32).also(RANDOM::nextBytes)
            val signature = try {
                SshKeystoreJca.signature(record.algorithm.selfTestMethod().jcaName).apply {
                    initSign(privateKey)
                }
            } catch (failure: Exception) {
                challenge.fill(0)
                if (record.isActiveStrongBoxCandidate()) return retryOperationalWithDefault(provisioning)
                throw SshOperationalOperationException(failure)
            }
            signatureAuthenticationRequired(
                provisioning,
                PreparedStorageStage.OperationalSelfTest(
                    signature,
                    challenge,
                    publicKey,
                    record.isActiveStrongBoxCandidate(),
                ),
                signature,
            )
        } else {
            try {
                selfTestOperational(privateKey, publicKey, record.algorithm)
            } catch (failure: SshOperationalOperationException) {
                if (record.isActiveStrongBoxCandidate()) return retryOperationalWithDefault(provisioning)
                throw failure
            }
            continueAfterOperationalValidation(provisioning)
        }
    }

    private suspend fun completeOperationalSelfTest(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalSelfTest,
        authenticatedSignature: Signature,
    ): SshKeyStorageResult {
        require(authenticatedSignature === stage.signature) { "SSH operational signing operation changed" }
        val rawSignature = try {
            authenticatedSignature.update(stage.challenge)
            authenticatedSignature.sign()
        } catch (failure: Exception) {
            stage.challenge.fill(0)
            if (stage.strongBox) return retryOperationalWithDefault(provisioning)
            throw SshOperationalOperationException(failure)
        }
        try {
            check(
                verifySelfTest(
                    stage.publicKey,
                    provisioning.record.algorithm,
                    stage.challenge,
                    rawSignature,
                ),
            ) { "Android Keystore SSH signing key failed its authenticated self-test" }
        } finally {
            stage.challenge.fill(0)
            rawSignature.fill(0)
        }
        return continueAfterOperationalValidation(provisioning)
    }

    private suspend fun retryOperationalWithDefault(
        provisioning: PendingSshKeyProvisioning,
    ): SshKeyStorageResult {
        val old = provisioning.record
        deleteAliasOrThrow(old.keyAlias)
        val publicKey: PublicKey
        val securityLevel: SshStorageSecurityLevel
        val privateBytes = provisioning.privateKeyPkcs8
        if (privateBytes != null) {
            publicKey = requireNotNull(provisioning.sourcePublicKey)
            securityLevel = installOperationalKey(
                old.keyAlias,
                privateBytes.bytes,
                publicKey,
                old.algorithm,
                old.createdAt,
                false,
                old.userVerificationPolicy,
            )
        } else {
            val candidate = generateOperationalKeyPair(
                old.algorithm,
                old.keyAlias,
                false,
                old.userVerificationPolicy,
                provisioning.rsaKeySizeBits,
            )
            publicKey = candidate.pair.public
            securityLevel = candidate.securityLevel
            val publicBlob = SshPublicKeyCodec.encode(publicKey, old.algorithm.toCoreType())
            provisioning.record = old.copy(
                publicBlob = publicBlob,
                publicHash = sha256(publicBlob),
            )
        }
        provisioning.record = provisioning.record.copy(
            operationalSecurityLevel = securityLevel,
            operationalStrongBoxFallback = true,
        )
        return validateOperationalOrContinue(provisioning, publicKey)
    }

    private fun PendingSshKeyRecord.isActiveStrongBoxCandidate(): Boolean =
        operationalStrongBoxAttempted &&
            !operationalStrongBoxFallback &&
            operationalSecurityLevel == SshStorageSecurityLevel.STRONGBOX

    private suspend fun continueAfterOperationalValidation(
        provisioning: PendingSshKeyProvisioning,
    ): SshKeyStorageResult {
        val exportPolicy = provisioning.exportCopyBackendPolicy
        return if (exportPolicy == null) {
            finalizeProvisioning(provisioning)
        } else {
            prepareExportEncryption(provisioning, exportVault.shouldAttemptStrongBox(exportPolicy))
        }
    }

    private suspend fun prepareExportEncryption(
        provisioning: PendingSshKeyProvisioning,
        strongBox: Boolean,
    ): SshKeyStorageResult.AuthenticationRequired {
        val record = provisioning.record
        if (strongBox) provisioning.exportStrongBoxAttempted = true
        val prepared = try {
            exportVault.prepareProtect(
                record.providerKeyId,
                requireNotNull(provisioning.privateKeyPkcs8),
                record.algorithm,
                record.publicHash,
                strongBox,
            )
        } catch (failure: SshExportCandidateException) {
            if (strongBox && failure.strongBox) return retryExportWithDefault(provisioning)
            throw failure
        }
        return authenticationRequired(
            provisioning,
            PreparedStorageStage.ExportEncrypt(prepared, strongBox),
            prepared.cipher,
            SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
    }

    private suspend fun completeExportEncryption(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.ExportEncrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val material = try {
            exportVault.completeProtect(stage.protection, authenticatedCipher)
        } catch (failure: SshExportOperationException) {
            if (stage.strongBox && failure.strongBox) return retryExportWithDefault(provisioning)
            throw failure
        }
        provisioning.exportMaterial = material
        keyDao.addLifecycleCandidate(
            SshKeyLifecycleCandidateEntity(
                provisioning.record.providerKeyId,
                SshLifecycleCandidatePurpose.EXPORT,
                exportVault.alias(provisioning.record.providerKeyId, stage.strongBox),
                material.ciphertext.copyOf(),
                material.nonce.copyOf(),
                material.securityLevel.toToken(),
            ),
        )
        return prepareExportValidation(provisioning, material, stage.strongBox)
    }

    private suspend fun prepareExportValidation(
        provisioning: PendingSshKeyProvisioning,
        material: ProtectedSshKeyMaterial,
        strongBox: Boolean,
    ): SshKeyStorageResult.AuthenticationRequired {
        val record = provisioning.record
        val opened = try {
            exportVault.prepareUnwrap(
                record.providerKeyId,
                material.ciphertext,
                material.nonce,
                record.algorithm,
                record.publicHash,
                material.securityLevel,
                strongBoxCandidate = strongBox,
            )
        } catch (failure: SshExportCandidateException) {
            if (strongBox && failure.strongBox) return retryExportWithDefault(provisioning)
            throw failure
        }
        return authenticationRequired(
            provisioning,
            PreparedStorageStage.ExportDecrypt(opened, material, strongBox),
            opened.cipher,
            SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
    }

    private suspend fun completeExportValidation(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.ExportDecrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val decrypted = try {
            exportVault.completeUnwrap(stage.unwrap, authenticatedCipher)
        } catch (failure: SshExportOperationException) {
            if (stage.strongBox && failure.strongBox) return retryExportWithDefault(provisioning)
            throw failure
        }
        decrypted.use { candidate ->
            val original = requireNotNull(provisioning.privateKeyPkcs8).bytes
            check(MessageDigest.isEqual(candidate.bytes, original)) {
                "SSH export copy did not reproduce the original private key"
            }
            selfTestSoftware(
                softwarePrivateKey(provisioning.record.algorithm, candidate.bytes),
                requireNotNull(provisioning.sourcePublicKey),
                provisioning.record.algorithm,
            )
        }
        return finalizeProvisioning(provisioning)
    }

    private suspend fun retryExportWithDefault(
        provisioning: PendingSshKeyProvisioning,
    ): SshKeyStorageResult.AuthenticationRequired {
        provisioning.exportMaterial?.ciphertext?.fill(0)
        provisioning.exportMaterial?.nonce?.fill(0)
        provisioning.exportMaterial = null
        exportVault.deleteCandidate(provisioning.record.providerKeyId, true)
        keyDao.deleteLifecycleCandidate(
            provisioning.record.providerKeyId,
            SshLifecycleCandidatePurpose.EXPORT,
        )
        provisioning.exportStrongBoxFallback = true
        return prepareExportEncryption(provisioning, false)
    }

    private fun authenticationRequired(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage,
        cipher: Cipher,
        promptAuthenticators: Int,
    ) = SshKeyStorageResult.AuthenticationRequired(
        PreparedSshKeyStorage(
            cipher,
            null,
            promptAuthenticators,
            this,
            0,
            provisioning,
            stage,
        ),
    )

    private fun signatureAuthenticationRequired(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage,
        signature: Signature,
    ) = SshKeyStorageResult.AuthenticationRequired(
        PreparedSshKeyStorage(
            null,
            signature,
            SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            this,
            0,
            provisioning,
            stage,
        ),
    )

    private fun closePreparedStorageStage(stage: PreparedStorageStage) {
        when (stage) {
            is PreparedStorageStage.OperationalSelfTest -> stage.challenge.fill(0)
            is PreparedStorageStage.OperationalWrapEncrypt -> stage.protection.close()
            is PreparedStorageStage.OperationalWrapDecrypt -> stage.unwrap.close()
            is PreparedStorageStage.ExportEncrypt -> stage.protection.close()
            is PreparedStorageStage.ExportDecrypt -> stage.unwrap.close()
        }
    }

    private suspend fun finalizeProvisioning(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult.Stored {
        val record = provisioning.record
        val op = SshOperationalKeyEntity(record.providerKeyId, record.keyAlias, record.operationalSecurityLevel.toToken(), record.userVerificationPolicy.toToken(), record.operationalStrongBoxAttempted, record.operationalStrongBoxFallback, record.createdAt)
        val export = provisioning.exportMaterial?.let { material ->
            SshExportCopyEntity(
                record.providerKeyId,
                exportVault.alias(
                    record.providerKeyId,
                    provisioning.exportStrongBoxAttempted && !provisioning.exportStrongBoxFallback,
                ),
                material.ciphertext.copyOf(),
                material.nonce.copyOf(),
                material.securityLevel.toToken(),
                requireNotNull(provisioning.exportCopyBackendPolicy).toToken(),
                SshExportAuthenticationToken.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE,
                provisioning.exportStrongBoxAttempted,
                provisioning.exportStrongBoxFallback,
                record.createdAt,
            )
        }
        val key = SshKeyEntity(record.providerKeyId, record.publicBlob.copyOf(), record.publicHash.copyOf(), record.algorithm.toToken(), record.displayName, record.origin.toToken(), SshApprovalPolicyToken.ALWAYS_ASK, record.createdAt, record.expiresAt, record.createdAt)
        val wrapped = record.operationalProvider == SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED
        if (wrapped) {
            val material = requireNotNull(provisioning.wrappedOperationalMaterial)
            keyDao.finalizeWrappedProvisioning(
                key,
                op,
                SshWrappedOperationalMaterialEntity(
                    record.providerKeyId,
                    material.ciphertext.copyOf(),
                    material.nonce.copyOf(),
                ),
                export,
                nextProviderState(record.createdAt),
            )
        } else {
            check(provisioning.wrappedOperationalMaterial == null) {
                "Direct SSH provisioning retained wrapped operational material"
            }
            keyDao.finalizeDirectProvisioning(key, op, export, nextProviderState(record.createdAt))
        }
        provisioning.finished = true
        provisioning.close()
        bumpChange()
        return SshKeyStorageResult.Stored(
            key.toDescriptor(op, wrapped = wrapped, export = export, namespaces = emptyList()),
        )
    }

    private suspend fun abortProvisioning(provisioning: PendingSshKeyProvisioning) =
        withContext(NonCancellable + ioDispatcher) {
            runCatching { deleteAlias(provisioning.record.keyAlias) }
            runCatching { exportVault.deleteAll(provisioning.record.providerKeyId) }
            runCatching { keyDao.deleteLifecycle(provisioning.record.providerKeyId) }
            provisioning.close()
        }

    private suspend fun accept(request: Any, kind: SshProviderRequestKind, history: SshRequestHistorySnapshot, at: Long): SshProviderAcceptResult {
        val requestId = when (request) {
            is SshSignRequest -> request.requestId
            is SshImportRequest -> request.requestId
            else -> error("unsupported SSH request")
        }
        val requester = when (request) {
            is SshSignRequest -> request.requesterClientId
            is SshImportRequest -> request.requesterClientId
            else -> error("unsupported SSH request")
        }
        val expiresAt = when (request) {
            is SshSignRequest -> request.expiresAt
            is SshImportRequest -> request.expiresAt
            else -> error("unsupported SSH request")
        }
        val encoded = when (request) {
            is SshSignRequest -> ProtocolCodec.encodeToCbor(request)
            is SshImportRequest -> ProtocolCodec.encodeToCbor(request)
            else -> error("unsupported SSH request")
        }
        return withPreparedProviderRequest(
            requestId = requestId,
            requester = requester,
            kind = kind,
            expiresAt = expiresAt,
            history = history,
            encoded = encoded,
        ) { entity, pending ->
            val result = requestDao.acceptProviderRequest(entity, pending, null, at)
            if (result == net.extrawdw.apps.notisync.data.storage.operational.SshProviderAcceptResult.STORED) {
                bumpChange()
            }
            result.toLegacy()
        }
    }

    private suspend fun <T> withPreparedProviderRequest(
        requestId: String,
        requester: ClientId,
        kind: SshProviderRequestKind,
        expiresAt: Long,
        history: SshRequestHistorySnapshot,
        encoded: ByteArray,
        commit: suspend (SshProviderRequestEntity, SshProviderPendingPayloadEntity) -> T,
    ): T {
        try {
            val generation = requireReadyGeneration()
            val pending = protectAtGeneration(
                encoded,
                ProtectedPayloadBinding.sshProviderPending(requestId),
                generation,
            )
            val historyBytes = ProtocolCodec.encodeToCbor(history)
            val protectedHistory = try {
                protectAtGeneration(
                    historyBytes,
                    ProtectedPayloadBinding.sshProviderHistory(requestId),
                    generation,
                )
            } finally {
                historyBytes.fill(0)
            }
            val entity = SshProviderRequestEntity(
                requestId = requestId,
                kind = kind.toStorage(),
                requesterClientId = requester.value,
                requestFingerprint = sha256(encoded),
                historyProtectionScheme = protectedHistory.scheme,
                historyProtectionVersion = protectedHistory.protectionVersion,
                historyProtectionKeyRef = protectedHistory.keyRef,
                historyProtectionGeneration = protectedHistory.generation,
                historyPayloadCodecVersion = protectedHistory.payloadCodecVersion,
                historyCiphertext = protectedHistory.ciphertextCopy(),
                historyNonce = protectedHistory.nonceCopy(),
                state = StorageSshProviderRequestState.PENDING_REVIEW,
                outcome = null,
                resultAt = null,
                expiresAt = expiresAt,
                createdAt = history.requestedAt,
                updatedAt = now().coerceAtLeast(1),
            )
            val pendingEntity = SshProviderPendingPayloadEntity(
                requestId = requestId,
                protectionScheme = pending.scheme,
                protectionVersion = pending.protectionVersion,
                protectionKeyRef = pending.keyRef,
                protectionGeneration = pending.generation,
                payloadCodecVersion = pending.payloadCodecVersion,
                requestCiphertext = pending.ciphertextCopy(),
                requestNonce = pending.nonceCopy(),
                createdAt = history.requestedAt,
            )
            return commit(entity, pendingEntity)
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun approveSign(requestId: String, provider: ClientId, at: Long, disposition: SshRememberDisposition, scope: SshRememberScope?): SshSignResult? = withContext(ioDispatcher) {
        val stored = decodeRequest(requestDao.findProviderRequest(requestId) ?: return@withContext null) ?: return@withContext null
        val request = stored.signRequest ?: return@withContext null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || requestExpiresAt(stored) < at) return@withContext null
        scope?.let { rememberAuthorization(stored, it, at) }
        val key = keyDao.findKeyByPublicHash(sha256(request.publicKeyBlob)) ?: return@withContext signFailure(request, provider, at, SshProviderFailureCode.KEY_NOT_FOUND).also { storeResponse(stored, it, at, null) }
        val method = signatureMethod(request)
        val signature = signRequest(key, method, request)
        val result = signedResult(request, provider, at, disposition, method, signature)
        storeResponse(stored, result, at, null)
        result
    }

    private suspend fun signRequest(key: SshKeyEntity, method: SshSignatureMethod, request: SshSignRequest): ByteArray {
        val op = keyDao.findOperationalKey(key.providerKeyId) ?: error("SSH operational key missing")
        val wrapped = keyDao.findWrappedOperationalMaterial(key.providerKeyId)
        return if (wrapped == null) {
            val privateKey = loadPrivateKey(op.keyAlias)
            SshKeystoreJca.signature(method.jcaName).run { initSign(privateKey); update(request.data); sign() }
        } else {
            wrappedVault.prepareUnwrap(op.keyAlias, key.providerKeyId, wrapped.privateKeyCiphertext, wrapped.privateKeyNonce, key.algorithm.toProtocol(), key.publicHash, op.securityLevel.toProtocol(), op.userVerificationPolicy.toProtocol()).let { prepared ->
                wrappedVault.completeUnwrap(prepared).use { bytes -> signSoftwareRaw(method, softwarePrivateKey(key.algorithm.toProtocol(), bytes.bytes), request.data) }
            }
        }
    }

    private suspend fun storeResponse(stored: StoredSshProviderRequest, response: Any, at: Long, audit: Any?): Boolean {
        val encoded = when (response) { is SshSignResult -> ProtocolCodec.encodeToCbor(response); is SshImportResult -> ProtocolCodec.encodeToCbor(response); else -> error("unsupported SSH response") }
        val outcome = when (response) {
            is SshSignResult -> when (response.kind) { SshSignResultKind.SIGNED -> StorageSshProviderRequestOutcome.SIGNED; SshSignResultKind.REJECTED_BY_USER -> StorageSshProviderRequestOutcome.REJECTED; SshSignResultKind.PROVIDER_FAILURE -> StorageSshProviderRequestOutcome.FAILED }
            is SshImportResult -> when (response.kind) { SshImportResultKind.IMPORTED -> StorageSshProviderRequestOutcome.IMPORTED; SshImportResultKind.ALREADY_PRESENT -> StorageSshProviderRequestOutcome.ALREADY_PRESENT; else -> StorageSshProviderRequestOutcome.FAILED }
            else -> error("unsupported SSH response")
        }
        return try {
            val committed = if (response is SshSignResult && response.kind == SshSignResultKind.SIGNED) {
                val protected = protectAtGeneration(encoded, ProtectedPayloadBinding.sshProviderResponse(stored.requestId), requireReadyGeneration())
                requestDao.recordProviderOutcomeAndQueueResponse(SshProviderOutcomeTransition(stored.requestId, outcome, at, protected.toResponseEntity(stored.requestId, at), null))
            } else {
                val changed = requestDao.terminalProviderRequest(
                    stored.requestId,
                    StorageSshProviderRequestState.PENDING_REVIEW,
                    StorageSshProviderRequestState.RESPONSE_QUEUED,
                    outcome,
                    at,
                ) == 1
                if (changed) transientResponses[stored.requestId] = encoded.copyOf()
                changed
            }
            if (committed) bumpChange()
            committed
        } finally { encoded.fill(0) }
    }

    private suspend fun requestsIn(state: SshProviderRequestState): List<StoredSshProviderRequest> =
        withContext(ioDispatcher) {
            buildList {
                requestDao.observeProviderHistory().first()
                    .filter { it.state.toLegacy() == state }
                    .forEach { entity -> decodeRequest(entity)?.let(::add) }
            }
        }

    private suspend fun decodeRequest(entity: SshProviderRequestEntity): StoredSshProviderRequest? {
        val generation = requireReadyGeneration()
        val history = decodeProtected(
            entity.historyProtection(),
            ProtectedPayloadBinding.sshProviderHistory(entity.requestId),
            generation,
        )
        val pending = requestDao.findProviderPendingPayload(entity.requestId)
        val requestBytes = if (pending == null) {
            null
        } else {
            decodeProtected(
                pending.toProtectedPayload(),
                ProtectedPayloadBinding.sshProviderPending(entity.requestId),
                generation,
            )
        }
        val request = requestBytes?.let { bytes ->
            try {
                if (entity.kind == StorageSshProviderRequestKind.SIGN) {
                    ProtocolCodec.decodeFromCbor<SshSignRequest>(bytes)
                } else {
                    ProtocolCodec.decodeFromCbor<SshImportRequest>(bytes)
                }
            } finally {
                bytes.fill(0)
            }
        }
        val historySnapshot = try {
            ProtocolCodec.decodeFromCbor<SshRequestHistorySnapshot>(history)
        } finally {
            history.fill(0)
        }
        val visibleHistory = transientRequestBodies[entity.requestId]?.let { bytes ->
            runCatching { ProtocolCodec.decodeFromCbor<SshRequestHistorySnapshot>(bytes) }.getOrNull()
        } ?: historySnapshot
        return StoredSshProviderRequest(
            requestId = entity.requestId,
            kind = entity.kind.toLegacy(),
            requesterClientId = ClientId(entity.requesterClientId),
            requestFingerprint = entity.requestFingerprint.copyOf(),
            signRequest = request as? SshSignRequest,
            importRequest = request as? SshImportRequest,
            history = visibleHistory,
            state = entity.state.toLegacy(),
            outcome = entity.outcome?.toLegacy(),
            resultAt = entity.resultAt,
            encodedResponse = transientResponses[entity.requestId]?.copyOf(),
            updatedAt = entity.updatedAt,
        )
    }

    private suspend fun updateHistory(entity: SshProviderRequestEntity, history: SshRequestHistorySnapshot) {
        // History is immutable in the normal path. Import preview uses the process-local request map; no protected
        // bytes are rewritten solely for a UI hint.
        transientRequestBodies[entity.requestId] = ProtocolCodec.encodeToCbor(history)
    }

    private suspend fun requireReadyGeneration(): Long = when (val availability = payloadKeyEnsurer.ensureCurrent()) {
        is OperationalPayloadKeyAvailability.Ready -> availability.generation
        is OperationalPayloadKeyAvailability.Unavailable -> error("SSH protected storage unavailable: ${availability.failure.code}")
    }

    private suspend fun protectAtGeneration(bytes: ByteArray, binding: ProtectedPayloadBinding, generation: Long): ProtectedPayload = withContext(ioDispatcher) { protector.protect(bytes, binding, generation) }
    private suspend fun openAtGeneration(payload: ProtectedPayload, binding: ProtectedPayloadBinding): ByteArray = withContext(ioDispatcher) { protector.open(payload, binding) }

    private suspend fun descriptor(
        key: SshKeyEntity,
        namespaces: List<net.extrawdw.notisync.protocol.SshRememberedNamespace>,
    ): SshKeyDescriptor? {
        val op = keyDao.findOperationalKey(key.providerKeyId) ?: return null
        val export = keyDao.findExportCopy(key.providerKeyId)
        val wrapped = keyDao.findWrappedOperationalMaterial(key.providerKeyId) != null
        return key.toDescriptor(op, wrapped, export, namespaces)
    }

    private fun SshKeyEntity.toDescriptor(
        op: SshOperationalKeyEntity,
        wrapped: Boolean,
        export: SshExportCopyEntity?,
        namespaces: List<net.extrawdw.notisync.protocol.SshRememberedNamespace>,
    ) = net.extrawdw.notisync.protocol.SshKeyDescriptor(
        providerKeyId,
        publicBlob.copyOf(),
        publicHash.copyOf(),
        algorithm.toProtocol(),
        displayName,
        origin.toProtocol(),
        SshOperationalKeyProtection(
            if (wrapped) SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED
            else SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
            op.securityLevel.toProtocol(),
            op.userVerificationPolicy.toProtocol(),
            op.strongBoxAttempted,
            op.strongBoxFallback,
        ),
        export?.let {
            SshExportCopyProtection(
                it.securityLevel.toProtocol(),
                it.backendPolicy.toProtocol(),
                it.authentication.toProtocol(),
                it.strongBoxAttempted,
                it.strongBoxFallback,
            )
        },
        approvalPolicy.toProtocol(),
        namespaces,
        createdAt,
    )

    private fun validateName(value: String): String = value.trim().also { require(it.isNotEmpty() && it.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) }
    private suspend fun rememberedNamespaces(): Map<String, List<SshRememberedNamespace>> {
        data class NamespaceKey(val requester: ClientId, val generation: String, val epoch: Long)
        val grouped = linkedMapOf<String, LinkedHashMap<NamespaceKey, MutableSet<SshRememberScope>>>()
        authorizationDao.observePeerAuthorizations().first().forEach { row ->
            val namespaces = grouped.getOrPut(row.providerKeyId) { linkedMapOf() }
            val key = NamespaceKey(
                ClientId(row.requesterClientId),
                row.authorizationGeneration,
                row.authorizationEpoch,
            )
            namespaces.getOrPut(key) { linkedSetOf() }.add(SshRememberScope.PEER)
        }
        authorizationDao.observeHostAuthorizations().first().forEach { row ->
            val namespaces = grouped.getOrPut(row.providerKeyId) { linkedMapOf() }
            val key = NamespaceKey(
                ClientId(row.requesterClientId),
                row.authorizationGeneration,
                row.authorizationEpoch,
            )
            namespaces.getOrPut(key) { linkedSetOf() }.add(SshRememberScope.PEER_HOST_KEY)
        }
        return grouped.mapValues { (_, namespaces) ->
            namespaces.entries.take(SshAgentLimits.MAX_REMEMBERED_NAMESPACES).map { (key, scopes) ->
                SshRememberedNamespace(
                    requesterClientId = key.requester,
                    authorizationGeneration = key.generation,
                    authorizationEpoch = key.epoch,
                    scopes = SshRememberScope.entries.filter(scopes::contains),
                )
            }
        }
    }

    private fun SshRememberScope.createdDisposition(): SshRememberDisposition = when (this) {
        SshRememberScope.PEER -> SshRememberDisposition.CREATED_PEER
        SshRememberScope.PEER_HOST_KEY -> SshRememberDisposition.CREATED_PEER_HOST_KEY
        SshRememberScope.APPLICATION_PROCESS -> SshRememberDisposition.CREATED_APPLICATION_PROCESS
    }

    private fun SshRememberScope.matchedDisposition(): SshRememberDisposition = when (this) {
        SshRememberScope.PEER -> SshRememberDisposition.MATCHED_PEER
        SshRememberScope.PEER_HOST_KEY -> SshRememberDisposition.MATCHED_PEER_HOST_KEY
        SshRememberScope.APPLICATION_PROCESS -> SshRememberDisposition.MATCHED_APPLICATION_PROCESS
    }

    private fun lifecycleHeader(id: String, alias: String, kind: SshStorageKind, at: Long) = SshKeyLifecycleEntity(id, alias, kind, SshKeyLifecycleState.PROVISIONING, at, at)
    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.contentKey(): String = joinToString("") { "%02x".format(it) }
    private fun deleteAliasOrThrow(alias: String): Boolean {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existed = store.containsAlias(alias)
        if (existed) store.deleteEntry(alias)
        return existed
    }
    private fun deleteAlias(alias: String): Boolean = runCatching { deleteAliasOrThrow(alias) }.getOrDefault(false)
    private fun loadPrivateKey(alias: String): PrivateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? PrivateKey ?: error("SSH key alias is unavailable")
    private fun softwarePrivateKey(algorithm: SshKeyAlgorithm, bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance(algorithm.toKeyFactory(), SOFTWARE_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(bytes))
    private fun generateSoftwarePair(algorithm: SshKeyAlgorithm, rsaBits: Int): KeyPair =
        generateSoftwareSshKeyPair(algorithm, rsaBits)
    private fun generateOperationalKeyPair(
        algorithm: SshKeyAlgorithm,
        alias: String,
        strongBox: Boolean,
        verification: SshUserVerificationPolicy,
        rsaBits: Int,
    ): OperationalKeyCandidate {
        val generatorAlgorithms = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            listOf("Ed25519", KeyProperties.KEY_ALGORITHM_EC)
        } else {
            listOf(algorithm.toKeyStoreAlgorithm())
        }
        var firstFailure: Exception? = null
        for (generatorAlgorithm in generatorAlgorithms) {
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)) {
                "SSH operational alias already exists"
            }
            try {
                val pair = generateAndroidKeyPair(
                    algorithm,
                    generatorAlgorithm,
                    alias,
                    strongBox,
                    verification,
                    rsaBits,
                )
                validateOperationalPublicKey(pair.public, algorithm)
                val level = inspectKeyInfo(pair.private, algorithm, verification)
                if (strongBox) {
                    check(level == SshStorageSecurityLevel.STRONGBOX) {
                        "Android Keystore did not honor the requested SSH signing StrongBox backend"
                    }
                }
                return OperationalKeyCandidate(pair, level)
            } catch (failure: Exception) {
                runCatching { deleteAliasOrThrow(alias) }.exceptionOrNull()?.let { deletionFailure ->
                    failure.addSuppressed(deletionFailure)
                    throw SshOperationalCandidateException(strongBox, failure)
                }
                if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
            }
        }
        throw SshOperationalCandidateException(strongBox, requireNotNull(firstFailure))
    }

    private fun generateAndroidKeyPair(
        algorithm: SshKeyAlgorithm,
        generatorAlgorithm: String,
        alias: String,
        strongBox: Boolean,
        verification: SshUserVerificationPolicy,
        rsaBits: Int,
    ): KeyPair {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).apply {
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> {
                    setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                    setDigests(KeyProperties.DIGEST_NONE)
                }
                SshKeyAlgorithm.SSH_RSA -> {
                    setKeySize(rsaBits)
                    setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                }
                SshKeyAlgorithm.ECDSA_NISTP256 -> {
                    setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    setDigests(KeyProperties.DIGEST_SHA256)
                }
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (verification == SshUserVerificationPolicy.PER_USE) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationParameters(0, SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS)
            }
        }
        return SshKeystoreJca.keyPairGenerator(generatorAlgorithm).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    private fun installOperationalKey(
        alias: String,
        privateKeyPkcs8: ByteArray,
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
        at: Long,
        strongBox: Boolean,
        verification: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!store.containsAlias(alias)) { "SSH operational alias already exists" }
        validateOperationalPublicKey(publicKey, algorithm)
        // Source parsing and certificate construction are application invariants. Failures here must not be
        // misclassified as evidence that a StrongBox import is unsupported.
        val privateKey = softwarePrivateKey(algorithm, privateKeyPkcs8)
        val certificate = createContainerCertificate(privateKey, publicKey, algorithm, at)
        try {
            installAndroidKeyStoreEntry(alias, privateKey, certificate, algorithm, strongBox, verification)
        } catch (failure: Exception) {
            runCatching { deleteAliasOrThrow(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw SshOperationalCandidateException(
                strongBox,
                failure,
                SshOperationalCandidateStage.DIRECT_PRIVATE_KEY_IMPORT,
            )
        }
        try {
            store.load(null)
            val installed = store.getKey(alias, null) as? PrivateKey
                ?: error("Imported Android Keystore SSH key is unavailable")
            check(installed.encoded == null) { "Android Keystore SSH signing key is unexpectedly exportable" }
            val level = inspectKeyInfo(installed, algorithm, verification)
            if (strongBox) {
                check(level == SshStorageSecurityLevel.STRONGBOX) {
                    "Android Keystore did not honor the requested SSH signing StrongBox backend"
                }
            }
            return level
        } catch (failure: Exception) {
            runCatching { deleteAliasOrThrow(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw SshOperationalCandidateException(strongBox, cause = failure)
        }
    }

    private fun installAndroidKeyStoreEntry(
        alias: String,
        privateKey: PrivateKey,
        certificate: Certificate,
        algorithm: SshKeyAlgorithm,
        strongBox: Boolean,
        verification: SshUserVerificationPolicy,
    ) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).apply {
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> setDigests(KeyProperties.DIGEST_NONE)
                SshKeyAlgorithm.SSH_RSA -> {
                    setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                }
                SshKeyAlgorithm.ECDSA_NISTP256 -> setDigests(KeyProperties.DIGEST_SHA256)
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (verification == SshUserVerificationPolicy.PER_USE) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationParameters(0, SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS)
            }
        }.build()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.setEntry(
            alias,
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
            protection,
        )
    }

    private fun createContainerCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
        at: Long,
    ): Certificate {
        val subject = X500Name("CN=NotiSync SSH key container")
        val signer = JcaContentSignerBuilder(
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
                SshKeyAlgorithm.SSH_RSA -> "SHA256withRSA"
                SshKeyAlgorithm.ECDSA_NISTP256 -> "SHA256withECDSA"
            },
        ).setProvider(SOFTWARE_PROVIDER).build(privateKey)
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, RANDOM).abs().max(BigInteger.ONE),
            Date(at - CERTIFICATE_CLOCK_SKEW_MILLIS),
            Date(at + CERTIFICATE_VALIDITY_MILLIS),
            subject,
            publicKey,
        ).build(signer)
        return JcaX509CertificateConverter().setProvider(SOFTWARE_PROVIDER).getCertificate(holder).also {
            it.checkValidity(Date(at))
            it.verify(publicKey)
        }
    }

    private fun inspectKeyInfo(
        privateKey: PrivateKey,
        algorithm: SshKeyAlgorithm,
        verification: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        val info = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            var found: KeyInfo? = null
            var firstFailure: Exception? = null
            for (factoryAlgorithm in listOf("Ed25519", "ED25519")) {
                try {
                    found = SshKeystoreJca.keyFactory(factoryAlgorithm)
                        .getKeySpec(privateKey, KeyInfo::class.java)
                    break
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
                }
            }
            found ?: throw IllegalStateException("Android Keystore exposes no Ed25519 KeyFactory", firstFailure)
        } else {
            SshKeystoreJca.keyFactory(algorithm.toKeyStoreAlgorithm())
                .getKeySpec(privateKey, KeyInfo::class.java)
        }
        check(info.purposes and KeyProperties.PURPOSE_SIGN != 0) { "Android Keystore SSH key cannot sign" }
        val requiredDigests = when (algorithm) {
            SshKeyAlgorithm.SSH_ED25519 -> setOf(KeyProperties.DIGEST_NONE)
            SshKeyAlgorithm.SSH_RSA -> setOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            SshKeyAlgorithm.ECDSA_NISTP256 -> setOf(KeyProperties.DIGEST_SHA256)
        }
        check(info.digests.toSet().containsAll(requiredDigests)) {
            "Android Keystore did not authorize the required SSH signature digest"
        }
        when (verification) {
            SshUserVerificationPolicy.NONE -> check(!info.isUserAuthenticationRequired) {
                "Android Keystore unexpectedly requires SSH user authentication"
            }
            SshUserVerificationPolicy.PER_USE -> {
                check(info.isUserAuthenticationRequired) {
                    "Android Keystore did not bind SSH user authentication"
                }
                check(info.userAuthenticationValidityDurationSeconds == 0) {
                    "Android Keystore did not bind SSH authentication to every use"
                }
                check(info.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0) {
                    "Android Keystore did not bind strong-biometric SSH authentication"
                }
            }
        }
        return info.securityLevel.toSshStorageSecurityLevel()
    }

    private fun validateOperationalPublicKey(publicKey: PublicKey, algorithm: SshKeyAlgorithm) {
        SshPublicKeyCodec.encode(publicKey, algorithm.toCoreType())
        if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            val encoded = requireNotNull(publicKey.encoded) { "Ed25519 public key is not encodable" }
            val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(encoded)
            check(spki.algorithm.algorithm.id == ED25519_OID) { "Android Keystore did not create Ed25519" }
            check(spki.publicKeyData.bytes.size == ED25519_PUBLIC_KEY_BYTES) {
                "Android Keystore returned an invalid Ed25519 public key"
            }
        }
    }

    private fun selfTestOperational(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
    ) {
        val data = ByteArray(32).also(RANDOM::nextBytes)
        val rawSignature = try {
            SshKeystoreJca.signature(algorithm.selfTestMethod().jcaName).run {
                initSign(privateKey)
                update(data)
                sign()
            }
        } catch (failure: Exception) {
            data.fill(0)
            throw SshOperationalOperationException(failure)
        }
        try {
            check(verifySelfTest(publicKey, algorithm, data, rawSignature)) {
                "Android Keystore SSH signing key failed its self-test"
            }
        } finally {
            data.fill(0)
            rawSignature.fill(0)
        }
    }

    private fun selfTestSoftware(privateKey: PrivateKey, publicKey: PublicKey, algorithm: SshKeyAlgorithm) {
        val data = ByteArray(32).also(RANDOM::nextBytes)
        val signature = signSoftwareRaw(algorithm.selfTestMethod(), privateKey, data)
        try {
            check(verifySelfTest(publicKey, algorithm, data, signature)) {
                "Imported SSH private key does not match its public key"
            }
        } finally {
            data.fill(0)
            signature.fill(0)
        }
    }

    private fun verifySelfTest(
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val verificationKey = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            KeyFactory.getInstance("Ed25519", SOFTWARE_PROVIDER).generatePublic(
                X509EncodedKeySpec(requireNotNull(publicKey.encoded) { "Ed25519 public key is not encodable" }),
            )
        } else {
            publicKey
        }
        val verifier = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            Signature.getInstance(algorithm.selfTestMethod().jcaName, SOFTWARE_PROVIDER)
        } else {
            Signature.getInstance(algorithm.selfTestMethod().jcaName)
        }
        return verifier.run {
            initVerify(verificationKey)
            update(data)
            verify(signature)
        }
    }
    private fun signSoftwareRaw(method: SshSignatureMethod, privateKey: PrivateKey, data: ByteArray): ByteArray = Signature.getInstance(method.jcaName, SOFTWARE_PROVIDER).run { initSign(privateKey); update(data); sign() }
    private fun signatureMethod(request: SshSignRequest): SshSignatureMethod = SshSignatureVerifier.methodFor(SshPublicKeyCodec.decode(request.publicKeyBlob).type, request.flags, false).also { require(it.toProtocol() == request.requestedSignatureAlgorithm) }
    private fun signedResult(request: SshSignRequest, provider: ClientId, at: Long, disposition: SshRememberDisposition, method: SshSignatureMethod, raw: ByteArray) = SshSignResult(
        request.requestId,
        request.requesterClientId,
        sha256(request.publicKeyBlob),
        SshSignResultKind.SIGNED,
        at,
        provider,
        signature = SshSignatureResult(
            encodeSshSignatureForProvider(method, raw),
            disposition,
            request.authorizationGeneration,
            request.authorizationEpoch,
        ),
    )
    private fun signFailure(request: SshSignRequest, provider: ClientId, at: Long, code: SshProviderFailureCode) = SshSignResult(request.requestId, request.requesterClientId, sha256(request.publicKeyBlob), SshSignResultKind.PROVIDER_FAILURE, at, provider, failure = SshProviderFailure(code))

    private fun SshSignRequest.historySnapshot(name: String?) = SshRequestHistorySnapshot(requestedAt, expiresAt, publicKeyBlob.copyOf(), name, signatureAlgorithm = requestedSignatureAlgorithm, processLineage = processContext.processLineage, destinationUsername = destinationContext.username, destinationHost = destinationContext.hostAliases.firstOrNull()?.value, payloadSize = data.size)
    private fun SshImportRequest.historySnapshot() = SshRequestHistorySnapshot(requestedAt, expiresAt, keyName = suggestedName, suggestedName = suggestedName, importSourceType = sourceType, encryptedImport = false, payloadSize = fileBytes?.size ?: agentIdentity?.size ?: 0)

    private fun SshKnownHostEntity.toDomain() = SshKnownHost(hostKeySha256.copyOf(), null, firstApprovedAt, lastApprovedAt)
    private fun SshPeerAuthorizationEntity.toDomain(scope: SshRememberScope, host: SshKnownHostEntity?) = SshRememberedAuthorization(authorizationId, providerKeyId, ClientId(requesterClientId), authorizationGeneration, authorizationEpoch, scope, null, null, createdAt)
    private fun SshHostAuthorizationEntity.toDomain(scope: SshRememberScope, host: SshKnownHostEntity?) = SshRememberedAuthorization(authorizationId, providerKeyId, ClientId(requesterClientId), authorizationGeneration, authorizationEpoch, scope, hostKeySha256.copyOf(), null, createdAt)
    private fun SshKeyEntity.algorithmToken(): SshKeyAlgorithm = algorithm.toProtocol()
    private fun SshKeyAlgorithm.toToken() = SshKeyAlgorithmToken.valueOf(name)
    private fun SshKeyAlgorithm.toCoreType() = when (this) { SshKeyAlgorithm.SSH_ED25519 -> SshKeyType.ED25519; SshKeyAlgorithm.SSH_RSA -> SshKeyType.RSA; SshKeyAlgorithm.ECDSA_NISTP256 -> SshKeyType.ECDSA_NISTP256 }
    private fun SshKeyAlgorithm.toKeyStoreAlgorithm() = if (this == SshKeyAlgorithm.SSH_ED25519 || this == SshKeyAlgorithm.ECDSA_NISTP256) KeyProperties.KEY_ALGORITHM_EC else KeyProperties.KEY_ALGORITHM_RSA
    private fun SshKeyAlgorithm.toKeyFactory() = if (this == SshKeyAlgorithm.ECDSA_NISTP256) "EC" else if (this == SshKeyAlgorithm.SSH_RSA) "RSA" else "Ed25519"
    private fun SshKeyAlgorithm.selfTestMethod() = when (this) { SshKeyAlgorithm.SSH_ED25519 -> SshSignatureMethod.ED25519; SshKeyAlgorithm.SSH_RSA -> SshSignatureMethod.RSA_SHA2_256; SshKeyAlgorithm.ECDSA_NISTP256 -> SshSignatureMethod.ECDSA_NISTP256 }
    private fun SshSignatureMethod.toProtocol() = when (this) { SshSignatureMethod.ED25519 -> net.extrawdw.notisync.protocol.SshSignatureAlgorithm.SSH_ED25519; SshSignatureMethod.RSA_SHA2_256 -> net.extrawdw.notisync.protocol.SshSignatureAlgorithm.RSA_SHA2_256; SshSignatureMethod.RSA_SHA2_512 -> net.extrawdw.notisync.protocol.SshSignatureAlgorithm.RSA_SHA2_512; SshSignatureMethod.ECDSA_NISTP256 -> net.extrawdw.notisync.protocol.SshSignatureAlgorithm.ECDSA_NISTP256; SshSignatureMethod.RSA_SHA1_LEGACY -> net.extrawdw.notisync.protocol.SshSignatureAlgorithm.RSA_SHA1_LEGACY }
    private fun SshSignatureAlgorithm.toKeyAlgorithm() = when (this) {
        SshSignatureAlgorithm.SSH_ED25519 -> SshKeyAlgorithm.SSH_ED25519
        SshSignatureAlgorithm.RSA_SHA2_256,
        SshSignatureAlgorithm.RSA_SHA2_512,
        SshSignatureAlgorithm.RSA_SHA1_LEGACY,
        -> SshKeyAlgorithm.SSH_RSA
        SshSignatureAlgorithm.ECDSA_NISTP256 -> SshKeyAlgorithm.ECDSA_NISTP256
    }
    private fun SshKeyAlgorithmToken.toProtocol() = SshKeyAlgorithm.valueOf(name)
    private fun SshKeyOriginToken.toProtocol() = SshKeyOrigin.valueOf(name)
    private fun SshApprovalPolicyToken.toProtocol() = SshApprovalPolicy.valueOf(name)
    private fun SshSecurityLevelToken.toProtocol() = SshStorageSecurityLevel.valueOf(name)
    private fun SshUserVerificationToken.toProtocol() = SshUserVerificationPolicy.valueOf(name)
    private fun SshSecurityLevelToken.toToken() = this
    private fun SshExportBackendToken.toProtocol() = SshExportCopyBackendPolicy.valueOf(name)
    private fun SshExportAuthenticationToken.toProtocol() = SshExportCopyAuthentication.valueOf(name)
    private fun SshStorageSecurityLevel.toToken() = SshSecurityLevelToken.valueOf(name)
    private fun SshUserVerificationPolicy.toToken() = SshUserVerificationToken.valueOf(name)
    private fun SshApprovalPolicy.toToken() = SshApprovalPolicyToken.valueOf(name)
    private fun SshKeyOrigin.toToken() = SshKeyOriginToken.valueOf(name)
    private fun SshExportCopyBackendPolicy.toToken() = SshExportBackendToken.valueOf(name)
    private fun SshExportCopyAuthentication.toToken() = SshExportAuthenticationToken.valueOf(name)
    private fun SshOperationalKeyEntity.providerToken() = if (keyAlias.isNotBlank()) SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED else SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY
    private fun SshProviderRequestKind.toStorage() = StorageSshProviderRequestKind.valueOf(name)
    private fun StorageSshProviderRequestKind.toLegacy() = SshProviderRequestKind.valueOf(name)
    private fun StorageSshProviderRequestState.toLegacy() = SshProviderRequestState.valueOf(
        when (this) {
            StorageSshProviderRequestState.RESPONSE_QUEUED -> "RESPONSE_PENDING_SEND"
            StorageSshProviderRequestState.COMPLETED -> "SENT"
            else -> name
        },
    )
    private fun StorageSshProviderRequestOutcome.toLegacy() = SshProviderRequestOutcome.valueOf(name)
    private fun net.extrawdw.apps.notisync.data.storage.operational.SshProviderAcceptResult.toLegacy() = SshProviderAcceptResult.valueOf(name)
    private fun ProtectedPayload.toResponseEntity(id: String, at: Long) = SshProviderResponseCustodyEntity(id, SshProviderResponsePayloadFormat.BODY, scheme, protectionVersion, keyRef, generation, payloadCodecVersion, ciphertextCopy(), nonceCopy(), at, at)
    private fun SshProviderRequestEntity.historyProtection() = ProtectedPayload.fromStorage(historyProtectionScheme, historyProtectionVersion, historyProtectionGeneration, historyProtectionKeyRef, historyPayloadCodecVersion, historyNonce, historyCiphertext)
    private fun SshProviderPendingPayloadEntity.toProtectedPayload() = ProtectedPayload.fromStorage(protectionScheme, protectionVersion, protectionGeneration, protectionKeyRef, payloadCodecVersion, requestNonce, requestCiphertext)
    private fun SshProviderResponseCustodyEntity.toProtectedPayload() = ProtectedPayload.fromStorage(protectionScheme, protectionVersion, protectionGeneration, protectionKeyRef, payloadCodecVersion, payloadNonce, payloadCiphertext)
    private suspend fun decodeProtected(payload: ProtectedPayload, binding: ProtectedPayloadBinding, generation: Long): ByteArray = protector.open(payload, binding).also { require(payload.generation == generation) }
    private suspend fun pruneExpiredKeys(at: Long) { keyDao.observeKeys().first().filter { it.expiresAt != null && it.expiresAt < at }.forEach { deleteKey(it.providerKeyId) } }
    private suspend fun matchingRememberedAuthorization(request: SshSignRequest): Match? = withContext(ioDispatcher) {
        val key = keyDao.findKeyByPublicHash(sha256(request.publicKeyBlob)) ?: return@withContext null
        val operational = keyDao.findOperationalKey(key.providerKeyId) ?: return@withContext null
        if (
            key.approvalPolicy.toProtocol() != SshApprovalPolicy.ALLOW_REMEMBER ||
            operational.userVerificationPolicy != SshUserVerificationToken.NONE ||
            request.authorizationEpoch <= (
                authorizationDao.findFloor(request.requesterClientId.value, request.authorizationGeneration)
                    ?.invalidatedThroughEpoch ?: Long.MIN_VALUE
                )
        ) return@withContext null
        val candidates = buildList {
            authorizationDao.observeHostAuthorizations().first()
                .filter {
                    it.providerKeyId == key.providerKeyId &&
                        it.requesterClientId == request.requesterClientId.value &&
                        it.authorizationGeneration == request.authorizationGeneration &&
                        it.authorizationEpoch == request.authorizationEpoch
                }
                .forEach { row -> add(Match(row.authorizationId, SshRememberScope.PEER_HOST_KEY, row.hostKeySha256.copyOf())) }
            authorizationDao.observePeerAuthorizations().first()
                .filter {
                    it.providerKeyId == key.providerKeyId &&
                        it.requesterClientId == request.requesterClientId.value &&
                        it.authorizationGeneration == request.authorizationGeneration &&
                        it.authorizationEpoch == request.authorizationEpoch
                }
                .forEach { row -> add(Match(row.authorizationId, SshRememberScope.PEER, null)) }
        }
        candidates.firstOrNull {
            it.scope == SshRememberScope.PEER_HOST_KEY &&
                SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                    it.scope,
                    it.hostKeySha256,
                    request.destinationContext,
                )
        } ?: candidates.firstOrNull { it.scope == SshRememberScope.PEER }
    }

    private suspend fun rememberAuthorization(stored: StoredSshProviderRequest, scope: SshRememberScope, at: Long) {
        val request = stored.signRequest ?: return
        if (scope.authorizationStorage != SshRememberAuthorizationStorage.DISK) return
        val key = keyDao.findKeyByPublicHash(sha256(request.publicKeyBlob)) ?: return
        val host = SshRememberAuthorizationPolicy.hostKeySha256ForPersistentAuthorization(scope, request.destinationContext)
        if (scope == SshRememberScope.PEER_HOST_KEY && host == null) return
        val authorizationId = randomId()
        try {
            when (scope) {
                SshRememberScope.PEER -> authorizationDao.rememberPeer(
                    SshPeerAuthorizationEntity(
                        providerKeyId = key.providerKeyId,
                        requesterClientId = request.requesterClientId.value,
                        authorizationGeneration = request.authorizationGeneration,
                        authorizationEpoch = request.authorizationEpoch,
                        authorizationId = authorizationId,
                        createdAt = at,
                    ),
                )
                SshRememberScope.PEER_HOST_KEY -> authorizationDao.rememberHost(
                    SshKnownHostEntity(host!!, at, at),
                    SshHostAuthorizationEntity(
                        providerKeyId = key.providerKeyId,
                        requesterClientId = request.requesterClientId.value,
                        authorizationGeneration = request.authorizationGeneration,
                        authorizationEpoch = request.authorizationEpoch,
                        hostKeySha256 = host.copyOf(),
                        authorizationId = authorizationId,
                        createdAt = at,
                    ),
                )
                SshRememberScope.APPLICATION_PROCESS -> Unit
            }
        } catch (_: IllegalArgumentException) {
            // Limits, floors, and duplicate tuples are a normal rejected-remember outcome.
        }
    }
    private fun SshImportRequest.requestedAtOrNow() = requestedAt
    private suspend fun importRequest(
        request: SshImportRequest,
        provider: ClientId,
        at: Long,
        allowExport: Boolean,
        policy: SshExportCopyBackendPolicy,
        verification: SshUserVerificationPolicy,
        passphrase: CharArray?,
    ): SshImportAttempt {
        return try {
            val imported = when (request.sourceType) {
                SshImportSourceType.AGENT_IDENTITY -> {
                    val parsed = AgentAddIdentityParser.parse(
                        requireNotNull(request.agentIdentity),
                        constrained = request.constraints != null,
                    )
                    require(parsed.constraints.lifetimeSeconds == request.constraints?.lifetimeSeconds)
                    require(parsed.constraints.confirm == (request.constraints?.confirmationRequired ?: false))
                    ImportedSshMaterial(
                        privateKey = parsed.privateKey,
                        publicKey = parsed.publicKey,
                        publicBlob = parsed.publicKeyBlob,
                        algorithm = parsed.type.toProtocolAlgorithm(),
                        comment = parsed.comment,
                    )
                }
                SshImportSourceType.PRIVATE_KEY_FILE -> {
                    val parsed = SshPrivateKeyFileParser.parse(requireNotNull(request.fileBytes), passphrase)
                    try {
                        ImportedSshMaterial(
                            privateKey = softwarePrivateKey(parsed.algorithm, parsed.pkcs8PrivateKey),
                            publicKey = SshPublicKeyCodec.decode(parsed.publicKeyBlob).publicKey,
                            publicBlob = parsed.publicKeyBlob.copyOf(),
                            algorithm = parsed.algorithm,
                            comment = "",
                        )
                    } finally {
                        parsed.pkcs8PrivateKey.fill(0)
                    }
                }
            }
            val existing = keyDao.findKeyByPublicHash(sha256(imported.publicBlob))
            if (existing != null) {
                SshImportAttempt.Complete(
                    SshImportResult(
                        request.requestId,
                        request.requesterClientId,
                        provider,
                        at,
                        SshImportResultKind.ALREADY_PRESENT,
                        existing.providerKeyId,
                        imported.publicBlob.copyOf(),
                    ),
                )
            } else {
                when (
                    val result = storeSoftware(
                        pair = KeyPair(imported.publicKey, imported.privateKey),
                        algorithm = imported.algorithm,
                        publicBlob = imported.publicBlob,
                        displayName = validateName(request.suggestedName ?: imported.comment.ifBlank { "Imported SSH key" }),
                        origin = if (request.sourceType == SshImportSourceType.AGENT_IDENTITY) SshKeyOrigin.AGENT_ADD else SshKeyOrigin.DATA_SYNC_FILE,
                        now = at,
                        exportPolicy = policy.takeIf { allowExport },
                        userVerificationPolicy = verification,
                    )
                ) {
                    is SshKeyStorageResult.Stored -> SshImportAttempt.Complete(
                        SshImportResult(
                            request.requestId,
                            request.requesterClientId,
                            provider,
                            at,
                            SshImportResultKind.IMPORTED,
                            result.descriptor.providerKeyId,
                            imported.publicBlob.copyOf(),
                        ),
                    )
                    is SshKeyStorageResult.AuthenticationRequired ->
                        SshImportAttempt.AuthenticationRequired(result.prepared, imported.publicBlob.copyOf())
                }
            }
        } catch (failure: Exception) {
            SshImportAttempt.Complete(
                SshImportResult(
                    request.requestId,
                    request.requesterClientId,
                    provider,
                    at,
                    SshImportResultKind.FAILED,
                    message = "SSH identity import failed: ${failure.message.orEmpty()}".take(512),
                ),
            )
        }
    }

    private data class ImportedSshMaterial(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val publicBlob: ByteArray,
        val algorithm: SshKeyAlgorithm,
        val comment: String,
    )

    private sealed interface SshImportAttempt {
        data class Complete(val response: SshImportResult) : SshImportAttempt
        data class AuthenticationRequired(
            val keyStorage: PreparedSshKeyStorage,
            val publicKeyBlob: ByteArray,
        ) : SshImportAttempt
    }

    private data class Match(
        val authorizationId: String,
        val scope: SshRememberScope,
        val hostKeySha256: ByteArray?,
    )

    private data class OperationalKeyCandidate(
        val pair: KeyPair,
        val securityLevel: SshStorageSecurityLevel,
    )

    private fun net.extrawdw.notisync.ssh.core.SshKeyType.toProtocolAlgorithm(): SshKeyAlgorithm = when (this) {
        SshKeyType.ED25519 -> SshKeyAlgorithm.SSH_ED25519
        SshKeyType.RSA -> SshKeyAlgorithm.SSH_RSA
        SshKeyType.ECDSA_NISTP256 -> SshKeyAlgorithm.ECDSA_NISTP256
    }
    private companion object {
        val RANDOM = SecureRandom()
        const val ED25519_OID = "1.3.101.112"
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val CERTIFICATE_CLOCK_SKEW_MILLIS = 5L * 60L * 1_000L
        const val CERTIFICATE_VALIDITY_MILLIS = 20L * 365L * 24L * 60L * 60L * 1_000L
    }
}

private val SOFTWARE_PROVIDER = BouncyCastleProvider()
private val SOFTWARE_RANDOM = SecureRandom()

/** Generates exportable key material through the bundled software provider, never Android Keystore. */
internal fun generateSoftwareSshKeyPair(algorithm: SshKeyAlgorithm, rsaBits: Int): KeyPair = when (algorithm) {
    SshKeyAlgorithm.SSH_ED25519 -> KeyPairGenerator.getInstance("Ed25519", SOFTWARE_PROVIDER).generateKeyPair()
    SshKeyAlgorithm.SSH_RSA -> KeyPairGenerator.getInstance("RSA", SOFTWARE_PROVIDER).apply { initialize(rsaBits, SOFTWARE_RANDOM) }.generateKeyPair()
    SshKeyAlgorithm.ECDSA_NISTP256 -> KeyPairGenerator.getInstance("EC", SOFTWARE_PROVIDER).apply { initialize(ECGenParameterSpec("secp256r1"), SOFTWARE_RANDOM) }.generateKeyPair()
}

/** Frames a JCA signature as the complete SSH signature blob expected by peers and the verifier. */
internal fun encodeSshSignatureForProvider(method: SshSignatureMethod, raw: ByteArray): ByteArray {
    val sshSignature = if (method == SshSignatureMethod.ECDSA_NISTP256) EcdsaSignatureTranscoder.derToSsh(raw) else raw
    return try {
        SshSignatureCodec.encode(method, sshSignature)
    } finally {
        sshSignature.fill(0)
        if (sshSignature !== raw) raw.fill(0)
    }
}
