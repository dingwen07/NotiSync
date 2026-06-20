package net.extrawdw.apps.notisync.data

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTableEntry

/**
 * One row of this device's local trust database, keyed by [clientId]. [updatedAt] is a high-water
 * mark of the newest assertion (local or relayed) we've incorporated for this id — the last-writer-wins
 * clock. [introducedBy] is local provenance: the peer whose broadcast first surfaced this id to us, or
 * null when this device's own user established it (optical scan / manual change). It is never sent on
 * the wire (a receiver derives it from the envelope signer) and exists so a future cascade revoke can
 * sweep `{ introducedBy == <revoked device> }`.
 */
@Serializable
data class TrustEntry(
    val clientId: ClientId,
    val status: TrustStatus,
    val updatedAt: Long,
    val introducedBy: ClientId? = null,
    /**
     * True if this is one of the user's own devices (the default for a QR pair and for any device learned
     * via trust propagation). False marks a separately-paired device that only exchanges profile updates —
     * never notifications, trust tables, or cards. Local-only; never travels on the wire.
     */
    val ownDevice: Boolean = true,
)

/** A UI signal that an incoming change needs the user's attention. */
enum class TrustPrompt {
    NEW_TRUST,   // a peer introduced a device we'd never seen
    RE_TRUST,    // a previously-revoked device is being re-introduced (resurrection — show the fingerprint)
    NEW_REVOKE,  // a peer revoked a device we currently trust (traffic is now paused pending our decision)
    CONFLICT,    // a device is being re-trusted while its removal is still pending — user must resolve
}

/** Result of folding one incoming assertion: the entry to store, and any prompt to raise. */
data class TrustResolution(val entry: TrustEntry, val prompt: TrustPrompt?)

/**
 * The trust state machine. Pure and deterministic so the whole transition table is unit-testable.
 *
 * The governing rule when trust and revoke collide on an unresolved entry: the protective interpretation
 * wins (drop a not-yet-approved trust; keep a removal paused) and only an explicit user action can land
 * on TRUSTED. Concurrent assertions are ordered by [TrustTableEntry.updatedAt] (last-writer-wins); an
 * assertion no newer than what we already hold is ignored as stale/duplicate.
 */
object TrustMachine {

    /** Fold a received (wire) assertion — only TRUSTED/REVOKED ever arrive — against the current entry. */
    fun resolveIncoming(current: TrustEntry?, incoming: TrustTableEntry, sender: ClientId): TrustResolution {
        val id = incoming.clientId
        // Last-writer-wins staleness guard: nothing older-or-equal can change our state.
        if (current != null && incoming.updatedAt <= current.updatedAt) return TrustResolution(current, null)
        val ts = incoming.updatedAt
        // Anything learned via trust propagation is an own-mesh device; keep an existing local flag if set.
        fun res(status: TrustStatus, introducedBy: ClientId?, prompt: TrustPrompt?) =
            TrustResolution(TrustEntry(id, status, ts, introducedBy, ownDevice = current?.ownDevice ?: true), prompt)

        return when (incoming.status) {
            TrustStatus.TRUSTED -> when (current?.status) {
                null -> res(TrustStatus.PENDING_TRUST, sender, TrustPrompt.NEW_TRUST)
                TrustStatus.PENDING_TRUST -> res(TrustStatus.PENDING_TRUST, current.introducedBy, null)
                TrustStatus.TRUSTED -> res(TrustStatus.TRUSTED, current.introducedBy, null)
                // Removal in progress: an incoming re-trust never silently restores it — keep it paused
                // and surface the conflict for the user to resolve.
                TrustStatus.PENDING_REVOKE -> res(TrustStatus.PENDING_REVOKE, current.introducedBy, TrustPrompt.CONFLICT)
                // Clearing a tombstone always requires explicit user approval (with the fingerprint shown).
                TrustStatus.REVOKED -> res(TrustStatus.PENDING_TRUST, sender, TrustPrompt.RE_TRUST)
            }
            TrustStatus.REVOKED -> when (current?.status) {
                null -> res(TrustStatus.REVOKED, sender, null) // silent tombstone; suppresses later stale re-prompts
                TrustStatus.PENDING_TRUST -> res(TrustStatus.REVOKED, sender, null) // never approved -> drop it
                TrustStatus.TRUSTED -> res(TrustStatus.PENDING_REVOKE, sender, TrustPrompt.NEW_REVOKE)
                TrustStatus.PENDING_REVOKE -> res(TrustStatus.PENDING_REVOKE, current.introducedBy, null)
                TrustStatus.REVOKED -> res(TrustStatus.REVOKED, current.introducedBy, null)
            }
            // PENDING_* must never appear on the wire; ignore defensively.
            else -> TrustResolution(current ?: TrustEntry(id, incoming.status, ts), null)
        }
    }

    // ---- Local user actions. The boolean is whether the change should be broadcast IMMEDIATELY:
    //      a fresh local decision or an OVERTURN of a peer's suggestion propagates at once; merely
    //      AGREEING with a peer (approve a pending trust / confirm a pending revoke) does not, since
    //      anti-entropy already carries it. ----

    /** This device's user trusts [id] via an optical scan / manual add ([ownDevice] chosen at pairing). */
    fun localAdd(id: ClientId, now: Long, ownDevice: Boolean = true) =
        TrustEntry(id, TrustStatus.TRUSTED, now, introducedBy = null, ownDevice = ownDevice)

    /** This device's user removes [id] directly. */
    fun localRevoke(id: ClientId, now: Long) = TrustEntry(id, TrustStatus.REVOKED, now, introducedBy = null)

    /** Approve a PENDING_TRUST -> TRUSTED. Agrees with the introducer; not re-broadcast immediately. */
    fun approveTrust(current: TrustEntry, now: Long) = current.copy(status = TrustStatus.TRUSTED, updatedAt = now)

    /** Reject a PENDING_TRUST -> REVOKED. Overturns the introducer; becomes our own decision (propagates). */
    fun rejectTrust(current: TrustEntry, now: Long) =
        current.copy(status = TrustStatus.REVOKED, updatedAt = now, introducedBy = null)

    /** Confirm a PENDING_REVOKE -> REVOKED. Agrees with the revoker; not re-broadcast immediately. */
    fun confirmRevoke(current: TrustEntry, now: Long) = current.copy(status = TrustStatus.REVOKED, updatedAt = now)

    /** Keep a device whose PENDING_REVOKE we reject -> TRUSTED. Overturns the revoker (propagates). */
    fun keepTrusted(current: TrustEntry, now: Long) =
        current.copy(status = TrustStatus.TRUSTED, updatedAt = now, introducedBy = null)
}
