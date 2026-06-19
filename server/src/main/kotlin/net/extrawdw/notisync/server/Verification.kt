package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier

/**
 * The broker verifies signatures but never decrypts content. It accepts a client card only if it is
 * self-signed and its id is the fingerprint of its own key, and a route/other claim only if signed
 * by the key of an already-known client. It is never a membership authority — that stays with clients.
 */
object Verification {

    /** A client card is self-authenticating: it carries the public key it is signed with. */
    fun verifyClientCard(blob: SignedBlob): ClientCard? {
        if (blob.typ != SignedType.CLIENT_CARD) return null
        val card = runCatching { blob.decode<ClientCard>() }.getOrNull() ?: return null
        if (card.clientId != blob.signerId) return null
        val ok = IdentityVerifier.verifyBound(blob.signerId, card.identityPublicKey, blob.payload, blob.sig)
        return if (ok) card else null
    }

    /** A route claim must be signed by the identity key of the client whose card we already hold. */
    fun verifyRouteClaim(blob: SignedBlob, signerSpki: ByteArray): RouteClaim? {
        if (blob.typ != SignedType.ROUTE_CLAIM) return null
        if (!IdentityVerifier.verifyBound(blob.signerId, signerSpki, blob.payload, blob.sig)) return null
        val claim = runCatching { blob.decode<RouteClaim>() }.getOrNull() ?: return null
        return if (claim.clientId == blob.signerId) claim else null
    }

    /** Verify a detached ECDSA signature over arbitrary bytes (used for the WebSocket handshake). */
    fun verifyDetached(signerSpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        IdentityVerifier.verify(signerSpki, data, signature)
}
