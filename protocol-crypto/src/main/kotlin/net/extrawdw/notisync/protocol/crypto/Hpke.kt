package net.extrawdw.notisync.protocol.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.util.Bytes

/** A serialized HPKE keypair. The private keyset MUST be stored encrypted by the caller. */
class HpkeKeyPair(
    /** Tink public keyset bytes (no secret) — published in the client card. */
    val publicKeyset: ByteArray,
    /** Tink private keyset bytes (contains secret) — wrap before persisting. */
    val privateKeyset: ByteArray,
)

/**
 * HPKE per-recipient sealing of small payload keys (the DEK). Suite NS1 uses
 * DHKEM_X25519_HKDF_SHA256 + HKDF-SHA256 + AES-256-GCM, NO_PREFIX (raw HPKE, single key per
 * keyset; rotation happens by issuing a new client card with a new key epoch).
 */
object Hpke {
    private val params: HpkeParameters by lazy {
        HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
    }

    fun generateKeyPair(): HpkeKeyPair {
        CryptoInit.ensure()
        val handle = KeysetHandle.generateNew(params)
        val publicKeyset = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle)
        val privateKeyset = TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
        return HpkeKeyPair(publicKeyset, privateKeyset)
    }

    /** Length of a raw X25519 public key — the discriminator between the raw and Tink-keyset wire forms. */
    private const val RAW_X25519_KEY_LEN = 32

    /**
     * Seal [dek] to a recipient identified by their published HPKE key. The key is length-discriminated:
     * a [RAW_X25519_KEY_LEN]-byte [publicKeyset] is the raw X25519 wire form (sealed via [sealToRaw]),
     * anything larger is a legacy Tink public keyset. A Tink HPKE public keyset is always far larger than
     * 32 bytes (its `type_url` alone is ~49), so the split is unambiguous. Both paths pin the same NS1/NS2
     * suite + NO_PREFIX, producing identical bare RFC-9180 framing, so a peer opens either the same way.
     */
    fun seal(dek: ByteArray, publicKeyset: ByteArray, contextInfo: ByteArray): ByteArray {
        CryptoInit.ensure()
        if (publicKeyset.size == RAW_X25519_KEY_LEN) return sealToRaw(dek, publicKeyset, contextInfo)
        val handle = TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicKeyset)
        val enc = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return enc.encrypt(dek, contextInfo)
    }

    /**
     * Extract the raw 32-byte X25519 public key from a Tink HPKE [publicKeyset] — the form published in
     * [net.extrawdw.notisync.protocol.ClientKeyEpoch.hpkePublicKeyset] so a Tink-free peer (iOS CryptoKit)
     * or [sealToRaw] can seal without parsing a keyset. The suite pins NO_PREFIX, so the key bytes are the
     * bare wire key with nothing to strip.
     */
    fun rawPublicKey(publicKeyset: ByteArray): ByteArray {
        CryptoInit.ensure()
        val handle = TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicKeyset)
        val key = handle.getAt(0).key as HpkePublicKey
        return key.publicKeyBytes.toByteArray()
    }

    /**
     * Seal [dek] to a recipient given only their raw 32-byte X25519 public key ([raw32]) — no Tink keyset.
     * Rebuilds a single-key NO_PREFIX keyset from the raw bytes so the framing matches [seal]'s keyset path
     * byte-for-byte; the recipient opens with their Tink private keyset via [open] unchanged.
     */
    fun sealToRaw(dek: ByteArray, raw32: ByteArray, contextInfo: ByteArray): ByteArray {
        CryptoInit.ensure()
        val key = HpkePublicKey.create(params, Bytes.copyFrom(raw32), null)
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        val enc = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return enc.encrypt(dek, contextInfo)
    }

    /** Open a sealed DEK using this device's [privateKeyset]. */
    fun open(sealedDek: ByteArray, privateKeyset: ByteArray, contextInfo: ByteArray): ByteArray {
        CryptoInit.ensure()
        val handle = TinkProtoKeysetFormat.parseKeyset(privateKeyset, InsecureSecretKeyAccess.get())
        val dec = handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
        return dec.decrypt(sealedDek, contextInfo)
    }
}
