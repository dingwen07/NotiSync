package net.extrawdw.notisync.server.crypto

import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs

/**
 * The broker verifies signatures but never decrypts content. It accepts a key-epoch only if it is
 * self-authenticating (its id is the fingerprint of the identity key it carries, which signed it), and a
 * route claim only if signed by the identity key of an already-known client. It is never a membership
 * authority — that stays with clients.
 */
object Verification {

    /** A route claim must be signed by the identity key of the client whose key-epoch we already hold. */
    fun verifyRouteClaim(blob: SignedBlob, signerSpki: ByteArray): RouteClaim? {
        if (blob.typ != SignedType.ROUTE_CLAIM) return null
        if (!IdentityVerifier.verifyBound(blob.signerId, signerSpki, blob.payload, blob.sig)) return null
        val claim = runCatching { blob.decode<RouteClaim>() }.getOrNull() ?: return null
        return if (claim.clientId == blob.signerId) claim else null
    }

    /** Verify a detached ECDSA signature over arbitrary bytes (used for the WebSocket handshake). */
    fun verifyDetached(signerSpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        IdentityVerifier.verify(signerSpki, data, signature)

    /**
     * Verify a self-contained NS2 key-epoch (the identity key authorized these operational keys). The
     * blob carries the identity key, so it self-verifies; the broker only caches what it has verified.
     */
    fun verifyKeyEpoch(blob: SignedBlob): ClientKeyEpoch? = KeyEpochs.verify(blob)
}
