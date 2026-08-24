package net.extrawdw.apps.notisync.sshagent

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProtection
import android.security.keystore.KeyProperties
import android.util.Log
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
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.cert.Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshExportCopyAuthentication
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshExportCopyProtection
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshImportResultKind
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshAgentLimits
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
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshRememberedNamespace
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.protocol.SshSignatureResult
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserRejection
import net.extrawdw.notisync.protocol.SshUserRejectionReason
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.protocol.SshWebAuthnCredentialProtection
import net.extrawdw.notisync.ssh.core.AgentAddIdentityParser
import net.extrawdw.notisync.ssh.core.EcdsaSignatureTranscoder
import net.extrawdw.notisync.ssh.core.SshFingerprint
import net.extrawdw.notisync.ssh.core.SshKeyType
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

enum class SshProviderRequestState { PENDING_REVIEW, RESPONSE_PENDING_SEND, SENT, CANCELLED, EXPIRED }
enum class SshProviderRequestKind { SIGN, IMPORT }
enum class SshProviderRequestOutcome { SIGNED, IMPORTED, ALREADY_PRESENT, REJECTED, FAILED, CANCELLED, EXPIRED }
enum class SshRequestApprovalKind { MANUAL, REMEMBERED_AUTHORIZATION }
enum class SshProviderAcceptResult {
    STORED,
    DUPLICATE,
    CONFLICT,
    RATE_LIMITED,
    AUTHORIZATION_INVALIDATED,
    KEY_NOT_FOUND,
}

data class SshAuthorizationForgetOutcome(
    val inventoryChanged: Boolean,
    val cancelledRequestIds: List<String>,
)

data class SshWebAuthnRecoverySource(
    val credential: RegisteredSshWebAuthnCredential,
    val displayName: String,
    val createdAt: Long,
)

data class StoredSshProviderRequest(
    val requestId: String,
    val kind: SshProviderRequestKind,
    val requesterClientId: ClientId,
    val requestFingerprint: ByteArray,
    val signRequest: SshSignRequest? = null,
    val importRequest: SshImportRequest? = null,
    val history: SshRequestHistorySnapshot,
    val state: SshProviderRequestState,
    val outcome: SshProviderRequestOutcome? = null,
    val resultAt: Long? = null,
    val encodedResponse: ByteArray? = null,
    val updatedAt: Long,
)

@Serializable
data class SshRequestHistorySnapshot(
    val requestedAt: Long,
    val expiresAt: Long,
    @ByteString val publicKeyBlob: ByteArray? = null,
    val keyName: String? = null,
    val suggestedName: String? = null,
    val importSourceType: SshImportSourceType? = null,
    val encryptedImport: Boolean = false,
    val signatureAlgorithm: SshSignatureAlgorithm? = null,
    val processLineage: List<DesktopProcessIdentity> = emptyList(),
    val destinationUsername: String? = null,
    val destinationHost: String? = null,
    val destinationHostKeyFingerprint: String? = null,
    val payloadSize: Int,
    val approvalKind: SshRequestApprovalKind? = null,
    val rememberedAuthorizationId: String? = null,
    val rememberedScope: SshRememberScope? = null,
)

data class SshKnownHost(
    val hostKeySha256: ByteArray,
    val hostname: String?,
    val firstApprovedAt: Long,
    val lastApprovedAt: Long,
)

data class SshRememberedAuthorization(
    val authorizationId: String,
    val providerKeyId: String,
    val requesterClientId: ClientId,
    val authorizationGeneration: String,
    val authorizationEpoch: Long,
    val scope: SshRememberScope,
    val hostKeySha256: ByteArray?,
    val hostname: String?,
    val createdAt: Long,
)

class PreparedSshSignature internal constructor(
    val requestId: String,
    val requestFingerprint: ByteArray,
    val signature: Signature?,
    val cipher: Cipher?,
    internal val method: SshSignatureMethod,
    internal val operation: PreparedSignatureOperation,
) : AutoCloseable {
    init {
        require((signature == null) != (cipher == null)) { "exactly one authenticated signing operation is required" }
    }

    override fun close() {
        requestFingerprint.fill(0)
        (operation as? PreparedSignatureOperation.Wrapped)?.unwrap?.close()
    }
}

class PreparedSshWebAuthnSignature internal constructor(
    val requestId: String,
    val requestFingerprint: ByteArray,
    val requestJson: String,
    internal val credential: StoredSshWebAuthnCredential,
) : AutoCloseable {
    override fun close() {
        requestFingerprint.fill(0)
    }
}

internal sealed interface PreparedSignatureOperation {
    data object Direct : PreparedSignatureOperation
    data class Wrapped(val unwrap: PreparedWrappedOperationalUnwrap) : PreparedSignatureOperation
}

class PreparedSshKeyExport internal constructor(
    val providerKeyId: String,
    val cipher: Cipher,
    internal val publicHash: ByteArray,
    internal val unwrap: PreparedSshKeyUnwrap,
    internal val securityLevel: SshStorageSecurityLevel,
)

sealed interface SshKeyStorageResult {
    data class Stored(val descriptor: SshKeyDescriptor) : SshKeyStorageResult
    data class AuthenticationRequired(val prepared: PreparedSshKeyStorage) : SshKeyStorageResult
}

class PreparedSshKeyStorage internal constructor(
    val cipher: Cipher?,
    val signature: Signature?,
    /** Bitmask from BiometricManager.Authenticators, never KeyProperties.AUTH_*. */
    val promptAuthenticators: Int,
    internal val owner: SshKeyProviderStore,
    internal val storeResetEpoch: Long,
    internal val provisioning: PendingSshKeyProvisioning,
    internal val stage: PreparedStorageStage,
) {
    init {
        require((cipher == null) != (signature == null)) { "exactly one authenticated operation is required" }
    }
}

data class SshKeyStoreResetResult(
    val removedKeyCount: Int,
    val removedRequestIds: List<String>,
)

sealed interface SshImportApprovalOutcome {
    data object Completed : SshImportApprovalOutcome
    data class AuthenticationRequired(val prepared: PreparedSshImportStorage) : SshImportApprovalOutcome
}

class PreparedSshImportStorage internal constructor(
    val keyStorage: PreparedSshKeyStorage,
    internal val requestId: String,
    internal val requestFingerprint: ByteArray,
    internal val requesterClientId: ClientId,
    internal val publicKeyBlob: ByteArray,
)

internal sealed interface PreparedStorageStage {
    data class OperationalSelfTest(
        val signature: Signature,
        val challenge: ByteArray,
        val publicKey: PublicKey,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class OperationalWrapEncrypt(
        val protection: PreparedWrappedOperationalProtection,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class OperationalWrapDecrypt(
        val unwrap: PreparedWrappedOperationalUnwrap,
        val material: ProtectedSshKeyMaterial,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class ExportEncrypt(
        val protection: PreparedSshKeyProtection,
        val strongBox: Boolean,
    ) : PreparedStorageStage

    data class ExportDecrypt(
        val unwrap: PreparedSshKeyUnwrap,
        val material: ProtectedSshKeyMaterial,
        val strongBox: Boolean,
    ) : PreparedStorageStage
}

internal data class PendingSshKeyRecord(
    val providerKeyId: String,
    val publicBlob: ByteArray,
    val publicHash: ByteArray,
    val algorithm: SshKeyAlgorithm,
    val displayName: String,
    val origin: SshKeyOrigin,
    val operationalProvider: SshOperationalKeyProvider,
    val operationalSecurityLevel: SshStorageSecurityLevel,
    val operationalStrongBoxAttempted: Boolean,
    val operationalStrongBoxFallback: Boolean,
    val userVerificationPolicy: SshUserVerificationPolicy,
    val keyAlias: String,
    val createdAt: Long,
    val expiresAt: Long?,
)

internal class PendingSshKeyProvisioning(
    var record: PendingSshKeyRecord,
    val privateKeyPkcs8: SensitiveBytes?,
    val sourcePublicKey: PublicKey?,
    val exportCopyBackendPolicy: SshExportCopyBackendPolicy?,
    val rsaKeySizeBits: Int,
) : AutoCloseable {
    var wrappedOperationalMaterial: ProtectedSshKeyMaterial? = null
    var exportMaterial: ProtectedSshKeyMaterial? = null
    var exportStrongBoxAttempted: Boolean = false
    var exportStrongBoxFallback: Boolean = false
    var finished: Boolean = false

    override fun close() {
        privateKeyPkcs8?.close()
        wrappedOperationalMaterial?.ciphertext?.fill(0)
        wrappedOperationalMaterial?.nonce?.fill(0)
        wrappedOperationalMaterial = null
        exportMaterial?.ciphertext?.fill(0)
        exportMaterial?.nonce?.fill(0)
        exportMaterial = null
    }
}

internal class SshOperationalCandidateException(
    val strongBox: Boolean,
    cause: Exception,
    val stage: SshOperationalCandidateStage = SshOperationalCandidateStage.OTHER,
) : Exception(
    "Android Keystore could not create the requested SSH operational-key candidate: " + cause.failureSummary(),
    cause,
)

internal enum class SshOperationalCandidateStage {
    DIRECT_PRIVATE_KEY_IMPORT,
    OTHER,
}

internal class SshHardwareBackedKeystoreUnavailableException(keyPurpose: String) :
    IllegalStateException("$keyPurpose is not hardware-backed")

internal fun Throwable.isHardwareBackedSshKeystoreUnavailable(): Boolean =
    generateSequence(this) { it.cause }.any { it is SshHardwareBackedKeystoreUnavailableException }

internal class SshOperationalOperationException(cause: Exception) :
    Exception("Android Keystore SSH signing operation failed: ${cause.failureSummary()}", cause)

/** Durable Android key inventory, pending approvals, and response outbox. */
class SshKeyProviderStore(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        OperationalDatabase.DATABASE_NAME,
        null,
        OperationalDatabase.VERSION,
    ) {
    private val appContext = context.applicationContext
    private val strongBoxAvailable = context.applicationContext.packageManager
        .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val auditWrapping = AndroidKeyWrapping(AUDIT_KEY_ALIAS)
    private val wrappedOperationalVault = SshWrappedOperationalKeyVault(strongBoxAvailable)
    private val exportVault = SshExportKeyVault(strongBoxAvailable)
    private val trustedWebAuthnOrigins by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SshWebAuthnCredentialManager.trustedOrigins(appContext)
    }
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()
    private var resetEpoch = 0L

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase): Nothing = roomMustOwnSshSchema()

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Nothing =
        error("Unsupported SSH key database schema $oldVersion; expected $newVersion. No data was modified.")

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Nothing =
        error("Unsupported SSH key database schema $oldVersion; expected $newVersion. No data was modified.")

    private fun roomMustOwnSshSchema(): Nothing =
        error("The operational Room database must be initialized before opening the SSH key store")

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        validateDatabaseSchema(db)
        repairInventoryGeneration(db)
        reconcileLifecycle(db)
        pruneHistory(db)
    }

    private fun repairInventoryGeneration(db: SQLiteDatabase) {
        val stored = db.rawQuery(
            "SELECT inventory_generation FROM provider_state WHERE singleton=1",
            emptyArray(),
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }
        if (stored == null) {
            val values = ContentValues().apply {
                put("singleton", 1)
                put("inventory_generation", SshInventoryGeneration.create())
                put("revision", 1)
            }
            check(db.insertOrThrow("provider_state", null, values) != -1L) {
                "Could not initialize SSH inventory generation"
            }
            return
        }
        val canonical = SshInventoryGeneration.canonicalize(stored)
        if (canonical == stored) return
        val values = ContentValues().apply { put("inventory_generation", canonical) }
        check(db.update("provider_state", values, "singleton=1", emptyArray()) == 1) {
            "Could not repair SSH inventory generation"
        }
    }

