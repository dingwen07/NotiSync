package net.extrawdw.apps.notisync.channel

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.RecipientKey

/**
 * Decoded key material for one peer — the strict projection [SecureChannel] needs. Deliberately does
 * NOT expose name / capabilities / trust status: the channel authenticates and seals, nothing else.
 * [ownDevice] is surfaced only so a handler above the channel can apply its own authorization policy;
 * the channel never gates on it.
 */
class PeerKeys(
    /** X.509 SubjectPublicKeyInfo of the sender's identity key — for signature verification. */
    val identitySpki: ByteArray,
    /** The peer's HPKE public keyset — for sealing per-recipient payload keys. */
    val hpkePublicKeyset: ByteArray,
    val ownDevice: Boolean,
)

/** Audience selector the directory resolves into a concrete recipient set. */
sealed interface Recipients {
    /** This user's own devices only (notifications, dismissals, trust, cards, asset repair). */
    data object OwnMesh : Recipients

    /** Every trusted device — own AND "other" (used only by profile updates). */
    data object AllTrusted : Recipients

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
    /** Keys for the device with this id, or null if it is not a trusted peer (the channel then drops). */
    fun lookup(id: ClientId): PeerKeys?

    /** The recipient keys for a [scope]; empty when no device matches (the channel then no-ops the send). */
    fun recipients(scope: Recipients): List<RecipientKey>
}
