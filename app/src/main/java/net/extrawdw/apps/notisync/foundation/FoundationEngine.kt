package net.extrawdw.apps.notisync.foundation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.channel.InboundMessage
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.apps.notisync.transport.ifKnown
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
 * handling for `TRUST` / `CARD` / `PROFILE` (forwarding `ASSET` to the notification application via
 * [onAsset]). All trust STATE stays in [TrustStore] / `TrustMachine`; this is purely the messaging
 * half that used to be stranded inside MirrorEngine.
 *
 * It is the SOLE registrant for [MessageType.DATA_SYNC]: it decodes the [DataSync] body once and
 * sub-dispatches by [DataSyncKind], so the channel stays ignorant of sub-kinds and the body is
 * decoded exactly once.
 */
class FoundationEngine(
    private val channel: SecureChannel,
    private val trust: TrustState,
    private val activityLog: ActivityLog,
    private val scope: CoroutineScope,
    /** Surfaces a trust decision needing the user (subject id, what, the introducing/revoking peer's name). */
    private val onTrustPrompt: (subject: ClientId, prompt: TrustPrompt, byName: String) -> Unit,
    /** Hands a DATA_SYNC `ASSET` sub-body to the notification app (the single-decode-point forward). */
    private val onAsset: (InboundMessage, DataSync) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Register the sole DATA_SYNC handler. Call during graph construction, before the channel runs. */
    fun register() = channel.onMessage(MessageType.DATA_SYNC, ::onDataSync)

    /**
     * Broadcast this device's trust roster to its own peers (anti-entropy + immediate propagation),
     * then push our trusted devices' cards alongside so a peer can name a newly-introduced (pending)
     * device or repair a keyless one. Other-device rows ride along but never leave our own mesh.
     */
    suspend fun broadcastTrust() {
        // The table and its cards are sealed to ONE resolved own-mesh set (sendAll), so a roster change
        // mid-broadcast can't split them across different device sets.
        val bodies = buildList {
            add(ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = trust.buildTrustTable())))
            trust.trustedCards().forEach { card ->
                add(ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.CARD, card = CardDelivery(card.signerId, card))))
            }
        }
        channel.sendAll(MessageType.DATA_SYNC, bodies, Recipients.OwnMesh, Urgency.NORMAL)
    }

    /**
     * Announce a change to this device's own mutable profile (e.g. a rename) to every peer, over the
     * low-urgency DATA_SYNC channel. Idempotent on the receiver via [ProfileUpdate.updatedAt], so it
     * is safe to re-send (e.g. on reconnect) to converge peers that were offline when the change landed.
     */
    suspend fun broadcastProfile(update: ProfileUpdate) {
        val n = channel.send(
            MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
            Recipients.AllTrusted, Urgency.NORMAL, // profile updates reach own AND other devices
        )
        if (n > 0) activityLog.add(ActivityEvent.Kind.SENT, "Device name", "updated on $n device(s)", now())
    }

    /** Deliver a signed card to one own-mesh peer (keyless repair / card announce). */
    private suspend fun sendCard(recipientId: ClientId, clientId: ClientId, card: SignedBlob) {
        channel.send(
            MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.CARD, card = CardDelivery(clientId, card))),
            Recipients.Only(recipientId), Urgency.NORMAL,
        )
    }

    /** Sole DATA_SYNC handler: decode once, sub-dispatch by kind. ASSET is forwarded to the app. */
    private fun onDataSync(msg: InboundMessage) {
        val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(msg.body) }.getOrNull() ?: return
        when (sync.kind) {
            // The notification app owns asset repair; forward unconditionally and let it apply its own gate.
            DataSyncKind.ASSET -> onAsset(msg, sync)

            DataSyncKind.PROFILE -> {
                val update = sync.profile ?: return
                // The envelope is already signature-verified against this peer's stored identity key;
                // require the update to be about that same peer (a peer can't rename another).
                if (update.clientId != msg.senderId) return
                val previousName = nameOf(msg.senderId)
                if (trust.applyProfile(update)) {
                    activityLog.add(
                        ActivityEvent.Kind.PAIRED,
                        update.displayName,
                        "renamed (was $previousName)",
                        now(),
                        deliveryMode = msg.deliveryMode.ifKnown(),
                    )
                }
            }

            DataSyncKind.TRUST -> {
                if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.TRUST, msg.senderOwnDevice)) return
                val table = sync.trust ?: return
                val byName = nameOf(msg.senderId)
                val result = trust.applyIncomingTable(msg.senderId, table)
                for ((id, prompt) in result.prompts) {
                    onTrustPrompt(id, prompt, byName)
                    activityLog.add(
                        ActivityEvent.Kind.PAIRED,
                        id.shortForm(),
                        "trust update from $byName ($prompt)",
                        now(),
                        deliveryMode = msg.deliveryMode.ifKnown(),
                    )
                }
                // Repair any device the sender trusts but lacks a key for, that we can vouch for.
                if (result.cardsToOffer.isNotEmpty()) {
                    scope.launch { result.cardsToOffer.forEach { sendCard(msg.senderId, it.signerId, it) } }
                }
                // We just learned a keyless device — advertise it so a card holder repairs us.
                if (result.needsBroadcast) scope.launch { broadcastTrust() }
            }

            DataSyncKind.CARD -> {
                if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.CARD, msg.senderOwnDevice)) return
                val delivery = sync.card ?: return
                trust.applyCard(delivery.clientId, delivery.card)
            }
        }
    }

    /** Best-known display name for an authenticated sender, falling back to its short id. */
    private fun nameOf(id: ClientId): String = trust.displayName(id) ?: id.shortForm()
}
