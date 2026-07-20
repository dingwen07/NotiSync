package net.extrawdw.notisync.peer.trust

import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.notisync.protocol.Capability
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

    /** Best-known platform for a device, or null when we have no profile/card metadata for it. */
    fun peerPlatform(clientId: ClientId): String? = null

    /** Best-known peer capability declaration, including for a trusted peer whose key epoch is missing. */
    fun peerCapabilities(clientId: ClientId): List<Capability> = emptyList()

    /**
     * Whether a trusted peer belongs to this user's own mesh, including when its key epoch is missing.
     * Null means the lightweight implementation has no ownership metadata for this peer.
     */
    fun peerOwnDevice(clientId: ClientId): Boolean? = null

    /** This device's broadcast roster (its TRUSTED + REVOKED decisions), for anti-entropy. */
    fun buildTrustTable(): TrustTable

    /** Apply a peer's announced profile (last-writer-wins). Returns true if anything changed. */
    fun applyProfile(update: ProfileUpdate): Boolean

    /** Fold a peer's broadcast roster into ours; returns prompts to raise + cards to offer. */
    fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult

    /** Finalize a verified incoming prompt under a platform's explicit auto-trust policy. */
    fun resolveIncomingPrompt(clientId: ClientId, prompt: TrustPrompt, now: Long): Boolean = false

    /** Store a verified delivered card when it is newer than the identity-pinned snapshot. */
    fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean

    // ---- NS2 operational-key epochs (§5/§6): generation ring + anti-rollback floor ----

    /**
     * Ingest a peer's self-contained, identity-signed [ClientKeyEpoch] [SignedBlob] (a pulled or pushed
     * key-epoch). Verifies it standalone, pins the identity anchor first-verified-wins (rejecting any key
     * swap), enforces the monotonic floor (rejects epoch < floor or minEpoch < floor), and folds it into
     * this peer's generation ring. Returns true if newly applied. Default no-op keeps lightweight
     * [TrustState] fakes from having to model the ring.
     */
    fun applyKeyEpoch(clientId: ClientId, keyEpochBlob: SignedBlob): Boolean = false

    /**
     * The OPERATIONAL signing-key SPKI a peer's envelope of [signerEpoch] (≥1) must verify against, or
     * null to DROP — the single anti-rollback gate on the inbound hot path. Returns non-null only when the
     * ring holds that epoch AND `epoch ≥ floor` AND the key-epoch carries [Purpose.ENVELOPE_SIGN]. (No
     * wall-clock window check here: §11 — the receiver gates on epoch-known + ≥ floor, never per-message
     * notBefore/notAfter, so an in-flight envelope around a rotation boundary still opens.)
     */
    fun peerOperationalSpki(clientId: ClientId, epoch: Int): ByteArray? = null

    /** The highest key-epoch we hold for [clientId] (0 = none) — advertised in [TrustTable] for convergence. */
    fun peerEpoch(clientId: ClientId): Int = 0

    /** Every TRUSTED peer's id (own + other, excluding self) — the set whose key-epochs we converge by pull. */
    fun trustedClientIds(): List<ClientId> = emptyList()

    /**
     * TRUSTED peers whose key-epoch is NOT usable as of [now] and should be refetched: none held, or the
     * current one is expired (`notAfter ≤ now`, i.e. the peer has almost certainly rotated past it). The
     * proactive convergence pull targets this set (key "available" ≠ "usable").
     */
    fun peersNeedingKeyEpoch(now: Long): List<ClientId> = emptyList()

    /** The current (highest-epoch) key-epoch [SignedBlob] we hold for [clientId] — relayed to repair a peer
     *  that lacks it (it self-authenticates, so relaying is safe). */
    fun currentKeyEpochBlob(clientId: ClientId): SignedBlob? = null

    /** Our own current operational epoch (≥1 once initialised), persisted in the signed floor section. */
    fun selfEpoch(): Int = 0

    /**
     * Raise our own persisted operational epoch counter to at least [to] (monotonic; never lowers it),
     * reconciling with a recovered broker floor and advancing on rotation. Returns the resulting epoch.
     */
    fun advanceSelfEpoch(to: Int): Int = to

    /** The in-flight self-rotation (§7), persisted in the signed floor section so it survives a restart. */
    fun pendingRotation(): PendingRotation? = null

    /** Persist (or clear, with null) the in-flight self-rotation. */
    fun setPendingRotation(pending: PendingRotation?) {}
}
