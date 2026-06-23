package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType

/**
 * Verification of NS2 [ClientKeyEpoch] certificates — the two-hop replacement for the NS1
 * "verifyBound on every path" model. Hop 1 is here (the identity key authorized these operational
 * keys); hop 2 is verifying an envelope/request against the returned [ClientKeyEpoch.operationalSigningKey]
 * (see [EnvelopeCrypto.verify] / [HttpRequestSigning.verify]).
 *
 * A [ClientKeyEpoch] is self-contained: it carries [ClientKeyEpoch.identityPublicKey], so it
 * self-verifies (`clientId == fingerprint(identityPublicKey)`) and can bootstrap a peer that holds
 * nothing about this client. Callers MUST NOT replace this with a bare SPKI swap into
 * [IdentityVerifier.verify] — the fingerprint binding is what keeps `clientId` pinned to the identity
 * key while the operational key rotates.
 */
object KeyEpochs {

    /**
     * Verify a self-contained [ClientKeyEpoch] [SignedBlob] and return the decoded epoch, or null.
     *
     * Checks: type is [SignedType.KEY_EPOCH]; epoch ≥ 1; the blob's signer is the carried clientId; the
     * clientId is the fingerprint of the carried identity key; the identity key signed the payload. When
     * [pinnedIdentitySpki] is provided (a peer already known), the carried identity key MUST byte-match
     * it — so a rotated [ClientKeyEpoch] can never swap the identity anchor for a known client.
     */
    fun verify(blob: SignedBlob, pinnedIdentitySpki: ByteArray? = null): ClientKeyEpoch? {
        if (blob.typ != SignedType.KEY_EPOCH) return null
        val ke = runCatching { blob.decode<ClientKeyEpoch>() }.getOrNull() ?: return null
        if (ke.epoch < 1) return null
        if (ke.clientId != blob.signerId) return null
        // The key-epoch normally carries its own identity anchor (self-contained). A pairing-QR copy may
        // STRIP it to shrink the code, since the accompanying ClientCard supplies it — fall back to the
        // pinned identity then. With neither present there is nothing to verify against → reject.
        val identityKey = ke.identityPublicKey.takeIf { it.isNotEmpty() } ?: pinnedIdentitySpki ?: return null
        if (ClientIds.derive(identityKey) != ke.clientId) return null
        if (pinnedIdentitySpki != null && !pinnedIdentitySpki.contentEquals(identityKey)) return null
        if (!IdentityVerifier.verify(identityKey, blob.payload, blob.sig)) return null
        return ke
    }
}