    private fun validateDatabaseSchema(db: SQLiteDatabase) {
        val expectedVersion = OperationalDatabase.VERSION
        check(db.version == expectedVersion) {
            "Unsupported SSH key database schema ${db.version}; expected $expectedVersion. No data was modified."
        }
        val actualTables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name!='android_metadata' ORDER BY name",
            emptyArray(),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val tableSetMatches = actualTables.containsAll(EXPECTED_DATABASE_SCHEMA.keys)
        check(tableSetMatches) {
            "SSH key database schema ${OperationalDatabase.VERSION} has an unexpected table set. " +
                "No data was modified."
        }
        EXPECTED_DATABASE_SCHEMA.forEach { (table, expectedColumns) ->
            val actualColumns = db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            check(actualColumns == expectedColumns) {
                "SSH key database schema ${OperationalDatabase.VERSION} has incompatible columns in $table. " +
                    "No data was modified."
            }
        }
        val integrity = db.rawQuery("PRAGMA quick_check(1)", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
        check(integrity == "ok") { "SSH key database integrity check failed: $integrity" }
        db.rawQuery("PRAGMA foreign_key_check", emptyArray()).use { cursor ->
            check(!cursor.moveToFirst()) { "SSH key database contains foreign-key violations" }
        }
    }

    @Synchronized
    fun snapshot(provider: ClientId, respondingToRequestId: String?, now: Long): SshKeysSnapshot {
        pruneExpiredKeys(now)
        val remembered = rememberedNamespaces()
        val state = readableDatabase.rawQuery(
            "SELECT inventory_generation, revision FROM provider_state WHERE singleton=1",
            emptyArray(),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0) to cursor.getLong(1)
        }
        val keys = readableDatabase.rawQuery(
            "SELECT k.provider_key_id, k.public_blob, k.public_hash, k.algorithm, k.display_name, k.origin, " +
                "o.provider_kind, o.security_level, o.user_verification_policy, o.strongbox_attempted, " +
                "o.strongbox_fallback, " +
                "e.security_level, e.backend_policy, e.authentication, e.strongbox_attempted, " +
                "e.strongbox_fallback, k.approval_policy, k.created_at, " +
                "w.rp_id, w.backup_eligible, w.backup_state " +
                "FROM ssh_keys k JOIN ssh_operational_keys o ON o.provider_key_id=k.provider_key_id " +
                "LEFT JOIN ssh_export_copies e ON e.provider_key_id=k.provider_key_id " +
                "LEFT JOIN ssh_webauthn_credentials w ON w.provider_key_id=k.provider_key_id " +
                "ORDER BY k.provider_key_id",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SshKeyDescriptor(
                            providerKeyId = cursor.getString(0),
                            publicKeyBlob = cursor.getBlob(1),
                            publicKeyBlobSha256 = cursor.getBlob(2),
                            algorithm = SshKeyAlgorithm.valueOf(cursor.getString(3)),
                            displayName = cursor.getString(4),
                            origin = SshKeyOrigin.valueOf(cursor.getString(5)),
                            operationalKey = SshOperationalKeyProtection(
                                provider = SshOperationalKeyProvider.valueOf(cursor.getString(6)),
                                securityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(7)),
                                userVerificationPolicy = SshUserVerificationPolicy.valueOf(cursor.getString(8)),
                                strongBoxAttempted = cursor.getInt(9) != 0,
                                strongBoxFallback = cursor.getInt(10) != 0,
                            ),
                            exportCopy = if (cursor.isNull(11)) {
                                null
                            } else {
                                SshExportCopyProtection(
                                    securityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(11)),
                                    backendPolicy = SshExportCopyBackendPolicy.valueOf(cursor.getString(12)),
                                    authentication = SshExportCopyAuthentication.valueOf(cursor.getString(13)),
                                    strongBoxAttempted = cursor.getInt(14) != 0,
                                    strongBoxFallback = cursor.getInt(15) != 0,
                                )
                            },
                            approvalPolicy = SshApprovalPolicy.valueOf(cursor.getString(16)),
                            rememberedNamespaces = remembered[cursor.getString(0)].orEmpty(),
                            createdAt = cursor.getLong(17),
                            webAuthn = if (cursor.isNull(18)) null else SshWebAuthnCredentialProtection(
                                rpId = cursor.getString(18),
                                backupEligible = cursor.getInt(19) != 0,
                                backupState = cursor.getInt(20) != 0,
                            ),
                        ),
                    )
                }
            }
        }
        return SshKeysSnapshot(provider, state.first, state.second, now, respondingToRequestId, keys, SshProviderHealth.HEALTHY)
    }

    @Synchronized
    fun knownHosts(): List<SshKnownHost> = readableDatabase.rawQuery(
        "SELECT host_key_sha256, hostname, first_approved_at, last_approved_at FROM ssh_known_hosts " +
            "ORDER BY hostname IS NULL, hostname COLLATE NOCASE, last_approved_at DESC",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SshKnownHost(
                        hostKeySha256 = cursor.getBlob(0),
                        hostname = if (cursor.isNull(1)) null else cursor.getString(1),
                        firstApprovedAt = cursor.getLong(2),
                        lastApprovedAt = cursor.getLong(3),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun knownHostHostname(hostKeySha256: ByteArray): String? {
        require(hostKeySha256.size == SshAgentLimits.DIGEST_BYTES) { "invalid SSH host-key fingerprint" }
        return readableDatabase.rawQuery(
            "SELECT hostname FROM ssh_known_hosts WHERE hex(host_key_sha256)=?",
            arrayOf(hostKeySha256.toHex().uppercase()),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
        }
    }

    @Synchronized
    fun knownHostHostname(destination: net.extrawdw.notisync.protocol.SshDestinationContext): String? =
        SshRememberAuthorizationPolicy.verifiedHostKeySha256(destination)?.let(::knownHostHostname)

    @Synchronized
    fun updateKnownHostHostname(hostKeySha256: ByteArray, hostname: String): Boolean {
        require(hostKeySha256.size == SshAgentLimits.DIGEST_BYTES) { "invalid SSH host-key fingerprint" }
        val values = ContentValues().apply {
            if (hostname.isBlank()) putNull("hostname") else put("hostname", hostname)
        }
        val changed = writableDatabase.update(
            "ssh_known_hosts",
            values,
            "hex(host_key_sha256)=?",
            arrayOf(hostKeySha256.toHex().uppercase()),
        ) == 1
        if (changed) notifyChanged()
        return changed
    }

    @Synchronized
    fun deleteKnownHost(hostKeySha256: ByteArray): Boolean {
        require(hostKeySha256.size == SshAgentLimits.DIGEST_BYTES) { "invalid SSH host-key fingerprint" }
        val changed = writableDatabase.delete(
            "ssh_known_hosts",
            "hex(host_key_sha256)=?",
            arrayOf(hostKeySha256.toHex().uppercase()),
        ) == 1
        if (changed) notifyChanged()
        return changed
    }

    @Synchronized
    fun rememberedAuthorizations(): List<SshRememberedAuthorization> = readableDatabase.rawQuery(
        "SELECT a.authorization_id, a.provider_key_id, a.requester_client_id, " +
            "a.authorization_generation, a.authorization_epoch, a.scope, a.host_key_sha256, " +
            "h.hostname, a.created_at FROM ssh_remembered_authorizations a " +
            "LEFT JOIN ssh_known_hosts h ON h.host_key_sha256=a.host_key_sha256 " +
            "ORDER BY a.provider_key_id, a.created_at DESC, a.authorization_id",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SshRememberedAuthorization(
                        authorizationId = cursor.getString(0),
                        providerKeyId = cursor.getString(1),
                        requesterClientId = ClientId(cursor.getString(2)),
                        authorizationGeneration = cursor.getString(3),
                        authorizationEpoch = cursor.getLong(4),
                        scope = SshRememberScope.valueOf(cursor.getString(5)),
                        hostKeySha256 = if (cursor.isNull(6)) null else cursor.getBlob(6),
                        hostname = if (cursor.isNull(7)) null else cursor.getString(7),
                        createdAt = cursor.getLong(8),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun deleteRememberedAuthorization(authorizationId: String): Boolean {
        require(authorizationId.isNotBlank()) { "authorization id must not be blank" }
        val database = writableDatabase
        database.beginTransaction()
        val changed = try {
            val removed = database.delete(
                "ssh_remembered_authorizations",
                "authorization_id=?",
                arrayOf(authorizationId),
            ) == 1
            if (removed) bumpRevision(database)
            database.setTransactionSuccessful()
            removed
        } finally {
            database.endTransaction()
        }
        if (changed) notifyChanged()
        return changed
    }

    @Synchronized
    fun webAuthnCredentialIds(): List<ByteArray> = readableDatabase.rawQuery(
        "SELECT credential_id FROM ssh_webauthn_credentials ORDER BY provider_key_id",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) } }

    @Synchronized
    fun storeWebAuthnCredential(
        credential: RegisteredSshWebAuthnCredential,
        displayName: String,
        now: Long,
        origin: SshKeyOrigin = SshKeyOrigin.WEBAUTHN_CREATED,
    ): SshKeyDescriptor {
        require(now > 0) { "credential creation time must be positive" }
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        val decoded = SshPublicKeyCodec.decode(credential.publicKeyBlob)
        require(decoded.type == SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 && decoded.application == credential.rpId) {
            "WebAuthn credential does not contain the expected SSH ECDSA-SK public key"
        }
        require(credential.rpId == SshWebAuthnCredential.RP_ID) { "unsupported WebAuthn RP ID" }
        require(credential.credentialId.isNotEmpty() && credential.credentialId.size <= 1024) {
            "WebAuthn credential ID is outside the allowed bounds"
        }
        require(credential.userHandle.isNotEmpty() && credential.userHandle.size <= 64 && credential.cosePublicKey.isNotEmpty()) {
            "WebAuthn public metadata is outside the allowed bounds"
        }
        SshWebAuthnCredential.passwordRecordId(credential.userHandle)
        require(credential.createdOrigin in trustedWebAuthnOrigins) { "WebAuthn origin is not trusted" }
        require(!credential.backupState || credential.backupEligible) {
            "WebAuthn backup state requires backup eligibility"
        }
        require(origin in setOf(SshKeyOrigin.WEBAUTHN_CREATED, SshKeyOrigin.WEBAUTHN_RECOVERED)) {
            "WebAuthn SSH key has an invalid origin"
        }
        val publicHash = sha256(credential.publicKeyBlob)
        check(findKeyId(publicHash) == null) { "WebAuthn SSH key already exists" }
        val providerKeyId = randomId()
        val operationalAlias = WEBAUTHN_ALIAS_PREFIX + providerKeyId
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.insertOrThrow("ssh_keys", null, ContentValues().apply {
                put("provider_key_id", providerKeyId)
                put("public_blob", credential.publicKeyBlob)
                put("public_hash", publicHash)
                put("algorithm", SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256.name)
                put("display_name", name)
                put("origin", origin.name)
                put("approval_policy", SshApprovalPolicy.ALWAYS_ASK.name)
                put("created_at", now)
            })
            database.insertOrThrow("ssh_operational_keys", null, ContentValues().apply {
                put("provider_key_id", providerKeyId)
                put("provider_kind", SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN.name)
                put("key_alias", operationalAlias)
                put("security_level", SshStorageSecurityLevel.CREDENTIAL_PROVIDER.name)
                put("user_verification_policy", SshUserVerificationPolicy.PER_USE.name)
                put("strongbox_attempted", false)
                put("strongbox_fallback", false)
            })
            database.insertOrThrow("ssh_webauthn_credentials", null, ContentValues().apply {
                put("provider_key_id", providerKeyId)
                put("credential_id", credential.credentialId)
                put("user_handle", credential.userHandle)
                put("rp_id", credential.rpId)
                put("cose_public_key", credential.cosePublicKey)
                put("created_origin", credential.createdOrigin)
                put("backup_eligible", credential.backupEligible)
                put("backup_state", credential.backupState)
            })
            bumpRevision(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        notifyChanged()
        return SshKeyDescriptor(
            providerKeyId = providerKeyId,
            publicKeyBlob = credential.publicKeyBlob.copyOf(),
            publicKeyBlobSha256 = publicHash,
            algorithm = SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256,
            displayName = name,
            origin = origin,
            operationalKey = SshOperationalKeyProtection(
                provider = SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN,
                securityLevel = SshStorageSecurityLevel.CREDENTIAL_PROVIDER,
                userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
                strongBoxAttempted = false,
                strongBoxFallback = false,
            ),
            exportCopy = null,
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            createdAt = now,
            webAuthn = SshWebAuthnCredentialProtection(
                credential.rpId,
                credential.backupEligible,
                credential.backupState,
            ),
        ).also { check(it.validationError(::sha256) == null) }
    }

    @Synchronized
    fun webAuthnRecoverySource(providerKeyId: String): SshWebAuthnRecoverySource? = readableDatabase.rawQuery(
        "SELECT k.public_blob, k.display_name, k.created_at, w.credential_id, w.user_handle, " +
            "w.rp_id, w.cose_public_key, w.created_origin, w.backup_eligible, w.backup_state " +
            "FROM ssh_keys k JOIN ssh_webauthn_credentials w ON w.provider_key_id=k.provider_key_id " +
            "WHERE k.provider_key_id=?",
        arrayOf(providerKeyId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            SshWebAuthnRecoverySource(
                credential = RegisteredSshWebAuthnCredential(
                    publicKeyBlob = cursor.getBlob(0),
                    credentialId = cursor.getBlob(3),
                    userHandle = cursor.getBlob(4),
                    rpId = cursor.getString(5),
                    cosePublicKey = cursor.getBlob(6),
                    createdOrigin = cursor.getString(7),
                    backupEligible = cursor.getInt(8) != 0,
                    backupState = cursor.getInt(9) != 0,
                ),
                displayName = cursor.getString(1),
                createdAt = cursor.getLong(2),
            )
        }
    }

    @Synchronized
    fun generateKey(
        algorithm: SshKeyAlgorithm,
        displayName: String,
        now: Long,
        allowExport: Boolean = false,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
        userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
        rsaKeySizeBits: Int = DEFAULT_RSA_KEY_SIZE_BITS,
    ): SshKeyStorageResult {
        require(now > 0) { "key creation time must be positive" }
        require(algorithm != SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256) {
            "WebAuthn SSH keys must be created through Credential Manager"
        }
        require(algorithm != SshKeyAlgorithm.SSH_RSA || rsaKeySizeBits in SUPPORTED_RSA_KEY_SIZE_BITS) {
            "unsupported RSA key size"
        }
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        return if (allowExport) {
            generateSoftwareKey(
                algorithm,
                name,
                now,
                rsaKeySizeBits,
                exportCopyBackendPolicy,
                userVerificationPolicy = userVerificationPolicy,
            )
        } else {
            generateAndroidKeyStoreKey(
                algorithm,
                name,
                now,
                rsaKeySizeBits,
                userVerificationPolicy,
            )
        }
    }

    private fun generateSoftwareKey(
        algorithm: SshKeyAlgorithm,
        name: String,
        now: Long,
        rsaKeySizeBits: Int,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyStorageResult {
        val pair = when (algorithm) {
            SshKeyAlgorithm.SSH_ED25519 ->
                KeyPairGenerator.getInstance("Ed25519", BOUNCY_CASTLE).generateKeyPair()
            SshKeyAlgorithm.SSH_RSA -> KeyPairGenerator.getInstance("RSA").run {
                initialize(rsaKeySizeBits)
                generateKeyPair()
            }
            SshKeyAlgorithm.ECDSA_NISTP256 -> KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
            SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
                error("WebAuthn SSH keys must be created through Credential Manager")
        }
        return storeImportedKey(
            privateKey = pair.private,
            publicKey = pair.public,
            publicBlob = SshPublicKeyCodec.encode(pair.public, algorithm.toCoreType()),
            algorithm = algorithm,
            displayName = name,
            origin = SshKeyOrigin.GENERATED,
            exportCopyBackendPolicy = exportCopyBackendPolicy,
            userVerificationPolicy = userVerificationPolicy,
            createdAt = now,
            expiresAt = null,
        )
    }

    private fun generateAndroidKeyStoreKey(
        algorithm: SshKeyAlgorithm,
        name: String,
        now: Long,
        rsaKeySizeBits: Int,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyStorageResult {
        val keyId = randomId()
        val alias = KEY_ALIAS_PREFIX + keyId
        beginLifecycle(keyId, alias, now)
        try {
            val strongBoxAttempted = shouldAttemptOperationalStrongBox(algorithm)
            var strongBoxFallback = false
            val pair = try {
                generateOperationalKeyPair(algorithm, alias, strongBoxAttempted, userVerificationPolicy, rsaKeySizeBits)
            } catch (failure: SshOperationalCandidateException) {
                if (!strongBoxAttempted || !failure.strongBox) throw failure
                deleteAndroidKeyStoreAlias(alias)
                strongBoxFallback = true
                generateOperationalKeyPair(algorithm, alias, false, userVerificationPolicy, rsaKeySizeBits)
            }
            val publicBlob = SshPublicKeyCodec.encode(pair.public, algorithm.toCoreType())
            val hash = sha256(publicBlob)
            check(findKeyId(hash) == null) { "generated SSH key already exists" }
            val securityLevel = inspectKeyInfo(pair.private, algorithm, userVerificationPolicy)
            val provisioning = PendingSshKeyProvisioning(
                record = PendingSshKeyRecord(
                    providerKeyId = keyId,
                    publicBlob = publicBlob,
                    publicHash = hash,
                    algorithm = algorithm,
                    displayName = name,
                    origin = SshKeyOrigin.GENERATED,
                    operationalProvider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                    operationalSecurityLevel = securityLevel,
                    operationalStrongBoxAttempted = strongBoxAttempted,
                    operationalStrongBoxFallback = strongBoxFallback,
                    userVerificationPolicy = userVerificationPolicy,
                    keyAlias = alias,
                    createdAt = now,
                    expiresAt = null,
                ),
                privateKeyPkcs8 = null,
                sourcePublicKey = null,
                exportCopyBackendPolicy = null,
                rsaKeySizeBits = rsaKeySizeBits,
            )
            return validateOperationalOrContinue(provisioning, pair.public)
        } catch (failure: Exception) {
            abortProvisioning(keyId, alias, null)
            throw failure
        }
    }

    @Synchronized
    fun importPrivateKeyFile(
        fileBytes: ByteArray,
        passphrase: CharArray?,
        displayName: String,
        now: Long,
        allowExport: Boolean = true,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy = SshExportCopyBackendPolicy.BEST_AVAILABLE,
        userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
    ): SshKeyStorageResult {
        require(now > 0) { "key import time must be positive" }
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        val parsed = SshPrivateKeyFileParser.parse(fileBytes, passphrase)
        return try {
            val publicKey = SshPublicKeyCodec.decode(parsed.publicKeyBlob).publicKey
            val privateKey = softwarePrivateKey(parsed.algorithm, parsed.pkcs8PrivateKey)
            storeImportedKey(
                privateKey = privateKey,
                publicKey = publicKey,
                publicBlob = parsed.publicKeyBlob,
                algorithm = parsed.algorithm,
                displayName = name,
                origin = SshKeyOrigin.SAF_IMPORT,
                exportCopyBackendPolicy = exportCopyBackendPolicy.takeIf { allowExport },
                userVerificationPolicy = userVerificationPolicy,
                createdAt = now,
                expiresAt = null,
            )
        } finally {
            parsed.pkcs8PrivateKey.fill(0)
        }
    }

    @Synchronized
    fun prepareExport(providerKeyId: String): PreparedSshKeyExport? {
        val row = readableDatabase.rawQuery(
            "SELECT k.public_hash, k.algorithm, e.ciphertext, e.nonce, e.security_level " +
                "FROM ssh_keys k JOIN ssh_export_copies e ON e.provider_key_id=k.provider_key_id " +
                "WHERE k.provider_key_id=?",
            arrayOf(providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            ExportMaterial(
                cursor.getBlob(0),
                SshKeyAlgorithm.valueOf(cursor.getString(1)),
                cursor.getBlob(2),
                cursor.getBlob(3),
                SshStorageSecurityLevel.valueOf(cursor.getString(4)),
            )
        }
        val prepared = exportVault.prepareUnwrap(
            providerKeyId,
            row.ciphertext,
            row.nonce,
            row.algorithm,
            row.publicHash,
            row.securityLevel,
        )
        return PreparedSshKeyExport(
            providerKeyId,
            prepared.cipher,
            row.publicHash.copyOf(),
            prepared,
            row.securityLevel,
        )
    }

    @Synchronized
    fun completeExport(prepared: PreparedSshKeyExport, authenticatedCipher: Cipher): ByteArray? {
        if (authenticatedCipher !== prepared.cipher) {
            cancelExport(prepared)
            return null
        }
        val current = readableDatabase.rawQuery(
            "SELECT k.public_hash, e.security_level FROM ssh_keys k " +
                "JOIN ssh_export_copies e ON e.provider_key_id=k.provider_key_id WHERE k.provider_key_id=?",
            arrayOf(prepared.providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getBlob(0) to SshStorageSecurityLevel.valueOf(cursor.getString(1))
        }
        if (current == null || !MessageDigest.isEqual(current.first, prepared.publicHash) ||
            current.second != prepared.securityLevel
        ) {
            cancelExport(prepared)
            return null
        }
        return try {
            exportVault.completeUnwrap(prepared.unwrap, authenticatedCipher).take()
        } finally {
            prepared.publicHash.fill(0)
        }
    }

    @Synchronized
    fun cancelExport(prepared: PreparedSshKeyExport) {
        prepared.unwrap.close()
        prepared.publicHash.fill(0)
    }

    private fun storeImportedKey(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        publicBlob: ByteArray,
        algorithm: SshKeyAlgorithm,
        displayName: String,
        origin: SshKeyOrigin,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy?,
        userVerificationPolicy: SshUserVerificationPolicy,
        createdAt: Long,
        expiresAt: Long?,
    ): SshKeyStorageResult {
        val hash = sha256(publicBlob)
        check(findKeyId(hash) == null) { "This SSH key is already present" }
        val keyId = randomId()
        val alias = KEY_ALIAS_PREFIX + keyId
        val encoded = SensitiveBytes.takeOwnership(
            requireNotNull(privateKey.encoded) { "Imported private key cannot be encoded" },
        )
        try {
            selfTestSoftware(privateKey, publicKey, algorithm)
            beginLifecycle(keyId, alias, createdAt)
            val strongBoxAttempted = shouldAttemptOperationalStrongBox(algorithm)
            var strongBoxFallback = false
            val securityLevel = try {
                installOperationalKey(
                    alias,
                    encoded.bytes,
                    publicKey,
                    algorithm,
                    createdAt,
                    strongBoxAttempted,
                    userVerificationPolicy,
                )
            } catch (failure: SshOperationalCandidateException) {
                if (strongBoxAttempted && failure.strongBox) {
                    deleteAndroidKeyStoreAlias(alias)
                    strongBoxFallback = true
                    installOperationalKey(
                        alias,
                        encoded.bytes,
                        publicKey,
                        algorithm,
                        createdAt,
                        false,
                        userVerificationPolicy,
                    )
                } else if (
                    failure.stage == SshOperationalCandidateStage.DIRECT_PRIVATE_KEY_IMPORT &&
                    SshKeyStoragePolicy.shouldUseWrappedOperationalFallback(algorithm)
                ) {
                    deleteAndroidKeyStoreAlias(alias)
                    val attemptWrappedStrongBox = wrappedOperationalVault.shouldAttemptStrongBox()
                    val provisioning = PendingSshKeyProvisioning(
                        record = PendingSshKeyRecord(
                            providerKeyId = keyId,
                            publicBlob = publicBlob.copyOf(),
                            publicHash = hash,
                            algorithm = algorithm,
                            displayName = displayName,
                            origin = origin,
                            operationalProvider = SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED,
                            operationalSecurityLevel = if (attemptWrappedStrongBox) {
                                SshStorageSecurityLevel.STRONGBOX
                            } else {
                                SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
                            },
                            operationalStrongBoxAttempted = attemptWrappedStrongBox,
                            operationalStrongBoxFallback = false,
                            userVerificationPolicy = userVerificationPolicy,
                            keyAlias = alias,
                            createdAt = createdAt,
                            expiresAt = expiresAt,
                        ),
                        privateKeyPkcs8 = encoded,
                        sourcePublicKey = publicKey,
                        exportCopyBackendPolicy = exportCopyBackendPolicy,
                        rsaKeySizeBits = DEFAULT_RSA_KEY_SIZE_BITS,
                    )
                    return prepareWrappedOperationalEncryption(provisioning, attemptWrappedStrongBox)
                } else {
                    throw failure
                }
            }
            val provisioning = PendingSshKeyProvisioning(
                record = PendingSshKeyRecord(
                    providerKeyId = keyId,
                    publicBlob = publicBlob.copyOf(),
                    publicHash = hash,
                    algorithm = algorithm,
                    displayName = displayName,
                    origin = origin,
                    operationalProvider = SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY,
                    operationalSecurityLevel = securityLevel,
                    operationalStrongBoxAttempted = strongBoxAttempted,
                    operationalStrongBoxFallback = strongBoxFallback,
                    userVerificationPolicy = userVerificationPolicy,
                    keyAlias = alias,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                ),
                privateKeyPkcs8 = encoded,
                sourcePublicKey = publicKey,
                exportCopyBackendPolicy = exportCopyBackendPolicy,
                rsaKeySizeBits = (publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength()
                    ?: DEFAULT_RSA_KEY_SIZE_BITS,
            )
            return validateOperationalOrContinue(provisioning, publicKey)
        } catch (failure: Exception) {
            encoded.close()
            abortProvisioning(keyId, alias, null)
            throw failure
        }
    }

    @Synchronized
    fun completePreparedKeyStorage(
        prepared: PreparedSshKeyStorage,
        authenticatedCipher: Cipher? = null,
        authenticatedSignature: Signature? = null,
    ): SshKeyStorageResult {
        require(prepared.owner === this) { "SSH key storage operation belongs to another provider" }
        if (prepared.storeResetEpoch != resetEpoch) {
            cancelPreparedKeyStorage(prepared)
            error("SSH key store was reset while key storage was awaiting authentication")
        }
        check(!prepared.provisioning.finished) { "SSH key provisioning has already finished" }
        return try {
            when (val stage = prepared.stage) {
                is PreparedStorageStage.OperationalSelfTest -> completeOperationalSelfTest(
                    prepared.provisioning,
                    stage,
                    requireNotNull(authenticatedSignature) { "authenticated SSH signing operation is required" },
                )
                is PreparedStorageStage.OperationalWrapEncrypt -> completeWrappedOperationalEncryption(
                    prepared.provisioning,
                    stage,
                    requireNotNull(authenticatedCipher) { "authenticated SSH operational encryption is required" },
                )
                is PreparedStorageStage.OperationalWrapDecrypt -> completeWrappedOperationalValidation(
                    prepared.provisioning,
                    stage,
                    requireNotNull(authenticatedCipher) { "authenticated SSH operational decryption is required" },
                )
                is PreparedStorageStage.ExportEncrypt -> completeExportEncryption(
                    prepared.provisioning,
                    stage,
                    requireNotNull(authenticatedCipher) { "authenticated SSH export encryption is required" },
                )
                is PreparedStorageStage.ExportDecrypt -> completeExportValidation(
                    prepared.provisioning,
                    stage,
                    requireNotNull(authenticatedCipher) { "authenticated SSH export decryption is required" },
                )
            }
        } catch (failure: Exception) {
            abortProvisioning(prepared.provisioning)
            throw failure
        }
    }

    @Synchronized
    fun cancelPreparedKeyStorage(prepared: PreparedSshKeyStorage) {
        require(prepared.owner === this) { "SSH key storage operation belongs to another provider" }
        val provisioning = prepared.provisioning
        if (provisioning.finished) return
        when (val stage = prepared.stage) {
            is PreparedStorageStage.OperationalSelfTest -> stage.challenge.fill(0)
            is PreparedStorageStage.OperationalWrapEncrypt -> stage.protection.close()
            is PreparedStorageStage.OperationalWrapDecrypt -> stage.unwrap.close()
            is PreparedStorageStage.ExportEncrypt -> stage.protection.close()
            is PreparedStorageStage.ExportDecrypt -> stage.unwrap.close()
        }
        abortProvisioning(provisioning)
    }

    private fun commitProvisioning(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult.Stored {
        val record = provisioning.record
        val material = provisioning.exportMaterial
        val database = writableDatabase
        try {
            database.beginTransaction()
            try {
                database.insertOrThrow("ssh_keys", null, ContentValues().apply {
                    put("provider_key_id", record.providerKeyId)
                    put("public_blob", record.publicBlob)
                    put("public_hash", record.publicHash)
                    put("algorithm", record.algorithm.name)
                    put("display_name", record.displayName)
                    put("origin", record.origin.name)
                    put("approval_policy", SshApprovalPolicy.ALWAYS_ASK.name)
                    put("created_at", record.createdAt)
                    if (record.expiresAt != null) put("expires_at", record.expiresAt)
                })
                database.insertOrThrow("ssh_operational_keys", null, ContentValues().apply {
                    put("provider_key_id", record.providerKeyId)
                    put("provider_kind", record.operationalProvider.name)
                    put("key_alias", record.keyAlias)
                    provisioning.wrappedOperationalMaterial?.let {
                        put("ciphertext", it.ciphertext)
                        put("nonce", it.nonce)
                    }
                    put("security_level", record.operationalSecurityLevel.name)
                    put("user_verification_policy", record.userVerificationPolicy.name)
                    put("strongbox_attempted", record.operationalStrongBoxAttempted)
                    put("strongbox_fallback", record.operationalStrongBoxFallback)
                })
                if (material != null) {
                    val policy = requireNotNull(provisioning.exportCopyBackendPolicy)
                    database.insertOrThrow("ssh_export_copies", null, ContentValues().apply {
                        put("provider_key_id", record.providerKeyId)
                        put("key_alias", exportVault.alias(record.providerKeyId, material.securityLevel == SshStorageSecurityLevel.STRONGBOX))
                        put("ciphertext", material.ciphertext)
                        put("nonce", material.nonce)
                        put("security_level", material.securityLevel.name)
                        put("backend_policy", policy.name)
                        put("authentication", SshExportCopyAuthentication.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE.name)
                        put("strongbox_attempted", provisioning.exportStrongBoxAttempted)
                        put("strongbox_fallback", provisioning.exportStrongBoxFallback)
                        put("last_verified_at", System.currentTimeMillis())
                    })
                }
                database.delete("ssh_key_lifecycle", "provider_key_id=?", arrayOf(record.providerKeyId))
                bumpRevision(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (failure: Exception) {
            runCatching { abortProvisioning(provisioning) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val descriptor = descriptorFor(provisioning)
        provisioning.finished = true
        provisioning.close()
        notifyChanged()
        return SshKeyStorageResult.Stored(descriptor)
    }

    private fun descriptorFor(provisioning: PendingSshKeyProvisioning): SshKeyDescriptor {
        val record = provisioning.record
        val material = provisioning.exportMaterial
        return SshKeyDescriptor(
            providerKeyId = record.providerKeyId,
            publicKeyBlob = record.publicBlob,
            publicKeyBlobSha256 = record.publicHash,
            algorithm = record.algorithm,
            displayName = record.displayName,
            origin = record.origin,
            operationalKey = SshOperationalKeyProtection(
                provider = record.operationalProvider,
                securityLevel = record.operationalSecurityLevel,
                userVerificationPolicy = record.userVerificationPolicy,
                strongBoxAttempted = record.operationalStrongBoxAttempted,
                strongBoxFallback = record.operationalStrongBoxFallback,
            ),
            exportCopy = material?.let {
                SshExportCopyProtection(
                    securityLevel = it.securityLevel,
                    backendPolicy = requireNotNull(provisioning.exportCopyBackendPolicy),
                    authentication = SshExportCopyAuthentication.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE,
                    strongBoxAttempted = provisioning.exportStrongBoxAttempted,
                    strongBoxFallback = provisioning.exportStrongBoxFallback,
                )
            },
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            createdAt = record.createdAt,
        )
    }

    private fun validateOperationalOrContinue(
        provisioning: PendingSshKeyProvisioning,
        publicKey: PublicKey,
    ): SshKeyStorageResult {
        val record = provisioning.record
        val privateKey = loadOperationalPrivateKey(record.keyAlias)
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            val challenge = ByteArray(32).also(RANDOM::nextBytes)
            val signature = try {
                SshKeystoreJca.signature(record.algorithm.selfTestSignatureAlgorithm()).apply {
                    initSign(privateKey)
                }
            } catch (failure: Exception) {
                challenge.fill(0)
                if (record.operationalSecurityLevel == SshStorageSecurityLevel.STRONGBOX) {
                    return retryOperationalInTee(provisioning)
                }
                throw SshOperationalOperationException(failure)
            }
            authenticationRequired(
                provisioning,
                PreparedStorageStage.OperationalSelfTest(signature, challenge, publicKey, record.operationalSecurityLevel == SshStorageSecurityLevel.STRONGBOX),
                signature = signature,
                promptAuthenticators = SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            )
        } else {
            try {
                selfTest(privateKey, publicKey, record.algorithm)
            } catch (failure: SshOperationalOperationException) {
                if (record.operationalSecurityLevel == SshStorageSecurityLevel.STRONGBOX) {
                    return retryOperationalInTee(provisioning)
                }
                throw failure
            }
            continueAfterOperationalValidation(provisioning)
        }
    }

    private fun prepareWrappedOperationalEncryption(
        provisioning: PendingSshKeyProvisioning,
        strongBox: Boolean,
    ): SshKeyStorageResult {
        val record = provisioning.record
        check(record.operationalProvider == SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED)
        val protection = try {
            wrappedOperationalVault.prepareProtect(
                alias = record.keyAlias,
                providerKeyId = record.providerKeyId,
                privateKeyPkcs8 = requireNotNull(provisioning.privateKeyPkcs8),
                algorithm = record.algorithm,
                publicKeyHash = record.publicHash,
                strongBox = strongBox,
                userVerificationPolicy = record.userVerificationPolicy,
            )
        } catch (failure: SshOperationalCandidateException) {
            if (strongBox && failure.strongBox) return retryWrappedOperationalInTee(provisioning)
            throw failure
        }
        provisioning.record = record.copy(operationalSecurityLevel = protection.securityLevel)
        val stage = PreparedStorageStage.OperationalWrapEncrypt(protection, strongBox)
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            authenticationRequired(
                provisioning,
                stage,
                cipher = protection.cipher,
                promptAuthenticators = SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            )
        } else {
            completeWrappedOperationalEncryption(provisioning, stage, protection.cipher)
        }
    }

    private fun completeWrappedOperationalEncryption(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalWrapEncrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val material = try {
            wrappedOperationalVault.completeProtect(stage.protection, authenticatedCipher)
        } catch (failure: SshWrappedOperationalOperationException) {
            if (stage.strongBox && failure.strongBox) return retryWrappedOperationalInTee(provisioning)
            throw failure
        }
        persistWrappedOperationalCandidate(provisioning.record.providerKeyId, material)
        material.ciphertext.fill(0)
        material.nonce.fill(0)
        val persisted = loadPersistedWrappedOperationalCandidate(provisioning.record.providerKeyId)
        provisioning.wrappedOperationalMaterial = persisted
        return prepareWrappedOperationalValidation(provisioning, persisted, stage.strongBox)
    }

    private fun prepareWrappedOperationalValidation(
        provisioning: PendingSshKeyProvisioning,
        material: ProtectedSshKeyMaterial,
        strongBox: Boolean,
    ): SshKeyStorageResult {
        val record = provisioning.record
        val unwrap = try {
            wrappedOperationalVault.prepareUnwrap(
                alias = record.keyAlias,
                providerKeyId = record.providerKeyId,
                ciphertext = material.ciphertext,
                nonce = material.nonce,
                algorithm = record.algorithm,
                publicKeyHash = record.publicHash,
                securityLevel = material.securityLevel,
                userVerificationPolicy = record.userVerificationPolicy,
            )
        } catch (failure: SshOperationalCandidateException) {
            if (strongBox && failure.strongBox) return retryWrappedOperationalInTee(provisioning)
            throw failure
        }
        val stage = PreparedStorageStage.OperationalWrapDecrypt(unwrap, material, strongBox)
        return if (record.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            authenticationRequired(
                provisioning,
                stage,
                cipher = unwrap.cipher,
                promptAuthenticators = SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
            )
        } else {
            completeWrappedOperationalValidation(provisioning, stage, unwrap.cipher)
        }
    }

    private fun completeWrappedOperationalValidation(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalWrapDecrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val decrypted = try {
            wrappedOperationalVault.completeUnwrap(stage.unwrap, authenticatedCipher)
        } catch (failure: SshWrappedOperationalOperationException) {
            if (stage.strongBox && failure.strongBox) return retryWrappedOperationalInTee(provisioning)
            throw failure
        }
        decrypted.use { candidate ->
            val original = requireNotNull(provisioning.privateKeyPkcs8).bytes
            check(MessageDigest.isEqual(candidate.bytes, original)) {
                "Wrapped SSH operational key did not reproduce the original private key"
            }
            val privateKey = softwarePrivateKey(provisioning.record.algorithm, candidate.bytes)
            selfTestSoftware(
                privateKey,
                requireNotNull(provisioning.sourcePublicKey),
                provisioning.record.algorithm,
            )
        }
        return continueAfterOperationalValidation(provisioning)
    }

    private fun retryWrappedOperationalInTee(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult {
        check(provisioning.record.operationalProvider == SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED)
        provisioning.wrappedOperationalMaterial?.ciphertext?.fill(0)
        provisioning.wrappedOperationalMaterial?.nonce?.fill(0)
        provisioning.wrappedOperationalMaterial = null
        clearPersistedWrappedOperationalCandidate(provisioning.record.providerKeyId)
        wrappedOperationalVault.delete(provisioning.record.keyAlias)
        provisioning.record = provisioning.record.copy(
            operationalSecurityLevel = SshStorageSecurityLevel.TRUSTED_ENVIRONMENT,
            operationalStrongBoxFallback = true,
        )
        return prepareWrappedOperationalEncryption(provisioning, false)
    }

    private fun completeOperationalSelfTest(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.OperationalSelfTest,
        authenticatedSignature: Signature,
    ): SshKeyStorageResult {
        require(authenticatedSignature === stage.signature) { "SSH signing self-test operation changed" }
        val raw = try {
            authenticatedSignature.run {
                update(stage.challenge)
                sign()
            }
        } catch (failure: Exception) {
            stage.challenge.fill(0)
            if (stage.strongBox) return retryOperationalInTee(provisioning)
            abortProvisioning(provisioning)
            throw IllegalStateException("TEE SSH signing self-test failed", failure)
        }
        val verified = try {
            verifySelfTestSignature(stage.publicKey, provisioning.record.algorithm, stage.challenge, raw)
        } finally {
            stage.challenge.fill(0)
            raw.fill(0)
        }
        if (!verified) {
            abortProvisioning(provisioning)
            error("Android Keystore SSH signing self-test produced an invalid signature")
        }
        return continueAfterOperationalValidation(provisioning)
    }

    private fun retryOperationalInTee(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult {
        val old = provisioning.record
        check(old.operationalSecurityLevel == SshStorageSecurityLevel.STRONGBOX)
        deleteAndroidKeyStoreAlias(old.keyAlias)
        val publicKey: PublicKey
        val securityLevel: SshStorageSecurityLevel
        if (provisioning.privateKeyPkcs8 != null) {
            publicKey = requireNotNull(provisioning.sourcePublicKey)
            securityLevel = installOperationalKey(
                old.keyAlias,
                provisioning.privateKeyPkcs8.bytes,
                publicKey,
                old.algorithm,
                old.createdAt,
                false,
                old.userVerificationPolicy,
            )
        } else {
            val pair = generateOperationalKeyPair(
                old.algorithm,
                old.keyAlias,
                false,
                old.userVerificationPolicy,
                provisioning.rsaKeySizeBits,
            )
            publicKey = pair.public
            securityLevel = inspectKeyInfo(pair.private, old.algorithm, old.userVerificationPolicy)
            val publicBlob = SshPublicKeyCodec.encode(publicKey, old.algorithm.toCoreType())
            val publicHash = sha256(publicBlob)
            check(findKeyId(publicHash) == null) { "generated SSH key already exists" }
            provisioning.record = old.copy(publicBlob = publicBlob, publicHash = publicHash)
        }
        provisioning.record = provisioning.record.copy(
            operationalSecurityLevel = securityLevel,
            operationalStrongBoxFallback = true,
        )
        return validateOperationalOrContinue(provisioning, publicKey)
    }

    private fun continueAfterOperationalValidation(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult =
        if (provisioning.exportCopyBackendPolicy == null) {
            commitProvisioning(provisioning)
        } else {
            prepareExportEncryption(provisioning, exportVault.shouldAttemptStrongBox(provisioning.exportCopyBackendPolicy))
        }

    private fun prepareExportEncryption(
        provisioning: PendingSshKeyProvisioning,
        strongBox: Boolean,
    ): SshKeyStorageResult.AuthenticationRequired {
        val record = provisioning.record
        if (strongBox) provisioning.exportStrongBoxAttempted = true
        val protection = try {
            exportVault.prepareProtect(
                record.providerKeyId,
                requireNotNull(provisioning.privateKeyPkcs8),
                record.algorithm,
                record.publicHash,
                strongBox,
            )
        } catch (failure: SshExportCandidateException) {
            if (!strongBox || !failure.strongBox) {
                abortProvisioning(provisioning)
                throw failure
            }
            exportVault.deleteCandidate(record.providerKeyId, true)
            provisioning.exportStrongBoxFallback = true
            return prepareExportEncryption(provisioning, false)
        }
        return authenticationRequired(
            provisioning,
            PreparedStorageStage.ExportEncrypt(protection, strongBox),
            cipher = protection.cipher,
            promptAuthenticators = SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
    }

    private fun completeExportEncryption(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.ExportEncrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val material = try {
            exportVault.completeProtect(stage.protection, authenticatedCipher)
        } catch (failure: SshExportOperationException) {
            if (stage.strongBox && failure.strongBox) {
                exportVault.deleteCandidate(provisioning.record.providerKeyId, true)
                provisioning.exportStrongBoxFallback = true
                return prepareExportEncryption(provisioning, false)
            }
            abortProvisioning(provisioning)
            throw failure
        }
        persistExportCandidate(provisioning.record.providerKeyId, material)
        material.ciphertext.fill(0)
        material.nonce.fill(0)
        val persisted = loadPersistedExportCandidate(provisioning.record.providerKeyId)
        provisioning.exportMaterial = persisted
        return prepareExportValidation(provisioning, persisted, stage.strongBox)
    }

    private fun prepareExportValidation(
        provisioning: PendingSshKeyProvisioning,
        material: ProtectedSshKeyMaterial,
        strongBox: Boolean,
    ): SshKeyStorageResult.AuthenticationRequired {
        val record = provisioning.record
        val unwrap = try {
            exportVault.prepareUnwrap(
                record.providerKeyId,
                material.ciphertext,
                material.nonce,
                record.algorithm,
                record.publicHash,
                material.securityLevel,
            )
        } catch (failure: SshExportCandidateException) {
            if (strongBox && failure.strongBox) return retryExportInTee(provisioning)
            abortProvisioning(provisioning)
            throw failure
        }
        return authenticationRequired(
            provisioning,
            PreparedStorageStage.ExportDecrypt(unwrap, material, strongBox),
            cipher = unwrap.cipher,
            promptAuthenticators = SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
    }

    private fun completeExportValidation(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage.ExportDecrypt,
        authenticatedCipher: Cipher,
    ): SshKeyStorageResult {
        val decrypted = try {
            exportVault.completeUnwrap(stage.unwrap, authenticatedCipher)
        } catch (failure: SshExportOperationException) {
            if (stage.strongBox && failure.strongBox) return retryExportInTee(provisioning)
            abortProvisioning(provisioning)
            throw failure
        }
        decrypted.use { candidate ->
            val original = requireNotNull(provisioning.privateKeyPkcs8).bytes
            if (!MessageDigest.isEqual(candidate.bytes, original)) {
                abortProvisioning(provisioning)
                error("SSH export copy did not reproduce the original private key")
            }
            val privateKey = softwarePrivateKey(provisioning.record.algorithm, candidate.bytes)
            selfTestSoftware(privateKey, requireNotNull(provisioning.sourcePublicKey), provisioning.record.algorithm)
        }
        return commitProvisioning(provisioning)
    }

    private fun retryExportInTee(provisioning: PendingSshKeyProvisioning): SshKeyStorageResult.AuthenticationRequired {
        provisioning.exportMaterial?.ciphertext?.fill(0)
        provisioning.exportMaterial?.nonce?.fill(0)
        provisioning.exportMaterial = null
        clearPersistedExportCandidate(provisioning.record.providerKeyId)
        exportVault.deleteCandidate(provisioning.record.providerKeyId, true)
        provisioning.exportStrongBoxFallback = true
        return prepareExportEncryption(provisioning, false)
    }

    private fun authenticationRequired(
        provisioning: PendingSshKeyProvisioning,
        stage: PreparedStorageStage,
        cipher: Cipher? = null,
        signature: Signature? = null,
        promptAuthenticators: Int,
    ): SshKeyStorageResult.AuthenticationRequired = SshKeyStorageResult.AuthenticationRequired(
        PreparedSshKeyStorage(
            cipher = cipher,
            signature = signature,
            promptAuthenticators = promptAuthenticators,
            owner = this,
            storeResetEpoch = resetEpoch,
            provisioning = provisioning,
            stage = stage,
        ),
    )

    private fun persistWrappedOperationalCandidate(providerKeyId: String, material: ProtectedSshKeyMaterial) {
        val changed = writableDatabase.update(
            "ssh_key_lifecycle",
            ContentValues().apply {
                put("operational_candidate_ciphertext", material.ciphertext)
                put("operational_candidate_nonce", material.nonce)
                put("operational_candidate_security_level", material.securityLevel.name)
            },
            "provider_key_id=? AND state='PROVISIONING'",
            arrayOf(providerKeyId),
        )
        check(changed == 1) { "SSH key provisioning journal is unavailable" }
    }

    private fun loadPersistedWrappedOperationalCandidate(providerKeyId: String): ProtectedSshKeyMaterial =
        readableDatabase.rawQuery(
            "SELECT operational_candidate_ciphertext, operational_candidate_nonce, " +
                "operational_candidate_security_level FROM ssh_key_lifecycle " +
                "WHERE provider_key_id=? AND state='PROVISIONING'",
            arrayOf(providerKeyId),
        ).use { cursor ->
            check(cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2)) {
                "Wrapped SSH operational candidate was not persisted"
            }
            ProtectedSshKeyMaterial(
                ciphertext = cursor.getBlob(0),
                nonce = cursor.getBlob(1),
                securityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(2)),
            )
        }

    private fun clearPersistedWrappedOperationalCandidate(providerKeyId: String) {
        writableDatabase.update(
            "ssh_key_lifecycle",
            ContentValues().apply {
                putNull("operational_candidate_ciphertext")
                putNull("operational_candidate_nonce")
                putNull("operational_candidate_security_level")
            },
            "provider_key_id=?",
            arrayOf(providerKeyId),
        )
    }

    private fun persistExportCandidate(providerKeyId: String, material: ProtectedSshKeyMaterial) {
        val changed = writableDatabase.update(
            "ssh_key_lifecycle",
            ContentValues().apply {
                put("export_candidate_ciphertext", material.ciphertext)
                put("export_candidate_nonce", material.nonce)
                put("export_candidate_security_level", material.securityLevel.name)
            },
            "provider_key_id=? AND state='PROVISIONING'",
            arrayOf(providerKeyId),
        )
        check(changed == 1) { "SSH key provisioning journal is unavailable" }
    }

    private fun loadPersistedExportCandidate(providerKeyId: String): ProtectedSshKeyMaterial =
        readableDatabase.rawQuery(
            "SELECT export_candidate_ciphertext, export_candidate_nonce, export_candidate_security_level " +
                "FROM ssh_key_lifecycle WHERE provider_key_id=? AND state='PROVISIONING'",
            arrayOf(providerKeyId),
        ).use { cursor ->
            check(cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2)) {
                "SSH export candidate was not persisted"
            }
            ProtectedSshKeyMaterial(
                ciphertext = cursor.getBlob(0),
                nonce = cursor.getBlob(1),
                securityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(2)),
            )
        }

    private fun clearPersistedExportCandidate(providerKeyId: String) {
        writableDatabase.update(
            "ssh_key_lifecycle",
            ContentValues().apply {
                putNull("export_candidate_ciphertext")
                putNull("export_candidate_nonce")
                putNull("export_candidate_security_level")
            },
            "provider_key_id=?",
            arrayOf(providerKeyId),
        )
    }

    private fun beginLifecycle(providerKeyId: String, operationalAlias: String, now: Long) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        check(!store.containsAlias(operationalAlias)) { "SSH operational alias already exists" }
        check(!store.containsAlias(exportVault.alias(providerKeyId, true))) { "SSH StrongBox export alias already exists" }
        check(!store.containsAlias(exportVault.alias(providerKeyId, false))) { "SSH TEE export alias already exists" }
        writableDatabase.insertOrThrow("ssh_key_lifecycle", null, ContentValues().apply {
            put("provider_key_id", providerKeyId)
            put("operational_alias", operationalAlias)
            put("state", "PROVISIONING")
            put("created_at", now)
        })
    }

    private fun abortProvisioning(provisioning: PendingSshKeyProvisioning) {
        if (provisioning.finished) return
        val record = provisioning.record
        abortProvisioning(record.providerKeyId, record.keyAlias, provisioning)
    }

    private fun abortProvisioning(
        providerKeyId: String,
        operationalAlias: String,
        provisioning: PendingSshKeyProvisioning?,
    ) {
        runCatching { deleteAndroidKeyStoreAlias(operationalAlias) }
        runCatching { exportVault.deleteAll(providerKeyId) }
        runCatching { writableDatabase.delete("ssh_key_lifecycle", "provider_key_id=?", arrayOf(providerKeyId)) }
        provisioning?.apply { finished = true }?.close()
    }

    private fun installAndroidKeyStoreEntry(
        alias: String,
        privateKey: PrivateKey,
        certificate: Certificate,
        algorithm: SshKeyAlgorithm,
        strongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ) {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).apply {
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> setDigests(KeyProperties.DIGEST_NONE)
                SshKeyAlgorithm.SSH_RSA -> {
                    setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                }
                SshKeyAlgorithm.ECDSA_NISTP256 -> setDigests(KeyProperties.DIGEST_SHA256)
                SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
                    error("WebAuthn SSH keys cannot be installed in Android Keystore")
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationParameters(0, SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS)
            }
        }.build()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.setEntry(
            alias,
            KeyStore.PrivateKeyEntry(privateKey, arrayOf(certificate)),
            protection,
        )
    }

    private fun createContainerCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
        now: Long,
    ): Certificate {
        val subject = X500Name("CN=NotiSync SSH key container")
        val signer = JcaContentSignerBuilder(
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
                SshKeyAlgorithm.SSH_RSA -> "SHA256withRSA"
                SshKeyAlgorithm.ECDSA_NISTP256 -> "SHA256withECDSA"
                SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
                    error("WebAuthn SSH keys cannot use Android Keystore certificates")
            },
        ).setProvider(BOUNCY_CASTLE).build(privateKey)
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, RANDOM).abs().max(BigInteger.ONE),
            Date(now - CERTIFICATE_CLOCK_SKEW_MILLIS),
            Date(now + CERTIFICATE_VALIDITY_MILLIS),
            subject,
            publicKey,
        ).build(signer)
        return JcaX509CertificateConverter().setProvider(BOUNCY_CASTLE).getCertificate(holder).also {
            it.checkValidity(Date(now))
            it.verify(publicKey)
        }
    }

    @Synchronized
    fun deleteKey(providerKeyId: String): Boolean {
        val stored = readableDatabase.rawQuery(
            "SELECT o.key_alias, e.provider_key_id IS NOT NULL, o.provider_kind FROM ssh_keys k " +
                "JOIN ssh_operational_keys o ON o.provider_key_id=k.provider_key_id " +
                "LEFT JOIN ssh_export_copies e ON e.provider_key_id=k.provider_key_id WHERE k.provider_key_id=?",
            arrayOf(providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            Triple(cursor.getString(0), cursor.getInt(1) != 0, SshOperationalKeyProvider.valueOf(cursor.getString(2)))
        }
        if (stored.third != SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN) {
            writableDatabase.insertOrThrow("ssh_key_lifecycle", null, ContentValues().apply {
                put("provider_key_id", providerKeyId)
                put("operational_alias", stored.first)
                put("state", "DELETING")
                put("created_at", System.currentTimeMillis())
            })
            deleteAndroidKeyStoreAlias(stored.first)
        }
        if (stored.second) exportVault.deleteAll(providerKeyId)
        val database = writableDatabase
        database.beginTransaction()
        val deleted = try {
            val deleted = database.delete("ssh_keys", "provider_key_id=?", arrayOf(providerKeyId)) == 1
            if (deleted) {
                database.delete("ssh_key_lifecycle", "provider_key_id=?", arrayOf(providerKeyId))
                bumpRevision(database)
            }
            database.setTransactionSuccessful()
            deleted
        } finally {
            database.endTransaction()
        }
        if (deleted) notifyChanged()
        return deleted
    }

    /** Explicit diagnostics recovery for the SSH Agent feature only. */
    @Synchronized
    fun resetAllSshStorage(): SshKeyStoreResetResult {
        // Both reads are best effort because this is the user-confirmed recovery path for an unreadable schema.
        val removedRequestIds = runCatching {
            readableDatabase.rawQuery(
                "SELECT request_id FROM provider_requests",
                emptyArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }.getOrDefault(emptyList())
        val removedKeyCount = runCatching {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ssh_keys",
                emptyArray(),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }.getOrDefault(0)

        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("provider_requests", null, null)
            database.delete("ssh_remembered_authorizations", null, null)
            database.delete("authorization_floors", null, null)
            database.delete("ssh_known_hosts", null, null)
            database.delete("ssh_export_copies", null, null)
            database.delete("ssh_webauthn_credentials", null, null)
            database.delete("ssh_operational_keys", null, null)
            database.delete("ssh_key_lifecycle", null, null)
            database.delete("ssh_keys", null, null)
            database.delete("provider_state", null, null)
            database.execSQL(
                "INSERT INTO provider_state(singleton, inventory_generation, revision) VALUES(1, ?, 1)",
                arrayOf(randomId()),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        resetEpoch = if (resetEpoch == Long.MAX_VALUE) 0L else resetEpoch + 1L
        val firstFailure = deleteAllSshKeyStoreAliases()
        notifyChanged()
        if (firstFailure != null) {
            throw IllegalStateException("Some SSH Android Keystore entries could not be removed", firstFailure)
        }
        return SshKeyStoreResetResult(removedKeyCount, removedRequestIds)
    }

    @Synchronized
    fun updateKeyMetadata(
        providerKeyId: String,
        displayName: String,
        approvalPolicy: SshApprovalPolicy,
    ): Boolean {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        val isWebAuthn = readableDatabase.rawQuery(
            "SELECT 1 FROM ssh_webauthn_credentials WHERE provider_key_id=?",
            arrayOf(providerKeyId),
        ).use { it.moveToFirst() }
        require(!isWebAuthn || approvalPolicy == SshApprovalPolicy.ALWAYS_ASK) {
            "WebAuthn SSH keys cannot allow remembered authorization"
        }
        val database = writableDatabase
        database.beginTransaction()
        val changed = try {
            val values = ContentValues().apply {
                put("display_name", name)
                put("approval_policy", approvalPolicy.name)
            }
            val changed = database.update("ssh_keys", values, "provider_key_id=?", arrayOf(providerKeyId)) == 1
            if (changed) {
                bumpRevision(database)
            }
            database.setTransactionSuccessful()
            changed
        } finally {
            database.endTransaction()
        }
        if (changed) notifyChanged()
        return changed
    }

    @Synchronized
    fun owns(publicKeyBlob: ByteArray, now: Long): Boolean {
        pruneExpiredKeys(now)
        return findKeyId(sha256(publicKeyBlob)) != null
    }

    @Synchronized
    fun acceptSign(request: SshSignRequest, now: Long): SshProviderAcceptResult {
        if (request.authorizationEpoch <= authorizationFloor(request.requesterClientId, request.authorizationGeneration)) {
            return SshProviderAcceptResult.AUTHORIZATION_INVALIDATED
        }
        pruneExpiredKeys(now)
        val keyName = keyDisplayName(request.publicKeyBlob) ?: return SshProviderAcceptResult.KEY_NOT_FOUND
        return accept(
            SshProviderRequestKind.SIGN,
            request.requestId,
            request.requesterClientId,
            ProtocolCodec.encodeToCbor(request),
            request.historySnapshot(keyName),
            now,
        )
    }

    @Synchronized
    fun acceptImport(request: SshImportRequest, now: Long): SshProviderAcceptResult = accept(
        SshProviderRequestKind.IMPORT,
        request.requestId,
        request.requesterClientId,
        ProtocolCodec.encodeToCbor(request),
        request.historySnapshot(),
        now,
    )

    @Synchronized
    fun find(requestId: String): StoredSshProviderRequest? = readableDatabase.rawQuery(
        "SELECT request_id, kind, requester_client_id, request_fingerprint, request_cbor, request_nonce, " +
            "history_cbor, history_nonce, state, outcome, result_at, response_cbor, response_nonce, updated_at " +
            "FROM provider_requests WHERE request_id=?",
        arrayOf(requestId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readRequest() else null }

    @Synchronized
    fun pendingReview(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.PENDING_REVIEW)

    @Synchronized
    fun pendingResponses(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.RESPONSE_PENDING_SEND)

    @Synchronized
    fun requests(): List<StoredSshProviderRequest> = readableDatabase.rawQuery(
        "SELECT request_id, kind, requester_client_id, request_fingerprint, request_cbor, request_nonce, " +
            "history_cbor, history_nonce, state, outcome, result_at, response_cbor, response_nonce, updated_at " +
            "FROM provider_requests WHERE state IN ('PENDING_REVIEW', 'RESPONSE_PENDING_SEND') OR request_id IN (" +
            "SELECT request_id FROM provider_requests WHERE state NOT IN " +
            "('PENDING_REVIEW', 'RESPONSE_PENDING_SEND') ORDER BY updated_at DESC LIMIT $MAX_HISTORY_ROWS) " +
            "ORDER BY updated_at DESC",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest(decodeActiveRequest = false)) } }

    /** Persists only the parsed public identity; private import material remains in the active request only. */
    @Synchronized
    fun recordImportPreview(requestId: String, publicKeyBlob: ByteArray): Boolean {
        SshPublicKeyCodec.decode(publicKeyBlob)
        val stored = find(requestId) ?: return false
        if (stored.kind != SshProviderRequestKind.IMPORT ||
            stored.state != SshProviderRequestState.PENDING_REVIEW
        ) return false
        stored.history.publicKeyBlob?.let { existing ->
            check(MessageDigest.isEqual(existing, publicKeyBlob)) { "SSH import public-key preview changed" }
            return true
        }
        val historyBytes = ProtocolCodec.encodeToCbor(
            stored.history.copy(publicKeyBlob = publicKeyBlob.copyOf()),
        )
        val encrypted = try {
            auditWrapping.encrypt(historyBytes, auditAad(requestId, AUDIT_HISTORY))
        } finally {
            historyBytes.fill(0)
        }
        val values = ContentValues().apply {
            put("history_cbor", encrypted.first)
            put("history_nonce", encrypted.second)
        }
        val changed = writableDatabase.update(
            "provider_requests",
            values,
            "request_id=? AND state=?",
            arrayOf(requestId, SshProviderRequestState.PENDING_REVIEW.name),
        ) == 1
        if (changed) notifyChanged()
        return changed
    }

    @Synchronized
    fun keyDisplayName(publicKeyBlob: ByteArray): String? = readableDatabase.rawQuery(
        "SELECT display_name FROM ssh_keys WHERE hex(public_hash)=?",
        arrayOf(sha256(publicKeyBlob).toHex().uppercase()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    @Synchronized
    fun availableRememberScopes(requestId: String): Set<SshRememberScope> {
        val stored = find(requestId) ?: return emptySet()
        val request = stored.signRequest ?: return emptySet()
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || request.confirmationRequired ||
            request.authorizationEpoch <= authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        ) return emptySet()
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return emptySet()
        if (!SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                policy.approvalPolicy,
                policy.userVerificationPolicy,
            )
        ) return emptySet()
        return SshRememberAuthorizationPolicy.availableDiskScopes(request.destinationContext)
    }

    @Synchronized
    fun requiresPerUseUserVerification(requestId: String): Boolean {
        val request = find(requestId)?.signRequest ?: return false
        return findKeyPolicy(request.publicKeyBlob)?.userVerificationPolicy == SshUserVerificationPolicy.PER_USE
    }

    @Synchronized
    fun requiresWebAuthnUserVerification(requestId: String): Boolean {
        val request = find(requestId)?.signRequest ?: return false
        return findKeyMaterial(request.publicKeyBlob)?.provider ==
            SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN
    }

    @Synchronized
    fun approve(requestId: String, provider: ClientId, now: Long): SshSignResult? {
        val stored = find(requestId) ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW) return null
        stored.signRequest?.let { request ->
            if (request.authorizationEpoch <= authorizationFloor(
                    request.requesterClientId,
                    request.authorizationGeneration,
                )
            ) return null
            if (findKeyPolicy(request.publicKeyBlob)?.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                return null
            }
        }
        if (stored.expiresAt() < now) {
            expireDue(now)
            return null
        }
        val response = when (stored.kind) {
            SshProviderRequestKind.SIGN -> sign(
                requireNotNull(stored.signRequest),
                provider,
                now,
                SshRememberDisposition.NONE,
            )
            // Imports must pass through the explicit storage-choice review flow.
            SshProviderRequestKind.IMPORT -> return null
        }
        return response.takeIf { storeUserApprovedSignResponse(stored, it, now) }
    }

    @Synchronized
    fun prepareUserVerifiedSignature(
        requestId: String,
        provider: ClientId,
        now: Long,
    ): PreparedSshSignature? {
        val stored = find(requestId) ?: return null
        val request = stored.signRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now) return null
        if (request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            )
        ) return null
        fun failPreparation(code: SshProviderFailureCode): PreparedSshSignature? {
            val response = signFailure(request, provider, now, code)
            storeResponse(stored, response, now)
            return null
        }
        val policy = findKeyPolicy(request.publicKeyBlob)
            ?: return failPreparation(SshProviderFailureCode.KEY_NOT_FOUND)
        if (policy.userVerificationPolicy != SshUserVerificationPolicy.PER_USE) return null
        val material = findKeyMaterial(request.publicKeyBlob)
            ?: return failPreparation(SshProviderFailureCode.KEY_NOT_FOUND)
        return try {
            val method = signatureMethod(request)
            when (material.provider) {
                SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY -> {
                    val signature = SshKeystoreJca.signature(method.jcaName).apply { initSign(material.privateKey()) }
                    PreparedSshSignature(
                        requestId,
                        stored.requestFingerprint.copyOf(),
                        signature = signature,
                        cipher = null,
                        method = method,
                        operation = PreparedSignatureOperation.Direct,
                    )
                }
                SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED -> {
                    val unwrap = material.prepareWrappedUnwrap()
                    PreparedSshSignature(
                        requestId,
                        stored.requestFingerprint.copyOf(),
                        signature = null,
                        cipher = unwrap.cipher,
                        method = method,
                        operation = PreparedSignatureOperation.Wrapped(unwrap),
                    )
                }
                SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN -> null
            }
        } catch (failure: Exception) {
            failPreparation(failure.signingFailureCode())
        }
    }

    @Synchronized
    fun completeUserVerifiedSignature(
        prepared: PreparedSshSignature,
        authenticatedSignature: Signature?,
        authenticatedCipher: Cipher?,
        provider: ClientId,
        now: Long,
    ): SshSignResult? {
        val stored = find(prepared.requestId) ?: run {
            prepared.close()
            return null
        }
        val request = stored.signRequest ?: run {
            prepared.close()
            return null
        }
        val operationMatches = when (val operation = prepared.operation) {
            PreparedSignatureOperation.Direct ->
                authenticatedSignature === prepared.signature && authenticatedCipher == null
            is PreparedSignatureOperation.Wrapped ->
                authenticatedCipher === prepared.cipher && authenticatedSignature == null &&
                    operation.unwrap.cipher === prepared.cipher
        }
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            !MessageDigest.isEqual(stored.requestFingerprint, prepared.requestFingerprint) ||
            !operationMatches ||
            request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            ) ||
            findKeyPolicy(request.publicKeyBlob)?.userVerificationPolicy != SshUserVerificationPolicy.PER_USE
        ) {
            prepared.close()
            return null
        }
        val response = try {
            val jcaSignature = when (val operation = prepared.operation) {
                PreparedSignatureOperation.Direct -> requireNotNull(authenticatedSignature).run {
                    update(request.data)
                    sign()
                }
                is PreparedSignatureOperation.Wrapped -> {
                    wrappedOperationalVault.completeUnwrap(
                        operation.unwrap,
                        requireNotNull(authenticatedCipher),
                    ).use { privateBytes ->
                        val privateKey = softwarePrivateKey(
                            SshKeyAlgorithm.SSH_ED25519,
                            privateBytes.bytes,
                        )
                        signSoftwareRaw(prepared.method, privateKey, request.data)
                    }
                }
            }
            signedResult(
                request,
                provider,
                now,
                SshRememberDisposition.NONE,
                prepared.method,
                jcaSignature,
            )
        } catch (failure: Exception) {
            signFailure(
                request,
                provider,
                now,
                failure.signingFailureCode(),
            )
        } finally {
            prepared.close()
        }
        return response.takeIf { storeUserApprovedSignResponse(stored, it, now) }
    }

    @Synchronized
    fun cancelPreparedSignature(prepared: PreparedSshSignature) {
        prepared.close()
    }

    @Synchronized
    fun prepareWebAuthnSignature(
        requestId: String,
        provider: ClientId,
        now: Long,
    ): PreparedSshWebAuthnSignature? {
        val stored = find(requestId) ?: return null
        val request = stored.signRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now) return null
        if (request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            )
        ) return null
        fun failPreparation(code: SshProviderFailureCode): PreparedSshWebAuthnSignature? {
            storeResponse(stored, signFailure(request, provider, now, code), now)
            return null
        }
        val credential = findWebAuthnCredential(request.publicKeyBlob)
            ?: return failPreparation(SshProviderFailureCode.KEY_NOT_FOUND)
        return try {
            require(signatureMethod(request) == SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256)
            PreparedSshWebAuthnSignature(
                requestId = requestId,
                requestFingerprint = stored.requestFingerprint.copyOf(),
                requestJson = SshWebAuthnCredential.assertionRequestJson(credential, request.data),
                credential = credential,
            )
        } catch (_: Exception) {
            failPreparation(SshProviderFailureCode.INTERNAL_FAILURE)
        }
    }

    @Synchronized
    fun completeWebAuthnSignature(
        prepared: PreparedSshWebAuthnSignature,
        responseJson: String,
        provider: ClientId,
        now: Long,
    ): SshSignResult? {
        val stored = find(prepared.requestId) ?: run {
            prepared.close()
            return null
        }
        val request = stored.signRequest ?: run {
            prepared.close()
            return null
        }
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            !MessageDigest.isEqual(stored.requestFingerprint, prepared.requestFingerprint) ||
            request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            ) ||
            findKeyMaterial(request.publicKeyBlob)?.provider !=
            SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN
        ) {
            prepared.close()
            return null
        }
        val result = try {
            val assertion = SshWebAuthnCredential.parseAssertion(
                stored = prepared.credential,
                challenge = request.data,
                responseJson = responseJson,
                allowedOrigins = trustedWebAuthnOrigins,
            )
            updateWebAuthnBackupState(prepared.credential, assertion)
            signedResultFromBlob(
                request,
                provider,
                now,
                SshRememberDisposition.NONE,
                SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256,
                assertion.signatureBlob,
            )
        } catch (failure: Exception) {
            Log.w(SSH_STORAGE_LOG_TAG, "Credential Manager WebAuthn assertion failed", failure)
            signFailure(request, provider, now, SshProviderFailureCode.INTERNAL_FAILURE)
        } finally {
            prepared.close()
        }
        return result.takeIf { storeUserApprovedSignResponse(stored, it, now) }
    }

    @Synchronized
    fun cancelPreparedWebAuthnSignature(prepared: PreparedSshWebAuthnSignature) {
        prepared.close()
    }

    @Synchronized
    fun failPreparedWebAuthnSignature(
        prepared: PreparedSshWebAuthnSignature,
        provider: ClientId,
        now: Long,
        code: SshProviderFailureCode,
    ): Boolean {
        require(
            code == SshProviderFailureCode.USER_VERIFICATION_CANCELLED ||
                code == SshProviderFailureCode.KEY_NOT_FOUND ||
                code == SshProviderFailureCode.INTERNAL_FAILURE,
        ) { "invalid WebAuthn failure code" }
        val stored = find(prepared.requestId)
        return try {
            val request = stored?.signRequest ?: return false
            if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
                !MessageDigest.isEqual(stored.requestFingerprint, prepared.requestFingerprint)
            ) return false
            storeResponse(stored, signFailure(request, provider, now, code), now)
        } finally {
            prepared.close()
        }
    }

    @Synchronized
    fun failUserVerification(
        requestId: String,
        provider: ClientId,
        now: Long,
        code: SshProviderFailureCode,
    ): Boolean {
        require(
            code == SshProviderFailureCode.USER_VERIFICATION_CANCELLED ||
                code == SshProviderFailureCode.USER_VERIFICATION_LOCKOUT,
        ) { "invalid user-verification failure code" }
        val stored = find(requestId) ?: return false
        val request = stored.signRequest ?: return false
        if (stored.state != SshProviderRequestState.PENDING_REVIEW) return false
        return storeResponse(stored, signFailure(request, provider, now, code), now)
    }

    @Synchronized
    fun approveImport(
        requestId: String,
        provider: ClientId,
        now: Long,
        displayName: String,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray? = null,
    ): SshImportApprovalOutcome? {
        val stored = find(requestId) ?: return null
        val request = stored.importRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW) return null
        if (stored.expiresAt() < now) {
            expireDue(now)
            return null
        }
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        val attempt = import(
            request,
            provider,
            now,
            name,
            allowExport,
            exportCopyBackendPolicy,
            userVerificationPolicy,
            passphrase,
        )
        return when (attempt) {
            is SshImportAttempt.Complete -> {
                if (storeResponse(stored, attempt.response, now)) SshImportApprovalOutcome.Completed else null
            }
            is SshImportAttempt.AuthenticationRequired -> SshImportApprovalOutcome.AuthenticationRequired(
                PreparedSshImportStorage(
                    keyStorage = attempt.keyStorage,
                    requestId = requestId,
                    requestFingerprint = stored.requestFingerprint.copyOf(),
                    requesterClientId = request.requesterClientId,
                    publicKeyBlob = attempt.publicKeyBlob.copyOf(),
                ),
            )
        }
    }

    @Synchronized
    fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: Cipher?,
        authenticatedSignature: Signature?,
        provider: ClientId,
        now: Long,
    ): SshImportApprovalOutcome? {
        val stored = find(prepared.requestId) ?: run {
            cancelPreparedKeyStorage(prepared.keyStorage)
            return null
        }
        if (stored.state != SshProviderRequestState.PENDING_REVIEW ||
            !MessageDigest.isEqual(stored.requestFingerprint, prepared.requestFingerprint) ||
            stored.requesterClientId != prepared.requesterClientId
        ) {
            cancelPreparedKeyStorage(prepared.keyStorage)
            return null
        }
        val result = runCatching {
            completePreparedKeyStorage(prepared.keyStorage, authenticatedCipher, authenticatedSignature)
        }.getOrElse { failure ->
            Log.w(SSH_STORAGE_LOG_TAG, "Prepared SSH import storage failed", failure)
            val response = SshImportResult(
                prepared.requestId, prepared.requesterClientId, provider, now,
                SshImportResultKind.FAILED,
                message = "SSH identity import failed: ${failure.failureSummary()}".take(512),
            )
            return if (storeResponse(stored, response, now)) SshImportApprovalOutcome.Completed else null
        }
        return when (result) {
            is SshKeyStorageResult.AuthenticationRequired -> SshImportApprovalOutcome.AuthenticationRequired(
                PreparedSshImportStorage(
                    keyStorage = result.prepared,
                    requestId = prepared.requestId,
                    requestFingerprint = prepared.requestFingerprint,
                    requesterClientId = prepared.requesterClientId,
                    publicKeyBlob = prepared.publicKeyBlob,
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
                if (storeResponse(stored, response, now)) SshImportApprovalOutcome.Completed else null
            }
        }
    }

    @Synchronized
    fun cancelPreparedImport(prepared: PreparedSshImportStorage) =
        cancelPreparedKeyStorage(prepared.keyStorage)

    @Synchronized
    fun approveAndRemember(
        requestId: String,
        provider: ClientId,
        scope: SshRememberScope,
        now: Long,
    ): SshSignResult? {
        val stored = find(requestId) ?: return null
        val request = stored.signRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            request.confirmationRequired ||
            request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            )
        ) return null
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return null
        if (!SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                policy.approvalPolicy,
                policy.userVerificationPolicy,
            )
        ) return null
        if (scope !in SshRememberAuthorizationPolicy.availableDiskScopes(request.destinationContext) ||
            scope.authorizationStorage != SshRememberAuthorizationStorage.DISK
        ) return null
        val disposition = when (scope) {
            SshRememberScope.PEER -> SshRememberDisposition.CREATED_PEER
            SshRememberScope.PEER_HOST_KEY -> SshRememberDisposition.CREATED_PEER_HOST_KEY
            SshRememberScope.APPLICATION_PROCESS -> return null
        }
        val response = sign(request, provider, now, disposition)
        if (response.kind != SshSignResultKind.SIGNED) {
            return response.takeIf { storeResponse(stored, it, now) }
        }
        val database = writableDatabase
        database.beginTransaction()
        val storedResponse = try {
            when (val write = persistRememberedAuthorization(database, policy.providerKeyId, request, scope, now)) {
                RememberedAuthorizationWrite.Rejected -> false
                is RememberedAuthorizationWrite.Stored -> {
                    observeKnownHost(database, request.destinationContext, now)
                    val consumed = storeResponse(
                        stored = stored,
                        response = response,
                        now = now,
                        database = database,
                        notify = false,
                        approvalAudit = ApprovalAudit(
                            kind = SshRequestApprovalKind.MANUAL,
                            rememberedAuthorizationId = write.authorizationId,
                            rememberedScope = scope,
                        ),
                    )
                    if (consumed && write.created) bumpRevision(database)
                    consumed
                }
            }
                .also { consumed -> if (consumed) database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
        if (storedResponse) notifyChanged()
        return response.takeIf { storedResponse }
    }

    @Synchronized
    fun autoApproveRemembered(requestId: String, provider: ClientId, now: Long): StoredSshProviderRequest? {
        val stored = find(requestId) ?: return null
        val request = stored.signRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            request.confirmationRequired
        ) return null
        val authorization = matchingRememberedAuthorization(request) ?: return null
        val disposition = when (authorization.scope) {
            SshRememberScope.PEER -> SshRememberDisposition.MATCHED_PEER
            SshRememberScope.PEER_HOST_KEY -> SshRememberDisposition.MATCHED_PEER_HOST_KEY
            SshRememberScope.APPLICATION_PROCESS -> return null
        }
        val consumed = storeResponse(
            stored,
            sign(request, provider, now, disposition),
            now,
            approvalAudit = ApprovalAudit(
                kind = SshRequestApprovalKind.REMEMBERED_AUTHORIZATION,
                rememberedAuthorizationId = authorization.authorizationId,
                rememberedScope = authorization.scope,
            ),
        )
        return if (consumed) find(requestId) else null
    }

    @Synchronized
    fun forgetAuthorization(
        requester: ClientId,
        generation: String,
        invalidatedThroughEpoch: Long,
        now: Long,
    ): SshAuthorizationForgetOutcome {
        val cancelled = pendingReview().filter { stored ->
            val request = stored.signRequest
            request != null && request.requesterClientId == requester &&
                request.authorizationGeneration == generation &&
                request.authorizationEpoch <= invalidatedThroughEpoch
        }.map(StoredSshProviderRequest::requestId)
        val database = writableDatabase
        database.beginTransaction()
        val changed = try {
            val currentFloor = database.rawQuery(
                "SELECT invalidated_through_epoch FROM authorization_floors " +
                    "WHERE requester_client_id=? AND authorization_generation=?",
                arrayOf(requester.value, generation),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
            val floorChanged = invalidatedThroughEpoch > currentFloor
            if (floorChanged) {
                val values = ContentValues().apply {
                    put("requester_client_id", requester.value)
                    put("authorization_generation", generation)
                    put("invalidated_through_epoch", invalidatedThroughEpoch)
                    put("updated_at", now)
                }
                database.insertWithOnConflict(
                    "authorization_floors",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val removed = database.delete(
                "ssh_remembered_authorizations",
                "requester_client_id=? AND authorization_generation=? AND authorization_epoch<=?",
                arrayOf(requester.value, generation, invalidatedThroughEpoch.toString()),
            )
            val changed = floorChanged || removed > 0
            if (changed) bumpRevision(database)
            if (cancelled.isNotEmpty()) {
                val values = ContentValues().apply {
                    put("state", SshProviderRequestState.CANCELLED.name)
                    put("outcome", SshProviderRequestOutcome.CANCELLED.name)
                    put("result_at", now)
                    putNull("request_cbor")
                    putNull("request_nonce")
                    put("updated_at", now)
                }
                cancelled.forEach { requestId ->
                    database.update(
                        "provider_requests",
                        values,
                        "request_id=? AND state=?",
                        arrayOf(requestId, SshProviderRequestState.PENDING_REVIEW.name),
                    )
                }
            }
            database.setTransactionSuccessful()
            changed
        } finally {
            database.endTransaction()
        }
        if (changed || cancelled.isNotEmpty()) notifyChanged()
        return SshAuthorizationForgetOutcome(changed, cancelled)
    }

    @Synchronized
    fun reject(requestId: String, provider: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state != SshProviderRequestState.PENDING_REVIEW) return false
        val response: Any = when (stored.kind) {
            SshProviderRequestKind.SIGN -> {
                val request = requireNotNull(stored.signRequest)
                SshSignResult(
                    request.requestId,
                    request.requesterClientId,
                    sha256(request.publicKeyBlob),
                    SshSignResultKind.REJECTED_BY_USER,
                    now,
                    provider,
                    rejection = SshUserRejection(SshUserRejectionReason.USER_TAPPED_REJECT),
                )
            }
            SshProviderRequestKind.IMPORT -> {
                val request = requireNotNull(stored.importRequest)
                SshImportResult(
                    request.requestId,
                    request.requesterClientId,
                    provider,
                    now,
                    SshImportResultKind.USER_DECLINED,
                )
            }
        }
        return storeResponse(stored, response, now)
    }

    @Synchronized
    fun cancelSign(requestId: String, requester: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.kind != SshProviderRequestKind.SIGN || stored.requesterClientId != requester ||
            stored.state != SshProviderRequestState.PENDING_REVIEW
        ) return false
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.CANCELLED.name)
            put("outcome", SshProviderRequestOutcome.CANCELLED.name)
            put("result_at", now)
            putNull("request_cbor")
            putNull("request_nonce")
            put("updated_at", now)
        }
        val changed = writableDatabase.update("provider_requests", values, "request_id=?", arrayOf(requestId)) == 1
        if (changed) {
            pruneHistory(writableDatabase)
            notifyChanged()
        }
        return changed
    }

    @Synchronized
    fun markSent(requestId: String, now: Long): Boolean {
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.SENT.name)
            putNull("response_cbor")
            putNull("response_nonce")
            put("updated_at", now)
        }
        val changed = writableDatabase.update(
            "provider_requests",
            values,
            "request_id=? AND state=?",
            arrayOf(requestId, SshProviderRequestState.RESPONSE_PENDING_SEND.name),
        ) == 1
        if (changed) {
            pruneHistory(writableDatabase)
            notifyChanged()
        }
        return changed
    }

    @Synchronized
    fun expireDue(now: Long): List<String> {
        val expired = pendingReview().filter { request ->
            val expiresAt = request.signRequest?.expiresAt ?: request.importRequest?.expiresAt ?: Long.MAX_VALUE
            now > expiresAt
        }.map(StoredSshProviderRequest::requestId)
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.EXPIRED.name)
            put("outcome", SshProviderRequestOutcome.EXPIRED.name)
            put("result_at", now)
            putNull("request_cbor")
            putNull("request_nonce")
            put("updated_at", now)
        }
        expired.forEach { writableDatabase.update("provider_requests", values, "request_id=?", arrayOf(it)) }
        if (expired.isNotEmpty()) {
            pruneHistory(writableDatabase)
            notifyChanged()
        }
        return expired
    }

    @Synchronized
    fun cancelInvalidatedPending(now: Long): List<String> {
        val cancelled = pendingReview().filter { stored ->
            val request = stored.signRequest
            request != null && request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            )
        }.map(StoredSshProviderRequest::requestId)
        if (cancelled.isEmpty()) return emptyList()
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.CANCELLED.name)
            put("outcome", SshProviderRequestOutcome.CANCELLED.name)
            put("result_at", now)
            putNull("request_cbor")
            putNull("request_nonce")
            put("updated_at", now)
        }
        cancelled.forEach { requestId ->
            writableDatabase.update(
                "provider_requests",
                values,
                "request_id=? AND state=?",
                arrayOf(requestId, SshProviderRequestState.PENDING_REVIEW.name),
            )
        }
        pruneHistory(writableDatabase)
        notifyChanged()
        return cancelled
    }

    private fun accept(
        kind: SshProviderRequestKind,
        requestId: String,
        requester: ClientId,
        cbor: ByteArray,
        history: SshRequestHistorySnapshot,
        now: Long,
    ): SshProviderAcceptResult {
        return try {
            pruneHistory(writableDatabase)
            val fingerprint = sha256(cbor)
            val existing = findRequestIdentity(requestId)
            if (existing != null) {
                if (existing.kind == kind && existing.requesterClientId == requester &&
                    MessageDigest.isEqual(existing.requestFingerprint, fingerprint)
                ) SshProviderAcceptResult.DUPLICATE else SshProviderAcceptResult.CONFLICT
            } else {
                expireDue(now)
                val pending = pendingReview()
                if (pending.size >= MAX_PENDING_GLOBAL ||
                    pending.count { it.requesterClientId == requester } >= MAX_PENDING_PER_REQUESTER
                ) {
                    SshProviderAcceptResult.RATE_LIMITED
                } else {
                    val values = ContentValues().apply {
                        val encrypted = auditWrapping.encrypt(cbor, auditAad(requestId, AUDIT_REQUEST))
                        val historyBytes = ProtocolCodec.encodeToCbor(history)
                        val encryptedHistory = try {
                            auditWrapping.encrypt(historyBytes, auditAad(requestId, AUDIT_HISTORY))
                        } finally {
                            historyBytes.fill(0)
                        }
                        put("request_id", requestId)
                        put("kind", kind.name)
                        put("requester_client_id", requester.value)
                        put("request_fingerprint", fingerprint)
                        put("request_cbor", encrypted.first)
                        put("request_nonce", encrypted.second)
                        put("history_cbor", encryptedHistory.first)
                        put("history_nonce", encryptedHistory.second)
                        put("state", SshProviderRequestState.PENDING_REVIEW.name)
                        put("updated_at", now)
                    }
                    writableDatabase.insertOrThrow("provider_requests", null, values)
                    notifyChanged()
                    SshProviderAcceptResult.STORED
                }
            }
        } finally {
            cbor.fill(0)
        }
    }

    private fun findRequestIdentity(requestId: String): StoredRequestIdentity? = readableDatabase.rawQuery(
        "SELECT kind, requester_client_id, request_fingerprint FROM provider_requests WHERE request_id=?",
        arrayOf(requestId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredRequestIdentity(
            kind = SshProviderRequestKind.valueOf(cursor.getString(0)),
            requesterClientId = ClientId(cursor.getString(1)),
            requestFingerprint = cursor.getBlob(2),
        )
    }

    private fun sign(
        request: SshSignRequest,
        provider: ClientId,
        now: Long,
        rememberDisposition: SshRememberDisposition,
    ): SshSignResult {
        val row = findKeyMaterial(request.publicKeyBlob)
            ?: return signFailure(request, provider, now, SshProviderFailureCode.KEY_NOT_FOUND)
        return try {
            val method = signatureMethod(request)
            val jca = row.sign(method, request.data)
            signedResult(request, provider, now, rememberDisposition, method, jca)
        } catch (failure: Exception) {
            signFailure(request, provider, now, failure.signingFailureCode())
        }
    }

    private fun Throwable.signingFailureCode(): SshProviderFailureCode =
        if (generateSequence(this) { it.cause }.any { it is KeyPermanentlyInvalidatedException }) {
            SshProviderFailureCode.KEY_INVALIDATED
        } else {
            SshProviderFailureCode.INTERNAL_FAILURE
        }

    private fun signedResult(
        request: SshSignRequest,
        provider: ClientId,
        now: Long,
        rememberDisposition: SshRememberDisposition,
        method: SshSignatureMethod,
        jcaSignature: ByteArray,
    ): SshSignResult {
        val raw = if (method == SshSignatureMethod.ECDSA_NISTP256) {
            EcdsaSignatureTranscoder.derToSsh(jcaSignature)
        } else {
            jcaSignature
        }
        val signatureBlob = SshSignatureCodec.encode(method, raw)
        return signedResultFromBlob(request, provider, now, rememberDisposition, method, signatureBlob)
    }

    private fun signedResultFromBlob(
        request: SshSignRequest,
        provider: ClientId,
        now: Long,
        rememberDisposition: SshRememberDisposition,
        method: SshSignatureMethod,
        signatureBlob: ByteArray,
    ): SshSignResult {
        check(method.toProtocol() == request.requestedSignatureAlgorithm)
        check(
            SshSignatureVerifier.verify(
                publicKeyBlob = request.publicKeyBlob,
                data = request.data,
                signatureBlob = signatureBlob,
                expectedMethod = method,
                allowLegacyRsaSha1 = false,
            ),
        ) { "SSH operational provider produced an invalid SSH signature" }
        return SshSignResult(
            request.requestId,
            request.requesterClientId,
            sha256(request.publicKeyBlob),
            SshSignResultKind.SIGNED,
            now,
            provider,
            signature = SshSignatureResult(
                signatureBlob,
                rememberDisposition,
                request.authorizationGeneration,
                request.authorizationEpoch,
            ),
        )
    }

    private fun signatureMethod(request: SshSignRequest): SshSignatureMethod {
        val decoded = SshPublicKeyCodec.decode(request.publicKeyBlob)
        return SshSignatureVerifier.methodFor(decoded.type, request.flags, allowLegacyRsaSha1 = false).also {
            require(it.toProtocol() == request.requestedSignatureAlgorithm)
        }
    }

    private fun findKeyMaterial(publicBlob: ByteArray): StoredKeyMaterial? = readableDatabase.rawQuery(
        "SELECT k.provider_key_id, k.public_hash, k.algorithm, o.provider_kind, o.key_alias, " +
            "o.ciphertext, o.nonce, o.security_level, o.user_verification_policy " +
            "FROM ssh_keys k JOIN ssh_operational_keys o ON o.provider_key_id=k.provider_key_id " +
            "WHERE hex(k.public_hash)=?",
        arrayOf(sha256(publicBlob).toHex().uppercase()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredKeyMaterial(
            providerKeyId = cursor.getString(0),
            publicHash = cursor.getBlob(1),
            algorithm = SshKeyAlgorithm.valueOf(cursor.getString(2)),
            provider = SshOperationalKeyProvider.valueOf(cursor.getString(3)),
            keyAlias = cursor.getString(4),
            ciphertext = if (cursor.isNull(5)) null else cursor.getBlob(5),
            nonce = if (cursor.isNull(6)) null else cursor.getBlob(6),
            securityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(7)),
            userVerificationPolicy = SshUserVerificationPolicy.valueOf(cursor.getString(8)),
        )
    }

    private fun findWebAuthnCredential(publicBlob: ByteArray): StoredSshWebAuthnCredential? =
        readableDatabase.rawQuery(
            "SELECT k.provider_key_id, k.public_blob, w.credential_id, w.user_handle, w.rp_id, " +
                "w.cose_public_key, w.created_origin, w.backup_eligible, w.backup_state " +
                "FROM ssh_keys k JOIN ssh_webauthn_credentials w ON w.provider_key_id=k.provider_key_id " +
                "WHERE hex(k.public_hash)=?",
            arrayOf(sha256(publicBlob).toHex().uppercase()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else StoredSshWebAuthnCredential(
                providerKeyId = cursor.getString(0),
                publicKeyBlob = cursor.getBlob(1),
                credentialId = cursor.getBlob(2),
                userHandle = cursor.getBlob(3),
                rpId = cursor.getString(4),
                cosePublicKey = cursor.getBlob(5),
                createdOrigin = cursor.getString(6),
                backupEligible = cursor.getInt(7) != 0,
                backupState = cursor.getInt(8) != 0,
            )
        }

    private fun updateWebAuthnBackupState(
        credential: StoredSshWebAuthnCredential,
        assertion: ParsedSshWebAuthnAssertion,
    ) {
        if (credential.backupEligible == assertion.backupEligible && credential.backupState == assertion.backupState) {
            return
        }
        require(!assertion.backupState || assertion.backupEligible)
        val database = writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                "ssh_webauthn_credentials",
                ContentValues().apply {
                    put("backup_eligible", assertion.backupEligible)
                    put("backup_state", assertion.backupState)
                },
                "provider_key_id=?",
                arrayOf(credential.providerKeyId),
            ) == 1
            check(changed) { "WebAuthn credential disappeared during assertion" }
            bumpRevision(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        notifyChanged()
    }

    private fun import(
        request: SshImportRequest,
        provider: ClientId,
        now: Long,
        displayName: String,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray?,
    ): SshImportAttempt {
        var sensitivePrivateBytes: ByteArray? = null
        return try {
            val material = when (request.sourceType) {
                SshImportSourceType.AGENT_IDENTITY -> {
                    val parsed = AgentAddIdentityParser.parse(
                        requireNotNull(request.agentIdentity),
                        constrained = request.constraints != null,
                    )
                    require(parsed.constraints.lifetimeSeconds == request.constraints?.lifetimeSeconds)
                    require(parsed.constraints.confirm == (request.constraints?.confirmationRequired ?: false))
                    ImportedKeyMaterial(
                        parsed.privateKey,
                        SshPublicKeyCodec.decode(parsed.publicKeyBlob).publicKey,
                        parsed.publicKeyBlob,
                        parsed.type.toProtocol(),
                        parsed.comment,
                    )
                }
                SshImportSourceType.PRIVATE_KEY_FILE -> {
                    val parsed = SshPrivateKeyFileParser.parse(requireNotNull(request.fileBytes), passphrase)
                    sensitivePrivateBytes = parsed.pkcs8PrivateKey
                    ImportedKeyMaterial(
                        softwarePrivateKey(parsed.algorithm, parsed.pkcs8PrivateKey),
                        SshPublicKeyCodec.decode(parsed.publicKeyBlob).publicKey,
                        parsed.publicKeyBlob,
                        parsed.algorithm,
                        "",
                    )
                }
            }
            val hash = sha256(material.publicBlob)
            val existing = findKeyId(hash)
            if (existing != null) {
                SshImportAttempt.Complete(
                    SshImportResult(
                        request.requestId, request.requesterClientId, provider, now,
                        SshImportResultKind.ALREADY_PRESENT, existing, material.publicBlob,
                    ),
                )
            } else {
                when (val storage = storeImportedKey(
                    privateKey = material.privateKey,
                    publicKey = material.publicKey,
                    publicBlob = material.publicBlob,
                    algorithm = material.algorithm,
                    displayName = displayName,
                    origin = if (request.sourceType == SshImportSourceType.AGENT_IDENTITY) {
                        SshKeyOrigin.AGENT_ADD
                    } else {
                        SshKeyOrigin.DATA_SYNC_FILE
                    },
                    exportCopyBackendPolicy = exportCopyBackendPolicy.takeIf { allowExport },
                    userVerificationPolicy = userVerificationPolicy,
                    createdAt = now,
                    expiresAt = request.constraints?.lifetimeSeconds?.let { now + it * 1_000 },
                )) {
                    is SshKeyStorageResult.Stored -> SshImportAttempt.Complete(
                        SshImportResult(
                            request.requestId, request.requesterClientId, provider, now,
                            SshImportResultKind.IMPORTED, storage.descriptor.providerKeyId, material.publicBlob,
                        ),
                    )
                    is SshKeyStorageResult.AuthenticationRequired -> SshImportAttempt.AuthenticationRequired(
                        storage.prepared,
                        material.publicBlob.copyOf(),
                    )
                }
            }
        } catch (failure: Exception) {
            Log.w(SSH_STORAGE_LOG_TAG, "SSH ${request.sourceType} import failed", failure)
            SshImportAttempt.Complete(
                SshImportResult(
                    request.requestId, request.requesterClientId, provider, now,
                    SshImportResultKind.FAILED,
                    message = "SSH identity import failed: ${failure.failureSummary()}".take(512),
                ),
            )
        } finally {
            sensitivePrivateBytes?.fill(0)
        }
    }

    private fun storeResponse(
        stored: StoredSshProviderRequest,
        response: Any,
        now: Long,
        database: SQLiteDatabase = writableDatabase,
        notify: Boolean = true,
        approvalAudit: ApprovalAudit? = null,
    ): Boolean {
        val encoded = when (response) {
            is SshSignResult -> ProtocolCodec.encodeToCbor(response)
            is SshImportResult -> ProtocolCodec.encodeToCbor(response)
            else -> error("unsupported SSH provider response")
        }
        val outcome = when (response) {
            is SshSignResult -> when (response.kind) {
                SshSignResultKind.SIGNED -> SshProviderRequestOutcome.SIGNED
                SshSignResultKind.REJECTED_BY_USER -> SshProviderRequestOutcome.REJECTED
                SshSignResultKind.PROVIDER_FAILURE -> SshProviderRequestOutcome.FAILED
            }
            is SshImportResult -> when (response.kind) {
                SshImportResultKind.IMPORTED -> SshProviderRequestOutcome.IMPORTED
                SshImportResultKind.ALREADY_PRESENT -> SshProviderRequestOutcome.ALREADY_PRESENT
                SshImportResultKind.USER_DECLINED -> SshProviderRequestOutcome.REJECTED
                SshImportResultKind.EXPIRED -> SshProviderRequestOutcome.EXPIRED
                SshImportResultKind.UNSUPPORTED,
                SshImportResultKind.FAILED,
                -> SshProviderRequestOutcome.FAILED
            }
            else -> error("unsupported SSH provider response")
        }
        val resultAt = when (response) {
            is SshSignResult -> response.resultAt
            is SshImportResult -> response.resultAt
            else -> error("unsupported SSH provider response")
        }
        val encrypted = auditWrapping.encrypt(encoded, auditAad(stored.requestId, AUDIT_RESPONSE))
        val responsePublicKey = (response as? SshImportResult)?.publicKeyBlob
        var updatedHistory = if (responsePublicKey != null) {
            stored.history.publicKeyBlob?.let { previewed ->
                check(MessageDigest.isEqual(previewed, responsePublicKey)) {
                    "SSH import result does not match the reviewed public key"
                }
            }
            stored.history.copy(
                publicKeyBlob = responsePublicKey.copyOf(),
                keyName = keyDisplayName(responsePublicKey) ?: stored.history.keyName,
            )
        } else {
            stored.history
        }
        if (approvalAudit != null && response is SshSignResult && response.kind == SshSignResultKind.SIGNED) {
            updatedHistory = updatedHistory.copy(
                approvalKind = approvalAudit.kind,
                rememberedAuthorizationId = approvalAudit.rememberedAuthorizationId,
                rememberedScope = approvalAudit.rememberedScope,
            )
        }
        val historyBytes = ProtocolCodec.encodeToCbor(updatedHistory)
        val encryptedHistory = try {
            auditWrapping.encrypt(historyBytes, auditAad(stored.requestId, AUDIT_HISTORY))
        } finally {
            historyBytes.fill(0)
        }
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.RESPONSE_PENDING_SEND.name)
            put("outcome", outcome.name)
            put("result_at", resultAt)
            putNull("request_cbor")
            putNull("request_nonce")
            put("history_cbor", encryptedHistory.first)
            put("history_nonce", encryptedHistory.second)
            put("response_cbor", encrypted.first)
            put("response_nonce", encrypted.second)
            put("updated_at", now)
        }
        val changed = database.update(
            "provider_requests",
            values,
            "request_id=? AND state=?",
            arrayOf(stored.requestId, SshProviderRequestState.PENDING_REVIEW.name),
        ) == 1
        if (changed && notify) notifyChanged()
        return changed
    }

    private fun storeUserApprovedSignResponse(
        stored: StoredSshProviderRequest,
        response: SshSignResult,
        now: Long,
    ): Boolean {
        if (response.kind != SshSignResultKind.SIGNED) return storeResponse(stored, response, now)
        val request = stored.signRequest ?: return false
        val database = writableDatabase
        database.beginTransaction()
        val consumed = try {
            observeKnownHost(database, request.destinationContext, now)
            storeResponse(
                stored,
                response,
                now,
                database,
                notify = false,
                approvalAudit = ApprovalAudit(SshRequestApprovalKind.MANUAL),
            ).also {
                if (it) database.setTransactionSuccessful()
            }
        } finally {
            database.endTransaction()
        }
        if (consumed) notifyChanged()
        return consumed
    }

    private fun observeKnownHost(
        database: SQLiteDatabase,
        destination: net.extrawdw.notisync.protocol.SshDestinationContext,
        now: Long,
    ): Boolean {
        val hostKeySha256 = SshRememberAuthorizationPolicy.verifiedHostKeySha256(destination) ?: return false
        val values = ContentValues().apply {
            put("host_key_sha256", hostKeySha256)
            putNull("hostname")
            put("first_approved_at", now)
            put("last_approved_at", now)
        }
        if (database.insertWithOnConflict(
                "ssh_known_hosts",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        ) return true
        val update = ContentValues().apply { put("last_approved_at", now) }
        return database.update(
            "ssh_known_hosts",
            update,
            "hex(host_key_sha256)=? AND last_approved_at<?",
            arrayOf(hostKeySha256.toHex().uppercase(), now.toString()),
        ) == 1
    }

    private fun signFailure(
        request: SshSignRequest,
        provider: ClientId,
        now: Long,
        code: SshProviderFailureCode,
    ) = SshSignResult(
        request.requestId,
        request.requesterClientId,
        sha256(request.publicKeyBlob),
        SshSignResultKind.PROVIDER_FAILURE,
        now,
        provider,
        failure = SshProviderFailure(code),
    )

    private fun requestsIn(state: SshProviderRequestState): List<StoredSshProviderRequest> =
        readableDatabase.rawQuery(
            "SELECT request_id, kind, requester_client_id, request_fingerprint, request_cbor, request_nonce, " +
                "history_cbor, history_nonce, state, outcome, result_at, response_cbor, response_nonce, updated_at " +
                "FROM provider_requests WHERE state=? ORDER BY updated_at",
            arrayOf(state.name),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest()) } }

    private fun android.database.Cursor.readRequest(decodeActiveRequest: Boolean = true): StoredSshProviderRequest {
        val kind = SshProviderRequestKind.valueOf(getString(1))
        val requestId = getString(0)
        val requestBytes = if (!decodeActiveRequest || isNull(4)) null else auditWrapping.decrypt(
            getBlob(4), getBlob(5), auditAad(requestId, AUDIT_REQUEST),
        )
        val historyBytes = auditWrapping.decrypt(
            getBlob(6), getBlob(7), auditAad(requestId, AUDIT_HISTORY),
        )
        return try {
            StoredSshProviderRequest(
                requestId = requestId,
                kind = kind,
                requesterClientId = ClientId(getString(2)),
                requestFingerprint = getBlob(3),
                signRequest = if (kind == SshProviderRequestKind.SIGN && requestBytes != null) {
                    ProtocolCodec.decodeFromCbor(requestBytes)
                } else null,
                importRequest = if (kind == SshProviderRequestKind.IMPORT && requestBytes != null) {
                    ProtocolCodec.decodeFromCbor(requestBytes)
                } else null,
                history = ProtocolCodec.decodeFromCbor(historyBytes),
                state = SshProviderRequestState.valueOf(getString(8)),
                outcome = if (isNull(9)) null else SshProviderRequestOutcome.valueOf(getString(9)),
                resultAt = if (isNull(10)) null else getLong(10),
                encodedResponse = if (isNull(11)) null else auditWrapping.decrypt(
                    getBlob(11),
                    getBlob(12),
                    auditAad(requestId, AUDIT_RESPONSE),
                ),
                updatedAt = getLong(13),
            )
        } finally {
            requestBytes?.fill(0)
            historyBytes.fill(0)
        }
    }

    private fun StoredSshProviderRequest.expiresAt(): Long =
        signRequest?.expiresAt ?: importRequest?.expiresAt ?: history.expiresAt

    private fun findKeyPolicy(publicKeyBlob: ByteArray): StoredKeyPolicy? = readableDatabase.rawQuery(
        "SELECT k.provider_key_id, k.approval_policy, o.user_verification_policy FROM ssh_keys k " +
            "JOIN ssh_operational_keys o ON o.provider_key_id=k.provider_key_id WHERE hex(k.public_hash)=?",
        arrayOf(sha256(publicKeyBlob).toHex().uppercase()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredKeyPolicy(
            providerKeyId = cursor.getString(0),
            approvalPolicy = SshApprovalPolicy.valueOf(cursor.getString(1)),
            userVerificationPolicy = SshUserVerificationPolicy.valueOf(cursor.getString(2)),
        )
    }

    private fun persistRememberedAuthorization(
        database: SQLiteDatabase,
        providerKeyId: String,
        request: SshSignRequest,
        scope: SshRememberScope,
        now: Long,
    ): RememberedAuthorizationWrite {
        val floor = authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        if (request.authorizationEpoch <= floor ||
            scope.authorizationStorage != SshRememberAuthorizationStorage.DISK
        ) {
            return RememberedAuthorizationWrite.Rejected
        }
        val hostKeySha256 = SshRememberAuthorizationPolicy.hostKeySha256ForPersistentAuthorization(
            scope,
            request.destinationContext,
        )
        if (scope == SshRememberScope.PEER_HOST_KEY && hostKeySha256 == null) {
            return RememberedAuthorizationWrite.Rejected
        }
        val existingId = database.rawQuery(
            "SELECT authorization_id, host_key_sha256 FROM ssh_remembered_authorizations WHERE provider_key_id=? " +
                "AND requester_client_id=? AND authorization_generation=? AND authorization_epoch=? AND scope=?",
            arrayOf(
                providerKeyId,
                request.requesterClientId.value,
                request.authorizationGeneration,
                request.authorizationEpoch.toString(),
                scope.name,
            ),
        ).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor else null }.firstNotNullOfOrNull {
                val storedHostKey = if (it.isNull(1)) null else it.getBlob(1)
                val matches = (scope == SshRememberScope.PEER && storedHostKey == null) ||
                    (hostKeySha256 != null && storedHostKey != null &&
                        MessageDigest.isEqual(hostKeySha256, storedHostKey))
                if (matches) it.getString(0) else null
            }
        }
        if (existingId != null) return RememberedAuthorizationWrite.Stored(existingId, created = false)
        if (database.rawQuery("SELECT COUNT(*) FROM ssh_remembered_authorizations", emptyArray()).use {
                it.moveToFirst() && it.getLong(0) >= MAX_REMEMBERED_AUTHORIZATIONS_GLOBAL
            }
        ) return RememberedAuthorizationWrite.Rejected
        if (database.rawQuery(
                "SELECT COUNT(*) FROM ssh_remembered_authorizations WHERE provider_key_id=?",
                arrayOf(providerKeyId),
            ).use {
                it.moveToFirst() && it.getLong(0) >= MAX_REMEMBERED_AUTHORIZATIONS_PER_KEY
            }
        ) return RememberedAuthorizationWrite.Rejected
        val authorizationId = randomId()
        val values = ContentValues().apply {
            put("authorization_id", authorizationId)
            put("provider_key_id", providerKeyId)
            put("requester_client_id", request.requesterClientId.value)
            put("authorization_generation", request.authorizationGeneration)
            put("authorization_epoch", request.authorizationEpoch)
            put("scope", scope.name)
            if (hostKeySha256 == null) putNull("host_key_sha256") else put("host_key_sha256", hostKeySha256)
            put("created_at", now)
        }
        return if (database.insertWithOnConflict(
                "ssh_remembered_authorizations",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
        ) {
            RememberedAuthorizationWrite.Stored(authorizationId, created = true)
        } else {
            // A concurrent duplicate can only be the same exact scope tuple; resolve its stable id.
            persistRememberedAuthorization(database, providerKeyId, request, scope, now)
        }
    }

    private fun matchingRememberedAuthorization(request: SshSignRequest): RememberedAuthorizationMatch? {
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return null
        // Persisted grants remain dormant while the key is set to Always ask.
        if (!SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                policy.approvalPolicy,
                policy.userVerificationPolicy,
            ) ||
            request.authorizationEpoch <= authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        ) return null
        val scopes = readableDatabase.rawQuery(
            "SELECT authorization_id, scope, host_key_sha256 FROM ssh_remembered_authorizations " +
                "WHERE provider_key_id=? AND requester_client_id=? " +
                "AND authorization_generation=? AND authorization_epoch=?",
            arrayOf(
                policy.providerKeyId,
                request.requesterClientId.value,
                request.authorizationGeneration,
                request.authorizationEpoch.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RememberedAuthorizationMatch(
                            authorizationId = cursor.getString(0),
                            scope = SshRememberScope.valueOf(cursor.getString(1)),
                            hostKeySha256 = if (cursor.isNull(2)) null else cursor.getBlob(2),
                        ),
                    )
                }
            }
        }
        scopes.firstOrNull {
                it.scope == SshRememberScope.PEER_HOST_KEY &&
                    SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                        it.scope,
                        it.hostKeySha256,
                        request.destinationContext,
                    )
            }
            ?.let { return it }
        return scopes.firstOrNull { it.scope == SshRememberScope.PEER }
    }

    private fun authorizationFloor(requester: ClientId, generation: String): Long = readableDatabase.rawQuery(
        "SELECT invalidated_through_epoch FROM authorization_floors " +
            "WHERE requester_client_id=? AND authorization_generation=?",
        arrayOf(requester.value, generation),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

    private fun rememberedNamespaces(): Map<String, List<SshRememberedNamespace>> {
        data class NamespaceKey(val requester: ClientId, val generation: String, val epoch: Long)
        val grouped = linkedMapOf<String, LinkedHashMap<NamespaceKey, MutableSet<SshRememberScope>>>()
        readableDatabase.rawQuery(
            "SELECT provider_key_id, requester_client_id, authorization_generation, authorization_epoch, scope " +
                "FROM ssh_remembered_authorizations " +
                "ORDER BY provider_key_id, requester_client_id, authorization_generation, " +
                "authorization_epoch, scope",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val byNamespace = grouped.getOrPut(cursor.getString(0)) { linkedMapOf() }
                val namespace = NamespaceKey(ClientId(cursor.getString(1)), cursor.getString(2), cursor.getLong(3))
                byNamespace.getOrPut(namespace) { linkedSetOf() }.add(SshRememberScope.valueOf(cursor.getString(4)))
            }
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

    private fun findKeyId(hash: ByteArray): String? = readableDatabase.rawQuery(
        "SELECT provider_key_id FROM ssh_keys WHERE hex(public_hash)=?",
        arrayOf(hash.toHex().uppercase()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun pruneExpiredKeys(now: Long) {
        val expired = readableDatabase.rawQuery(
            "SELECT provider_key_id FROM ssh_keys WHERE expires_at IS NOT NULL AND expires_at < ?",
            arrayOf(now.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        if (expired.isEmpty()) return
        expired.forEach(::deleteKey)
    }

    /** Keeps terminal audit rendering bounded without ever deleting pending reviews or unsent responses. */
    private fun pruneHistory(database: SQLiteDatabase) {
        database.execSQL(
            "DELETE FROM provider_requests WHERE request_id IN (" +
                "SELECT request_id FROM provider_requests WHERE state NOT IN " +
                "('PENDING_REVIEW', 'RESPONSE_PENDING_SEND') ORDER BY updated_at DESC " +
                "LIMIT -1 OFFSET $MAX_HISTORY_ROWS)",
        )
    }

    private fun bumpRevision(database: SQLiteDatabase = writableDatabase) {
        database.execSQL("UPDATE provider_state SET revision=revision+1 WHERE singleton=1")
    }

    private fun notifyChanged() {
        _changeVersion.update { if (it == Long.MAX_VALUE) 0 else it + 1 }
    }

    private fun shouldAttemptOperationalStrongBox(algorithm: SshKeyAlgorithm): Boolean =
        SshKeyStoragePolicy.shouldAttemptOperationalStrongBox(strongBoxAvailable, algorithm)

    private fun generateOperationalKeyPair(
        algorithm: SshKeyAlgorithm,
        alias: String,
        strongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
        rsaKeySizeBits: Int,
    ): KeyPair {
        val algorithms = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            listOf("Ed25519", KeyProperties.KEY_ALGORITHM_EC)
        } else {
            listOf(algorithm.keyStoreAlgorithm())
        }
        var firstFailure: Exception? = null
        for (generatorAlgorithm in algorithms) {
            check(!KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)) {
                "SSH operational alias already exists"
            }
            try {
                val pair = generateAndroidKeyPair(
                    algorithm,
                    generatorAlgorithm,
                    alias,
                    strongBox,
                    userVerificationPolicy,
                    rsaKeySizeBits,
                )
                validateOperationalPublicKey(pair.public, algorithm)
                val level = inspectKeyInfo(pair.private, algorithm, userVerificationPolicy)
                val expected = if (strongBox) {
                    SshStorageSecurityLevel.STRONGBOX
                } else {
                    SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
                }
                check(level == expected) { "Android Keystore did not honor the requested SSH signing backend" }
                return pair
            } catch (failure: Exception) {
                deleteAndroidKeyStoreAlias(alias)
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
        userVerificationPolicy: SshUserVerificationPolicy,
        rsaKeySizeBits: Int,
    ): java.security.KeyPair {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).apply {
            when (algorithm) {
                SshKeyAlgorithm.SSH_ED25519 -> {
                    // The Android Keystore EC+curve API is the CTS-covered path across Android releases. Some
                    // OEM providers expose an Ed25519-named generator but incorrectly create their default
                    // P-256 curve when no explicit parameter is supplied.
                    setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                    setDigests(KeyProperties.DIGEST_NONE)
                }
                SshKeyAlgorithm.SSH_RSA -> {
                    setKeySize(rsaKeySizeBits)
                    setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                }
                SshKeyAlgorithm.ECDSA_NISTP256 -> {
                    setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    setDigests(KeyProperties.DIGEST_SHA256)
                }
                SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
                    error("WebAuthn SSH keys cannot be generated in Android Keystore")
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
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
        now: Long,
        strongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        check(!store.containsAlias(alias)) { "SSH operational alias already exists" }
        validateOperationalPublicKey(publicKey, algorithm)
        // Software parsing and container-certificate construction are application invariants, not evidence that
        // a StrongBox candidate is unsupported. Keep them outside the typed candidate-fallback boundary.
        val privateKey = softwarePrivateKey(algorithm, privateKeyPkcs8)
        val certificate = createContainerCertificate(privateKey, publicKey, algorithm, now)
        try {
            installAndroidKeyStoreEntry(alias, privateKey, certificate, algorithm, strongBox, userVerificationPolicy)
        } catch (failure: Exception) {
            runCatching { deleteAndroidKeyStoreAlias(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
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
            val level = inspectKeyInfo(installed, algorithm, userVerificationPolicy)
            val expected = if (strongBox) {
                SshStorageSecurityLevel.STRONGBOX
            } else {
                SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
            }
            check(level == expected) { "Android Keystore did not honor the requested SSH signing backend" }
            return level
        } catch (failure: Exception) {
            runCatching { deleteAndroidKeyStoreAlias(alias) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw SshOperationalCandidateException(strongBox, cause = failure)
        }
    }

    private fun selfTest(
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey,
        algorithm: SshKeyAlgorithm,
    ) {
        val data = ByteArray(32).also(RANDOM::nextBytes)
        val signature = try {
            SshKeystoreJca.signature(algorithm.selfTestSignatureAlgorithm()).run {
                initSign(privateKey)
                update(data)
                sign()
            }
        } catch (failure: Exception) {
            data.fill(0)
            throw SshOperationalOperationException(failure)
        }
        check(verifySelfTestSignature(publicKey, algorithm, data, signature)) {
            "Android Keystore generated a key that failed its signing self-test"
        }
        data.fill(0)
        signature.fill(0)
    }

    private fun selfTestSoftware(privateKey: PrivateKey, publicKey: PublicKey, algorithm: SshKeyAlgorithm) {
        val data = ByteArray(32).also(RANDOM::nextBytes)
        val signer = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            Signature.getInstance(algorithm.selfTestSignatureAlgorithm(), BOUNCY_CASTLE)
        } else {
            Signature.getInstance(algorithm.selfTestSignatureAlgorithm())
        }
        val signature = signer.run {
            initSign(privateKey)
            update(data)
            sign()
        }
        check(verifySelfTestSignature(publicKey, algorithm, data, signature)) {
            "Imported SSH private key does not match its public key"
        }
        data.fill(0)
        signature.fill(0)
    }

    private fun verifySelfTestSignature(
        publicKey: PublicKey,
        algorithm: SshKeyAlgorithm,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val verificationKey = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            KeyFactory.getInstance("Ed25519", BOUNCY_CASTLE).generatePublic(
                X509EncodedKeySpec(requireNotNull(publicKey.encoded) { "Ed25519 public key is not encodable" }),
            )
        } else {
            publicKey
        }
        val verifier = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            Signature.getInstance(algorithm.selfTestSignatureAlgorithm(), BOUNCY_CASTLE)
        } else {
            Signature.getInstance(algorithm.selfTestSignatureAlgorithm())
        }
        return verifier.run {
            initVerify(verificationKey)
            update(data)
            verify(signature)
        }
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

    private fun inspectKeyInfo(
        privateKey: PrivateKey,
        algorithm: SshKeyAlgorithm,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        val info = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            var found: KeyInfo? = null
            var firstFailure: Exception? = null
            for (factoryAlgorithm in listOf("Ed25519", "ED25519")) {
                try {
                    found = SshKeystoreJca.keyFactory(factoryAlgorithm).getKeySpec(privateKey, KeyInfo::class.java)
                    break
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
                }
            }
            found ?: throw IllegalStateException("Android Keystore exposes no Ed25519 KeyFactory", firstFailure)
        } else {
            SshKeystoreJca.keyFactory(algorithm.keyStoreAlgorithm()).getKeySpec(privateKey, KeyInfo::class.java)
        }
        check(info.purposes and KeyProperties.PURPOSE_SIGN != 0) { "Android Keystore key cannot sign" }
        val requiredDigests = when (algorithm) {
            SshKeyAlgorithm.SSH_ED25519 -> setOf(KeyProperties.DIGEST_NONE)
            SshKeyAlgorithm.SSH_RSA -> setOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            SshKeyAlgorithm.ECDSA_NISTP256 -> setOf(KeyProperties.DIGEST_SHA256)
            SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
                error("WebAuthn SSH keys have no Android Keystore KeyInfo")
        }
        check(info.digests.toSet().containsAll(requiredDigests)) {
            "Android Keystore did not authorize the required SSH signature digest"
        }
        when (userVerificationPolicy) {
            SshUserVerificationPolicy.NONE -> check(!info.isUserAuthenticationRequired) {
                "Android Keystore unexpectedly requires user authentication"
            }
            SshUserVerificationPolicy.PER_USE -> {
                check(info.isUserAuthenticationRequired) { "Android Keystore did not bind user authentication" }
                check(info.userAuthenticationValidityDurationSeconds == 0) {
                    "Android Keystore did not bind authentication to every use"
                }
                check(info.userAuthenticationType and KeyProperties.AUTH_BIOMETRIC_STRONG != 0) {
                    "Android Keystore did not bind strong-biometric authentication"
                }
                check(info.isUserAuthenticationRequirementEnforcedBySecureHardware) {
                    "Android Keystore did not enforce SSH signing authentication in secure hardware"
                }
            }
        }
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SshStorageSecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
            else -> throw SshHardwareBackedKeystoreUnavailableException("Android Keystore SSH signing key")
        }
    }

    private fun deleteAndroidKeyStoreAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun reconcileLifecycle(db: SQLiteDatabase) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val unfinished = db.rawQuery(
            "SELECT provider_key_id, operational_alias, state FROM ssh_key_lifecycle",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        var inventoryChanged = false
        unfinished.forEach { (providerKeyId, operationalAlias, state) ->
            store.deleteEntry(operationalAlias)
            exportVault.deleteAll(providerKeyId)
            if (state == "DELETING") {
                inventoryChanged = db.delete("ssh_keys", "provider_key_id=?", arrayOf(providerKeyId)) > 0 ||
                    inventoryChanged
            }
            db.delete("ssh_key_lifecycle", "provider_key_id=?", arrayOf(providerKeyId))
        }
        // A missing Android Keystore alias can mean permanent invalidation, an OS restore, or a provider
        // failure. None of those authorizes silently deleting the key record or its independent export copy.
        // Keep the record and fail the requested operation; destructive recovery remains an explicit action.
        val referenced = linkedSetOf<String>()
        db.rawQuery("SELECT key_alias FROM ssh_operational_keys", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) referenced += cursor.getString(0)
        }
        db.rawQuery("SELECT key_alias FROM ssh_export_copies", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) referenced += cursor.getString(0)
        }
        store.aliases().toList()
            .filter { it.startsWith(KEY_ALIAS_PREFIX) || it.startsWith(EXPORT_COPY_ALIAS_PREFIX) }
            .filterNot(referenced::contains)
            .forEach(store::deleteEntry)
        if (inventoryChanged) bumpRevision(db)
    }

    private fun deleteAllSshKeyStoreAliases(): Exception? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliases = keyStore.aliases().toList().filter { it.startsWith(SSH_KEYSTORE_ALIAS_PREFIX) }
        var firstFailure: Exception? = null
        aliases.forEach { alias ->
            try {
                keyStore.deleteEntry(alias)
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure else firstFailure?.addSuppressed(failure)
            }
        }
        return firstFailure
    }

    private fun loadOperationalPrivateKey(alias: String): PrivateKey =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as? PrivateKey
            ?: error("Android Keystore SSH key is unavailable")

    private fun StoredKeyMaterial.privateKey(): PrivateKey {
        check(provider == SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY)
        return loadOperationalPrivateKey(keyAlias)
    }

    private fun StoredKeyMaterial.prepareWrappedUnwrap(): PreparedWrappedOperationalUnwrap {
        check(provider == SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED)
        check(algorithm == SshKeyAlgorithm.SSH_ED25519) {
            "Only Ed25519 may use the wrapped SSH operational provider"
        }
        return wrappedOperationalVault.prepareUnwrap(
            alias = keyAlias,
            providerKeyId = providerKeyId,
            ciphertext = requireNotNull(ciphertext),
            nonce = requireNotNull(nonce),
            algorithm = algorithm,
            publicKeyHash = publicHash,
            securityLevel = securityLevel,
            userVerificationPolicy = userVerificationPolicy,
        )
    }

    private fun StoredKeyMaterial.sign(method: SshSignatureMethod, data: ByteArray): ByteArray = when (provider) {
        SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY -> signRaw(method, privateKey(), data)
        SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED -> {
            check(userVerificationPolicy == SshUserVerificationPolicy.NONE) {
                "Per-use wrapped SSH keys require an authenticated operation"
            }
            val unwrap = prepareWrappedUnwrap()
            wrappedOperationalVault.completeUnwrap(unwrap).use { privateBytes ->
                signSoftwareRaw(method, softwarePrivateKey(algorithm, privateBytes.bytes), data)
            }
        }
        SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN ->
            error("WebAuthn SSH signatures require a Credential Manager assertion")
    }

    private class AndroidKeyWrapping(private val alias: String) {
        fun encrypt(plaintext: ByteArray, associatedData: ByteArray = EMPTY_BYTES): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            return cipher.doFinal(plaintext) to cipher.iv
        }

        fun decrypt(
            ciphertext: ByteArray,
            nonce: ByteArray,
            associatedData: ByteArray = EMPTY_BYTES,
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }
    }

    private fun SshKeyType.toProtocol(): SshKeyAlgorithm = when (this) {
        SshKeyType.ED25519 -> SshKeyAlgorithm.SSH_ED25519
        SshKeyType.RSA -> SshKeyAlgorithm.SSH_RSA
        SshKeyType.ECDSA_NISTP256 -> SshKeyAlgorithm.ECDSA_NISTP256
        SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256 -> SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256
    }

    private fun SshKeyAlgorithm.toKeyFactory(): String = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
        SshKeyAlgorithm.SSH_RSA -> "RSA"
        SshKeyAlgorithm.ECDSA_NISTP256 -> "EC"
        SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
            error("WebAuthn SSH keys have no exportable private-key factory")
    }

    private fun softwarePrivateKey(algorithm: SshKeyAlgorithm, encoded: ByteArray): PrivateKey {
        val factory = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            KeyFactory.getInstance(algorithm.toKeyFactory(), BOUNCY_CASTLE)
        } else {
            KeyFactory.getInstance(algorithm.toKeyFactory())
        }
        return factory.generatePrivate(PKCS8EncodedKeySpec(encoded))
    }

    private fun signRaw(
        method: SshSignatureMethod,
        privateKey: PrivateKey,
        data: ByteArray,
    ): ByteArray {
        return SshKeystoreJca.signature(method.jcaName).run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    private fun signSoftwareRaw(
        method: SshSignatureMethod,
        privateKey: PrivateKey,
        data: ByteArray,
    ): ByteArray {
        val signature = if (method == SshSignatureMethod.ED25519) {
            Signature.getInstance(method.jcaName, BOUNCY_CASTLE)
        } else {
            Signature.getInstance(method.jcaName)
        }
        return signature.run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    private fun SshKeyAlgorithm.selfTestSignatureAlgorithm(): String = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
        SshKeyAlgorithm.SSH_RSA -> "SHA256withRSA"
        SshKeyAlgorithm.ECDSA_NISTP256 -> "SHA256withECDSA"
        SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
            error("WebAuthn SSH keys are self-tested by assertion verification")
    }

    private fun SshKeyAlgorithm.keyStoreAlgorithm(): String = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> KeyProperties.KEY_ALGORITHM_EC
        SshKeyAlgorithm.SSH_RSA -> KeyProperties.KEY_ALGORITHM_RSA
        SshKeyAlgorithm.ECDSA_NISTP256 -> KeyProperties.KEY_ALGORITHM_EC
        SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 ->
            error("WebAuthn SSH keys are not Android Keystore keys")
    }

    private fun SshKeyAlgorithm.toCoreType(): SshKeyType = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> SshKeyType.ED25519
        SshKeyAlgorithm.SSH_RSA -> SshKeyType.RSA
        SshKeyAlgorithm.ECDSA_NISTP256 -> SshKeyType.ECDSA_NISTP256
        SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 -> SshKeyType.WEBAUTHN_SK_ECDSA_NISTP256
    }

    private fun SshSignatureMethod.toProtocol(): SshSignatureAlgorithm = when (this) {
        SshSignatureMethod.ED25519 -> SshSignatureAlgorithm.SSH_ED25519
        SshSignatureMethod.RSA_SHA2_256 -> SshSignatureAlgorithm.RSA_SHA2_256
        SshSignatureMethod.RSA_SHA2_512 -> SshSignatureAlgorithm.RSA_SHA2_512
        SshSignatureMethod.ECDSA_NISTP256 -> SshSignatureAlgorithm.ECDSA_NISTP256
        SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256 ->
            SshSignatureAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256
        SshSignatureMethod.RSA_SHA1_LEGACY -> SshSignatureAlgorithm.RSA_SHA1_LEGACY
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).toHex()
    private fun boundedImportName(candidate: String?): String {
        val name = candidate?.trim().orEmpty()
        return if (name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            name
        } else {
            "Imported SSH key"
        }
    }
    private fun SshSignRequest.historySnapshot(keyName: String?): SshRequestHistorySnapshot {
        return SshRequestHistorySnapshot(
            requestedAt = requestedAt,
            expiresAt = expiresAt,
            publicKeyBlob = publicKeyBlob.copyOf(),
            keyName = keyName,
            signatureAlgorithm = requestedSignatureAlgorithm,
            processLineage = processContext.processLineage,
            destinationUsername = destinationContext.username,
            destinationHost = destinationContext.hostAliases.firstOrNull()?.value,
            destinationHostKeyFingerprint = destinationContext.serverHostKeyBlob?.let { hostKey ->
                runCatching { SshFingerprint.sha256(hostKey) }.getOrNull()
            },
            payloadSize = data.size,
        )
    }
    private fun SshImportRequest.historySnapshot() = SshRequestHistorySnapshot(
        requestedAt = requestedAt,
        expiresAt = expiresAt,
        keyName = boundedImportName(suggestedName),
        suggestedName = suggestedName,
        importSourceType = sourceType,
        encryptedImport = sourceType == SshImportSourceType.PRIVATE_KEY_FILE &&
            runCatching { SshPrivateKeyFileParser.isEncrypted(requireNotNull(fileBytes)) }.getOrDefault(false),
        payloadSize = fileBytes?.size ?: agentIdentity?.size ?: 0,
    )
    private fun auditAad(requestId: String, purpose: String): ByteArray =
        "notisync:ssh-provider-audit:v1:$purpose:$requestId".encodeToByteArray()
    private data class StoredKeyMaterial(
        val providerKeyId: String,
        val publicHash: ByteArray,
        val algorithm: SshKeyAlgorithm,
        val provider: SshOperationalKeyProvider,
        val keyAlias: String,
        val ciphertext: ByteArray?,
        val nonce: ByteArray?,
        val securityLevel: SshStorageSecurityLevel,
        val userVerificationPolicy: SshUserVerificationPolicy,
    )

    private data class StoredRequestIdentity(
        val kind: SshProviderRequestKind,
        val requesterClientId: ClientId,
        val requestFingerprint: ByteArray,
    )

    private data class ImportedKeyMaterial(
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

    private data class ExportMaterial(
        val publicHash: ByteArray,
        val algorithm: SshKeyAlgorithm,
        val ciphertext: ByteArray,
        val nonce: ByteArray,
        val securityLevel: SshStorageSecurityLevel,
    )

    private data class StoredKeyPolicy(
        val providerKeyId: String,
        val approvalPolicy: SshApprovalPolicy,
        val userVerificationPolicy: SshUserVerificationPolicy,
    )

    private data class RememberedAuthorizationMatch(
        val authorizationId: String,
        val scope: SshRememberScope,
        val hostKeySha256: ByteArray?,
    )

    private sealed interface RememberedAuthorizationWrite {
        data object Rejected : RememberedAuthorizationWrite
        data class Stored(val authorizationId: String, val created: Boolean) : RememberedAuthorizationWrite
    }

    private data class ApprovalAudit(
        val kind: SshRequestApprovalKind,
        val rememberedAuthorizationId: String? = null,
        val rememberedScope: SshRememberScope? = null,
    )

    private companion object {
        const val SSH_STORAGE_LOG_TAG = "NotiSyncSshStorage"
        const val SSH_KEYSTORE_ALIAS_PREFIX = "notisync_ssh_"
        const val AUDIT_KEY_ALIAS = "notisync_ssh_audit_wrapping_v1"
        const val KEY_ALIAS_PREFIX = "notisync_ssh_identity_"
        const val WEBAUTHN_ALIAS_PREFIX = "notisync_ssh_webauthn_"
        const val EXPORT_COPY_ALIAS_PREFIX = "notisync_ssh_export_copy_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ED25519_OID = "1.3.101.112"
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val AUDIT_REQUEST = "request"
        const val AUDIT_RESPONSE = "response"
        const val AUDIT_HISTORY = "history"
        const val MAX_PENDING_GLOBAL = 128
        const val MAX_PENDING_PER_REQUESTER = 16
        const val MAX_HISTORY_ROWS = 500
        const val MAX_REMEMBERED_AUTHORIZATIONS_GLOBAL = 1_024L
        const val CERTIFICATE_CLOCK_SKEW_MILLIS = 5 * 60_000L
        const val CERTIFICATE_VALIDITY_MILLIS = 20L * 365 * 24 * 60 * 60 * 1_000
        const val DEFAULT_RSA_KEY_SIZE_BITS = 3072
        val SUPPORTED_RSA_KEY_SIZE_BITS = setOf(2048, DEFAULT_RSA_KEY_SIZE_BITS, 4096)
        val BOUNCY_CASTLE = BouncyCastleProvider()
        const val MAX_REMEMBERED_AUTHORIZATIONS_PER_KEY = 128L
        val EMPTY_BYTES = ByteArray(0)
        val RANDOM = SecureRandom()
        val EXPECTED_DATABASE_SCHEMA = mapOf(
            "provider_state" to setOf("singleton", "inventory_generation", "revision"),
            "ssh_keys" to setOf(
                "provider_key_id", "public_blob", "public_hash", "algorithm", "display_name", "origin",
                "approval_policy", "created_at", "expires_at",
            ),
            "ssh_operational_keys" to setOf(
                "provider_key_id", "provider_kind", "key_alias", "ciphertext", "nonce", "security_level",
                "user_verification_policy", "strongbox_attempted", "strongbox_fallback",
            ),
            "ssh_webauthn_credentials" to setOf(
                "provider_key_id", "credential_id", "user_handle", "rp_id", "cose_public_key",
                "created_origin", "backup_eligible", "backup_state",
            ),
            "ssh_export_copies" to setOf(
                "provider_key_id", "key_alias", "ciphertext", "nonce", "security_level", "backend_policy",
                "authentication", "strongbox_attempted", "strongbox_fallback", "last_verified_at",
            ),
            "ssh_key_lifecycle" to setOf(
                "provider_key_id", "operational_alias", "state", "created_at",
                "operational_candidate_ciphertext", "operational_candidate_nonce",
                "operational_candidate_security_level", "export_candidate_ciphertext", "export_candidate_nonce",
                "export_candidate_security_level",
            ),
            "authorization_floors" to setOf(
                "requester_client_id", "authorization_generation", "invalidated_through_epoch", "updated_at",
            ),
            "ssh_remembered_authorizations" to setOf(
                "authorization_id", "provider_key_id", "requester_client_id", "authorization_generation",
                "authorization_epoch", "scope", "host_key_sha256", "created_at",
            ),
            "ssh_known_hosts" to setOf(
                "host_key_sha256", "hostname", "first_approved_at", "last_approved_at",
            ),
            "provider_requests" to setOf(
                "request_id", "kind", "requester_client_id", "request_fingerprint", "request_cbor",
                "request_nonce", "history_cbor", "history_nonce", "state", "outcome", "result_at",
                "response_cbor", "response_nonce", "updated_at",
            ),
        )
    }
}

private fun Throwable.failureSummary(): String =
    "${javaClass.simpleName}${message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
