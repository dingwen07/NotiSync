package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/** Discriminator for the inner DTO carried by a [SignedBlob.payload]. */
object SignedType {
    const val CLIENT_CARD = "client-card"
    const val ROUTE_CLAIM = "route-claim"
    const val KEY_EPOCH = "key-epoch"     // NS2: an identity-signed ClientKeyEpoch (operational keys)
    // NB: no MEMBERSHIP/REVOCATION — there is no group-admin/CA model (MembershipCard/RevocationCard removed).
}

/**
 * A signed, self-describing wrapper around a CBOR-encoded protocol DTO.
 *
 * The signature is computed over the raw [payload] bytes exactly as transmitted; verifiers check
 * the signature against those bytes and only then decode the inner DTO. This sidesteps any
 * canonicalization fragility: the bytes that were signed are the bytes that travel on the wire.
 *
 * A verifier must additionally confirm that [signerId] is the fingerprint of the public key used
 * to verify (binding identity to key material) and that the signer is a trusted group member.
 */
@Serializable
data class SignedBlob(
    val typ: String,
    @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
    val signerId: ClientId,
    @ByteString val payload: ByteArray,
    @ByteString val sig: ByteArray,
) {
    /** Decode the inner DTO. Call only after the signature has been verified. */
    inline fun <reified T> decode(): T = ProtocolCodec.decodeFromCbor(payload)
}
