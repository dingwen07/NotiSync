package net.extrawdw.apps.notisync.data

import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable

/**
 * The read/apply surface the trust FOUNDATION and the channel's peer directory need from the trust
 * store, expressed as an interface so the wire-I/O engines (FoundationEngine, TrustPeerDirectory)
 * can be unit-tested without DataStore. [TrustStore] is the production implementation; all decision
 * logic and persistence stay there.
 */
interface TrustState {
    /** TRUSTED devices whose card we hold — the recipient roster and the inbound sender set. */
    val activePeers: StateFlow<List<Peer>>

    /** Best-known display name for a device, or null when we hold no card for it. */
    fun displayName(clientId: ClientId): String?

    /** This device's broadcast roster (its TRUSTED + REVOKED decisions), for anti-entropy. */
    fun buildTrustTable(): TrustTable

    /** Cards we hold for every trusted device (own + other), pushed alongside the roster. */
    fun trustedCards(): List<SignedBlob>

    /** Apply a peer's announced profile (last-writer-wins). Returns true if anything changed. */
    fun applyProfile(update: ProfileUpdate): Boolean

    /** Fold a peer's broadcast roster into ours; returns prompts to raise + cards to offer. */
    fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult

    /** Pin a delivered card (first-verified-wins). Returns true if newly stored. */
    fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean
}
