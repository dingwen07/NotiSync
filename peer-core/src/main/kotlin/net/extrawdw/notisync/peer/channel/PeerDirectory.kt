package net.extrawdw.notisync.peer.channel

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.RecipientKey

/**
 * The verification material [SecureChannel] needs for ONE inbound envelope: the public key the
 * envelope's signature must verify against — already resolved for the claimed `signerEpoch` (the
 * sender's identity key for epoch 0, or the floored/purpose-gated operational key for ≥1) — and whether
 * the sender is an own-mesh device. The channel verifies against [verifySpki] and never sees epoch
 * policy; [ownDevice] is surfaced only so a handler above the channel can apply its own authorization
 * policy (the channel never gates on it).
 */
class SenderKey(
    /** X.509 SubjectPublicKeyInfo of the key that must have signed this envelope (identity or operational). */
    val verifySpki: ByteArray,
    val ownDevice: Boolean,
)

/**
 * One immutable outbound audience decision. Recipient key bytes are copied on construction and every read so a
 * directory update or caller mutation cannot change the exact audience after policy validation.
 */
class AudienceSnapshot(
    recipients: List<RecipientKey>,
    unsealableRecipientIds: Set<ClientId> = emptySet(),
) {
    private val recipientSnapshot = recipients.map(RecipientKey::defensiveCopy)
    private val unsealableSnapshot = unsealableRecipientIds.toSet()

    val recipients: List<RecipientKey>
        get() = recipientSnapshot.map(RecipientKey::defensiveCopy)

    val unsealableRecipientIds: Set<ClientId>
        get() = unsealableSnapshot.toSet()
}

/**
 * The read-only port [SecureChannel] depends on for its only two directory needs: authenticate a
 * sender by id, and enumerate a recipient set for a scope. The channel DEFINES this interface; the
 * trust foundation IMPLEMENTS it — so key material flows foundation → channel with no back-edge, and
 * the channel never imports the trust store or any feature type.
 */
interface PeerDirectory {
    /**
     * Resolve the key an envelope from [id] claiming [signerEpoch] must verify against, or null to DROP.
     * Epoch 0 ⇒ the sender's identity key. Epoch ≥1 ⇒ the operational key of that `ClientKeyEpoch`,
     * returned ONLY when the epoch is ≥ the peer's anti-rollback floor and carries `ENVELOPE_SIGN` — so a
     * replayed retired epoch resolves to null and the channel drops it before any signature check.
     */
    fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey?

    /**
     * Resolve the recipient keys and every targeted trusted peer that is temporarily unsealable from one directory
     * state version. Implementations must not assemble this from independently changing reads. The channel freezes
     * this snapshot for the complete batch; missing-key peers drive the same convergence repair as inbound misses.
     */
    fun resolveAudience(scope: Recipients): AudienceSnapshot
}

private fun RecipientKey.defensiveCopy(): RecipientKey = RecipientKey(
    clientId = clientId,
    hpkePublicKey = hpkePublicKey.copyOf(),
    recipientEpoch = recipientEpoch,
)
