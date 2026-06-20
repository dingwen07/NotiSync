package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import java.util.Base64

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
)

/** What [TrustStore.applyIncomingTable] surfaces back to the caller. */
data class IncomingTrustResult(
    val prompts: List<Pair<ClientId, TrustPrompt>>,
    /** Cards the sender advertised it lacks that we hold (and trust) — to repair it over DataSyncKind.CARD. */
    val cardsToOffer: List<SignedBlob>,
    /** True if we just created a keyless entry — re-broadcast our table so a holder can repair us. */
    val needsBroadcast: Boolean = false,
)

/**
 * The device's trust roster. Three layers, keyed by [ClientId]:
 *  - trust decisions ([TrustEntry], the [TrustMachine] state),
 *  - keys (a verified, first-verified-wins, immutable-key card store),
 *  - a mutable profile overlay (live names from [ProfileUpdate]).
 *
 * The *active* roster — the only thing [recipients] and inbound verification consult — is the
 * intersection of TRUSTED decisions and held cards, so pending/revoked and keyless ids are neither
 * sealed to nor accepted from.
 */
class TrustStore(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    /** This device's own id — a device is implicitly trusted to itself and ignores external rows about it. */
    private val selfId: ClientId,
) : TrustState {
    private val entriesKey = stringPreferencesKey("trust_entries_json")
    private val cardsKey = stringPreferencesKey("trust_cards_json")     // clientId -> base64(CBOR(SignedBlob))
    private val overlaysKey = stringPreferencesKey("trust_overlays_json") // clientId -> ProfileOverlay

    private data class State(
        val entries: Map<ClientId, TrustEntry>,
        val cards: Map<ClientId, SignedBlob>,
        val overlays: Map<ClientId, ProfileOverlay>,
    )

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private val _state = MutableStateFlow(load())

    // Exposed views are directly-updated StateFlows (refreshed in mutate), so a UI state change
    // recomposes synchronously on the action's thread — no derived-flow round trip.
    private val _activePeers = MutableStateFlow(computeActivePeers(_state.value))
    private val _roster = MutableStateFlow(computeRoster(_state.value))

    /** TRUSTED devices whose card we hold — recipients() / handleEnvelope's roster. */
    override val activePeers: StateFlow<List<Peer>> = _activePeers

    /** Everything the user reviews — trusted + pending (revoked tombstones hidden) — for the Devices UI. */
    val roster: StateFlow<List<RosterDevice>> = _roster

    fun cardFor(clientId: ClientId): SignedBlob? = _state.value.cards[clientId]
    override fun displayName(clientId: ClientId): String? = displayNameFor(clientId, _state.value)
    fun statusOf(clientId: ClientId): TrustStatus? = _state.value.entries[clientId]?.status

    // ---- local user actions (return true when the change should be broadcast immediately) ----

    /** Optical/manual add: pin [cardBlob]'s keys and trust it. Returns false if the card fails verification. */
    fun addLocal(cardBlob: SignedBlob, now: Long, ownDevice: Boolean = true): Boolean {
        val card = verifyCard(cardBlob) ?: return false
        mutate { st ->
            st.copy(
                cards = putCard(st.cards, card.clientId, cardBlob),
                entries = st.entries + (card.clientId to TrustMachine.localAdd(card.clientId, now, ownDevice)),
            )
        }
        return true
    }

    fun revokeLocal(clientId: ClientId, now: Long): Boolean {
        // Keep the device's own/other classification on its tombstone — a revoke must never reclassify it.
        mutate { st ->
            val ownDevice = st.entries[clientId]?.ownDevice ?: true
            st.copy(entries = st.entries + (clientId to TrustMachine.localRevoke(clientId, now, ownDevice)))
        }
        return true // overturn/own-decision -> broadcast now
    }

    /**
     * Permanently forget a REVOKED device — drops its tombstone, card, and overlay. Local cleanup only
     * (not broadcast). Only safe once the tombstone has outlived any lagging peer's stale-trust window,
     * so the UI gates this on [REVOKE_PURGE_DELAY_MS]; until then a stale re-introduction is re-tombstoned.
     */
    fun purgeRevoked(clientId: ClientId): Boolean {
        if (_state.value.entries[clientId]?.status != TrustStatus.REVOKED) return false
        mutate { it.copy(entries = it.entries - clientId, cards = it.cards - clientId, overlays = it.overlays - clientId) }
        return false // purely local — nothing to propagate
    }

    /** Approve a PENDING_TRUST. Silent (anti-entropy carries it). */
    fun approveTrust(clientId: ClientId, now: Long): Boolean = transition(clientId, now, broadcast = false) {
        if (it.status == TrustStatus.PENDING_TRUST) TrustMachine.approveTrust(it, now) else null
    }

    /** Reject a PENDING_TRUST -> REVOKED. Overturn -> broadcast. */
    fun rejectTrust(clientId: ClientId, now: Long): Boolean = transition(clientId, now, broadcast = true) {
        if (it.status == TrustStatus.PENDING_TRUST) TrustMachine.rejectTrust(it, now) else null
    }

    /** Confirm a PENDING_REVOKE -> REVOKED. Silent. */
    fun confirmRevoke(clientId: ClientId, now: Long): Boolean = transition(clientId, now, broadcast = false) {
        if (it.status == TrustStatus.PENDING_REVOKE) TrustMachine.confirmRevoke(it, now) else null
    }

    /** Reject a PENDING_REVOKE (keep the device) -> TRUSTED. Overturn -> broadcast. */
    fun keepTrusted(clientId: ClientId, now: Long): Boolean = transition(clientId, now, broadcast = true) {
        if (it.status == TrustStatus.PENDING_REVOKE) TrustMachine.keepTrusted(it, now) else null
    }

    // ---- incoming ----

    /** Fold a peer's broadcast roster into ours. Returns prompts to raise, cards to offer, and whether to re-broadcast. */
    override fun applyIncomingTable(sender: ClientId, table: TrustTable): IncomingTrustResult {
        val prompts = mutableListOf<Pair<ClientId, TrustPrompt>>()
        val offers = mutableListOf<SignedBlob>()
        var needsBroadcast = false
        mutate { st ->
            var entries = st.entries
            for (wire in table.entries) {
                // Ignore a peer's self-row and any external assertion about THIS device's own trust state.
                if (wire.clientId == sender || wire.clientId == selfId) continue
                // Only TRUSTED/REVOKED assertions change our trust state; a peer's PENDING_* is informational.
                if (wire.status == TrustStatus.TRUSTED || wire.status == TrustStatus.REVOKED) {
                    val r = TrustMachine.resolveIncoming(entries[wire.clientId], wire, sender)
                    if (r.entry != entries[wire.clientId]) {
                        entries = entries + (wire.clientId to r.entry)
                        // A new keyless trust/pending entry: re-broadcast so a card holder repairs us.
                        if (!st.cards.containsKey(wire.clientId) &&
                            (r.entry.status == TrustStatus.PENDING_TRUST || r.entry.status == TrustStatus.TRUSTED)
                        ) {
                            needsBroadcast = true
                        }
                    }
                    r.prompt?.let { prompts += wire.clientId to it }
                }
                // Keyless repair (runs for ANY wire status, incl. pending): offer our card if the sender
                // lacks it — for own AND other trusted devices, since both now propagate within the mesh.
                val mine = entries[wire.clientId] // running accumulator, consistent with the fold above
                if (!wire.keyAvailable && mine?.status == TrustStatus.TRUSTED) {
                    st.cards[wire.clientId]?.let { offers += it }
                }
            }
            st.copy(entries = entries)
        }
        return IncomingTrustResult(prompts, offers, needsBroadcast)
    }

    /**
     * Store a delivered card. Self-verifying (clientId == fingerprint + self-sig) and first-verified-wins,
     * so it is safe to accept even before a trust entry exists — that's what lets a card pushed alongside
     * an introduction resolve a still-pending device's name instead of leaving it "Unknown".
     */
    override fun applyCard(clientId: ClientId, cardBlob: SignedBlob): Boolean {
        val card = verifyCard(cardBlob) ?: return false
        if (card.clientId != clientId) return false
        if (_state.value.cards.containsKey(clientId)) return false // already pinned (immutable) — see putCard
        mutate { it.copy(cards = putCard(it.cards, clientId, cardBlob)) }
        return true
    }

    /** Cards we hold for every trusted device (own + other) — pushed alongside the roster (to our own
     *  devices only) so a peer can name a still-pending device or repair a keyless one. */
    override fun trustedCards(): List<SignedBlob> = _state.value.let { st ->
        st.entries.values.filter { it.status == TrustStatus.TRUSTED }.mapNotNull { st.cards[it.clientId] }
    }

    /** Apply a live profile update (LWW vs the card's createdAt floor). Returns true if anything changed. */
    override fun applyProfile(update: ProfileUpdate): Boolean {
        val st = _state.value
        if (st.entries[update.clientId]?.status != TrustStatus.TRUSTED) return false // only trusted devices' profiles converge
        val card = st.cards[update.clientId]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() } ?: return false
        val floor = st.overlays[update.clientId]?.updatedAt ?: card.createdAt
        if (update.updatedAt <= floor) return false
        mutate {
            it.copy(overlays = it.overlays + (update.clientId to ProfileOverlay(
                update.displayName, update.platform, update.capabilities, update.updatedAt,
            )))
        }
        return true
    }

    /**
     * Our broadcast roster, sent only to our own devices: every entry but our self-row, each tagged with
     * its [TrustEntry.ownDevice] category and key-availability. Own-mesh rows include both PENDING_* states
     * (informational — a receiver never acts on a peer's pending, only honours keyAvailable=false to repair
     * a keyless one); "other" rows are always TRUSTED/REVOKED and a receiver applies them immediately.
     */
    override fun buildTrustTable(): TrustTable {
        val st = _state.value
        return TrustTable(
            st.entries.values
                .filter { it.clientId != selfId }
                .map { TrustTableEntry(it.clientId, it.status, it.updatedAt, keyAvailable = st.cards.containsKey(it.clientId), ownDevice = it.ownDevice) },
        )
    }

    // ---- internals ----

    private inline fun transition(
        clientId: ClientId,
        now: Long,
        broadcast: Boolean,
        next: (TrustEntry) -> TrustEntry?,
    ): Boolean {
        val cur = _state.value.entries[clientId] ?: return false
        val updated = next(cur) ?: return false
        mutate { it.copy(entries = it.entries + (clientId to updated)) }
        return broadcast
    }

    private fun mutate(f: (State) -> State) {
        val next0 = f(_state.value)
        // Overlays only ever apply to non-revoked devices; drop any left behind by a removal (bounded growth).
        val overlays = next0.overlays.filterKeys { next0.entries[it]?.status != null && next0.entries[it]?.status != TrustStatus.REVOKED }
        val next = if (overlays.size != next0.overlays.size) next0.copy(overlays = overlays) else next0
        _state.value = next
        _activePeers.value = computeActivePeers(next)
        _roster.value = computeRoster(next)
        persist()
    }

    /** Verify a card blob exactly like QR pairing does; returns the decoded card or null. */
    private fun verifyCard(blob: SignedBlob): ClientCard? = runCatching {
        require(blob.typ == SignedType.CLIENT_CARD)
        val card = blob.decode<ClientCard>()
        require(card.clientId == blob.signerId)
        require(IdentityVerifier.verifyBound(blob.signerId, card.identityPublicKey, blob.payload, blob.sig))
        card
    }.getOrNull()

    /** First-verified-wins, immutable keys: keep the pinned card; never overwrite with a re-keyed one. */
    private fun putCard(cards: Map<ClientId, SignedBlob>, id: ClientId, blob: SignedBlob): Map<ClientId, SignedBlob> =
        if (cards.containsKey(id)) cards else cards + (id to blob)

    private fun computeActivePeers(st: State): List<Peer> = st.entries.values.mapNotNull { toPeer(it, st) }

    /** All known devices — pending at the top, trusted in the middle, revoked tombstones at the bottom. */
    private fun computeRoster(st: State): List<RosterDevice> = st.entries.values
        .map {
            RosterDevice(
                clientId = it.clientId,
                status = it.status,
                displayName = displayNameFor(it.clientId, st),
                keyAvailable = st.cards.containsKey(it.clientId),
                introducedByName = it.introducedBy?.let { by -> displayNameFor(by, st) },
                revokedAt = if (it.status == TrustStatus.REVOKED) it.updatedAt else null,
                ownDevice = it.ownDevice,
            )
        }
        .sortedWith(compareBy({ statusOrder(it.status) }, { it.displayName ?: it.clientId.value }))

    private fun statusOrder(s: TrustStatus): Int = when (s) {
        TrustStatus.PENDING_TRUST -> 0
        TrustStatus.PENDING_REVOKE -> 1
        TrustStatus.TRUSTED -> 2
        TrustStatus.REVOKED -> 3
    }

    private fun displayNameFor(id: ClientId, st: State): String? =
        st.overlays[id]?.displayName ?: st.cards[id]?.let { runCatching { it.decode<ClientCard>() }.getOrNull() }?.displayName

    private fun toPeer(entry: TrustEntry, st: State): Peer? {
        if (entry.status != TrustStatus.TRUSTED) return null
        val blob = st.cards[entry.clientId] ?: return null
        val card = runCatching { blob.decode<ClientCard>() }.getOrNull() ?: return null
        val overlay = st.overlays[entry.clientId]
        return Peer(
            clientId = entry.clientId,
            displayName = overlay?.displayName ?: card.displayName,
            platform = overlay?.platform ?: card.platform,
            identityPublicKeyB64 = b64e.encodeToString(card.identityPublicKey),
            hpkePublicKeysetB64 = b64e.encodeToString(card.hpkePublicKeyset),
            addedAt = entry.updatedAt,
            capabilities = overlay?.capabilities ?: card.capabilities,
            profileUpdatedAt = overlay?.updatedAt ?: card.createdAt,
            ownDevice = entry.ownDevice,
        )
    }

    private fun load(): State = runBlocking {
        val raw = store.data.first()[entriesKey]
        val cardsRaw = store.data.first()[cardsKey]
        val overlaysRaw = store.data.first()[overlaysKey]
        val entries = raw?.let { runCatching { ProtocolCodec.decodeFromJson<List<TrustEntry>>(it) }.getOrNull() }.orEmpty()
        val cards = cardsRaw?.let { runCatching { ProtocolCodec.decodeFromJson<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        val overlays = overlaysRaw?.let { runCatching { ProtocolCodec.decodeFromJson<Map<String, ProfileOverlay>>(it) }.getOrNull() }.orEmpty()
        State(
            entries = entries.associateBy { it.clientId },
            cards = cards.mapNotNull { (k, v) -> runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(b64d.decode(v)) }.getOrNull()?.let { ClientId(k) to it } }.toMap(),
            overlays = overlays.mapKeys { ClientId(it.key) },
        )
    }

    private fun persist() {
        val st = _state.value
        val entriesJson = ProtocolCodec.encodeToJson(st.entries.values.toList())
        val cardsJson = ProtocolCodec.encodeToJson(st.cards.mapKeys { it.key.value }.mapValues { b64e.encodeToString(ProtocolCodec.encodeToCbor(it.value)) })
        val overlaysJson = ProtocolCodec.encodeToJson(st.overlays.mapKeys { it.key.value })
        scope.launch {
            store.edit {
                it[entriesKey] = entriesJson
                it[cardsKey] = cardsJson
                it[overlaysKey] = overlaysJson
            }
        }
    }

    companion object {
        // How long a revoked tombstone must persist before it can be permanently deleted. Purging drops
        // the entry, so the LWW staleness guard no longer protects that id: this delay only BOUNDS (it does
        // not eliminate) resurrection — a peer offline longer than the delay can still re-surface the row
        // (silently re-tombstoned, or a re-approval prompt). Set it longer than any realistic peer-offline window.
        const val REVOKE_PURGE_DELAY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
