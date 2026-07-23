package net.extrawdw.notisync.peer.trust

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import java.util.Base64
import java.security.MessageDigest

/**
 * NS2 generation ring for one peer (§6): the last [TrustStore.RING_SIZE] of its identity-signed
 * [ClientKeyEpoch] blobs (each base64(CBOR(SignedBlob)), ascending epoch / newest-last) and the monotonic
 * anti-rollback [floor]. The ring lets a receiver still verify an in-flight envelope signed by a peer's
 * epoch N after it has learned N+1 (overlap), bounded by the floor.
 */
@Serializable
data class PeerEpochs(val ringB64: List<String> = emptyList(), val floor: Int = 0)

/**
 * An in-flight self-rotation (§7), persisted so staged activation/retirement survives a restart and is
 * tamper-protected by the roster signature. [targetEpoch] is the minted next epoch; it activates at
 * [notBefore] (we start signing with it) and the retired epoch's keys are destroyed at [retireRetiredAt]
 * (the old epoch's notAfter + a relay-TTL grace). Null when not rotating.
 */
@Serializable
data class PendingRotation(
    val targetEpoch: Int,
    val notBefore: Long,
    val notAfter: Long,
    val retiredEpoch: Int,
    val retireRetiredAt: Long,
)

/**
 * The fourth, identity-signed TrustStore section (§6): this device's own operational epoch counter, any
 * in-flight rotation, and every peer's generation ring + floor. Living inside [TrustStoreSigning]'s signed
 * canonical is what makes the floor tamper-proof — it cannot be wipe+replayed off the disposable broker nor
 * stripped from an unsigned sidecar (both quarantine like any other roster tamper).
 */
@Serializable
data class EpochSection(
    val selfEpoch: Int = 1,
    val peers: Map<String, PeerEpochs> = emptyMap(),
    val pending: PendingRotation? = null,
)

/** Mutable profile fields ([displayName]/[platform]/[capabilities]) that converge via [ProfileUpdate]. */
@Serializable
data class ProfileOverlay(
    val displayName: String,
    val platform: String,
    val capabilities: List<Capability>,
    val updatedAt: Long,
)

/** A device as shown in the Devices UI. */
data class RosterDevice(
    val clientId: ClientId,
    val status: TrustStatus,
    /** Best-known name, or null when we hold no card for it (then it is also keyless). */
    val displayName: String?,
    /** Whether we hold this device's card (keys); false means it can't yet be mirrored to. */
    val keyAvailable: Boolean,
    /** Name of the peer who introduced/revoked it (for pending rows); null for a local action. */
    val introducedByName: String?,
    /** When it was revoked (for REVOKED rows), so the UI can gate permanent deletion; null otherwise. */
    val revokedAt: Long?,
    /** One of the user's own devices (full mirroring) vs an "other" device in the synced private
     *  contact list (separate UI listing). */
    val ownDevice: Boolean,
    /** NS2: the highest key-epoch we hold for it (0 = none → not sealable, shown "unavailable"). The UI
     *  surfaces this so a device that is trusted-but-not-yet-converged is visibly distinct from a reachable one. */
    val currentEpoch: Int = 0,
    /** Mutable profile fields shown in the device-details sheet. */
    val platform: String? = null,
    val capabilities: List<Capability> = emptyList(),
    /** Fingerprint of the verified, immutable identity anchor. Null when the device is still keyless. */
    val identityKeyFingerprint: String? = null,
    /** The newest verified operational certificate held for this device. */
    val keyEpoch: RosterKeyEpoch? = null,
    /** Whether the held identity/card and newest epoch (when present) pass their cryptographic checks. */
    val verified: Boolean = false,
)

/** User-visible, non-secret fields from a verified [ClientKeyEpoch]. */
data class RosterKeyEpoch(
    val epoch: Int,
    val signingKeyFingerprint: String?,
    val encryptionKeyFingerprint: String?,
    val notBefore: Long,
    val notAfter: Long,
    val minEpoch: Int,
)

/** What [TrustStore.applyIncomingTable] surfaces back to the caller. */
data class IncomingTrustResult(
    val prompts: List<Pair<ClientId, TrustPrompt>>,
    /** Cards the sender advertised it lacks that we hold for a trusted or pending-trust subject. */
    val cardsToOffer: List<SignedBlob>,
    /** True if we just created a keyless entry — re-broadcast our table so a holder can repair us. */
    val needsBroadcast: Boolean = false,
    /** NS2: current key-epoch blobs for trusted devices whose epoch the sender advertised BEHIND what we hold
     *  (incl. epoch 0 = none) — relayed to the sender as a CardDelivery(epochBlob). Self-authenticating, so
     *  vouching by relay is safe: the receiver verifies each key-epoch independently of us. */
    val keyEpochsToOffer: List<SignedBlob> = emptyList(),
    /** Prompts whose pending transition was resolved atomically with this table fold. */
    val automaticallyAppliedPrompts: Set<Pair<ClientId, TrustPrompt>> = emptySet(),
)

/**
 * The device's trust roster. Three layers, keyed by [ClientId]:
 *  - trust decisions ([TrustEntry], the [TrustMachine] state),
 *  - cards (verified, identity-pinned snapshots that advance strictly by [ClientCard.createdAt]),
 *  - a mutable profile overlay (live names from [ProfileUpdate]).
 *
 * The *active* roster — the only thing [recipients] and inbound verification consult — is the
 * intersection of TRUSTED decisions and held cards, so pending/revoked and keyless ids are neither
 * sealed to nor accepted from.
 */
