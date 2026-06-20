package net.extrawdw.apps.notisync.domain

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.IncomingTrustResult
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.notisync.protocol.AssetSync
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.AssetSyncItem
import net.extrawdw.notisync.protocol.AssetSyncKind
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Renders / clears mirrored notifications. Implemented by the notification layer. */
interface MirrorRenderer {
    fun render(notif: CapturedNotification)
    fun clear(sourceClientId: ClientId, sourceKey: String)
}

/** Cancels a local original notification (provider side) when a remote dismissal arrives. */
fun interface OriginalCanceler {
    fun cancel(sourceKey: String)
}

/** The outcome of resolving a batch of private-asset refs on the consumer side. */
data class ResolveResult(val newlyAvailable: Boolean, val stillMissing: List<PrivateAssetRef>)

/** Fetches/repairs private graphics for the mirror engine (consumer + provider sides). */
interface AssetResolver {
    /** Fetch + decrypt + verify any [refs] not already cached (consumer side). */
    suspend fun ensureLocal(refs: List<PrivateAssetRef>): ResolveResult

    /**
     * Re-seal + re-upload a previously-sent asset under its existing id (provider repair). Returns a
     * fresh ref to hand back to the requester, or null if this device no longer holds the plaintext
     * or ticket needed to repair it.
     */
    suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef?
}

/**
 * Orchestrates the E2E mirror flow:
 *  - capture (provider): seal a [CapturedNotification] to every peer and send it,
 *  - receive (consumer): verify the sender is a trusted peer, decrypt, and render or clear,
 *  - dismissal: propagate idempotent [DismissEvent]s both directions.
 * The broker only ever sees ciphertext + routing metadata.
 */
