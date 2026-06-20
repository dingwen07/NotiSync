package net.extrawdw.apps.notisync.data

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTableEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustMachineTest {

    private val C = ClientId("C")
    private val A = ClientId("A") // the asserting/introducing peer (envelope signer)

    private fun entry(status: TrustStatus, ts: Long, introducedBy: ClientId? = A) =
        TrustEntry(C, status, ts, introducedBy)

    private fun incoming(status: TrustStatus, ts: Long) = TrustTableEntry(C, status, ts, keyAvailable = true)

    private fun resolve(current: TrustEntry?, status: TrustStatus, ts: Long) =
        TrustMachine.resolveIncoming(current, incoming(status, ts), sender = A)

    // ---- LWW staleness guard ----

    @Test fun ignoresOlderOrEqualAssertion() {
        val cur = entry(TrustStatus.TRUSTED, 100L)
        assertEquals(cur, resolve(cur, TrustStatus.REVOKED, 100L).entry) // equal -> ignored
        assertEquals(cur, resolve(cur, TrustStatus.REVOKED, 50L).entry)  // older -> ignored
        assertNull(resolve(cur, TrustStatus.REVOKED, 100L).prompt)
    }

    // ---- incoming TRUSTED across every current state ----

    @Test fun trusted_fromUnknown_isPendingTrust() {
        val r = resolve(null, TrustStatus.TRUSTED, 10L)
        assertEquals(TrustStatus.PENDING_TRUST, r.entry.status)
        assertEquals(A, r.entry.introducedBy)
        assertEquals(TrustPrompt.NEW_TRUST, r.prompt)
    }

    @Test fun trusted_whilePendingTrust_refreshesNoPrompt() {
        val r = resolve(entry(TrustStatus.PENDING_TRUST, 10L), TrustStatus.TRUSTED, 20L)
        assertEquals(TrustStatus.PENDING_TRUST, r.entry.status)
        assertEquals(20L, r.entry.updatedAt)
        assertNull(r.prompt)
    }

    @Test fun trusted_whileTrusted_isNoOp() {
        val r = resolve(entry(TrustStatus.TRUSTED, 10L), TrustStatus.TRUSTED, 20L)
        assertEquals(TrustStatus.TRUSTED, r.entry.status)
        assertNull(r.prompt)
    }

    // (b) trust received for a PENDING_REVOKE client -> stays paused, surfaces CONFLICT, never auto-restores
    @Test fun trusted_whilePendingRevoke_staysPausedWithConflict() {
        val r = resolve(entry(TrustStatus.PENDING_REVOKE, 10L), TrustStatus.TRUSTED, 99L)
        assertEquals(TrustStatus.PENDING_REVOKE, r.entry.status)
        assertEquals(TrustPrompt.CONFLICT, r.prompt)
    }

    // resurrection: a revoked id can only come back through explicit user approval
    @Test fun trusted_whileRevoked_needsReApproval() {
        val r = resolve(entry(TrustStatus.REVOKED, 10L), TrustStatus.TRUSTED, 20L)
        assertEquals(TrustStatus.PENDING_TRUST, r.entry.status)
        assertEquals(TrustPrompt.RE_TRUST, r.prompt)
    }

    // ---- incoming REVOKED across every current state ----

    @Test fun revoked_fromUnknown_isSilentTombstone() {
        val r = resolve(null, TrustStatus.REVOKED, 10L)
        assertEquals(TrustStatus.REVOKED, r.entry.status)
        assertNull(r.prompt)
    }

    // (a) revoke received for a PENDING_TRUST client -> drop the pending trust
    @Test fun revoked_whilePendingTrust_dropsToRevoked() {
        val r = resolve(entry(TrustStatus.PENDING_TRUST, 10L), TrustStatus.REVOKED, 20L)
        assertEquals(TrustStatus.REVOKED, r.entry.status)
        assertNull(r.prompt)
    }

    @Test fun revoked_whileTrusted_pausesAndPrompts() {
        val r = resolve(entry(TrustStatus.TRUSTED, 10L), TrustStatus.REVOKED, 20L)
        assertEquals(TrustStatus.PENDING_REVOKE, r.entry.status)
        assertEquals(TrustPrompt.NEW_REVOKE, r.prompt)
    }

    @Test fun revoked_whilePendingRevoke_refreshesNoPrompt() {
        val r = resolve(entry(TrustStatus.PENDING_REVOKE, 10L), TrustStatus.REVOKED, 20L)
        assertEquals(TrustStatus.PENDING_REVOKE, r.entry.status)
        assertNull(r.prompt)
    }

    @Test fun revoked_whileRevoked_isNoOp() {
        val r = resolve(entry(TrustStatus.REVOKED, 10L), TrustStatus.REVOKED, 20L)
        assertEquals(TrustStatus.REVOKED, r.entry.status)
        assertNull(r.prompt)
    }

    // a stale revoke older than a re-introduction must NOT clobber it
    @Test fun staleRevoke_doesNotClobberNewerTrust() {
        val cur = entry(TrustStatus.PENDING_TRUST, 200L)
        val r = resolve(cur, TrustStatus.REVOKED, 100L) // older than the introduction
        assertEquals(cur, r.entry)
    }

    // ---- "other" devices (private contact list): no pending, immediate last-writer-wins ----

    private fun entryOther(status: TrustStatus, ts: Long, introducedBy: ClientId? = A) =
        TrustEntry(C, status, ts, introducedBy, ownDevice = false)

    private fun resolveOther(current: TrustEntry?, status: TrustStatus, ts: Long) =
        TrustMachine.resolveIncoming(current, TrustTableEntry(C, status, ts, keyAvailable = true, ownDevice = false), sender = A)

    @Test fun other_trustedFromUnknown_isImmediatelyTrusted() {
        val r = resolveOther(null, TrustStatus.TRUSTED, 10L)
        assertEquals(TrustStatus.TRUSTED, r.entry.status) // never PENDING_TRUST
        assertFalse(r.entry.ownDevice)
        assertEquals(TrustPrompt.OTHER_ADDED, r.prompt)
    }

    @Test fun other_revokedFromUnknown_isImmediatelyRevoked() {
        val r = resolveOther(null, TrustStatus.REVOKED, 10L)
        assertEquals(TrustStatus.REVOKED, r.entry.status)
        assertFalse(r.entry.ownDevice)
        assertEquals(TrustPrompt.OTHER_REMOVED, r.prompt)
    }

    // Re-adding a removed other-device applies at once (no resurrection re-approval, unlike own devices).
    @Test fun other_reTrustAfterRevoke_appliesImmediately() {
        val r = resolveOther(entryOther(TrustStatus.REVOKED, 10L), TrustStatus.TRUSTED, 20L)
        assertEquals(TrustStatus.TRUSTED, r.entry.status)
        assertEquals(TrustPrompt.OTHER_ADDED, r.prompt)
    }

    @Test fun other_repeatedTrusted_refreshesNoPrompt() {
        val r = resolveOther(entryOther(TrustStatus.TRUSTED, 10L), TrustStatus.TRUSTED, 20L)
        assertEquals(TrustStatus.TRUSTED, r.entry.status)
        assertEquals(20L, r.entry.updatedAt)
        assertNull(r.prompt) // same status, newer clock — already known, nothing to announce
    }

    @Test fun other_staleAssertionIgnored() {
        val cur = entryOther(TrustStatus.TRUSTED, 100L)
        assertEquals(cur, resolveOther(cur, TrustStatus.REVOKED, 50L).entry)
    }

    // Category is sticky: an incoming ownDevice=false row can't downgrade a device we hold as our own.
    @Test fun ownDevice_notReclassifiedByOtherWireFlag() {
        val own = entry(TrustStatus.TRUSTED, 10L)
        val r = resolveOther(own, TrustStatus.REVOKED, 20L) // wire says ownDevice=false
        assertTrue(r.entry.ownDevice)
        assertEquals(TrustStatus.PENDING_REVOKE, r.entry.status) // own-mesh consensus path -> pending
        assertEquals(TrustPrompt.NEW_REVOKE, r.prompt)
    }

    // ...and the converse: an incoming ownDevice=true row can't promote a device we hold as "other".
    @Test fun otherDevice_notReclassifiedByOwnWireFlag() {
        val other = entryOther(TrustStatus.TRUSTED, 10L)
        val r = TrustMachine.resolveIncoming(
            other, TrustTableEntry(C, TrustStatus.REVOKED, 20L, keyAvailable = true, ownDevice = true), sender = A,
        )
        assertFalse(r.entry.ownDevice)
        assertEquals(TrustStatus.REVOKED, r.entry.status) // immediate, no pending
        assertEquals(TrustPrompt.OTHER_REMOVED, r.prompt)
    }

    // ---- local actions ----

    @Test fun localAdd_and_localRevoke_areLocalOrigin() {
        assertEquals(TrustEntry(C, TrustStatus.TRUSTED, 5L, null), TrustMachine.localAdd(C, 5L))
        assertEquals(TrustEntry(C, TrustStatus.REVOKED, 5L, null), TrustMachine.localRevoke(C, 5L))
    }

    // Revoking carries the device's own/other category onto its tombstone (so it isn't reclassified).
    @Test fun localAdd_and_localRevoke_carryOtherCategory() {
        assertFalse(TrustMachine.localAdd(C, 5L, ownDevice = false).ownDevice)
        assertFalse(TrustMachine.localRevoke(C, 5L, ownDevice = false).ownDevice)
    }

    @Test fun approveTrust_keepsIntroducerProvenance() {
        val approved = TrustMachine.approveTrust(entry(TrustStatus.PENDING_TRUST, 10L), 30L)
        assertEquals(TrustStatus.TRUSTED, approved.status)
        assertEquals(A, approved.introducedBy) // approval is silent; provenance kept
        assertEquals(30L, approved.updatedAt)
    }

    @Test fun rejectTrust_becomesOwnDecision() {
        val rejected = TrustMachine.rejectTrust(entry(TrustStatus.PENDING_TRUST, 10L), 30L)
        assertEquals(TrustStatus.REVOKED, rejected.status)
        assertNull("an overturn becomes this device's own decision", rejected.introducedBy)
    }

    @Test fun confirmRevoke_and_keepTrusted() {
        val confirmed = TrustMachine.confirmRevoke(entry(TrustStatus.PENDING_REVOKE, 10L), 30L)
        assertEquals(TrustStatus.REVOKED, confirmed.status)
        assertEquals(A, confirmed.introducedBy) // agreeing keeps provenance

        val kept = TrustMachine.keepTrusted(entry(TrustStatus.PENDING_REVOKE, 10L), 30L)
        assertEquals(TrustStatus.TRUSTED, kept.status)
        assertNull("overturning a revoke becomes our own decision", kept.introducedBy)
    }
}
