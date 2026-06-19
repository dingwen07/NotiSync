package net.extrawdw.notisync.protocol

/**
 * Algorithm-agile cipher-suite identifier. The suite id is pinned inside every signed and
 * encrypted structure (never inferred), so old blobs remain parseable after an algorithm bump.
 *
 * NS1 = ECDSA-P256-SHA256 (signing)
 *     + HPKE DHKEM_X25519_HKDF_SHA256 (per-recipient key encapsulation)
 *     + HKDF-SHA256 (key derivation)
 *     + AES-256-GCM (payload AEAD)
 */
enum class CipherSuite(val id: String) {
    NS1("NS1");

    companion object {
        const val CURRENT_ID: String = "NS1"
        val CURRENT: CipherSuite = NS1

        fun fromId(id: String): CipherSuite? = entries.firstOrNull { it.id == id }
    }
}
