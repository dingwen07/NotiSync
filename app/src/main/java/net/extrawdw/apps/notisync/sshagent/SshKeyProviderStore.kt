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
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshExportability
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshImportResultKind
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
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
import net.extrawdw.notisync.protocol.SshStorageBackend
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserRejection
import net.extrawdw.notisync.protocol.SshUserRejectionReason
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.AgentAddIdentityParser
import net.extrawdw.notisync.ssh.core.EcdsaSignatureTranscoder
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
enum class SshProviderAcceptResult { STORED, DUPLICATE, CONFLICT, RATE_LIMITED, AUTHORIZATION_INVALIDATED }

data class SshAuthorizationForgetOutcome(
    val inventoryChanged: Boolean,
    val cancelledRequestIds: List<String>,
)

data class StoredSshProviderRequest(
    val requestId: String,
    val kind: SshProviderRequestKind,
    val requesterClientId: ClientId,
    val requestDigest: ByteArray,
    val signRequest: SshSignRequest? = null,
    val importRequest: SshImportRequest? = null,
    val state: SshProviderRequestState,
    val encodedResponse: ByteArray? = null,
    val updatedAt: Long,
)

class PreparedSshSignature internal constructor(
    val requestId: String,
    val requestDigest: ByteArray,
    val signature: Signature? = null,
    internal val keyUnwrap: PreparedSshKeyUnwrap? = null,
    internal val method: SshSignatureMethod,
)

class PreparedSshKeyExport internal constructor(
    val providerKeyId: String,
    val cipher: Cipher,
    val requiresCryptoAuthentication: Boolean,
    internal val publicHash: ByteArray,
    internal val unwrap: PreparedSshKeyUnwrap,
    internal val backend: SshStorageBackend,
)

sealed interface SshKeyStorageResult {
    data class Stored(val descriptor: SshKeyDescriptor) : SshKeyStorageResult
    data class AuthenticationRequired(val prepared: PreparedSshKeyStorage) : SshKeyStorageResult
}

class PreparedSshKeyStorage internal constructor(
    val cipher: Cipher,
    internal val owner: SshKeyProviderStore,
    internal val storeResetEpoch: Long,
    internal val protection: PreparedSshKeyProtection,
    internal val record: PendingSshKeyRecord,
    internal val materialKind: PreparedStorageMaterialKind,
) {
    internal var committed = false
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
    val cipher: Cipher,
    internal val keyStorage: PreparedSshKeyStorage,
    internal val requestId: String,
    internal val requestDigest: ByteArray,
    internal val requesterClientId: ClientId,
    internal val publicKeyBlob: ByteArray,
)

internal enum class PreparedStorageMaterialKind { EXPORT_BACKUP, OPERATIONAL_KEY }

internal data class PendingSshKeyRecord(
    val providerKeyId: String,
    val publicBlob: ByteArray,
    val publicHash: ByteArray,
    val algorithm: SshKeyAlgorithm,
    val displayName: String,
    val origin: SshKeyOrigin,
    val exportability: SshExportability,
    val backend: SshStorageBackend,
    val securityLevel: SshStorageSecurityLevel,
    val userVerificationPolicy: SshUserVerificationPolicy,
    val keyAlias: String?,
    val createdAt: Long,
    val expiresAt: Long?,
)

