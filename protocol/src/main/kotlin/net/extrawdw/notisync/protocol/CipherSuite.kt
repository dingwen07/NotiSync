package net.extrawdw.notisync.protocol

/**
 * Algorithm-agile cipher-suite identifier. The suite id is pinned inside every signed and
 * encrypted structure (never inferred), so old blobs remain parseable after an algorithm bump.
 *
 * NotiSync Suite
 *
 * NS1 = ECDSA-P256-SHA256 (signing)
 *     + HPKE DHKEM_X25519_HKDF_SHA256 (per-recipient key encapsulation)
 *     + HKDF-SHA256 (key derivation)
 *     + AES-256-GCM (payload AEAD)
 *
 * NS2 = same primitives as NS1, plus operational-key delegation + epoch rotation: the identity key
 *     signs a [ClientKeyEpoch] that authorizes a rotatable operational signing key + HPKE keyset; the
 *     epoch is bound into [Envelope]/[EnvelopeAuth] and the per-recipient HPKE context. The NS2 server is
 *     a clean break served on `/v2` — it requires NS2 and shares no code with NS1 (the legacy NS1 JAR runs
 *     on `/v1` for old binaries). Within NS2, `signerEpoch` 0 = identity-signed, ≥1 = operational.
 */
enum class CipherSuite(val id: String) {
    NS1("NS1"), // retained only as a known id the NS2 server rejects; no NS1 code path exists
    NS2("NS2");

    companion object {
        const val CURRENT_ID: String = "NS2"
        val CURRENT: CipherSuite = NS2

        fun fromId(id: String): CipherSuite? = entries.firstOrNull { it.id == id }
    }
}