open class TrustStore(
    private val persistence: TrustPersistence,
    /** This device's identity: its [IdentitySigner.clientId] is our own id (implicitly trusted, external
     *  rows about it ignored), and the key signs the persisted roster and verifies it on load. */
    private val identity: IdentitySigner,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrustState {
    private val selfId: ClientId = identity.clientId
    private val entriesKey = ENTRIES_KEY
    private val cardsKey = CARDS_KEY
    private val overlaysKey = OVERLAYS_KEY
    private val epochsKey = EPOCHS_KEY
    private val sigKey = SIGNATURE_KEY

    private data class State(
        val entries: Map<ClientId, TrustEntry>,
        val cards: Map<ClientId, SignedBlob>,
        val overlays: Map<ClientId, ProfileOverlay>,
        val epochs: EpochSection,
    )

    /** What [load] produces: the decoded state and whether its on-disk signature failed to verify. */
    private data class Loaded(val state: State, val quarantined: Boolean)

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private val loaded = load()

    // Set when a non-empty persisted roster carries no valid identity signature — tampered with, or a
    // pre-signing store whose signature was simply never written. While quarantined we neither seal to
    // nor accept from any peer (activePeers is forced empty) and never auto-persist; the user resolves it
    // from the Devices banner via approveQuarantine() (re-sign as-is) or clearQuarantine() (wipe + re-pair).
    private val _quarantined = MutableStateFlow(loaded.quarantined)

    /** True while the on-disk roster can't be verified against our identity key. Drives the Devices banner. */
    val quarantined: StateFlow<Boolean> = _quarantined

    private val _state = MutableStateFlow(loaded.state)

    // Exposed views are directly-updated StateFlows (refreshed in mutate), so a UI state change
    // recomposes synchronously on the action's thread — no derived-flow round trip.
    private val _activePeers =
        MutableStateFlow(if (loaded.quarantined) emptyList<Peer>() else computeActivePeers(loaded.state))
    private val _roster = MutableStateFlow(computeRoster(loaded.state))

    /** TRUSTED devices whose card we hold — recipients() / handleEnvelope's roster. Forced empty while [quarantined]. */
    override val activePeers: StateFlow<List<Peer>> = _activePeers

    /** Everything the user reviews — trusted, pending, and revoked tombstones (until purged) — for the Devices UI. */
    val roster: StateFlow<List<RosterDevice>> = _roster

    fun cardFor(clientId: ClientId): SignedBlob? = _state.value.cards[clientId]
    override fun displayName(clientId: ClientId): String? = displayNameFor(clientId, _state.value)
    override fun peerPlatform(clientId: ClientId): String? = platformFor(clientId, _state.value)
    override fun peerCapabilities(clientId: ClientId): List<Capability> = capabilitiesFor(clientId, _state.value)
    override fun peerOwnDevice(clientId: ClientId): Boolean? = _state.value.entries[clientId]?.ownDevice
    fun statusOf(clientId: ClientId): TrustStatus? = _state.value.entries[clientId]?.status

    // ---- local user actions (return true when the change should be broadcast immediately) ----

    /** Optical/manual add: pin [cardBlob]'s keys and trust it. Returns false if the card fails verification. */
    @Synchronized
    fun addLocal(cardBlob: SignedBlob, now: Long, ownDevice: Boolean = true): Boolean {
        if (_quarantined.value) return false
        val card = verifyCard(cardBlob) ?: return false
        if (!isAcceptableCardCreatedAt(card.createdAt, now)) return false
        mutate { st ->
            val cards = putCard(st.cards, card.clientId, cardBlob)
            val overlay = st.overlays[card.clientId]
            st.copy(
                cards = cards,
                overlays = if (cards !== st.cards && overlay != null && overlay.updatedAt < card.createdAt) {
                    st.overlays - card.clientId
                } else {
                    st.overlays
                },
                entries = st.entries + (card.clientId to TrustMachine.localAdd(
                    card.clientId,
                    now,
                    ownDevice
                )),
            )
        }
        return true
    }

    @Synchronized
    fun revokeLocal(clientId: ClientId, now: Long): Boolean {
        if (_quarantined.value) return false
        // Keep the device's own/other classification on its tombstone — a revoke must never reclassify it.
        mutate { st ->
            val ownDevice = st.entries[clientId]?.ownDevice ?: true
            st.copy(
                entries = st.entries + (clientId to TrustMachine.localRevoke(
                    clientId,
                    now,
                    ownDevice
                ))
            )
        }
        return true // overturn/own-decision -> broadcast now
    }

    /**
     * Permanently forget a REVOKED device — drops its tombstone, card, and overlay. Local cleanup only
     * (not broadcast). Only safe once the tombstone has outlived any lagging peer's stale-trust window,
     * so the UI gates this on [REVOKE_PURGE_DELAY_MS]; until then a stale re-introduction is re-tombstoned.
     */
    @Synchronized
    fun purgeRevoked(clientId: ClientId): Boolean {
        if (_quarantined.value) return false
        if (_state.value.entries[clientId]?.status != TrustStatus.REVOKED) return false
        // Purge forgets the device WHOLESALE — entry, card, overlay, AND its epoch ring + floor. Dropping the
        // floor is consistent with forgetting: a later re-introduction is a fresh peer whose first key-epoch
        // re-establishes the floor. The residual "replay an old key-epoch during the re-converge window"
        // resurrection edge is the same one the long [REVOKE_PURGE_DELAY_MS] gate already bounds (the same
        // device never reincarnates at a lower epoch — uninstall yields a new identity ⇒ new clientId, §6).
        mutate {
            it.copy(
                entries = it.entries - clientId,
                cards = it.cards - clientId,
                overlays = it.overlays - clientId,
                epochs = it.epochs.copy(peers = it.epochs.peers - clientId.value),
            )
        }
        return false // purely local — nothing to propagate
    }

    /**
     * One-time upgrade hygiene: forget every roster row whose held card/identity/epoch material no longer
     * verifies. This is deliberately a wholesale, local deletion (entry, card, profile, and epoch floor),
     * not a revocation to propagate: these rows cannot participate in the compact-NS2 trust relationship and
     * must be paired again. Returns null while the signed store is quarantined so the caller leaves its
     * versioned completion marker unset and retries after the user resolves quarantine.
     */
    @Synchronized
    fun removeUnverifiedDevices(): Set<ClientId>? {
        if (_quarantined.value) return null
        val ids = computeRoster(_state.value)
            .filterNot(RosterDevice::verified)
            .mapTo(linkedSetOf(), RosterDevice::clientId)
        if (ids.isEmpty()) return emptySet()
        mutate { state ->
            state.copy(
                entries = state.entries - ids,
                cards = state.cards - ids,
                overlays = state.overlays - ids,
                epochs = state.epochs.copy(peers = state.epochs.peers - ids.map(ClientId::value).toSet()),
            )
        }
        return ids
    }

    /** Approve a PENDING_TRUST. Silent (anti-entropy carries it). */
    @Synchronized
    fun approveTrust(clientId: ClientId, now: Long): Boolean =
        transition(clientId, now, broadcast = false) {
            if (it.status == TrustStatus.PENDING_TRUST) TrustMachine.approveTrust(it, now) else null
        }

    /** Reject a PENDING_TRUST -> REVOKED. Overturn -> broadcast. */
    @Synchronized
    fun rejectTrust(clientId: ClientId, now: Long): Boolean =
        transition(clientId, now, broadcast = true) {
            if (it.status == TrustStatus.PENDING_TRUST) TrustMachine.rejectTrust(it, now) else null
        }

    /** Confirm a PENDING_REVOKE -> REVOKED. Silent. */
    @Synchronized
    fun confirmRevoke(clientId: ClientId, now: Long): Boolean =
        transition(clientId, now, broadcast = false) {
            if (it.status == TrustStatus.PENDING_REVOKE) TrustMachine.confirmRevoke(
                it,
                now
            ) else null
        }

    /** Reject a PENDING_REVOKE (keep the device) -> TRUSTED. Overturn -> broadcast. */
    @Synchronized
    fun keepTrusted(clientId: ClientId, now: Long): Boolean =
        transition(clientId, now, broadcast = true) {
            if (it.status == TrustStatus.PENDING_REVOKE) TrustMachine.keepTrusted(it, now) else null
        }

    /** Revert a local delete: REVOKED -> TRUSTED. Own decision -> broadcast (peers re-confirm via RE_TRUST).
     *  Its card/key-epoch survive a revoke (only [purgeRevoked] drops them), so the device becomes reachable
     *  again as soon as its key-epoch is held. */
    @Synchronized
    fun restoreTrust(clientId: ClientId, now: Long): Boolean =
        transition(clientId, now, broadcast = true) {
            if (it.status == TrustStatus.REVOKED) TrustMachine.restoreTrust(it, now) else null
        }

    // ---- tamper quarantine (recover from a roster that fails its identity signature) ----

    /**
     * The user vouched for the current on-disk roster from the Devices banner: re-sign it as-is with our
     * identity key and resume. The right choice when the mismatch is benign — most commonly the first
     * launch after signing shipped, when a pre-existing roster simply had no signature yet.
     */
    @Synchronized
    fun approveQuarantine() {
        if (!_quarantined.value) return
        val st = _state.value
        writeSigned(st)
        _quarantined.value = false
        _activePeers.value = computeActivePeers(st)
        _roster.value = computeRoster(st)
    }

    /** The user declined to trust the unverifiable roster: wipe it, sign the empty store, and resume (re-pair). */
    @Synchronized
    fun clearQuarantine() {
        if (!_quarantined.value) return
        val empty = State(emptyMap(), emptyMap(), emptyMap(), EpochSection())
        writeSigned(empty)
        _quarantined.value = false
        _state.value = empty
        _activePeers.value = emptyList()
        _roster.value = emptyList()
    }

    /**
     * Diagnostics only: strip the on-disk signature and enter quarantine now, to exercise the tamper path
     * end-to-end (Devices banner + high-importance notification + the send/receive freeze) without editing
     * storage by hand. Survives a restart — [load] then re-detects the missing signature.
     */
    @Synchronized
    fun simulateSignatureTamper() {
        persistence.write(mapOf(sigKey to null))
        if (!_quarantined.value) {
            _quarantined.value = true
            _activePeers.value = emptyList()
        }
    }

    // ---- incoming ----

    /** Fold a peer's broadcast roster into ours. Returns prompts to raise, cards to offer, and whether to re-broadcast. */
    @Synchronized
    override fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult =
        applyIncomingTable(sender, table, clock()) { _, _ -> false }

    @Synchronized
    override fun applyIncomingTable(
        sender: ClientId,
        table: TrustTable,
        decisionTime: Long,
        shouldAutoApply: (ClientId, TrustPrompt) -> Boolean,
    ): IncomingTrustResult {
        if (_quarantined.value) return IncomingTrustResult(
            emptyList(),
            emptyList(),
            needsBroadcast = false
        )
        val prompts = mutableListOf<Pair<ClientId, TrustPrompt>>()
        val offers = mutableListOf<SignedBlob>()
        val keyEpochOffers = mutableListOf<SignedBlob>()
        val automaticallyAppliedPrompts = mutableSetOf<Pair<ClientId, TrustPrompt>>()
        var needsBroadcast = false
        mutate { st ->
            var entries = st.entries
            for (wire in table.entries) {
                // Ignore a peer's self-row and any external assertion about THIS device's own trust state.
                if (wire.clientId == sender || wire.clientId == selfId) continue
                // Only TRUSTED/REVOKED assertions change our trust state; a peer's PENDING_* is informational.
                if (wire.status == TrustStatus.TRUSTED || wire.status == TrustStatus.REVOKED) {
                    val r = TrustMachine.resolveIncoming(entries[wire.clientId], wire, sender)
                    val promptKey = r.prompt?.let { wire.clientId to it }
                    val resolved = if (
                        promptKey != null && shouldAutoApply(promptKey.first, promptKey.second)
                    ) {
                        resolvePrompt(
                            r.entry,
                            promptKey.second,
                            maxOf(decisionTime, r.entry.updatedAt),
                        )?.also { automaticallyAppliedPrompts += promptKey } ?: r.entry
                    } else {
                        r.entry
                    }
                    if (resolved != entries[wire.clientId]) {
                        entries = entries + (wire.clientId to resolved)
                        // A new keyless trust/pending entry: re-broadcast so a card holder repairs us.
                        if (!st.cards.containsKey(wire.clientId) &&
                            (resolved.status == TrustStatus.PENDING_TRUST || resolved.status == TrustStatus.TRUSTED)
                        ) {
                            needsBroadcast = true
                        }
                    }
                    promptKey?.let { prompts += it }
                }
                // Keyless repair (runs for ANY wire status, incl. pending): offer our card if the sender
                // lacks it and our local subject is trusted or pending trust.
                val mine = entries[wire.clientId] // running accumulator, consistent with the fold above
                // CARD material self-authenticates, so a pending introduction or re-introduction may relay
                // what it holds without approving C.
                if (mine?.status == TrustStatus.TRUSTED || mine?.status == TrustStatus.PENDING_TRUST) {
                    if (!wire.keyAvailable) st.cards[wire.clientId]?.let { offers += it }
                    // NS2 key-epoch repair: if the sender's advertised epoch for this subject is BEHIND what
                    // we hold (incl. epoch 0 = none), relay our current key epoch so it becomes reachable.
                    if (wire.epoch < peerEpochOf(
                            wire.clientId,
                            st
                        )
                    ) currentKeyEpochBlobOf(wire.clientId, st)?.let { keyEpochOffers += it }
                }
            }
            st.copy(entries = entries)
        }
        return IncomingTrustResult(
            prompts,
            offers,
            needsBroadcast,
            keyEpochOffers,
            automaticallyAppliedPrompts,
        )
    }

    /**
     * Store a delivered card. It must self-verify (clientId == fingerprint + self-sig) and may only advance
     * the identity-pinned snapshot to a strictly newer [ClientCard.createdAt]. This makes stale relay replay a
     * no-op while still allowing a newer self-announcement to refresh the signed card retained for repair.
     * A profile overlay newer than the incoming card remains authoritative; an older overlay is discarded.
     */
    @Synchronized
    override fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean {
        if (_quarantined.value) return false
        val card = verifyCard(cardBlob) ?: return false
        if (card.clientId != clientId) return false
        if (!isAcceptableCardCreatedAt(card.createdAt, clock())) return false
        val before = _state.value
        val pinnedIdentity = pinnedIdentityOf(clientId, before)
        if (pinnedIdentity != null && !pinnedIdentity.contentEquals(card.identityPublicKey)) return false
        val existing = before.cards[clientId]?.let(::verifyCard)
        if (existing != null && card.createdAt <= existing.createdAt) return false

        var applied = false
        mutate { current ->
            // Re-check against the state passed to the mutation so a racing newer delivery cannot be replaced
            // by the card that won the optimistic check above.
            val currentCard = current.cards[clientId]?.let(::verifyCard)
            val currentIdentity = pinnedIdentityOf(clientId, current)
            if ((currentIdentity != null && !currentIdentity.contentEquals(card.identityPublicKey)) ||
                (currentCard != null && card.createdAt <= currentCard.createdAt)
            ) {
                current
            } else {
                applied = true
                val overlay = current.overlays[clientId]
                current.copy(
                    cards = current.cards + (clientId to cardBlob),
                    overlays = if (overlay != null && overlay.updatedAt < card.createdAt) {
                        current.overlays - clientId
                    } else {
                        current.overlays
                    },
                )
            }
        }
        return applied
    }

    /** Apply a live profile update (LWW vs the card's createdAt floor). Returns true if anything changed. */
    @Synchronized
    override fun applyProfile(update: ProfileUpdate): Boolean {
        if (_quarantined.value) return false
        val st = _state.value
        if (st.entries[update.clientId]?.status != TrustStatus.TRUSTED) return false // only trusted devices' profiles converge
        val card =
            st.cards[update.clientId]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }
                ?: return false
        val floor = st.overlays[update.clientId]?.updatedAt ?: card.createdAt
        if (update.updatedAt <= floor) return false
        mutate {
            it.copy(
                overlays = it.overlays + (update.clientId to ProfileOverlay(
                    update.displayName, update.platform, update.capabilities, update.updatedAt,
                ))
            )
        }
        return true
    }

    /**
     * Our broadcast roster, sent only to our own devices: every entry but our self-row and expired revoked
     * tombstones, each tagged with
     * its [TrustEntry.ownDevice] category and key-availability. Own-mesh rows include both PENDING_* states
     * (informational — a receiver never acts on a peer's pending, only honours keyAvailable=false to repair
     * a keyless one); "other" rows are always TRUSTED/REVOKED and a receiver applies them immediately.
     *
     * A tombstone remains in the local Devices UI until [REVOKE_PURGE_DELAY_MS], but stops circulating after
     * [REVOKE_ANNOUNCE_DELAY_MS]. This leaves another half-window for lagging peers to receive the removal
     * while preventing old tombstones from normally recreating a device already purged on another peer.
     */
    override fun buildTrustTable(): TrustTable {
        val st = _state.value
        val now = clock()
        return TrustTable(
            st.entries.values
                .filter { it.clientId != selfId }
                .filter {
                    it.status != TrustStatus.REVOKED || it.updatedAt > now ||
                        now - it.updatedAt < REVOKE_ANNOUNCE_DELAY_MS
                }
                // keyAvailable stays card-based (the existing keyless-repair signal); the NS2 [epoch] column
                // advertises the highest key-epoch we hold so a peer refetches when it sees a higher one.
                .map {
                    TrustTableEntry(
                        it.clientId,
                        it.status,
                        it.updatedAt,
                        keyAvailable = st.cards.containsKey(it.clientId),
                        ownDevice = it.ownDevice,
                        epoch = peerEpochOf(it.clientId, st)
                    )
                },
        )
    }

    // ---- NS2 epochs (§5/§6): generation ring, monotonic floor, self counter ----

    @Synchronized
    override fun applyKeyEpoch(clientId: ClientId, keyEpochBlob: SignedBlob): Boolean {
        if (_quarantined.value) return false
        val st = _state.value
        val existing = st.epochs.peers[clientId.value]
        val floor = existing?.floor ?: 0
        // Verify standalone and pin the identity anchor first-verified-wins (rejects any key swap): the pin
        // is the card's identity if held, else the newest ring entry's identity, else this blob bootstraps it.
        val ke = KeyEpochs.verify(keyEpochBlob, pinnedIdentitySpki = pinnedIdentityOf(clientId, st))
            ?: return false
        if (ke.clientId != clientId) return false
        if (ke.epoch < floor) return false        // rollback: an epoch below the floor is a retired/superseded key
        if (ke.minEpoch < floor) return false      // a replayed bundle must not assert a stale minEpoch to drag the floor down
        val sameEpoch = existing?.let(::decodeRing)?.firstOrNull { it.first.epoch == ke.epoch }
        if (sameEpoch != null) {
            val (held, _) = sameEpoch
            // One epoch names one operational/HPKE key pair. A conflicting identity-signed certificate for
            // the same epoch is equivocation, not rotation; rotation must increment the epoch.
            if (!held.operationalSigningKey.contentEquals(ke.operationalSigningKey) ||
                !held.hpkePublicKey.contentEquals(ke.hpkePublicKey)
            ) return false
            // Purpose authorization belongs to the key generation. A different purpose set must advance the
            // epoch; otherwise delayed same-epoch certificates could restore permissions that were removed.
            if (held.purposes.toSet() != ke.purposes.toSet()) return false
            // A compact pairing-QR certificate omits the identity anchor. It may be upgraded by a full copy,
            // but must never replace a self-contained copy that can be relayed to a cardless peer.
            if (held.identityPublicKey.isNotEmpty() && ke.identityPublicKey.isEmpty()) return false
            // ECDSA signatures are randomized. Re-signing the same semantic certificate must not cause a
            // persistent write merely because SignedBlob.sig differs.
            if (held.semanticallyEquals(ke)) return false
        }
        val newFloor = maxOf(floor, ke.minEpoch)
        val next = PeerEpochs(
            mergeRing(existing?.ringB64.orEmpty(), keyEpochBlob, ke.epoch, newFloor),
            newFloor
        )
        if (existing == next) return false          // idempotent re-apply of an already-held epoch
        mutate { it.copy(epochs = it.epochs.copy(peers = it.epochs.peers + (clientId.value to next))) }
        return true
    }

    override fun peerOperationalSpki(clientId: ClientId, epoch: Int): ByteArray? {
        val pe = _state.value.epochs.peers[clientId.value] ?: return null
        if (epoch < pe.floor) return null           // below floor → retired key, reject (anti-rollback)
        val ke = decodeRing(pe).map { it.first }.firstOrNull { it.epoch == epoch } ?: return null
        if (Purpose.ENVELOPE_SIGN !in ke.purposes) return null   // closed-by-default purpose scoping
        return ke.operationalSigningKey
    }

    override fun peerEpoch(clientId: ClientId): Int = peerEpochOf(clientId, _state.value)

    override fun trustedClientIds(): List<ClientId> =
        _state.value.entries.values.filter { it.status == TrustStatus.TRUSTED && it.clientId != selfId }
            .map { it.clientId }

    override fun peersNeedingKeyEpoch(now: Long): List<ClientId> {
        val st = _state.value
        return st.entries.values
            .filter { it.status == TrustStatus.TRUSTED && it.clientId != selfId }
            // Refetch when the current key-epoch is missing, expired, OR stripped (a pairing-QR copy with no
            // identity anchor) — the last upgrades it to the full self-contained copy so it becomes relayable.
            .filter {
                val ke = currentSealableEpoch(
                    it.clientId,
                    st,
                    now
                ); ke == null || ke.notAfter <= now || ke.identityPublicKey.isEmpty()
            }
            .map { it.clientId }
    }

    override fun currentKeyEpochBlob(clientId: ClientId): SignedBlob? =
        currentKeyEpochBlobOf(clientId, _state.value)

    override fun selfEpoch(): Int = _state.value.epochs.selfEpoch

    @Synchronized
    override fun advanceSelfEpoch(to: Int): Int {
        val cur = _state.value.epochs.selfEpoch
        if (to <= cur || _quarantined.value) return cur
        mutate { it.copy(epochs = it.epochs.copy(selfEpoch = to)) }
        return to
    }

    override fun pendingRotation(): PendingRotation? = _state.value.epochs.pending

    @Synchronized
    override fun setPendingRotation(pending: PendingRotation?) {
        if (_quarantined.value) return
        if (_state.value.epochs.pending == pending) return
        mutate { it.copy(epochs = it.epochs.copy(pending = pending)) }
    }

    // ---- internals ----

    private inline fun transition(
        clientId: ClientId,
        now: Long,
        broadcast: Boolean,
        next: (TrustEntry) -> TrustEntry?,
    ): Boolean {
        if (_quarantined.value) return false
        val cur = _state.value.entries[clientId] ?: return false
        val updated = next(cur) ?: return false
        mutate { it.copy(entries = it.entries + (clientId to updated)) }
        return broadcast
    }

    private fun mutate(f: (State) -> State) {
        val current = _state.value
        val next0 = f(current)
        // Drop overlays orphaned by a removal (bounded growth), but KEEP them for revoked tombstones: the
        // tombstone is still shown in the UI until purge, and its live (renamed) name must not revert to the
        // card's original pairing-time name. purgeRevoked() drops the overlay with the entry at permanent delete.
        val overlays = next0.overlays.filterKeys { next0.entries.containsKey(it) }
        val next =
            if (overlays.size != next0.overlays.size) next0.copy(overlays = overlays) else next0
        // Equal/stale anti-entropy still computes prompts and material-repair offers in [f], but it must not
        // spend a Keystore signature and disk transaction on an identical state snapshot.
        if (next == current) return
        // Durable-before-visible: an inbound relay must not appear applied in memory if its signed
        // persistence transaction fails. The caller can then classify the failure as retryable and a
        // redelivery will genuinely attempt the mutation again instead of observing an in-memory no-op.
        if (!_quarantined.value) writeSigned(next)
        _state.value = next
        _activePeers.value = computeActivePeers(next)
        _roster.value = computeRoster(next)
    }

    /** Resolve only prompts whose pending state has an automatic/user agreement transition. */
    private fun resolvePrompt(current: TrustEntry, prompt: TrustPrompt, now: Long): TrustEntry? =
        when (prompt) {
            TrustPrompt.NEW_TRUST, TrustPrompt.RE_TRUST ->
                if (current.status == TrustStatus.PENDING_TRUST) {
                    TrustMachine.approveTrust(current, now)
                } else {
                    null
                }

            TrustPrompt.NEW_REVOKE ->
                if (current.status == TrustStatus.PENDING_REVOKE) {
                    TrustMachine.confirmRevoke(current, now)
                } else {
                    null
                }

            TrustPrompt.CONFLICT, TrustPrompt.OTHER_ADDED, TrustPrompt.OTHER_REMOVED -> null
        }

    /** Verify a card blob exactly like QR pairing does; returns the decoded card or null. */
    private fun verifyCard(blob: SignedBlob): ClientCard? = runCatching {
        require(blob.typ == SignedType.CLIENT_CARD)
        val card = blob.decode<ClientCard>()
        require(card.clientId == blob.signerId)
        require(
            IdentityVerifier.verifyBound(
                blob.signerId,
                card.identityPublicKey,
                blob.payload,
                blob.sig
            )
        )
        card
    }.getOrNull()

    /** Advance a verified card snapshot strictly by createdAt while preserving its immutable identity anchor. */
    private fun putCard(
        cards: Map<ClientId, SignedBlob>,
        id: ClientId,
        blob: SignedBlob
    ): Map<ClientId, SignedBlob> {
        val incoming = verifyCard(blob) ?: return cards
        val held = cards[id]?.let(::verifyCard)
        if (held != null && (!held.identityPublicKey.contentEquals(incoming.identityPublicKey) ||
                incoming.createdAt <= held.createdAt)
        ) return cards
        return cards + (id to blob)
    }

    private fun computeActivePeers(st: State): List<Peer> =
        st.entries.values.mapNotNull { toPeer(it, st) }

    /** All known devices — pending at the top, trusted in the middle, revoked tombstones at the bottom. */
    private fun computeRoster(st: State): List<RosterDevice> = st.entries.values
        .map { entry ->
            // Re-check the individual signatures before marking anything "Verified" in the details sheet.
            // The store signature protects these values at rest, while these checks preserve the more precise
            // claim the badge makes: the identity authorized this card and operational key-epoch.
            val cardBlob = st.cards[entry.clientId]
            val card = cardBlob?.let(::verifyCard)
            val newestEpochPair = st.epochs.peers[entry.clientId.value]?.let { peerEpochs ->
                decodeRing(peerEpochs)
                    .filter { it.first.epoch >= peerEpochs.floor }
                    .maxByOrNull { it.first.epoch }
            }
            val verifiedEpoch = newestEpochPair?.second?.let { blob ->
                KeyEpochs.verify(blob, pinnedIdentitySpki = card?.identityPublicKey)
            }
            val identityKey = card?.identityPublicKey
                ?: verifiedEpoch?.identityPublicKey?.takeIf { it.isNotEmpty() }
            val epochDetails = verifiedEpoch?.let { epoch ->
                RosterKeyEpoch(
                    epoch = epoch.epoch,
                    signingKeyFingerprint = fingerprintOrNull(epoch.operationalSigningKey),
                    encryptionKeyFingerprint = fingerprintOrNull(epoch.hpkePublicKey),
                    notBefore = epoch.notBefore,
                    notAfter = epoch.notAfter,
                    minEpoch = epoch.minEpoch,
                )
            }
            RosterDevice(
                clientId = entry.clientId,
                status = entry.status,
                displayName = displayNameFor(entry.clientId, st),
                keyAvailable = st.cards.containsKey(entry.clientId),
                introducedByName = entry.introducedBy?.let { by -> displayNameFor(by, st) },
                revokedAt = if (entry.status == TrustStatus.REVOKED) entry.updatedAt else null,
                ownDevice = entry.ownDevice,
                currentEpoch = peerEpochOf(entry.clientId, st),
                platform = platformFor(entry.clientId, st),
                capabilities = capabilitiesFor(entry.clientId, st),
                identityKeyFingerprint = identityKey?.let(::shortFingerprint),
                keyEpoch = epochDetails,
                verified = identityKey != null && (cardBlob == null || card != null) &&
                    (newestEpochPair == null || verifiedEpoch != null),
            )
        }
        .sortedWith(compareBy({ statusOrder(it.status) }, { it.displayName ?: it.clientId.value }))

    private fun fingerprintOrNull(key: ByteArray): String? =
        key.takeIf { it.isNotEmpty() }?.let(::shortFingerprint)

    private fun shortFingerprint(key: ByteArray, bytes: Int = 8): String =
        MessageDigest.getInstance("SHA-256").digest(key).copyOf(bytes)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun statusOrder(s: TrustStatus): Int = when (s) {
        TrustStatus.PENDING_TRUST -> 0
        TrustStatus.PENDING_REVOKE -> 1
        TrustStatus.TRUSTED -> 2
        TrustStatus.REVOKED -> 3
    }

    private fun displayNameFor(id: ClientId, st: State): String? =
        st.overlays[id]?.displayName
            ?: st.cards[id]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }?.displayName

    private fun platformFor(id: ClientId, st: State): String? =
        st.overlays[id]?.platform
            ?: st.cards[id]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }?.platform

    private fun capabilitiesFor(id: ClientId, st: State): List<Capability> =
        st.overlays[id]?.capabilities
            ?: st.cards[id]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }?.capabilities
            ?: emptyList()

    // A peer is *sealable* (active) once we hold a usable key-epoch for it — that is what carries the
    // current operational + HPKE keys an NS2 envelope needs. The card (if held) only supplies the display
    // profile; identity comes from the key-epoch's carried identity key (== the card's, by fingerprint).
    private fun toPeer(entry: TrustEntry, st: State): Peer? {
        if (entry.status != TrustStatus.TRUSTED) return null
        val current = currentSealableEpoch(entry.clientId, st) ?: return null
        // Anchor from the pinned identity (card first), NOT the key-epoch's own copy — a pairing-QR key-epoch
        // omits it. No anchor (no card, and only a stripped ring entry) ⇒ unverifiable ⇒ not sealable.
        val identity = pinnedIdentityOf(entry.clientId, st) ?: return null
        val card =
            st.cards[entry.clientId]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }
        val overlay = st.overlays[entry.clientId]
        return Peer(
            clientId = entry.clientId,
            displayName = overlay?.displayName ?: card?.displayName ?: entry.clientId.shortForm(),
            platform = platformFor(entry.clientId, st) ?: "",
            identityPublicKeyB64 = b64e.encodeToString(identity),
            hpkePublicKeyB64 = b64e.encodeToString(current.hpkePublicKey),
            addedAt = entry.updatedAt,
            capabilities = overlay?.capabilities ?: card?.capabilities ?: emptyList(),
            profileUpdatedAt = overlay?.updatedAt ?: card?.createdAt ?: 0L,
            ownDevice = entry.ownDevice,
            currentEpoch = current.epoch,
        )
    }

    /** Decode (without re-verifying — the signed store already protects integrity) a peer's ring. */
    private fun decodeBlobB64(s: String): SignedBlob? =
        runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(b64d.decode(s)) }.getOrNull()

    private fun decodeRing(pe: PeerEpochs): List<Pair<ClientKeyEpoch, SignedBlob>> =
        pe.ringB64.mapNotNull { s ->
            decodeBlobB64(s)?.let { blob ->
                runCatching { blob.decode<ClientKeyEpoch>() }.getOrNull()?.let { it to blob }
            }
        }

    /** Value equality for a certificate whose key fields are byte arrays (data-class equality is referential). */
    private fun ClientKeyEpoch.semanticallyEquals(other: ClientKeyEpoch): Boolean =
        suite == other.suite &&
            clientId == other.clientId &&
            identityPublicKey.contentEquals(other.identityPublicKey) &&
            epoch == other.epoch &&
            operationalSigningKey.contentEquals(other.operationalSigningKey) &&
            hpkePublicKey.contentEquals(other.hpkePublicKey) &&
            purposes.toSet() == other.purposes.toSet() &&
            notBefore == other.notBefore &&
            notAfter == other.notAfter &&
            minEpoch == other.minEpoch

    /** The peer's highest key-epoch that is ≥ its floor AND whose notBefore has arrived — what we seal to (§7). */
    private fun currentSealableEpoch(
        id: ClientId,
        st: State,
        now: Long = System.currentTimeMillis()
    ): ClientKeyEpoch? {
        val pe = st.epochs.peers[id.value] ?: return null
        return decodeRing(pe).map { it.first }
            .filter { it.epoch >= pe.floor && it.notBefore <= now }.maxByOrNull { it.epoch }
    }

    private fun peerEpochOf(id: ClientId, st: State): Int =
        st.epochs.peers[id.value]?.let { pe -> decodeRing(pe).maxOfOrNull { it.first.epoch } } ?: 0

    /** The highest-epoch SELF-CONTAINED key-epoch [SignedBlob] held for a peer (for relaying to repair a
     *  peer). A stripped pairing-QR copy (no identity anchor) is NOT relayable — a card-less recipient
     *  couldn't verify it — so it is skipped; the background pull upgrades it to the full copy. */
    private fun currentKeyEpochBlobOf(id: ClientId, st: State): SignedBlob? =
        st.epochs.peers[id.value]?.let { pe ->
            decodeRing(pe).filter { it.first.identityPublicKey.isNotEmpty() }
                .maxByOrNull { it.first.epoch }?.second
        }

    /** First-verified-wins identity anchor for a peer: the card's identity if held, else the newest ring
     *  entry that carries one (a stripped pairing-QR key-epoch has none — the card is the source then). */
    private fun pinnedIdentityOf(id: ClientId, st: State): ByteArray? {
        st.cards[id]?.let {
            runCatching { it.decode<ClientCard>() }.getOrNull()?.identityPublicKey?.takeIf { spki -> spki.isNotEmpty() }
                ?.let { spki -> return spki }
        }
        val pe = st.epochs.peers[id.value] ?: return null
        return decodeRing(pe).sortedByDescending { it.first.epoch }
            .firstNotNullOfOrNull { it.first.identityPublicKey.takeIf { spki -> spki.isNotEmpty() } }
    }

    /** Merge a verified key-epoch into a ring: drop below-[floor] generations, replace same-epoch, keep newest K. */
    private fun mergeRing(
        ring: List<String>,
        blob: SignedBlob,
        epoch: Int,
        floor: Int
    ): List<String> {
        val byEpoch = sortedMapOf<Int, String>()
        for (s in ring) {
            val ke =
                decodeBlobB64(s)?.let { runCatching { it.decode<ClientKeyEpoch>() }.getOrNull() }
                    ?: continue
            if (ke.epoch >= floor) byEpoch[ke.epoch] = s
        }
        byEpoch[epoch] = b64e.encodeToString(ProtocolCodec.encodeToCbor(blob))
        return byEpoch.keys.sorted().takeLast(RING_SIZE).map { byEpoch.getValue(it) }
    }

    private fun load(): Loaded {
        val entriesRaw = persistence.read(entriesKey)
        val cardsRaw = persistence.read(cardsKey)
        val overlaysRaw = persistence.read(overlaysKey)
        val epochsRaw = persistence.read(epochsKey)
        val sigRaw = persistence.read(sigKey)
        val entries =
            entriesRaw?.let { runCatching { ProtocolCodec.decodeFromJson<List<TrustEntry>>(it) }.getOrNull() }
                .orEmpty()
        val cards =
            cardsRaw?.let { runCatching { ProtocolCodec.decodeFromJson<Map<String, String>>(it) }.getOrNull() }
                .orEmpty()
        val overlays = overlaysRaw?.let {
            runCatching {
                ProtocolCodec.decodeFromJson<Map<String, ProfileOverlay>>(it)
            }.getOrNull()
        }.orEmpty()
        val epochs =
            epochsRaw?.let { runCatching { ProtocolCodec.decodeFromJson<EpochSection>(it) }.getOrNull() }
                ?: EpochSection()
        val state = State(
            entries = entries.associateBy { it.clientId },
            cards = cards.mapNotNull { (k, v) ->
                runCatching {
                    ProtocolCodec.decodeFromCbor<SignedBlob>(
                        b64d.decode(v)
                    )
                }.getOrNull()?.let { ClientId(k) to it }
            }.toMap(),
            overlays = overlays.mapKeys { ClientId(it.key) },
            epochs = epochs,
        )
        // Verify over the EXACT persisted strings. An empty store has nothing to protect (fresh install,
        // or post-Clear), so it is never quarantined and gets signed on its first write. A non-empty store
        // MUST carry a signature this identity produced over these very bytes; anything else (tampered,
        // stripped, or never-signed legacy data) is unverifiable and quarantines.
        //
        // Upgrade path (§6): a roster written before the epoch section existed was signed over only the
        // first THREE sections. When no epoch section is persisted, fall back to the legacy three-section
        // verify so a pre-NS2 roster MIGRATES (loads, then re-signs as four sections on its next write)
        // instead of false-quarantining. With an epoch section present, only the four-section signature is
        // accepted — so a stripped/forged floor cannot pass.
        val baseOk = sigRaw != null && entriesRaw != null && cardsRaw != null && overlaysRaw != null
        val verified = baseOk && when {
            epochsRaw != null ->
                TrustStoreSigning.verify(
                    identity.publicKeySpki,
                    selfId,
                    entriesRaw,
                    cardsRaw,
                    overlaysRaw,
                    epochsRaw,
                    sigRaw
                )

            else ->
                TrustStoreSigning.verifyLegacyThreeSection(
                    identity.publicKeySpki,
                    selfId,
                    entriesRaw,
                    cardsRaw,
                    overlaysRaw,
                    sigRaw
                )
        }
        val isEmpty = state.entries.isEmpty() && state.cards.isEmpty()
        return Loaded(state, quarantined = !isEmpty && !verified)
    }

    /** Serialize [st], sign it with the identity key, and durably persist all sections + signature as one
     *  transaction before publishing the new in-memory state. Platform adapters must not return early. */
    private fun writeSigned(st: State) {
        val entriesJson = ProtocolCodec.encodeToJson(st.entries.values.toList())
        val cardsJson = ProtocolCodec.encodeToJson(st.cards.mapKeys { it.key.value }
            .mapValues { b64e.encodeToString(ProtocolCodec.encodeToCbor(it.value)) })
        val overlaysJson = ProtocolCodec.encodeToJson(st.overlays.mapKeys { it.key.value })
        val epochsJson = ProtocolCodec.encodeToJson(st.epochs)
        val sig = TrustStoreSigning.sign(identity, entriesJson, cardsJson, overlaysJson, epochsJson)
        persistence.write(
            mapOf(
                entriesKey to entriesJson,
                cardsKey to cardsJson,
                overlaysKey to overlaysJson,
                epochsKey to epochsJson,
                sigKey to sig,
            )
        )
    }

    private fun isAcceptableCardCreatedAt(createdAt: Long, now: Long): Boolean {
        val latestAccepted = if (now > Long.MAX_VALUE - MAX_CARD_FUTURE_SKEW_MS) {
            Long.MAX_VALUE
        } else {
            now + MAX_CARD_FUTURE_SKEW_MS
        }
        return createdAt <= latestAccepted
    }

    companion object {
        const val ENTRIES_KEY = "trust_entries_json"
        const val CARDS_KEY = "trust_cards_json"
        const val OVERLAYS_KEY = "trust_overlays_json"
        const val EPOCHS_KEY = "trust_epochs_json"
        const val SIGNATURE_KEY = "trust_sig"
        /** Generations retained per peer (§6, K≈3): enough to verify an in-flight epoch-N envelope after
         *  learning N+1, bounded by the floor. */
        const val RING_SIZE = 3
        /** Allow ordinary clock drift without letting a far-future card pin profile LWW indefinitely. */
        const val MAX_CARD_FUTURE_SKEW_MS = 5L * 60 * 1000

        // How long a revoked tombstone must persist before it can be permanently deleted. Purging drops
        // the entry, so the LWW staleness guard no longer protects that id: this delay only BOUNDS (it does
        // not eliminate) resurrection — a peer offline longer than the delay can still re-surface the row
        // (silently re-tombstoned, or a re-approval prompt). Set it longer than any realistic peer-offline window.
        const val REVOKE_PURGE_DELAY_MS = 30L * 24 * 60 * 60 * 1000

        // Stop gossiping a removal before it becomes locally purgeable. The remaining half is a convergence
        // margin for temporarily-offline devices.
        const val REVOKE_ANNOUNCE_DELAY_MS = REVOKE_PURGE_DELAY_MS / 2
    }
}
