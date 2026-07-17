package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.DurableJsonState
import net.extrawdw.notisync.daemon.storage.SecureFileSystem
import net.extrawdw.notisync.peer.ports.KeyMaterialProvider
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.OperationalSigner

/**
 * Initial desktop key provider.
 *
 * All secret material is intentionally unencrypted in `private-keys-v1`: EC private keys are stored
 * in PKCS#8 form and HPKE keys use Tink's serialized keyset form. Keeping key operations behind
 * [KeyMaterialProvider] is the security boundary that lets a future Keychain, Secret Service, or
 * GnuPG provider replace this implementation without changing the peer runtime.
 *
 * Each key pair is committed as one JSON document. This avoids an unrecoverable half-created
 * identity if the daemon crashes between writing its PKCS#8 private key and matching SPKI public
 * key. Files are written atomically and mode 0600 by [SecureFileSystem].
 */
class FileKeyMaterialProvider(
    private val layout: DaemonStorageLayout,
    private val fileSystem: SecureFileSystem = SecureFileSystem(),
    initialEpoch: Int = INITIAL_EPOCH,
) : KeyMaterialProvider {
    private val lock = Any()
    private val metadataState: DurableJsonState<KeyMetadata>
    private val identityState: DurableJsonState<EcKeyRecord>
    private val operationalSigners = mutableMapOf<Int, OperationalSigner>()
    private val hpkeKeys = mutableMapOf<Int, HpkeKeyRecord>()

    override val identity: IdentitySigner

    init {
        require(initialEpoch >= 1) { "initialEpoch must be at least 1" }
        layout.prepare(fileSystem)
        metadataState = DurableJsonState(
            path = layout.privateKeyFile(METADATA_FILE),
            serializer = KeyMetadata.serializer(),
            defaultValue = { KeyMetadata(currentEpoch = initialEpoch) },
            fileSystem = fileSystem,
        )
        identityState = DurableJsonState(
            path = layout.privateKeyFile(IDENTITY_FILE),
            serializer = EcKeyRecord.serializer(),
            defaultValue = ::generateEcKeyRecord,
            fileSystem = fileSystem,
        )

        val metadata = metadataState.initialize().validated()
        identity = identityState.initialize().toIdentitySigner()
        synchronized(lock) {
            loadOrCreateOperational(metadata.currentEpoch)
            loadOrCreateHpke(metadata.currentEpoch)
        }
    }

    override fun operationalSigner(epoch: Int): OperationalSigner = synchronized(lock) {
        requireEpoch(epoch)
        loadOrCreateOperational(epoch)
    }

    override fun currentOperationalSigner(): OperationalSigner = synchronized(lock) {
        val epoch = metadataState.load().validated().currentEpoch
        loadOrCreateOperational(epoch)
    }

    override fun hpkePrivateKeyset(epoch: Int): ByteArray? = synchronized(lock) {
        requireEpoch(epoch)
        val state = hpkeState(epoch)
        if (!state.exists()) null else loadHpke(epoch).privateKeyset.copyOf()
    }

    override fun hpkePublicKeyset(epoch: Int): ByteArray = synchronized(lock) {
        requireEpoch(epoch)
        loadOrCreateHpke(epoch).publicKeyset.copyOf()
    }

    /**
     * Mark a previously minted epoch active. Rotation code should call this only after its pre-warm
     * phase. Merely asking for [operationalSigner] does not advance the active epoch.
     */
    fun activateEpoch(epoch: Int) = synchronized(lock) {
        requireEpoch(epoch)
        loadOrCreateOperational(epoch)
        loadOrCreateHpke(epoch)
        metadataState.save(KeyMetadata(currentEpoch = epoch))
    }

    override fun currentKeyEpoch(): SignedBlob = synchronized(lock) {
        val epoch = metadataState.load().validated().currentEpoch
        val operational = loadOrCreateOperational(epoch)
        val hpke = loadOrCreateHpke(epoch)
        val keyEpoch = ClientKeyEpoch(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = operational.operationalPublicKeySpki,
            hpkePublicKey = Hpke.rawPublicKey(hpke.publicKeyset),
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = Long.MAX_VALUE,
            minEpoch = epoch,
        )
        val payload = ProtocolCodec.encodeToCbor(keyEpoch)
        SignedBlob(
            typ = SignedType.KEY_EPOCH,
            signerId = identity.clientId,
            payload = payload,
            sig = identity.sign(payload),
        )
    }

    override fun destroyEpoch(epoch: Int): Unit = synchronized(lock) {
        requireEpoch(epoch)
        val current = metadataState.load().validated().currentEpoch
        require(epoch != current) { "refusing to destroy active key epoch $epoch" }
        operationalState(epoch).delete()
        hpkeState(epoch).delete()
        operationalSigners.remove(epoch)
        hpkeKeys.remove(epoch)
    }

    /** Epochs with a complete operational and HPKE record, useful to rotation cleanup. */
    fun retainedEpochs(): Set<Int> = synchronized(lock) {
        if (!Files.exists(layout.privateKeysDirectory, LinkOption.NOFOLLOW_LINKS)) return@synchronized emptySet()
        Files.list(layout.privateKeysDirectory).use { paths ->
            val operational = paths.iterator().asSequence()
                .map(Path::getFileName)
                .map(Path::toString)
                .mapNotNull { OPERATIONAL_FILE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                .toSet()
            operational.filterTo(linkedSetOf()) { hpkeState(it).exists() }
        }
    }

    private fun loadOrCreateOperational(epoch: Int): OperationalSigner =
        operationalSigners.getOrPut(epoch) {
            operationalState(epoch).initialize().toOperationalSigner(identity.clientId, epoch)
        }

    private fun loadOrCreateHpke(epoch: Int): HpkeKeyRecord =
        hpkeKeys.getOrPut(epoch) { hpkeState(epoch).initialize().validated() }

    private fun loadHpke(epoch: Int): HpkeKeyRecord =
        hpkeKeys.getOrPut(epoch) { hpkeState(epoch).load().validated() }

    private fun operationalState(epoch: Int) = DurableJsonState(
        path = layout.privateKeyFile("operational-$epoch.json"),
        serializer = EcKeyRecord.serializer(),
        defaultValue = ::generateEcKeyRecord,
        fileSystem = fileSystem,
    )

    private fun hpkeState(epoch: Int) = DurableJsonState(
        path = layout.privateKeyFile("hpke-$epoch.json"),
        serializer = HpkeKeyRecord.serializer(),
        defaultValue = {
            val generated = Hpke.generateKeyPair()
            HpkeKeyRecord(
                privateKeyset = generated.privateKeyset,
                publicKeyset = generated.publicKeyset,
            )
        },
        fileSystem = fileSystem,
    )

    private fun requireEpoch(epoch: Int) {
        require(epoch >= 1) { "key epoch must be at least 1" }
    }

    private companion object {
        const val INITIAL_EPOCH = 1
        const val METADATA_FILE = "key-material.json"
        const val IDENTITY_FILE = "identity.json"
        val OPERATIONAL_FILE = Regex("operational-([1-9][0-9]*)\\.json")
    }
}

@Serializable
private data class KeyMetadata(
    val schemaVersion: Int = 1,
    val currentEpoch: Int,
) {
    fun validated(): KeyMetadata = also {
        require(schemaVersion == 1) { "unsupported key metadata version $schemaVersion" }
        require(currentEpoch >= 1) { "invalid active key epoch $currentEpoch" }
    }
}

@Serializable
private data class EcKeyRecord(
    val schemaVersion: Int = 1,
    /** DER PKCS#8 EC private key. Deliberately not encrypted in the first provider. */
    val privateKeyPkcs8: ByteArray,
    /** DER X.509 SubjectPublicKeyInfo matching [privateKeyPkcs8]. */
    val publicKeySpki: ByteArray,
) {
    fun toIdentitySigner(): IdentitySigner {
        validatedKeyPair()
        val signer = FileIdentitySigner(publicKeySpki.copyOf(), decodePrivateKey(privateKeyPkcs8))
        val challenge = "notisyncd identity key validation".encodeToByteArray()
        require(IdentityVerifier.verify(signer.publicKeySpki, challenge, signer.sign(challenge))) {
            "identity PKCS#8 key does not match its public SPKI"
        }
        return signer
    }

    fun toOperationalSigner(clientId: ClientId, epoch: Int): OperationalSigner {
        validatedKeyPair()
        val signer = FileOperationalSigner(
            operationalPublicKeySpki = publicKeySpki.copyOf(),
            clientId = clientId,
            signerEpoch = epoch,
            privateKey = decodePrivateKey(privateKeyPkcs8),
        )
        val challenge = "notisyncd operational key validation:$epoch".encodeToByteArray()
        require(IdentityVerifier.verify(signer.operationalPublicKeySpki, challenge, signer.sign(challenge))) {
            "operational PKCS#8 key for epoch $epoch does not match its public SPKI"
        }
        require(signer.clientId == clientId && signer.signerEpoch == epoch)
        return signer
    }

    private fun validatedKeyPair() {
        require(schemaVersion == 1) { "unsupported EC key record version $schemaVersion" }
        require(privateKeyPkcs8.isNotEmpty()) { "empty PKCS#8 key" }
        require(publicKeySpki.isNotEmpty()) { "empty public SPKI" }
        decodePrivateKey(privateKeyPkcs8)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
    }
}

@Serializable
private data class HpkeKeyRecord(
    val schemaVersion: Int = 1,
    val privateKeyset: ByteArray,
    val publicKeyset: ByteArray,
) {
    fun validated(): HpkeKeyRecord = also {
        require(schemaVersion == 1) { "unsupported HPKE key record version $schemaVersion" }
        require(privateKeyset.isNotEmpty()) { "empty private HPKE keyset" }
        require(publicKeyset.isNotEmpty()) { "empty public HPKE keyset" }
        // Parsing/extracting the public key catches malformed Tink material immediately.
        require(Hpke.rawPublicKey(publicKeyset).size == 32) { "invalid HPKE public key" }
        val context = "notisyncd HPKE key validation".encodeToByteArray()
        val plaintext = "key-pair".encodeToByteArray()
        val sealed = Hpke.seal(plaintext, publicKeyset, context)
        require(Hpke.open(sealed, privateKeyset, context).contentEquals(plaintext)) {
            "private HPKE keyset does not match its public keyset"
        }
    }
}

private class FileIdentitySigner(
    publicKeySpki: ByteArray,
    private val privateKey: PrivateKey,
) : IdentitySigner {
    private val publicSpki = publicKeySpki.copyOf()
    override val publicKeySpki: ByteArray get() = publicSpki.copyOf()
    override val clientId: ClientId = ClientIds.derive(publicSpki)
    override fun sign(data: ByteArray): ByteArray = sign(privateKey, data)
}

private class FileOperationalSigner(
    operationalPublicKeySpki: ByteArray,
    override val clientId: ClientId,
    override val signerEpoch: Int,
    private val privateKey: PrivateKey,
) : OperationalSigner {
    private val publicSpki = operationalPublicKeySpki.copyOf()
    override val operationalPublicKeySpki: ByteArray get() = publicSpki.copyOf()
    override fun sign(data: ByteArray): ByteArray = sign(privateKey, data)
}

private fun generateEcKeyRecord(): EcKeyRecord {
    val generator = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }
    val pair = generator.generateKeyPair()
    return EcKeyRecord(
        privateKeyPkcs8 = pair.private.encoded,
        publicKeySpki = pair.public.encoded,
    )
}

private fun decodePrivateKey(encoded: ByteArray): PrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

private fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
    Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(data)
        sign()
    }
