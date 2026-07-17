package net.extrawdw.notisync.peer.channel

import net.extrawdw.notisync.protocol.Capability
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

/** Audience selector the directory resolves into a concrete recipient set. */
sealed interface Recipients {
    /** This user's own devices only (notifications, dismissals, trust, cards, asset repair). */
    data object OwnMesh : Recipients

    /** Every trusted device — own AND "other" (used only by profile updates). */
    data object AllTrusted : Recipients

    /** This user's own devices except specific peers and (optionally) platform families. [excludedPlatforms]
     *  is an unconditional policy/user gate. [legacyExcludedPlatforms] is only the compatibility fallback for
     *  peers without CAPABILITY_ROUTING_V1; a routed peer uses [requiredCapabilities] instead. */
    data class OwnMeshFiltered(
        val excluded: Set<ClientId> = emptySet(),
        val excludedPlatforms: Set<String> = emptySet(),
        val legacyExcludedPlatforms: Set<String> = emptySet(),
        /** Existing requirements (for example DISPLAY) apply to every peer. New requirements apply once a
         *  peer advertises CAPABILITY_ROUTING_V1; legacy peers use [legacyExcludedPlatforms] as fallback. */
        val requiredCapabilities: Set<Capability> = emptySet(),
        /** Reject legacy peers that do not advertise capability-routing support. Default false keeps
         * existing Android routing source-compatible; callers such as nsrun periodic updates set true. */
        val requireCapabilityRoutingV1: Boolean = false,
    ) : Recipients

    /** A single device by id (unicast: card / asset repair). */
    data class Only(val id: ClientId) : Recipients
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

    /** The recipient keys for a [scope] (each bound to the recipient's current HPKE epoch); empty when no
     *  device matches (the channel then no-ops the send). */
    fun recipients(scope: Recipients): List<RecipientKey>

    /**
     * Trusted peers the [scope] targets that are NOT currently sealable — no usable key-epoch held (missing,
     * expired, or stripped). Such a peer is silently absent from [recipients], so a send would otherwise never
     * repair it on the sender's initiative. The channel feeds these to the same key-epoch refetch the receive
     * path uses (an unresolved sender), so *attempting to deliver* drives a broker pull. Empty by default.
     */
    fun unsealableRecipients(scope: Recipients): Set<ClientId> = emptySet()
}