/** Durable Android key inventory, pending approvals, and response outbox. */
class SshKeyProviderStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION) {
    private val appContext = context.applicationContext
    private val strongBoxAvailable = context.applicationContext.packageManager
        .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val auditWrapping = AndroidKeyWrapping(AUDIT_KEY_ALIAS)
    private val exportVault = SshExportKeyVault(strongBoxAvailable)
    private val perKeyWrapping = SshAesKeyWrapper(strongBoxAvailable)
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()
    private var resetEpoch = 0L

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE provider_state(
              singleton INTEGER PRIMARY KEY CHECK(singleton=1),
              inventory_generation TEXT NOT NULL,
              revision INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE ssh_keys(
              provider_key_id TEXT PRIMARY KEY,
              public_blob BLOB NOT NULL,
              public_hash BLOB NOT NULL UNIQUE,
              algorithm TEXT NOT NULL,
              display_name TEXT NOT NULL,
              origin TEXT NOT NULL,
              exportability TEXT NOT NULL,
              storage_backend TEXT NOT NULL,
              storage_security_level TEXT NOT NULL,
              approval_policy TEXT NOT NULL,
              user_verification_policy TEXT NOT NULL,
              encrypted_pkcs8 BLOB,
              nonce BLOB,
              key_alias TEXT UNIQUE,
              created_at INTEGER NOT NULL,
              expires_at INTEGER,
              CHECK(
                (storage_backend='WRAPPED_SOFTWARE' AND encrypted_pkcs8 IS NOT NULL AND nonce IS NOT NULL AND key_alias IS NULL)
                OR (storage_backend='ANDROID_KEYSTORE' AND key_alias IS NOT NULL)
              )
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE authorization_floors(
              requester_client_id TEXT NOT NULL,
              authorization_generation TEXT NOT NULL,
              invalidated_through_epoch INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              PRIMARY KEY(requester_client_id, authorization_generation)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE remember_rules(
              rule_id TEXT PRIMARY KEY,
              provider_key_id TEXT NOT NULL REFERENCES ssh_keys(provider_key_id) ON DELETE CASCADE,
              requester_client_id TEXT NOT NULL,
              authorization_generation TEXT NOT NULL,
              authorization_epoch INTEGER NOT NULL,
              scope TEXT NOT NULL,
              leaf_executable_path TEXT,
              parent_pid INTEGER,
              parent_start_epoch_millis INTEGER,
              parent_executable_path TEXT,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX remember_rules_match_idx ON remember_rules(" +
                "provider_key_id, requester_client_id, authorization_generation, authorization_epoch)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX remember_rules_peer_unique ON remember_rules(" +
                "provider_key_id, requester_client_id, authorization_generation, authorization_epoch, scope) " +
                "WHERE scope='PEER'",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX remember_rules_parent_unique ON remember_rules(" +
                "provider_key_id, requester_client_id, authorization_generation, authorization_epoch, scope, " +
                "leaf_executable_path, parent_pid, parent_start_epoch_millis, parent_executable_path) " +
                "WHERE scope='PARENT_PROCESS_SESSION'",
        )
        db.execSQL(
            """
            CREATE TABLE provider_requests(
              request_id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              requester_client_id TEXT NOT NULL,
              request_digest BLOB NOT NULL,
              request_cbor BLOB NOT NULL,
              request_nonce BLOB NOT NULL,
              state TEXT NOT NULL,
              response_cbor BLOB,
              response_nonce BLOB,
              updated_at INTEGER NOT NULL,
              CHECK(
                (response_cbor IS NULL AND response_nonce IS NULL)
                OR (response_cbor IS NOT NULL AND response_nonce IS NOT NULL)
              )
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX provider_requests_state_idx ON provider_requests(state, updated_at)")
        db.execSQL(
            "INSERT INTO provider_state(singleton, inventory_generation, revision) VALUES(1, ?, 1)",
            arrayOf(randomId()),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("unsupported SSH key provider database migration $oldVersion to $newVersion")

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
            "SELECT provider_key_id, public_blob, public_hash, algorithm, display_name, origin, " +
                "exportability, storage_backend, storage_security_level, approval_policy, " +
                "user_verification_policy, created_at FROM ssh_keys ORDER BY provider_key_id",
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
                            exportability = SshExportability.valueOf(cursor.getString(6)),
                            storageBackend = SshStorageBackend.valueOf(cursor.getString(7)),
                            storageSecurityLevel = SshStorageSecurityLevel.valueOf(cursor.getString(8)),
                            approvalPolicy = SshApprovalPolicy.valueOf(cursor.getString(9)),
                            userVerificationPolicy = SshUserVerificationPolicy.valueOf(cursor.getString(10)),
                            rememberedNamespaces = remembered[cursor.getString(0)].orEmpty(),
                            createdAt = cursor.getLong(11),
                        ),
                    )
                }
            }
        }
        return SshKeysSnapshot(provider, state.first, state.second, now, respondingToRequestId, keys, SshProviderHealth.HEALTHY)
    }

    @Synchronized
    fun generateKey(
        algorithm: SshKeyAlgorithm,
        displayName: String,
        now: Long,
        exportability: SshExportability = SshExportability.EXPORTABLE,
        preferStrongBox: Boolean = false,
        userVerificationPolicy: SshUserVerificationPolicy = SshUserVerificationPolicy.NONE,
        rsaKeySizeBits: Int = DEFAULT_RSA_KEY_SIZE_BITS,
    ): SshKeyStorageResult {
        require(now > 0) { "key creation time must be positive" }
        require(algorithm != SshKeyAlgorithm.SSH_RSA || rsaKeySizeBits in SUPPORTED_RSA_KEY_SIZE_BITS) {
            "unsupported RSA key size"
        }
        val name = displayName.trim()
        require(name.isNotEmpty() && name.encodeToByteArray().size <= SshAgentLimits.MAX_DISPLAY_NAME_UTF8_BYTES) {
            "key name is outside the allowed bounds"
        }
        return when (exportability) {
            SshExportability.EXPORTABLE -> generateSoftwareKey(
                algorithm,
                name,
                now,
                rsaKeySizeBits,
                exportability,
                preferStrongBox,
                userVerificationPolicy = userVerificationPolicy,
            )
            SshExportability.NON_EXPORTABLE -> runCatching {
                SshKeyStorageResult.Stored(generateAndroidKeyStoreKey(
                    algorithm,
                    name,
                    now,
                    rsaKeySizeBits,
                    preferStrongBox,
                    userVerificationPolicy,
                ))
            }.getOrElse {
                generateSoftwareKey(
                    algorithm,
                    name,
                    now,
                    rsaKeySizeBits,
                    exportability,
                    preferStrongBox,
                    userVerificationPolicy,
                )
            }
        }
    }

    private fun generateSoftwareKey(
        algorithm: SshKeyAlgorithm,
        name: String,
        now: Long,
        rsaKeySizeBits: Int,
        exportability: SshExportability,
        preferStrongBox: Boolean,
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
        }
        return storeImportedKey(
            privateKey = pair.private,
            publicKey = pair.public,
            publicBlob = SshPublicKeyCodec.encode(pair.public, algorithm.toCoreType()),
            algorithm = algorithm,
            displayName = name,
            origin = SshKeyOrigin.GENERATED,
            exportability = exportability,
            preferStrongBox = preferStrongBox,
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
        preferStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshKeyDescriptor {
        val keyId = randomId()
        val alias = KEY_ALIAS_PREFIX + keyId
        // Capability declarations are coarse. Honor the user's preference for every algorithm, wait for the
        // provider to complete without an app timeout, and fall back only after an actual StrongBox failure.
        val requestStrongBox = preferStrongBox && strongBoxAvailable
        fun generate(strongBox: Boolean): KeyPair {
            val generatorAlgorithms = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
                // Android has exposed Ed25519 through both the named generator and the older CTS-covered
                // EC+curve route. Try both for the requested key because OEM provider registrations differ.
                listOf("Ed25519", KeyProperties.KEY_ALGORITHM_EC)
            } else {
                listOf(algorithm.keyStoreAlgorithm())
            }
            var firstFailure: Exception? = null
            for (generatorAlgorithm in generatorAlgorithms) {
                deleteAndroidKeyStoreAlias(alias)
                try {
                    return generateAndroidKeyPair(
                        algorithm,
                        generatorAlgorithm,
                        alias,
                        strongBox,
                        userVerificationPolicy,
                        rsaKeySizeBits,
                    ).also { generated ->
                        // Never persist an OEM's default P-256 key as Ed25519 merely because the named generator
                        // accepted the request. The public half must match the requested SSH algorithm.
                        SshPublicKeyCodec.encode(generated.public, algorithm.toCoreType())
                        if (strongBox) {
                            check(
                                inspectKeyInfo(generated.private, algorithm, userVerificationPolicy) ==
                                    SshStorageSecurityLevel.STRONGBOX,
                            ) { "Android Keystore did not honor the StrongBox SSH key request" }
                        }
                        if (userVerificationPolicy == SshUserVerificationPolicy.NONE) {
                            selfTest(generated.private, generated.public, algorithm)
                        }
                    }
                } catch (failure: Exception) {
                    deleteAndroidKeyStoreAlias(alias)
                    val rootFailure = firstFailure
                    if (rootFailure == null) firstFailure = failure else rootFailure.addSuppressed(failure)
                }
            }
            throw requireNotNull(firstFailure)
        }
        val pair = try {
            try {
                generate(requestStrongBox)
            } catch (failure: Exception) {
                if (!requestStrongBox) throw failure
                deleteAndroidKeyStoreAlias(alias)
                generate(false)
            }
        } catch (failure: Exception) {
            deleteAndroidKeyStoreAlias(alias)
            throw IllegalStateException(
                if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
                    "This Android device cannot create a non-exportable Ed25519 key"
                } else {
                    "Android Keystore key generation failed"
                },
                failure,
            )
        }
        try {
            val publicBlob = SshPublicKeyCodec.encode(pair.public, algorithm.toCoreType())
            val hash = sha256(publicBlob)
            check(findKeyId(hash) == null) { "generated SSH key already exists" }
            val securityLevel = inspectKeyInfo(pair.private, algorithm, userVerificationPolicy)
            val values = ContentValues().apply {
                put("provider_key_id", keyId)
                put("public_blob", publicBlob)
                put("public_hash", hash)
                put("algorithm", algorithm.name)
                put("display_name", name)
                put("origin", SshKeyOrigin.GENERATED.name)
                put("exportability", SshExportability.NON_EXPORTABLE.name)
                put("storage_backend", SshStorageBackend.ANDROID_KEYSTORE.name)
                put("storage_security_level", securityLevel.name)
                put("approval_policy", SshApprovalPolicy.ALWAYS_ASK.name)
                put("user_verification_policy", userVerificationPolicy.name)
                // Version-1 databases retain NOT NULL on these columns after migration.
                put("encrypted_pkcs8", EMPTY_BYTES)
                put("nonce", EMPTY_BYTES)
                put("key_alias", alias)
                put("created_at", now)
            }
            val database = writableDatabase
            database.beginTransaction()
            try {
                database.insertOrThrow("ssh_keys", null, values)
                bumpRevision(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            notifyChanged()
            return SshKeyDescriptor(
                providerKeyId = keyId,
                publicKeyBlob = publicBlob,
                publicKeyBlobSha256 = hash,
                algorithm = algorithm,
                displayName = name,
                origin = SshKeyOrigin.GENERATED,
                exportability = SshExportability.NON_EXPORTABLE,
                storageBackend = SshStorageBackend.ANDROID_KEYSTORE,
                storageSecurityLevel = securityLevel,
                approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
                userVerificationPolicy = userVerificationPolicy,
                createdAt = now,
            )
        } catch (failure: Exception) {
            deleteAndroidKeyStoreAlias(alias)
            throw failure
        }
    }

    @Synchronized
    fun importPrivateKeyFile(
        fileBytes: ByteArray,
        passphrase: CharArray?,
        displayName: String,
        now: Long,
        exportability: SshExportability = SshExportability.NON_EXPORTABLE,
        preferStrongBox: Boolean = false,
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
                exportability = exportability,
                preferStrongBox = preferStrongBox,
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
            "SELECT public_hash, algorithm, exportability, storage_backend, encrypted_pkcs8, " +
                "user_verification_policy " +
                "FROM ssh_keys WHERE provider_key_id=?",
            arrayOf(providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            ExportMaterial(
                cursor.getBlob(0),
                SshKeyAlgorithm.valueOf(cursor.getString(1)),
                SshExportability.valueOf(cursor.getString(2)),
                SshStorageBackend.valueOf(cursor.getString(3)),
                if (cursor.isNull(4)) null else cursor.getBlob(4),
                SshUserVerificationPolicy.valueOf(cursor.getString(5)),
            )
        }
        if (row.exportability != SshExportability.EXPORTABLE) return null
        val bundle = SshKeyMaterialBundle.decode(requireNotNull(row.encodedMaterial))
        val prepared = when (row.backend) {
            SshStorageBackend.ANDROID_KEYSTORE -> exportVault.prepareUnwrap(
                requireNotNull(bundle.exportEnvelope) { "exportable Keystore key has no export backup" },
                providerKeyId,
                row.algorithm,
                row.publicHash,
            )
            SshStorageBackend.WRAPPED_SOFTWARE -> perKeyWrapping.prepareUnwrap(
                operationalWrappingAlias(providerKeyId),
                requireNotNull(bundle.operationalEnvelope) { "wrapped SSH key has no operational material" },
                operationalAad(providerKeyId, row.algorithm, row.publicHash),
            )
        }
        val requiresCryptoAuthentication = row.backend == SshStorageBackend.ANDROID_KEYSTORE ||
            row.userVerificationPolicy == SshUserVerificationPolicy.PER_USE
        return PreparedSshKeyExport(
            providerKeyId,
            prepared.cipher,
            requiresCryptoAuthentication,
            row.publicHash.copyOf(),
            prepared,
            row.backend,
        )
    }

    @Synchronized
    fun completeExport(prepared: PreparedSshKeyExport, authenticatedCipher: Cipher): ByteArray? {
        if (authenticatedCipher !== prepared.cipher) return null
        val current = readableDatabase.rawQuery(
            "SELECT public_hash, exportability, storage_backend FROM ssh_keys WHERE provider_key_id=?",
            arrayOf(prepared.providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Triple(
                cursor.getBlob(0),
                SshExportability.valueOf(cursor.getString(1)),
                SshStorageBackend.valueOf(cursor.getString(2)),
            )
        }
        if (!MessageDigest.isEqual(current.first, prepared.publicHash) ||
            current.second != SshExportability.EXPORTABLE || current.third != prepared.backend
        ) return null
        return when (prepared.backend) {
            SshStorageBackend.ANDROID_KEYSTORE -> exportVault.completeUnwrap(prepared.unwrap, authenticatedCipher)
            SshStorageBackend.WRAPPED_SOFTWARE -> perKeyWrapping.completeUnwrap(prepared.unwrap, authenticatedCipher)
        }
    }

    private fun storeImportedKey(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        publicBlob: ByteArray,
        algorithm: SshKeyAlgorithm,
        displayName: String,
        origin: SshKeyOrigin,
        exportability: SshExportability,
        preferStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
        createdAt: Long,
        expiresAt: Long?,
    ): SshKeyStorageResult {
        val hash = sha256(publicBlob)
        check(findKeyId(hash) == null) { "This SSH key is already present" }
        val keyId = randomId()
        val encoded = requireNotNull(privateKey.encoded) { "Imported private key cannot be encoded" }
        try {
            val alias = KEY_ALIAS_PREFIX + keyId
            val requestStrongBox = preferStrongBox && strongBoxAvailable
            val signingCopy = runCatching {
                val certificate = createContainerCertificate(privateKey, publicKey, algorithm, createdAt)
                fun install(strongBox: Boolean): SshStorageSecurityLevel {
                    installAndroidKeyStoreEntry(
                        alias,
                        privateKey,
                        certificate,
                        algorithm,
                        strongBox,
                        userVerificationPolicy,
                    )
                    val installed = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                        .getKey(alias, null) as? PrivateKey
                        ?: error("Imported Android Keystore key is unavailable")
                    check(installed.encoded == null) { "Android Keystore signing copy is unexpectedly exportable" }
                    if (userVerificationPolicy == SshUserVerificationPolicy.NONE) {
                        selfTest(installed, publicKey, algorithm)
                    }
                    return inspectKeyInfo(installed, algorithm, userVerificationPolicy).also { securityLevel ->
                        if (strongBox) {
                            check(securityLevel == SshStorageSecurityLevel.STRONGBOX) {
                                "Android Keystore did not honor the StrongBox SSH key import request"
                            }
                        }
                    }
                }
                try {
                    install(requestStrongBox)
                } catch (failure: Exception) {
                    if (!requestStrongBox) throw failure
                    deleteAndroidKeyStoreAlias(alias)
                    install(false)
                }
            }
            if (signingCopy.isSuccess) {
                val record = PendingSshKeyRecord(
                    keyId,
                    publicBlob.copyOf(),
                    hash,
                    algorithm,
                    displayName,
                    origin,
                    exportability,
                    SshStorageBackend.ANDROID_KEYSTORE,
                    signingCopy.getOrThrow(),
                    userVerificationPolicy,
                    alias,
                    createdAt,
                    expiresAt,
                )
                if (exportability == SshExportability.EXPORTABLE) {
                    val protection = try {
                        exportVault.protect(encoded, keyId, algorithm, hash)
                    } catch (failure: Exception) {
                        deleteAndroidKeyStoreAlias(alias)
                        throw failure
                    }
                    return when (protection) {
                        is SshKeyProtectionResult.Complete -> SshKeyStorageResult.Stored(
                            insertKeyRecord(
                                record,
                                SshKeyMaterialBundle(exportEnvelope = protection.material.envelope).encode(),
                            ),
                        )
                        is SshKeyProtectionResult.AuthenticationRequired ->
                            SshKeyStorageResult.AuthenticationRequired(
                                PreparedSshKeyStorage(
                                    protection.prepared.cipher,
                                    this,
                                    resetEpoch,
                                    protection.prepared,
                                    record,
                                    PreparedStorageMaterialKind.EXPORT_BACKUP,
                                ),
                            )
                    }
                }
                return SshKeyStorageResult.Stored(insertKeyRecord(record, null))
            } else {
                deleteAndroidKeyStoreAlias(alias)
                val wrappingAlias = operationalWrappingAlias(keyId)
                val protection = try {
                    perKeyWrapping.protect(
                        wrappingAlias,
                        encoded,
                        operationalAad(keyId, algorithm, hash),
                        preferStrongBox,
                        userVerificationPolicy,
                    )
                } catch (failure: Exception) {
                    perKeyWrapping.delete(wrappingAlias)
                    throw failure
                }
                val securityLevel = when (protection) {
                    is SshKeyProtectionResult.Complete -> protection.material.securityLevel
                    is SshKeyProtectionResult.AuthenticationRequired -> protection.prepared.securityLevel
                }
                val record = PendingSshKeyRecord(
                    keyId,
                    publicBlob.copyOf(),
                    hash,
                    algorithm,
                    displayName,
                    origin,
                    SshExportability.EXPORTABLE,
                    SshStorageBackend.WRAPPED_SOFTWARE,
                    securityLevel,
                    userVerificationPolicy,
                    null,
                    createdAt,
                    expiresAt,
                )
                return when (protection) {
                    is SshKeyProtectionResult.Complete -> SshKeyStorageResult.Stored(
                        insertKeyRecord(
                            record,
                            SshKeyMaterialBundle(operationalEnvelope = protection.material.envelope).encode(),
                        ),
                    )
                    is SshKeyProtectionResult.AuthenticationRequired ->
                        SshKeyStorageResult.AuthenticationRequired(
                            PreparedSshKeyStorage(
                                protection.prepared.cipher,
                                this,
                                resetEpoch,
                                protection.prepared,
                                record,
                                PreparedStorageMaterialKind.OPERATIONAL_KEY,
                            ),
                        )
                }
            }
        } finally {
            encoded.fill(0)
        }
    }

    @Synchronized
    fun completePreparedKeyStorage(
        prepared: PreparedSshKeyStorage,
        authenticatedCipher: Cipher,
    ): SshKeyDescriptor {
        require(prepared.owner === this) { "SSH key storage operation belongs to another provider" }
        if (prepared.storeResetEpoch != resetEpoch) {
            cancelPreparedKeyStorage(prepared)
            error("SSH key store was reset while key storage was awaiting authentication")
        }
        val material = when (prepared.materialKind) {
            PreparedStorageMaterialKind.EXPORT_BACKUP ->
                exportVault.completeProtect(prepared.protection, authenticatedCipher)
            PreparedStorageMaterialKind.OPERATIONAL_KEY ->
                perKeyWrapping.completeProtect(prepared.protection, authenticatedCipher)
        }
        val bundle = when (prepared.materialKind) {
            PreparedStorageMaterialKind.EXPORT_BACKUP -> SshKeyMaterialBundle(exportEnvelope = material.envelope)
            PreparedStorageMaterialKind.OPERATIONAL_KEY ->
                SshKeyMaterialBundle(operationalEnvelope = material.envelope)
        }
        return insertKeyRecord(prepared.record, bundle.encode()).also {
            prepared.committed = true
        }
    }

    @Synchronized
    fun cancelPreparedKeyStorage(prepared: PreparedSshKeyStorage) {
        require(prepared.owner === this) { "SSH key storage operation belongs to another provider" }
        if (prepared.committed) return
        when (prepared.materialKind) {
            PreparedStorageMaterialKind.EXPORT_BACKUP -> {
                if (!prepared.protection.consumed) exportVault.cancelProtect(prepared.protection)
            }
            PreparedStorageMaterialKind.OPERATIONAL_KEY -> {
                if (!prepared.protection.consumed) perKeyWrapping.cancelProtect(prepared.protection)
                perKeyWrapping.delete(operationalWrappingAlias(prepared.record.providerKeyId))
            }
        }
        prepared.record.keyAlias?.let(::deleteAndroidKeyStoreAlias)
    }

    private fun insertKeyRecord(record: PendingSshKeyRecord, encodedMaterial: ByteArray?): SshKeyDescriptor {
        val values = ContentValues().apply {
            put("provider_key_id", record.providerKeyId)
            put("public_blob", record.publicBlob)
            put("public_hash", record.publicHash)
            put("algorithm", record.algorithm.name)
            put("display_name", record.displayName)
            put("origin", record.origin.name)
            put("exportability", record.exportability.name)
            put("storage_backend", record.backend.name)
            put("storage_security_level", record.securityLevel.name)
            put("approval_policy", SshApprovalPolicy.ALWAYS_ASK.name)
            put("user_verification_policy", record.userVerificationPolicy.name)
            if (encodedMaterial == null) putNull("encrypted_pkcs8") else put("encrypted_pkcs8", encodedMaterial)
            if (encodedMaterial == null) putNull("nonce") else put("nonce", EMPTY_BYTES)
            if (record.keyAlias == null) putNull("key_alias") else put("key_alias", record.keyAlias)
            put("created_at", record.createdAt)
            if (record.expiresAt != null) put("expires_at", record.expiresAt)
        }
        val database = writableDatabase
        try {
            database.beginTransaction()
            try {
                database.insertOrThrow("ssh_keys", null, values)
                bumpRevision(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (failure: Exception) {
            record.keyAlias?.let(::deleteAndroidKeyStoreAlias)
            if (record.backend == SshStorageBackend.WRAPPED_SOFTWARE) {
                perKeyWrapping.delete(operationalWrappingAlias(record.providerKeyId))
            }
            throw failure
        }
        notifyChanged()
        return SshKeyDescriptor(
            providerKeyId = record.providerKeyId,
            publicKeyBlob = record.publicBlob,
            publicKeyBlobSha256 = record.publicHash,
            algorithm = record.algorithm,
            displayName = record.displayName,
            origin = record.origin,
            exportability = record.exportability,
            storageBackend = record.backend,
            storageSecurityLevel = record.securityLevel,
            approvalPolicy = SshApprovalPolicy.ALWAYS_ASK,
            userVerificationPolicy = record.userVerificationPolicy,
            createdAt = record.createdAt,
        )
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
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
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
            "SELECT storage_backend, key_alias FROM ssh_keys WHERE provider_key_id=?",
            arrayOf(providerKeyId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            SshStorageBackend.valueOf(cursor.getString(0)) to if (cursor.isNull(1)) null else cursor.getString(1)
        }
        val database = writableDatabase
        database.beginTransaction()
        val deleted = try {
            val deleted = database.delete("ssh_keys", "provider_key_id=?", arrayOf(providerKeyId)) == 1
            if (deleted) {
                if (stored.first == SshStorageBackend.ANDROID_KEYSTORE) {
                    deleteAndroidKeyStoreAlias(requireNotNull(stored.second))
                } else {
                    perKeyWrapping.delete(operationalWrappingAlias(providerKeyId))
                }
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
        // Both reads are best effort so this action can recover an incomplete/corrupt version-1 schema.
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

        close()
        check(appContext.deleteDatabase(DB_NAME) || !appContext.getDatabasePath(DB_NAME).exists()) {
            "SSH Agent database could not be removed"
        }

        resetEpoch = if (resetEpoch == Long.MAX_VALUE) 0L else resetEpoch + 1L
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliases = keyStore.aliases().toList().filter { it.startsWith(SSH_KEYSTORE_ALIAS_PREFIX) }
        var firstFailure: Exception? = null
        aliases.forEach { alias ->
            try {
                keyStore.deleteEntry(alias)
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
                else firstFailure?.addSuppressed(failure)
            }
        }
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
        val database = writableDatabase
        database.beginTransaction()
        val changed = try {
            val values = ContentValues().apply {
                put("display_name", name)
                put("approval_policy", approvalPolicy.name)
            }
            val changed = database.update("ssh_keys", values, "provider_key_id=?", arrayOf(providerKeyId)) == 1
            if (changed) {
                if (approvalPolicy == SshApprovalPolicy.ALWAYS_ASK) {
                    database.delete("remember_rules", "provider_key_id=?", arrayOf(providerKeyId))
                }
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
        return accept(
            SshProviderRequestKind.SIGN,
            request.requestId,
            request.requesterClientId,
            sha256(ProtocolCodec.encodeToCbor(request)),
            ProtocolCodec.encodeToCbor(request),
            now,
        )
    }

    @Synchronized
    fun acceptImport(request: SshImportRequest, now: Long): SshProviderAcceptResult = accept(
        SshProviderRequestKind.IMPORT,
        request.requestId,
        request.requesterClientId,
        sha256(ProtocolCodec.encodeToCbor(request)),
        ProtocolCodec.encodeToCbor(request),
        now,
    )

    @Synchronized
    fun find(requestId: String): StoredSshProviderRequest? = readableDatabase.rawQuery(
        "SELECT request_id, kind, requester_client_id, request_digest, request_cbor, request_nonce, " +
            "state, response_cbor, response_nonce, updated_at " +
            "FROM provider_requests WHERE request_id=?",
        arrayOf(requestId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readRequest() else null }

    @Synchronized
    fun pendingReview(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.PENDING_REVIEW)

    @Synchronized
    fun pendingResponses(): List<StoredSshProviderRequest> = requestsIn(SshProviderRequestState.RESPONSE_PENDING_SEND)

    @Synchronized
    fun requests(): List<StoredSshProviderRequest> = readableDatabase.rawQuery(
        "SELECT request_id, kind, requester_client_id, request_digest, request_cbor, request_nonce, " +
            "state, response_cbor, response_nonce, updated_at " +
            "FROM provider_requests ORDER BY updated_at DESC",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest()) } }

    @Synchronized
    fun availableRememberScopes(requestId: String): Set<SshRememberScope> {
        val stored = find(requestId) ?: return emptySet()
        val request = stored.signRequest ?: return emptySet()
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || request.confirmationRequired ||
            request.authorizationEpoch <= authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        ) return emptySet()
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return emptySet()
        if (policy.approvalPolicy != SshApprovalPolicy.ALLOW_REMEMBER ||
            policy.userVerificationPolicy != SshUserVerificationPolicy.NONE
        ) return emptySet()
        return buildSet {
            add(SshRememberScope.PEER)
            if (request.processContext.leaf != null && request.processContext.directParent != null) {
                add(SshRememberScope.PARENT_PROCESS_SESSION)
            }
        }
    }

    @Synchronized
    fun requiresPerUseUserVerification(requestId: String): Boolean {
        val request = find(requestId)?.signRequest ?: return false
        return findKeyPolicy(request.publicKeyBlob)?.userVerificationPolicy == SshUserVerificationPolicy.PER_USE
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
                stored.requestDigest,
                provider,
                now,
                SshRememberDisposition.NONE,
            )
            // Imports must pass through the explicit storage-choice review flow.
            SshProviderRequestKind.IMPORT -> return null
        }
        return response.takeIf { storeResponse(stored, it, now) }
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
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return null
        if (policy.userVerificationPolicy != SshUserVerificationPolicy.PER_USE) return null
        val material = findKeyMaterial(request.publicKeyBlob) ?: return null
        val method = signatureMethod(request)
        return when (material.backend) {
            SshStorageBackend.ANDROID_KEYSTORE -> PreparedSshSignature(
                requestId,
                stored.requestDigest.copyOf(),
                signature = Signature.getInstance(method.jcaName).apply { initSign(material.privateKey()) },
                method = method,
            )
            SshStorageBackend.WRAPPED_SOFTWARE -> {
                val bundle = SshKeyMaterialBundle.decode(requireNotNull(material.encryptedPkcs8))
                val envelope = requireNotNull(bundle.operationalEnvelope) { "wrapped SSH key has no operational material" }
                PreparedSshSignature(
                    requestId,
                    stored.requestDigest.copyOf(),
                    keyUnwrap = perKeyWrapping.prepareUnwrap(
                        operationalWrappingAlias(material.providerKeyId),
                        envelope,
                        operationalAad(material.providerKeyId, material.algorithm, material.publicHash),
                    ),
                    method = method,
                )
            }
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
        val stored = find(prepared.requestId) ?: return null
        val request = stored.signRequest ?: return null
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            !MessageDigest.isEqual(stored.requestDigest, prepared.requestDigest) ||
            (prepared.signature != null && authenticatedSignature !== prepared.signature) ||
            (prepared.keyUnwrap != null && authenticatedCipher !== prepared.keyUnwrap.cipher) ||
            request.authorizationEpoch <= authorizationFloor(
                request.requesterClientId,
                request.authorizationGeneration,
            ) ||
            findKeyPolicy(request.publicKeyBlob)?.userVerificationPolicy != SshUserVerificationPolicy.PER_USE
        ) return null
        val response = try {
            val jcaSignature = if (prepared.signature != null) {
                requireNotNull(authenticatedSignature).run {
                    update(request.data)
                    sign()
                }
            } else {
                val privateBytes = perKeyWrapping.completeUnwrap(
                    requireNotNull(prepared.keyUnwrap),
                    requireNotNull(authenticatedCipher),
                )
                try {
                    val privateKey = softwarePrivateKey(
                        requireNotNull(findKeyMaterial(request.publicKeyBlob)).algorithm,
                        privateBytes,
                    )
                    signRaw(prepared.method, privateKey, request.data, softwareKey = true)
                } finally {
                    privateBytes.fill(0)
                }
            }
            signedResult(
                request,
                stored.requestDigest,
                provider,
                now,
                SshRememberDisposition.NONE,
                prepared.method,
                jcaSignature,
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            signFailure(request, stored.requestDigest, provider, now, SshProviderFailureCode.KEY_INVALIDATED)
        } catch (_: Exception) {
            signFailure(request, stored.requestDigest, provider, now, SshProviderFailureCode.INTERNAL_FAILURE)
        }
        return response.takeIf { storeResponse(stored, it, now) }
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
        return storeResponse(stored, signFailure(request, stored.requestDigest, provider, now, code), now)
    }

    @Synchronized
    fun approveImport(
        requestId: String,
        provider: ClientId,
        now: Long,
        exportability: SshExportability,
        preferStrongBox: Boolean,
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
        val attempt = import(
            request,
            stored.requestDigest,
            provider,
            now,
            exportability,
            preferStrongBox,
            userVerificationPolicy,
            passphrase,
        )
        return when (attempt) {
            is SshImportAttempt.Complete -> {
                if (storeResponse(stored, attempt.response, now)) SshImportApprovalOutcome.Completed else null
            }
            is SshImportAttempt.AuthenticationRequired -> SshImportApprovalOutcome.AuthenticationRequired(
                PreparedSshImportStorage(
                    attempt.keyStorage.cipher,
                    attempt.keyStorage,
                    requestId,
                    stored.requestDigest.copyOf(),
                    request.requesterClientId,
                    attempt.publicKeyBlob.copyOf(),
                ),
            )
        }
    }

    @Synchronized
    fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: Cipher,
        provider: ClientId,
        now: Long,
    ): Boolean {
        val stored = find(prepared.requestId) ?: run {
            cancelPreparedKeyStorage(prepared.keyStorage)
            return false
        }
        if (stored.state != SshProviderRequestState.PENDING_REVIEW ||
            !MessageDigest.isEqual(stored.requestDigest, prepared.requestDigest) ||
            stored.requesterClientId != prepared.requesterClientId
        ) {
            cancelPreparedKeyStorage(prepared.keyStorage)
            return false
        }
        val response = runCatching {
            val descriptor = completePreparedKeyStorage(prepared.keyStorage, authenticatedCipher)
            SshImportResult(
                prepared.requestId,
                prepared.requestDigest,
                prepared.requesterClientId,
                provider,
                now,
                SshImportResultKind.IMPORTED,
                descriptor.providerKeyId,
                prepared.publicKeyBlob,
            )
        }.getOrElse {
            SshImportResult(
                prepared.requestId,
                prepared.requestDigest,
                prepared.requesterClientId,
                provider,
                now,
                SshImportResultKind.FAILED,
                message = "SSH identity import failed",
            )
        }
        return storeResponse(stored, response, now)
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
            request.confirmationRequired
        ) return null
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return null
        if (policy.approvalPolicy != SshApprovalPolicy.ALLOW_REMEMBER ||
            policy.userVerificationPolicy != SshUserVerificationPolicy.NONE
        ) return null
        if (!createRememberRule(policy.providerKeyId, request, scope, now)) return null
        val disposition = when (scope) {
            SshRememberScope.PEER -> SshRememberDisposition.CREATED_PEER
            SshRememberScope.PARENT_PROCESS_SESSION -> SshRememberDisposition.CREATED_PARENT_PROCESS
        }
        val response = sign(request, stored.requestDigest, provider, now, disposition)
        return response.takeIf { storeResponse(stored, it, now) }
    }

    @Synchronized
    fun autoApproveRemembered(requestId: String, provider: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        val request = stored.signRequest ?: return false
        if (stored.state != SshProviderRequestState.PENDING_REVIEW || stored.expiresAt() < now ||
            request.confirmationRequired
        ) return false
        val disposition = matchingRememberDisposition(request) ?: return false
        return storeResponse(
            stored,
            sign(request, stored.requestDigest, provider, now, disposition),
            now,
        )
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
                "remember_rules",
                "requester_client_id=? AND authorization_generation=? AND authorization_epoch<=?",
                arrayOf(requester.value, generation, invalidatedThroughEpoch.toString()),
            )
            val changed = floorChanged || removed > 0
            if (changed) bumpRevision(database)
            if (cancelled.isNotEmpty()) {
                val values = ContentValues().apply {
                    put("state", SshProviderRequestState.CANCELLED.name)
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
                    stored.requestDigest,
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
                    stored.requestDigest,
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
    fun cancelSign(requestId: String, requester: ClientId, requestDigest: ByteArray, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.kind != SshProviderRequestKind.SIGN || stored.requesterClientId != requester ||
            !MessageDigest.isEqual(stored.requestDigest, requestDigest) ||
            stored.state != SshProviderRequestState.PENDING_REVIEW
        ) return false
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.CANCELLED.name)
            put("updated_at", now)
        }
        val changed = writableDatabase.update("provider_requests", values, "request_id=?", arrayOf(requestId)) == 1
        if (changed) notifyChanged()
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
        if (changed) notifyChanged()
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
            put("updated_at", now)
        }
        expired.forEach { writableDatabase.update("provider_requests", values, "request_id=?", arrayOf(it)) }
        if (expired.isNotEmpty()) notifyChanged()
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
        notifyChanged()
        return cancelled
    }

    private fun accept(
        kind: SshProviderRequestKind,
        requestId: String,
        requester: ClientId,
        digest: ByteArray,
        cbor: ByteArray,
        now: Long,
    ): SshProviderAcceptResult {
        find(requestId)?.let { existing ->
            return if (existing.kind == kind && existing.requesterClientId == requester &&
                MessageDigest.isEqual(existing.requestDigest, digest)
            ) SshProviderAcceptResult.DUPLICATE else SshProviderAcceptResult.CONFLICT
        }
        expireDue(now)
        if (pendingReview().size >= MAX_PENDING_GLOBAL || pendingReview().count { it.requesterClientId == requester } >= MAX_PENDING_PER_REQUESTER) {
            return SshProviderAcceptResult.RATE_LIMITED
        }
        val values = ContentValues().apply {
            val encrypted = auditWrapping.encrypt(cbor, auditAad(requestId, AUDIT_REQUEST))
            put("request_id", requestId)
            put("kind", kind.name)
            put("requester_client_id", requester.value)
            put("request_digest", digest)
            put("request_cbor", encrypted.first)
            put("request_nonce", encrypted.second)
            put("state", SshProviderRequestState.PENDING_REVIEW.name)
            put("updated_at", now)
        }
        writableDatabase.insertOrThrow("provider_requests", null, values)
        notifyChanged()
        return SshProviderAcceptResult.STORED
    }

    private fun sign(
        request: SshSignRequest,
        digest: ByteArray,
        provider: ClientId,
        now: Long,
        rememberDisposition: SshRememberDisposition,
    ): SshSignResult {
        val row = findKeyMaterial(request.publicKeyBlob)
            ?: return signFailure(request, digest, provider, now, SshProviderFailureCode.KEY_NOT_FOUND)
        return try {
            val method = signatureMethod(request)
            val privateKey = row.privateKey()
            val jca = signRaw(
                method,
                privateKey,
                request.data,
                softwareKey = row.backend == SshStorageBackend.WRAPPED_SOFTWARE,
            )
            signedResult(request, digest, provider, now, rememberDisposition, method, jca)
        } catch (_: KeyPermanentlyInvalidatedException) {
            signFailure(request, digest, provider, now, SshProviderFailureCode.KEY_INVALIDATED)
        } catch (_: Exception) {
            signFailure(request, digest, provider, now, SshProviderFailureCode.INTERNAL_FAILURE)
        }
    }

    private fun signedResult(
        request: SshSignRequest,
        digest: ByteArray,
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
        return SshSignResult(
            request.requestId,
            digest,
            request.requesterClientId,
            sha256(request.publicKeyBlob),
            SshSignResultKind.SIGNED,
            now,
            provider,
            signature = SshSignatureResult(
                SshSignatureCodec.encode(method, raw),
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
        "SELECT provider_key_id, public_hash, algorithm, storage_backend, encrypted_pkcs8, nonce, key_alias " +
            "FROM ssh_keys WHERE hex(public_hash)=?",
        arrayOf(sha256(publicBlob).toHex().uppercase()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredKeyMaterial(
            providerKeyId = cursor.getString(0),
            publicHash = cursor.getBlob(1),
            algorithm = SshKeyAlgorithm.valueOf(cursor.getString(2)),
            backend = SshStorageBackend.valueOf(cursor.getString(3)),
            encryptedPkcs8 = if (cursor.isNull(4)) null else cursor.getBlob(4),
            nonce = if (cursor.isNull(5)) null else cursor.getBlob(5),
            keyAlias = if (cursor.isNull(6)) null else cursor.getString(6),
        )
    }

    private fun import(
        request: SshImportRequest,
        digest: ByteArray,
        provider: ClientId,
        now: Long,
        exportability: SshExportability,
        preferStrongBox: Boolean,
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
                        request.requestId, digest, request.requesterClientId, provider, now,
                        SshImportResultKind.ALREADY_PRESENT, existing, material.publicBlob,
                    ),
                )
            } else {
                when (val storage = storeImportedKey(
                    privateKey = material.privateKey,
                    publicKey = material.publicKey,
                    publicBlob = material.publicBlob,
                    algorithm = material.algorithm,
                    displayName = boundedImportName(request.suggestedName ?: material.comment),
                    origin = if (request.sourceType == SshImportSourceType.AGENT_IDENTITY) {
                        SshKeyOrigin.AGENT_ADD
                    } else {
                        SshKeyOrigin.DATA_SYNC_FILE
                    },
                    exportability = exportability,
                    preferStrongBox = preferStrongBox,
                    userVerificationPolicy = userVerificationPolicy,
                    createdAt = now,
                    expiresAt = request.constraints?.lifetimeSeconds?.let { now + it * 1_000 },
                )) {
                    is SshKeyStorageResult.Stored -> SshImportAttempt.Complete(
                        SshImportResult(
                            request.requestId, digest, request.requesterClientId, provider, now,
                            SshImportResultKind.IMPORTED, storage.descriptor.providerKeyId, material.publicBlob,
                        ),
                    )
                    is SshKeyStorageResult.AuthenticationRequired -> SshImportAttempt.AuthenticationRequired(
                        storage.prepared,
                        material.publicBlob.copyOf(),
                    )
                }
            }
        } catch (_: Exception) {
            SshImportAttempt.Complete(
                SshImportResult(
                    request.requestId, digest, request.requesterClientId, provider, now,
                    SshImportResultKind.FAILED, message = "SSH identity import failed",
                ),
            )
        } finally {
            sensitivePrivateBytes?.fill(0)
        }
    }

    private fun storeResponse(stored: StoredSshProviderRequest, response: Any, now: Long): Boolean {
        val encoded = when (response) {
            is SshSignResult -> ProtocolCodec.encodeToCbor(response)
            is SshImportResult -> ProtocolCodec.encodeToCbor(response)
            else -> error("unsupported SSH provider response")
        }
        val encrypted = auditWrapping.encrypt(encoded, auditAad(stored.requestId, AUDIT_RESPONSE))
        val values = ContentValues().apply {
            put("state", SshProviderRequestState.RESPONSE_PENDING_SEND.name)
            put("response_cbor", encrypted.first)
            put("response_nonce", encrypted.second)
            put("updated_at", now)
        }
        val changed = writableDatabase.update(
            "provider_requests",
            values,
            "request_id=? AND state=?",
            arrayOf(stored.requestId, SshProviderRequestState.PENDING_REVIEW.name),
        ) == 1
        if (changed) notifyChanged()
        return changed
    }

    private fun signFailure(
        request: SshSignRequest,
        digest: ByteArray,
        provider: ClientId,
        now: Long,
        code: SshProviderFailureCode,
    ) = SshSignResult(
        request.requestId,
        digest,
        request.requesterClientId,
        sha256(request.publicKeyBlob),
        SshSignResultKind.PROVIDER_FAILURE,
        now,
        provider,
        failure = SshProviderFailure(code),
    )

    private fun requestsIn(state: SshProviderRequestState): List<StoredSshProviderRequest> =
        readableDatabase.rawQuery(
            "SELECT request_id, kind, requester_client_id, request_digest, request_cbor, request_nonce, " +
                "state, response_cbor, response_nonce, updated_at " +
                "FROM provider_requests WHERE state=? ORDER BY updated_at",
            arrayOf(state.name),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readRequest()) } }

    private fun android.database.Cursor.readRequest(): StoredSshProviderRequest {
        val kind = SshProviderRequestKind.valueOf(getString(1))
        val requestId = getString(0)
        val requestBytes = auditWrapping.decrypt(
            getBlob(4),
            getBlob(5),
            auditAad(requestId, AUDIT_REQUEST),
        )
        return StoredSshProviderRequest(
            requestId = requestId,
            kind = kind,
            requesterClientId = ClientId(getString(2)),
            requestDigest = getBlob(3),
            signRequest = if (kind == SshProviderRequestKind.SIGN) ProtocolCodec.decodeFromCbor(requestBytes) else null,
            importRequest = if (kind == SshProviderRequestKind.IMPORT) ProtocolCodec.decodeFromCbor(requestBytes) else null,
            state = SshProviderRequestState.valueOf(getString(6)),
            encodedResponse = if (isNull(7)) null else auditWrapping.decrypt(
                getBlob(7),
                getBlob(8),
                auditAad(requestId, AUDIT_RESPONSE),
            ),
            updatedAt = getLong(9),
        )
    }

    private fun StoredSshProviderRequest.expiresAt(): Long =
        signRequest?.expiresAt ?: importRequest?.expiresAt ?: Long.MIN_VALUE

    private fun findKeyPolicy(publicKeyBlob: ByteArray): StoredKeyPolicy? = readableDatabase.rawQuery(
        "SELECT provider_key_id, approval_policy, user_verification_policy FROM ssh_keys WHERE hex(public_hash)=?",
        arrayOf(sha256(publicKeyBlob).toHex().uppercase()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else StoredKeyPolicy(
            providerKeyId = cursor.getString(0),
            approvalPolicy = SshApprovalPolicy.valueOf(cursor.getString(1)),
            userVerificationPolicy = SshUserVerificationPolicy.valueOf(cursor.getString(2)),
        )
    }

    private fun createRememberRule(
        providerKeyId: String,
        request: SshSignRequest,
        scope: SshRememberScope,
        now: Long,
    ): Boolean {
        val floor = authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        if (request.authorizationEpoch <= floor) return false
        val leaf = request.processContext.leaf
        val parent = request.processContext.directParent
        if (scope == SshRememberScope.PARENT_PROCESS_SESSION && (leaf == null || parent == null)) return false
        val database = writableDatabase
        if (database.rawQuery("SELECT COUNT(*) FROM remember_rules", emptyArray()).use {
                it.moveToFirst() && it.getLong(0) >= MAX_REMEMBER_RULES_GLOBAL
            }
        ) return false
        if (database.rawQuery("SELECT COUNT(*) FROM remember_rules WHERE provider_key_id=?", arrayOf(providerKeyId)).use {
                it.moveToFirst() && it.getLong(0) >= MAX_REMEMBER_RULES_PER_KEY
            }
        ) return false
        val values = ContentValues().apply {
            put("rule_id", randomId())
            put("provider_key_id", providerKeyId)
            put("requester_client_id", request.requesterClientId.value)
            put("authorization_generation", request.authorizationGeneration)
            put("authorization_epoch", request.authorizationEpoch)
            put("scope", scope.name)
            if (scope == SshRememberScope.PARENT_PROCESS_SESSION) {
                put("leaf_executable_path", requireNotNull(leaf).executablePath)
                put("parent_pid", requireNotNull(parent).pid)
                put("parent_start_epoch_millis", parent.startEpochMillis)
                put("parent_executable_path", parent.executablePath)
            } else {
                putNull("leaf_executable_path")
                putNull("parent_pid")
                putNull("parent_start_epoch_millis")
                putNull("parent_executable_path")
            }
            put("created_at", now)
        }
        database.beginTransaction()
        val created = try {
            val created = database.insertWithOnConflict(
                "remember_rules",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE,
            ) != -1L
            if (created) bumpRevision(database)
            database.setTransactionSuccessful()
            created
        } finally {
            database.endTransaction()
        }
        if (created) notifyChanged()
        return created
    }

    private fun matchingRememberDisposition(request: SshSignRequest): SshRememberDisposition? {
        val policy = findKeyPolicy(request.publicKeyBlob) ?: return null
        if (policy.approvalPolicy != SshApprovalPolicy.ALLOW_REMEMBER ||
            policy.userVerificationPolicy != SshUserVerificationPolicy.NONE ||
            request.authorizationEpoch <= authorizationFloor(request.requesterClientId, request.authorizationGeneration)
        ) return null
        val leaf = request.processContext.leaf
        val parent = request.processContext.directParent
        val scopes = readableDatabase.rawQuery(
            "SELECT scope, leaf_executable_path, parent_pid, parent_start_epoch_millis, parent_executable_path " +
                "FROM remember_rules WHERE provider_key_id=? AND requester_client_id=? " +
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
                        RememberRuleMatch(
                            scope = SshRememberScope.valueOf(cursor.getString(0)),
                            leafExecutablePath = if (cursor.isNull(1)) null else cursor.getString(1),
                            parentPid = if (cursor.isNull(2)) null else cursor.getLong(2),
                            parentStartEpochMillis = if (cursor.isNull(3)) null else cursor.getLong(3),
                            parentExecutablePath = if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
        if (leaf != null && parent != null && scopes.any {
                it.scope == SshRememberScope.PARENT_PROCESS_SESSION &&
                    it.leafExecutablePath == leaf.executablePath &&
                    it.parentPid == parent.pid &&
                    it.parentStartEpochMillis == parent.startEpochMillis &&
                    it.parentExecutablePath == parent.executablePath
            }
        ) return SshRememberDisposition.MATCHED_PARENT_PROCESS
        return if (scopes.any { it.scope == SshRememberScope.PEER }) {
            SshRememberDisposition.MATCHED_PEER
        } else null
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
                "FROM remember_rules ORDER BY provider_key_id, requester_client_id, authorization_generation, " +
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
            "SELECT provider_key_id, storage_backend, key_alias FROM ssh_keys " +
                "WHERE expires_at IS NOT NULL AND expires_at < ?",
            arrayOf(now.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(0),
                            SshStorageBackend.valueOf(cursor.getString(1)),
                            if (cursor.isNull(2)) null else cursor.getString(2),
                        ),
                    )
                }
            }
        }
        if (expired.isEmpty()) return
        val database = writableDatabase
        database.beginTransaction()
        try {
            expired.forEach { (keyId, backend, alias) ->
                database.delete("ssh_keys", "provider_key_id=?", arrayOf(keyId))
                if (backend == SshStorageBackend.ANDROID_KEYSTORE) {
                    deleteAndroidKeyStoreAlias(requireNotNull(alias))
                } else {
                    perKeyWrapping.delete(operationalWrappingAlias(keyId))
                }
            }
            bumpRevision(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        notifyChanged()
    }

    private fun bumpRevision(database: SQLiteDatabase = writableDatabase) {
        database.execSQL("UPDATE provider_state SET revision=revision+1 WHERE singleton=1")
    }

    private fun notifyChanged() {
        _changeVersion.update { if (it == Long.MAX_VALUE) 0 else it + 1 }
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
            }
            if (strongBox) setIsStrongBoxBacked(true)
            if (userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
        }
        return KeyPairGenerator.getInstance(generatorAlgorithm, ANDROID_KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    }

    private fun selfTest(
        privateKey: PrivateKey,
        publicKey: java.security.PublicKey,
        algorithm: SshKeyAlgorithm,
    ) {
        val data = ByteArray(32).also(RANDOM::nextBytes)
        val jcaName = when (algorithm) {
            SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
            SshKeyAlgorithm.SSH_RSA -> "SHA256withRSA"
            SshKeyAlgorithm.ECDSA_NISTP256 -> "SHA256withECDSA"
        }
        val signature = Signature.getInstance(jcaName).run {
            initSign(privateKey)
            update(data)
            sign()
        }
        val verificationKey = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            KeyFactory.getInstance("Ed25519", BOUNCY_CASTLE).generatePublic(
                X509EncodedKeySpec(requireNotNull(publicKey.encoded) { "Ed25519 public key is not encodable" }),
            )
        } else {
            publicKey
        }
        val verifier = if (algorithm == SshKeyAlgorithm.SSH_ED25519) {
            Signature.getInstance(jcaName, BOUNCY_CASTLE)
        } else {
            Signature.getInstance(jcaName)
        }
        check(
            verifier.run {
                initVerify(verificationKey)
                update(data)
                verify(signature)
            },
        ) { "Android Keystore generated a key that failed its signing self-test" }
    }

    private fun inspectKeyInfo(
        privateKey: PrivateKey,
        algorithm: SshKeyAlgorithm,
        userVerificationPolicy: SshUserVerificationPolicy,
    ): SshStorageSecurityLevel {
        val info = KeyFactory.getInstance(algorithm.keyStoreAlgorithm(), ANDROID_KEYSTORE)
            .getKeySpec(privateKey, KeyInfo::class.java)
        check(info.purposes and KeyProperties.PURPOSE_SIGN != 0) { "Android Keystore key cannot sign" }
        val requiredDigests = when (algorithm) {
            SshKeyAlgorithm.SSH_ED25519 -> setOf(KeyProperties.DIGEST_NONE)
            SshKeyAlgorithm.SSH_RSA -> setOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            SshKeyAlgorithm.ECDSA_NISTP256 -> setOf(KeyProperties.DIGEST_SHA256)
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
            }
        }
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SshStorageSecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SshStorageSecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> SshStorageSecurityLevel.SOFTWARE
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
            KeyProperties.SECURITY_LEVEL_UNKNOWN,
            -> SshStorageSecurityLevel.UNKNOWN
            else -> SshStorageSecurityLevel.UNKNOWN
        }
    }

    private fun deleteAndroidKeyStoreAlias(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    private fun StoredKeyMaterial.privateKey(): PrivateKey = when (backend) {
        SshStorageBackend.ANDROID_KEYSTORE -> {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .getKey(requireNotNull(keyAlias), null) as? PrivateKey
                ?: error("Android Keystore key is unavailable")
        }
        SshStorageBackend.WRAPPED_SOFTWARE -> {
            val bundle = SshKeyMaterialBundle.decode(requireNotNull(encryptedPkcs8))
            val prepared = perKeyWrapping.prepareUnwrap(
                operationalWrappingAlias(providerKeyId),
                requireNotNull(bundle.operationalEnvelope) { "wrapped SSH key has no operational material" },
                operationalAad(providerKeyId, algorithm, publicHash),
            )
            val privateBytes = perKeyWrapping.completeUnwrap(prepared)
            try {
                softwarePrivateKey(algorithm, privateBytes)
            } finally {
                privateBytes.fill(0)
            }
        }
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
    }

    private fun SshKeyAlgorithm.toKeyFactory(): String = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
        SshKeyAlgorithm.SSH_RSA -> "RSA"
        SshKeyAlgorithm.ECDSA_NISTP256 -> "EC"
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
        softwareKey: Boolean,
    ): ByteArray {
        val signature = if (softwareKey && method == SshSignatureMethod.ED25519) {
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

    private fun SshKeyAlgorithm.keyStoreAlgorithm(): String = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> KeyProperties.KEY_ALGORITHM_EC
        SshKeyAlgorithm.SSH_RSA -> KeyProperties.KEY_ALGORITHM_RSA
        SshKeyAlgorithm.ECDSA_NISTP256 -> KeyProperties.KEY_ALGORITHM_EC
    }

    private fun SshKeyAlgorithm.toCoreType(): SshKeyType = when (this) {
        SshKeyAlgorithm.SSH_ED25519 -> SshKeyType.ED25519
        SshKeyAlgorithm.SSH_RSA -> SshKeyType.RSA
        SshKeyAlgorithm.ECDSA_NISTP256 -> SshKeyType.ECDSA_NISTP256
    }

    private fun SshSignatureMethod.toProtocol(): SshSignatureAlgorithm = when (this) {
        SshSignatureMethod.ED25519 -> SshSignatureAlgorithm.SSH_ED25519
        SshSignatureMethod.RSA_SHA2_256 -> SshSignatureAlgorithm.RSA_SHA2_256
        SshSignatureMethod.RSA_SHA2_512 -> SshSignatureAlgorithm.RSA_SHA2_512
        SshSignatureMethod.ECDSA_NISTP256 -> SshSignatureAlgorithm.ECDSA_NISTP256
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
    private fun auditAad(requestId: String, purpose: String): ByteArray =
        "notisync:ssh-provider-audit:v1:$purpose:$requestId".encodeToByteArray()
    private fun operationalWrappingAlias(providerKeyId: String): String = OPERATIONAL_WRAP_ALIAS_PREFIX + providerKeyId
    private fun operationalAad(
        providerKeyId: String,
        algorithm: SshKeyAlgorithm,
        publicHash: ByteArray,
    ): ByteArray = "notisync:ssh-operational-wrap:v1:$providerKeyId:${algorithm.name}:${publicHash.toHex()}".encodeToByteArray()

    private data class StoredKeyMaterial(
        val providerKeyId: String,
        val publicHash: ByteArray,
        val algorithm: SshKeyAlgorithm,
        val backend: SshStorageBackend,
        val encryptedPkcs8: ByteArray?,
        val nonce: ByteArray?,
        val keyAlias: String?,
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
        val exportability: SshExportability,
        val backend: SshStorageBackend,
        val encodedMaterial: ByteArray?,
        val userVerificationPolicy: SshUserVerificationPolicy,
    )

    private data class StoredKeyPolicy(
        val providerKeyId: String,
        val approvalPolicy: SshApprovalPolicy,
        val userVerificationPolicy: SshUserVerificationPolicy,
    )

    private data class RememberRuleMatch(
        val scope: SshRememberScope,
        val leafExecutablePath: String?,
        val parentPid: Long?,
        val parentStartEpochMillis: Long?,
        val parentExecutablePath: String?,
    )

    private companion object {
        const val DB_NAME = "ssh-key-provider.sqlite3"
        const val VERSION = 1
        const val SSH_KEYSTORE_ALIAS_PREFIX = "notisync_ssh_"
        const val AUDIT_KEY_ALIAS = "notisync_ssh_audit_wrapping_v1"
        const val KEY_ALIAS_PREFIX = "notisync_ssh_identity_"
        const val OPERATIONAL_WRAP_ALIAS_PREFIX = "notisync_ssh_operational_wrap_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AUDIT_REQUEST = "request"
        const val AUDIT_RESPONSE = "response"
        const val MAX_PENDING_GLOBAL = 128
        const val MAX_PENDING_PER_REQUESTER = 16
        const val MAX_REMEMBER_RULES_GLOBAL = 1_024L
        const val CERTIFICATE_CLOCK_SKEW_MILLIS = 5 * 60_000L
        const val CERTIFICATE_VALIDITY_MILLIS = 20L * 365 * 24 * 60 * 60 * 1_000
        const val DEFAULT_RSA_KEY_SIZE_BITS = 3072
        val SUPPORTED_RSA_KEY_SIZE_BITS = setOf(2048, DEFAULT_RSA_KEY_SIZE_BITS, 4096)
        val BOUNCY_CASTLE = BouncyCastleProvider()
        const val MAX_REMEMBER_RULES_PER_KEY = 128L
        val EMPTY_BYTES = ByteArray(0)
        val RANDOM = SecureRandom()
    }
}
