package net.extrawdw.notisync.protocol.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HpkeParameters

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

    /** Seal [dek] to a recipient identified by their published [publicKeyset]. */
    fun seal(dek: ByteArray, publicKeyset: ByteArray, contextInfo: ByteArray): ByteArray {
        CryptoInit.ensure()
        val handle = TinkProtoKeysetFormat.parseKeysetWithoutSecret(publicKeyset)
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
