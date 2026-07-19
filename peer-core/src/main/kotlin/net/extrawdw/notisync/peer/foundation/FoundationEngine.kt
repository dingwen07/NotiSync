package net.extrawdw.notisync.peer.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.peer.ports.FoundationEventSink
import net.extrawdw.notisync.peer.ports.IncomingTrustChange
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.transport.ifKnown
import net.extrawdw.notisync.peer.trust.TrustPrompt
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Urgency

/**
 * The device-trust / profile / card FOUNDATION's wire I/O — a first-class [SecureChannel] client
 * beside [TrustStore]. It owns the outbound trust/profile/card broadcasts and the inbound DATA_SYNC
 * handling for `TRUST` / `CARD` / `PROFILE` (forwarding `ASSET` and `FILTER` to the notification
 * application via [onAsset] / [onFilter]). All trust STATE stays in [TrustStore] / `TrustMachine`; this is
 * purely the messaging half that used to be stranded inside MirrorEngine.
 *
 * It is the SOLE registrant for [MessageType.DATA_SYNC]: it decodes the [DataSync] body once and
 * sub-dispatches by [DataSyncKind], so the channel stays ignorant of sub-kinds and the body is
 * decoded exactly once.
 */
open class FoundationEngine(
    private val channel: SecureChannel,
    private val trust: TrustState,
    private val scope: CoroutineScope,
    /** Surfaces a trust decision needing the user (subject id, what, the introducing/revoking peer's name). */
    private val onTrustPrompt: (subject: ClientId, prompt: TrustPrompt, byName: String) -> Unit,
    /** Hands a DATA_SYNC `ASSET` sub-body to the notification app (the single-decode-point forward). */
    private val onAsset: (InboundMessage, DataSync) -> Unit,
    /** Hands a DATA_SYNC `FILTER` sub-body to the notification app (a peer's request to suppress some of the
     *  captures this device sends it). Defaults to a no-op for tests / provider-only builds. */
    private val onFilter: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    /** Hands a DATA_SYNC `NOTIFICATION` sub-body — a quiet full notification (e.g. a throttled update to an
     *  ongoing notification) — to the notification app. Defaults to a no-op for tests / provider-only builds. */
    private val onNotificationSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    /** Hands an authenticated own-mesh DATA_SYNC `RUN` sub-body to the platform Run feature. The foundation
     *  remains the single decoder; state persistence and control validation stay with that feature. */
    private val onRunSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    /** Observes every successfully decoded DATA_SYNC before its sub-kind handler. Generic local bridges can
     *  reuse the exact decoded object without becoming a second decoder. */
    private val onDecodedDataSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    /** Observes authenticated DATA_SYNC bytes that do not decode. A generic type-only bridge may still
     *  deliver the opaque body; semantic Foundation handlers continue to ignore it. */
    private val onMalformedDataSync: (InboundMessage) -> Unit = {},
    private val eventSink: FoundationEventSink = FoundationEventSink.None,
    private val incomingTrustPolicy: IncomingTrustPolicy = IncomingTrustPolicy.MANUAL,
    /** Our own current key-epoch [SignedBlob], announced E2E in each trust broadcast so own-mesh peers
     *  converge it without polling the broker. Null when the NS2 operational layer isn't available. */
    private val selfKeyEpoch: () -> SignedBlob? = { null },
    /** Pull a peer's [SignedBlob] key-epoch from the broker (`GET /v2/keyepoch`) when a roster advertises a
     *  higher epoch than we hold — the §5 convergence trigger. */
    private val fetchKeyEpoch: suspend (ClientId, Int?) -> SignedBlob? = { _, _ -> null },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Register the sole DATA_SYNC handler. Call during graph construction, before the channel runs. */
    fun register() = channel.onMessage(MessageType.DATA_SYNC, ::onDataSync)

    /** Per-peer last key-epoch refetch time, so the responsive (inbound-driven) path can't fetch-storm. */
    private val lastRefetch = java.util.concurrent.ConcurrentHashMap<ClientId, Long>()

    /**
     * Proactively pull + apply the current key-epoch for every trusted peer whose key is NOT usable — none
     * held, OR the held one is expired (the peer has rotated past it). "Available ≠ usable." This is the
     * BOOTSTRAP that breaks the upgrade deadlock: a peer is sealable only once we hold its key-epoch, but until
     * then it is not an active peer, so neither device can E2E-send the other its key-epoch (and the reactive
     * refetch-on-higher-epoch can't fire — the TrustTable that triggers it can't be delivered either). Each
     * device self-publishes to the broker on startup, so each independently pulls the other's here — no re-pair,
     * no E2E round-trip. A not-yet-published peer (404) is simply retried next start/foreground.
     */
    suspend fun convergeKeyEpochs() {
        for (id in trust.peersNeedingKeyEpoch(now())) refetchKeyEpoch(id)
    }

    /**
     * Fetch a peer's CURRENT key-epoch from the broker and apply it (per-peer cooldown to avoid storms). When
     * the pull fails (the broker hasn't got it, or it didn't apply) and [broadcastOnFailure], re-broadcast our
     * roster so a mesh peer that DOES hold this peer's key-epoch can repair us via a CardDelivery(epochBlob).
     * Returns true iff a key-epoch was applied.
     */
    suspend fun refetchKeyEpoch(id: ClientId, broadcastOnFailure: Boolean = false): Boolean {
        val nowMs = now()
        lastRefetch[id]?.let { if (nowMs - it < REFETCH_COOLDOWN_MS) return false }
        lastRefetch[id] = nowMs
        val applied =
            runCatching { fetchKeyEpoch(id, null) }.getOrNull()?.let { trust.applyKeyEpoch(id, it) }
                ?: false
        if (!applied && broadcastOnFailure) broadcastTrust()
        return applied
    }

    /**
     * Inbound from a sender whose operational epoch we couldn't resolve (we hold no usable key-epoch for it):
     * fetch its key-epoch; if we can't, advertise our roster so a mesh peer repairs us. Only for TRUSTED
     * senders — an untrusted/unknown id must not drive a fetch.
     */
    suspend fun onUnresolvedSender(id: ClientId) {
        if (id in trust.trustedClientIds()) refetchKeyEpoch(id, broadcastOnFailure = true)
    }

    /** Relay a peer's self-authenticating key-epoch to one recipient (repair: it trusts the subject but lacks
     *  its current key). The receiver verifies the key-epoch independently — we are only the relay. */
    private suspend fun offerKeyEpoch(recipientId: ClientId, keyEpoch: SignedBlob) {
        channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.CARD,
                    card = CardDelivery(keyEpoch.signerId, epochBlob = keyEpoch)
                )
            ),
            Recipients.Only(recipientId), Urgency.NORMAL,
        )
    }

    /**
     * Broadcast this device's trust roster to its own peers (anti-entropy + immediate propagation),
     * then push our trusted devices' cards alongside so a peer can name a newly-introduced (pending)
     * device or repair a keyless one. Other-device rows ride along but never leave our own mesh.
     */
    suspend fun broadcastTrust() {
        // The table and its cards/key-epoch are sealed to ONE resolved own-mesh set (sendAll), so a roster
        // change mid-broadcast can't split them across different device sets. Signed with the IDENTITY root:
        // a TrustTable is a roster assertion that must bind to the immutable root and verify without epoch
        // convergence (§2.3); the card/key-epoch payloads ride along (CARD accepts any signer) and each
        // self-authenticates independently of the relaying envelope.
        val bodies = buildList {
            add(
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        DataSyncKind.TRUST,
                        trust = trust.buildTrustTable()
                    )
                )
            )
            // Announce our own current key-epoch so own-mesh peers converge it E2E (no poll), per §5.
            selfKeyEpoch()?.let {
                add(
                    ProtocolCodec.encodeToCbor(
                        DataSync(
                            DataSyncKind.CARD,
                            card = CardDelivery(channel.clientId, epochBlob = it)
                        )
                    )
                )
            }
            trust.trustedCards().forEach { card ->
                add(
                    ProtocolCodec.encodeToCbor(
                        DataSync(
                            DataSyncKind.CARD,
                            card = CardDelivery(card.signerId, card = card)
                        )
                    )
                )
            }
        }
        channel.sendAll(
            MessageType.DATA_SYNC,
            bodies,
            Recipients.OwnMesh,
            Urgency.NORMAL,
            SignerSelection.IDENTITY
        )
    }

    /**
     * Announce a change to this device's own mutable profile (e.g. a rename) to every peer, over the
     * low-urgency DATA_SYNC channel. Idempotent on the receiver via [ProfileUpdate.updatedAt], so it
     * is safe to re-send (e.g. on reconnect) to converge peers that were offline when the change landed.
     */
    suspend fun broadcastProfile(update: ProfileUpdate) {
        val n = channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
            Recipients.AllTrusted,
            Urgency.NORMAL, // profile updates reach own AND other devices
        )
        if (n > 0) eventSink.profileBroadcast(n)
    }

    /**
     * Push a single key-epoch E2E to own-mesh (rotation pre-warm, §7) so peers cache the next epoch before
     * its activation without polling the broker. Identity-signed (always valid, no epoch dependency); the
     * epochBlob self-authenticates independently of the relaying envelope.
     */
    suspend fun announceKeyEpoch(keyEpoch: SignedBlob) {
        channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.CARD,
                    card = CardDelivery(channel.clientId, epochBlob = keyEpoch)
                )
            ),
            Recipients.OwnMesh, Urgency.NORMAL, SignerSelection.IDENTITY,
        )
    }

    /** Deliver a signed card to one own-mesh peer (keyless repair / card announce). */
    private suspend fun sendCard(recipientId: ClientId, clientId: ClientId, card: SignedBlob) {
        channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.CARD,
                    card = CardDelivery(clientId, card)
                )
            ),
            Recipients.Only(recipientId),
            Urgency.NORMAL,
        )
    }

    /** Sole DATA_SYNC handler: decode once, sub-dispatch by kind. ASSET is forwarded to the app. */
    private fun onDataSync(msg: InboundMessage) {
        val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(msg.body) }.getOrNull()
            ?: return onMalformedDataSync(msg)
        onDecodedDataSync(msg, sync)
        when (sync.kind) {
            // The notification app owns asset repair; forward unconditionally and let it apply its own gate.
            DataSyncKind.ASSET -> onAsset(msg, sync)

            // A peer's notification-suppression request — notification-app business, like ASSET; forward and
            // let it apply its own own-mesh gate + persistence.
            DataSyncKind.FILTER -> onFilter(msg, sync)

            // A quiet full notification (e.g. a throttled ongoing-notification update) — notification-app
            // business like ASSET/FILTER; forward and let it apply its own own-mesh gate + last-writer-wins.
            DataSyncKind.NOTIFICATION -> onNotificationSync(msg, sync)

            DataSyncKind.RUN -> {
                if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.RUN, msg.senderOwnDevice)) return
                onRunSync(msg, sync)
            }

            DataSyncKind.PROFILE -> {
                val update = sync.profile ?: return
                // The envelope is already signature-verified against this peer's stored identity key;
                // require the update to be about that same peer (a peer can't rename another).
                if (update.clientId != msg.senderId) return
                val previousName = nameOf(msg.senderId)
                if (trust.applyProfile(update)) {
                    eventSink.profileRenamed(update.displayName, previousName, msg.deliveryMode.ifKnown())
                }
            }

            DataSyncKind.TRUST -> {
                if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.TRUST, msg.senderOwnDevice)) return
                // A roster assertion MUST bind to the immutable identity root (§2.3): reject an operationally
                // signed TrustTable so a leaked TEE operational key can never drive roster gossip (§8 #12).
                if (msg.signerEpoch != 0) return
                val table = sync.trust ?: return
                val byName = nameOf(msg.senderId)
                val result = trust.applyIncomingTable(msg.senderId, table)
                for ((id, prompt) in result.prompts) {
                    val auto = incomingTrustPolicy.shouldAutoApply(
                        IncomingTrustChange(
                            senderId = msg.senderId,
                            subjectId = id,
                            prompt = prompt,
                            senderIsTrustedOwnDevice = msg.senderOwnDevice,
                        )
                    )
                    val applied = auto && trust.resolveIncomingPrompt(id, prompt, now())
                    if (!applied) onTrustPrompt(id, prompt, byName)
                    eventSink.trustChanged(
                        id,
                        prompt,
                        byName,
                        msg.deliveryMode.ifKnown(),
                        automaticallyApplied = applied,
                    )
                }
                // Repair any device the sender trusts but lacks a card for, that we can vouch for.
                // This and the two launches below are best-effort sends, guarded so a broker failure
                // (socket timeout, attestation cooldown) drops just that repair round instead of escaping
                // the launch — anti-entropy re-runs it on the next trust exchange. Whole-batch guards: the
                // first failure means the broker is unreachable for the rest of the batch too.
                if (result.cardsToOffer.isNotEmpty()) {
                    scope.launch {
                        runCatching {
                            result.cardsToOffer.forEach {
                                sendCard(
                                    msg.senderId,
                                    it.signerId,
                                    it
                                )
                            }
                        }
                    }
                }
                // NS2 key-epoch repair: the sender advertised a device at an epoch BEHIND what we hold (incl.
                // none) — relay our current key-epoch for it so the sender can reach it (e.g. A relays C's
                // key-epoch to B when B trusts C but lacks its key). Self-authenticating, so B verifies it itself.
                if (result.keyEpochsToOffer.isNotEmpty()) {
                    scope.launch {
                        runCatching {
                            result.keyEpochsToOffer.forEach {
                                offerKeyEpoch(
                                    msg.senderId,
                                    it
                                )
                            }
                        }
                    }
                }
                // Converge operational keys: pull a peer's key-epoch when the roster advertises a higher epoch
                // than we hold (the §5 refetch trigger). Self-authenticating, so pulling is safe; idempotent +
                // bounded (each id fetched only while its advertised epoch outruns ours).
                val stale =
                    table.entries.filter { it.epoch > trust.peerEpoch(it.clientId) && it.clientId != channel.clientId }
                if (stale.isNotEmpty()) scope.launch {
                    for (e in stale) runCatching { fetchKeyEpoch(e.clientId, e.epoch) }.getOrNull()
                        ?.let { trust.applyKeyEpoch(e.clientId, it) }
                }
                // We just learned a keyless device — advertise it so a card holder repairs us.
                if (result.needsBroadcast) scope.launch { runCatching { broadcastTrust() } }
            }

            DataSyncKind.CARD -> {
                if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.CARD, msg.senderOwnDevice)) return
                val delivery = sync.card ?: return
                // A delivery carries self-authenticating material — a card (identity + profile) and/or a
                // key-epoch (NS2 operational keys); each verifies and applies independently of the relaying
                // envelope, so delivery.clientId need not be the sender.
                delivery.card?.let { trust.applyCard(delivery.clientId, it) }
                delivery.epochBlob?.let { trust.applyKeyEpoch(delivery.clientId, it) }
            }
        }
    }

    /** Best-known display name for an authenticated sender, falling back to its short id. */
    private fun nameOf(id: ClientId): String = trust.displayName(id) ?: id.shortForm()

    private companion object {
        /** Min interval between key-epoch refetches for the same peer — bounds the inbound-driven path so a
         *  burst of undecryptable messages from one sender triggers at most one fetch + one roster broadcast. */
        const val REFETCH_COOLDOWN_MS = 30_000L
    }
}