class MirrorEngine(
    private val signer: IdentitySigner,
    private val myHpkePrivateKeyset: ByteArray,
    private val transport: net.extrawdw.notisync.protocol.Transport,
    private val peersProvider: () -> List<Peer>,
    private val renderer: MirrorRenderer,
    private val activityLog: ActivityLog,
    private val scope: CoroutineScope,
    private val assetResolver: AssetResolver? = null,
    /**
     * Applies a peer's announced [ProfileUpdate] to local roster state (last-writer-wins), returning
     * true if anything changed. Null in tests / contexts with no roster store.
     */
    private val profileUpdater: ((ProfileUpdate) -> Boolean)? = null,
    /** This device's broadcast trust roster, for anti-entropy + immediate propagation. */
    private val trustTableProvider: (() -> TrustTable)? = null,
    /** Cards for our own trusted devices, pushed alongside the roster so peers can name pending devices. */
    private val ownCardsProvider: (() -> List<SignedBlob>)? = null,
    /** Folds a peer's incoming roster into local trust state; returns prompts to raise + cards to offer. */
    private val onTrustTable: ((sender: ClientId, table: TrustTable) -> IncomingTrustResult)? = null,
    /** Pins a delivered card (first-verified-wins). Returns true if newly stored. */
    private val onCardDelivery: ((ClientId, SignedBlob) -> Boolean)? = null,
    /** Surfaces a trust decision needing the user (subject id, what, and the introducing/revoking peer's name). */
    private val onTrustPrompt: ((subject: ClientId, prompt: TrustPrompt, byName: String) -> Unit)? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Resolves a package name to a user-facing app label; defaults to the package itself. */
    private val appLabelResolver: (String) -> String = { it },
) {
    @Volatile var originalCanceler: OriginalCanceler? = null

    private val b64 = Base64.getDecoder()
    private val seq = AtomicLong(now())

    /** Bounded LRU of recently-seen message ids for idempotent delivery (dedupes FCM + WebSocket). */
    private val seen: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 512
        })
    )

    /** Notifications awaiting a repaired asset, keyed by the missing assetHash (bounded LRU). */
    private val pendingAssetRenders: MutableMap<String, CapturedNotification> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, CapturedNotification>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CapturedNotification>): Boolean = size > 128
        }
    )

    private fun rk(peer: Peer) = RecipientKey(peer.clientId, b64.decode(peer.hpkePublicKeysetB64))

    /** Own-mesh recipients — notifications, dismissals, trust tables, cards, and asset repair go only here. */
    private fun recipients(): List<RecipientKey> = peersProvider().filter { it.ownDevice }.map(::rk)

    /** Every trusted recipient (own + profile-only not-own) — only profile updates fan out this wide. */
    private fun allRecipients(): List<RecipientKey> = peersProvider().map(::rk)

    /**
     * Best-effort app name for a dismissal Activity row. A [DismissEvent] carries only the opaque
     * source key — the origin device's Android notification key, `userId|package|id|tag|uid` — so we
     * recover the package from it and resolve a friendly label when that app is installed on this
     * device. Otherwise we fall back to the package (still tells the user which app), then the key.
     */
    private fun dismissedAppName(sourceKey: String): String {
        val pkg = sourceKey.split('|').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return sourceKey
        return appLabelResolver(pkg)
    }

    suspend fun captureLocal(notif: CapturedNotification) {
        val recipients = recipients()
        if (recipients.isEmpty()) return
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = ProtocolCodec.encodeToCbor(notif),
            recipients = recipients,
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.HIGH)
        activityLog.add(ActivityEvent.Kind.SENT, notif.appLabel, "mirrored to ${recipients.size} device(s)", now())
    }

    suspend fun dismissLocal(sourceClientId: ClientId, sourceKey: String) {
        // No local-suppression set here: echoes from our own cancelNotification() (issued on a remote
        // dismissal) carry REASON_LISTENER_CANCEL and are filtered out by the listener's reason
        // allowlist. A permanent suppression set would wrongly block re-dismissals of reused keys.
        val recipients = recipients()
        if (recipients.isEmpty()) return
        val event = DismissEvent(sourceClientId, sourceKey, now())
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.DISMISSAL,
            bodyPlaintext = ProtocolCodec.encodeToCbor(event),
            recipients = recipients,
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.NORMAL)
        activityLog.add(ActivityEvent.Kind.DISMISSED, dismissedAppName(sourceKey), "synced to ${recipients.size} device(s)", now())
    }

    /**
     * Announce a change to this device's own mutable profile (e.g. a rename) to every peer, over the
     * low-urgency DATA_SYNC channel. Idempotent on the receiver via [ProfileUpdate.updatedAt], so it
     * is safe to re-send (e.g. on reconnect) to converge peers that were offline when the change landed.
     */
    suspend fun broadcastProfile(update: ProfileUpdate) {
        val recipients = allRecipients() // profile updates reach own AND profile-only devices
        if (recipients.isEmpty()) return
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.DATA_SYNC,
            bodyPlaintext = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = update)),
            recipients = recipients,
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.NORMAL)
        activityLog.add(ActivityEvent.Kind.SENT, "Device name", "updated on ${recipients.size} device(s)", now())
    }

    /** Broadcast this device's trust roster to every own peer (anti-entropy + immediate propagation), and
     *  push our own devices' cards alongside so a peer can resolve a newly-introduced (pending) device's name. */
    suspend fun broadcastTrust() {
        val table = trustTableProvider?.invoke() ?: return
        val recipients = recipients() // own only
        if (recipients.isEmpty()) return
        transport.send(sealDataSync(DataSync(DataSyncKind.TRUST, trust = table), recipients), Urgency.NORMAL)
        ownCardsProvider?.invoke()?.forEach { card ->
            transport.send(sealDataSync(DataSync(DataSyncKind.CARD, card = CardDelivery(card.signerId, card)), recipients), Urgency.NORMAL)
        }
    }

    private fun sealDataSync(body: DataSync, recipients: List<RecipientKey>): Envelope = EnvelopeCrypto.seal(
        signer = signer,
        typ = MessageType.DATA_SYNC,
        bodyPlaintext = ProtocolCodec.encodeToCbor(body),
        recipients = recipients,
        messageId = UUID.randomUUID().toString(),
        seq = seq.incrementAndGet(),
        createdAt = now(),
    )

    /** Deliver a signed card to one own-mesh peer (keyless repair / card announce). */
    private suspend fun sendCard(recipientId: ClientId, clientId: ClientId, card: SignedBlob) {
        val peer = peersProvider().firstOrNull { it.clientId == recipientId && it.ownDevice } ?: return
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.DATA_SYNC,
            bodyPlaintext = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.CARD, card = CardDelivery(clientId, card))),
            recipients = listOf(rk(peer)),
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.NORMAL)
    }

    /** Handle an envelope received via WebSocket or FCM. Idempotent: duplicates are dropped. */
    fun handleEnvelope(envelope: Envelope) {
        if (!seen.add(envelope.messageId)) return
        val sender = peersProvider().firstOrNull { it.clientId == envelope.signerId }
        if (sender == null) {
            Log.w(TAG, "dropping envelope from unknown sender ${envelope.signerId.shortForm()}")
            return
        }
        val senderSpki = b64.decode(sender.identityPublicKeyB64)
        if (!EnvelopeCrypto.verify(envelope, senderSpki)) {
            Log.w(TAG, "signature verification failed for ${envelope.messageId}")
            activityLog.add(ActivityEvent.Kind.ERROR, "Rejected", "bad signature from ${sender.displayName}", now())
            return
        }
        val body = runCatching {
            EnvelopeCrypto.open(envelope, signer.clientId, myHpkePrivateKeyset)
        }.getOrElse {
            Log.w(TAG, "decrypt failed for ${envelope.messageId}: ${it.message}")
            return
        }
        // A profile-only (not-own) device may only send profile updates; everything else from it is dropped.
        when (envelope.typ) {
            MessageType.NOTIFICATION -> {
                if (!sender.ownDevice) return
                val notif = ProtocolCodec.decodeFromCbor<CapturedNotification>(body)
                renderer.render(notif) // text + any already-cached graphics, posted immediately
                activityLog.add(ActivityEvent.Kind.RECEIVED, notif.appLabel, "from ${sender.displayName}", now())
                // Fetch any missing private graphics in the background, then re-post (same tag/id) with
                // them attached. The notification is never blocked on asset transfer. Anything still
                // missing is remembered and repaired over an encrypted DATA_SYNC request to the sender.
                val refs = notif.privateRefs()
                val resolver = assetResolver
                if (resolver != null && refs.isNotEmpty()) {
                    scope.launch {
                        val result = resolver.ensureLocal(refs)
                        if (result.newlyAvailable) renderer.render(notif)
                        if (result.stillMissing.isNotEmpty()) {
                            result.stillMissing.forEach { pendingAssetRenders[it.assetHash] = notif }
                            sendAssetSync(
                                notif.sourceClientId,
                                AssetSync(AssetSyncKind.ASSET_MISSING, result.stillMissing.map { AssetSyncItem(it.assetHash, it.assetId) }),
                            )
                        }
                    }
                }
            }
            MessageType.DISMISSAL -> {
                if (!sender.ownDevice) return
                val event = ProtocolCodec.decodeFromCbor<DismissEvent>(body)
                renderer.clear(event.sourceClientId, event.sourceKey)
                originalCanceler?.cancel(event.sourceKey)
                activityLog.add(ActivityEvent.Kind.DISMISSED, dismissedAppName(event.sourceKey), "by ${sender.displayName}", now())
            }
            MessageType.DATA_SYNC -> {
                val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(body) }.getOrNull() ?: return
                when (sync.kind) {
                    DataSyncKind.ASSET -> {
                        if (!sender.ownDevice) return
                        val asset = sync.asset ?: return
                        val resolver = assetResolver ?: return
                        when (asset.kind) {
                            AssetSyncKind.ASSET_MISSING -> scope.launch { repairAndReply(envelope.signerId, asset.items, resolver) }
                            AssetSyncKind.ASSET_READY -> scope.launch { applyRepaired(asset.items, resolver) }
                        }
                    }
                    DataSyncKind.PROFILE -> {
                        val update = sync.profile ?: return
                        // The envelope is already signature-verified against this peer's stored identity
                        // key; require the update to be about that same peer (a peer can't rename another).
                        if (update.clientId != envelope.signerId) return
                        if (profileUpdater?.invoke(update) == true) {
                            activityLog.add(ActivityEvent.Kind.PAIRED, update.displayName, "renamed (was ${sender.displayName})", now())
                        }
                    }
                    DataSyncKind.TRUST -> {
                        if (!sender.ownDevice) return
                        val table = sync.trust ?: return
                        val result = onTrustTable?.invoke(envelope.signerId, table) ?: return
                        for ((id, prompt) in result.prompts) {
                            onTrustPrompt?.invoke(id, prompt, sender.displayName)
                            activityLog.add(ActivityEvent.Kind.PAIRED, id.shortForm(), "trust update from ${sender.displayName} ($prompt)", now())
                        }
                        // Repair any device the sender trusts but lacks a key for, that we can vouch for.
                        if (result.cardsToOffer.isNotEmpty()) {
                            scope.launch { result.cardsToOffer.forEach { sendCard(envelope.signerId, it.signerId, it) } }
                        }
                        // We just learned a keyless device — advertise it so a card holder repairs us.
                        if (result.needsBroadcast) scope.launch { broadcastTrust() }
                    }
                    DataSyncKind.CARD -> {
                        if (!sender.ownDevice) return
                        val delivery = sync.card ?: return
                        onCardDelivery?.invoke(delivery.clientId, delivery.card)
                    }
                }
            }
        }
    }

    /** Provider side: re-upload each requested asset under its existing id and reply ASSET_READY. */
    private suspend fun repairAndReply(requester: ClientId, items: List<AssetSyncItem>, resolver: AssetResolver) {
        val ready = ArrayList<PrivateAssetRef>(items.size)
        for (item in items) resolver.repair(item.assetHash, signer.clientId)?.let { ready.add(it) }
        if (ready.isEmpty()) return
        sendAssetSync(requester, AssetSync(AssetSyncKind.ASSET_READY, ready.map { AssetSyncItem(it.assetHash, it.assetId, it) }))
        activityLog.add(ActivityEvent.Kind.SENT, "Asset repair", "re-sent ${ready.size} to ${requester.shortForm()}", now())
    }

    /** Consumer side: fetch each repaired asset and re-post the notification that was waiting on it. */
    private suspend fun applyRepaired(items: List<AssetSyncItem>, resolver: AssetResolver) {
        for (item in items) {
            val ref = item.ref ?: continue
            if (resolver.ensureLocal(listOf(ref)).newlyAvailable) {
                pendingAssetRenders.remove(ref.assetHash)?.let { renderer.render(it) }
            }
        }
    }

    /** Seal an [AssetSync] to a single own-mesh peer over DATA_SYNC (FCM NORMAL priority). */
    private suspend fun sendAssetSync(recipientId: ClientId, sync: AssetSync) {
        val peer = peersProvider().firstOrNull { it.clientId == recipientId && it.ownDevice } ?: return
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.DATA_SYNC,
            bodyPlaintext = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.ASSET, asset = sync)),
            recipients = listOf(rk(peer)),
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.NORMAL)
    }

    companion object {
        private const val TAG = "MirrorEngine"
    }
}

/** Every private graphic referenced by a notification body (large icon, big picture, message avatars). */
private fun CapturedNotification.privateRefs(): List<PrivateAssetRef> =
    listOfNotNull(largeIcon, bigPicture) + messages.mapNotNull { it.avatar }
