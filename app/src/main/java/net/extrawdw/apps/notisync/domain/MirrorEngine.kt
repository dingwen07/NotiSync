package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.channel.InboundMessage
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.apps.notisync.foundation.SendPolicy
import net.extrawdw.apps.notisync.transport.ifKnown
import net.extrawdw.notisync.protocol.AssetSync
import net.extrawdw.notisync.protocol.AssetSyncItem
import net.extrawdw.notisync.protocol.AssetSyncKind
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency

/** Renders / clears mirrored notifications. Implemented by the notification layer. */
interface MirrorRenderer {
    fun render(notif: CapturedNotification)
    fun clear(sourceClientId: ClientId, sourceKey: String)
}

/** Cancels a local original notification (provider side) when a remote dismissal arrives. */
fun interface OriginalCanceler {
    fun cancel(sourceKey: String)
}

/**
 * Maps a rendered mirror back to the relay message that delivered it, so a *local* dismissal can tell
 * the broker to drop that message — otherwise the still-queued copy is redelivered later and the
 * dismissed notification reappears. Persisted by the data layer (survives a restart); a no-op when the
 * engine runs without one (tests, provider-only devices).
 */
interface MirrorAckIndex {
    /** Remember that [messageId] delivered the mirror for ([sourceClientId], [sourceKey]). */
    fun recordMirror(sourceClientId: ClientId, sourceKey: String, messageId: String)

    /** The mirror for ([sourceClientId], [sourceKey]) was dismissed — queue its message for relay-ack. */
    fun onDismissed(sourceClientId: ClientId, sourceKey: String)
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
 * The notification-mirroring application over [SecureChannel] — one feature client among future
 * several. It seals captures/dismissals to the own mesh, renders inbound `NOTIFICATION` / `DISMISSAL`
 * (verified + decrypted by the channel before it ever reaches here), and runs the private-asset
 * (`ASSET`) repair dance. It holds no crypto, no fan-out, and no trust state — the channel and the
 * trust foundation own those.
 */
class MirrorEngine(
    private val channel: SecureChannel,
    private val renderer: MirrorRenderer,
    private val activityLog: ActivityLog,
    private val activityText: ActivityText,
    private val scope: CoroutineScope,
    private val assetResolver: AssetResolver? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Resolves a package name to a user-facing app label; defaults to the package itself. */
    private val appLabelResolver: (String) -> String = { it },
    /** Resolves an authenticated sender id to a display name for activity rows; defaults to its short id. */
    private val peerNameResolver: (ClientId) -> String = { it.shortForm() },
    /** Records mirror→message mappings and queues a relay-ack on local dismissal; null disables both. */
    private val ackIndex: MirrorAckIndex? = null,
) {
    @Volatile var originalCanceler: OriginalCanceler? = null

    /** Notifications awaiting a repaired asset, keyed by the missing assetHash (bounded LRU). */
    private val pendingAssetRenders: MutableMap<String, CapturedNotification> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, CapturedNotification>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CapturedNotification>): Boolean = size > 128
        }
    )

    /** Register inbound handlers. Call during graph construction, before the channel starts delivering. */
    fun register() {
        channel.onMessage(MessageType.NOTIFICATION, ::onNotification)
        channel.onMessage(MessageType.DISMISSAL, ::onDismissal)
    }

    /** Seal [notif] to the own mesh; returns the number of peer devices it was sealed to. */
    suspend fun captureLocal(notif: CapturedNotification): Int {
        val n = channel.send(MessageType.NOTIFICATION, ProtocolCodec.encodeToCbor(notif), Recipients.OwnMesh, Urgency.HIGH)
        if (n > 0) activityLog.add(ActivityEvent.Kind.SENT, notif.appLabel, activityText.mirroredToDevices(n), now())
        return n
    }

    suspend fun dismissLocal(sourceClientId: ClientId, sourceKey: String) {
        // Drop the still-queued relay copy of the notification we're dismissing (no-op for our own
        // captures, which were never received). Done first so the ack is queued even if the DISMISSAL
        // broadcast below fails — the queue is local and drained by the relay worker.
        ackIndex?.onDismissed(sourceClientId, sourceKey)
        // No local-suppression set here: echoes from our own cancelNotification() (issued on a remote
        // dismissal) carry REASON_LISTENER_CANCEL and are filtered out by the listener's reason
        // allowlist. A permanent suppression set would wrongly block re-dismissals of reused keys.
        val event = DismissEvent(sourceClientId, sourceKey, now())
        val n = channel.send(MessageType.DISMISSAL, ProtocolCodec.encodeToCbor(event), Recipients.OwnMesh, Urgency.NORMAL)
        if (n > 0) activityLog.add(ActivityEvent.Kind.DISMISSED, dismissedAppName(sourceKey), activityText.syncedToDevices(n), now())
    }

    private fun onNotification(msg: InboundMessage) {
        if (!SendPolicy.mayAccept(msg.typ, null, msg.senderOwnDevice)) return
        val notif = ProtocolCodec.decodeFromCbor<CapturedNotification>(msg.body)
        // Remember which relay message delivered this mirror, so a later local dismissal can ack it
        // (drop the still-queued copy) instead of letting it be redelivered and reappear.
        ackIndex?.recordMirror(notif.sourceClientId, notif.sourceKey, msg.messageId)
        renderer.render(notif) // text + any already-cached graphics, posted immediately
        activityLog.add(
            ActivityEvent.Kind.RECEIVED,
            notif.appLabel,
            activityText.fromDevice(peerNameResolver(msg.senderId)),
            now(),
            deliveryMode = msg.deliveryMode.ifKnown(),
        )
        // Fetch any missing private graphics in the background, then re-post (same tag/id) with them
        // attached. The notification is never blocked on asset transfer. Anything still missing is
        // remembered and repaired over an encrypted DATA_SYNC request to the sender.
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

    private fun onDismissal(msg: InboundMessage) {
        if (!SendPolicy.mayAccept(msg.typ, null, msg.senderOwnDevice)) return
        val event = ProtocolCodec.decodeFromCbor<DismissEvent>(msg.body)
        renderer.clear(event.sourceClientId, event.sourceKey)
        originalCanceler?.cancel(event.sourceKey)
        activityLog.add(
            ActivityEvent.Kind.DISMISSED,
            dismissedAppName(event.sourceKey),
            activityText.byDevice(peerNameResolver(msg.senderId)),
            now(),
            deliveryMode = msg.deliveryMode.ifKnown(),
        )
    }

    /**
     * Private-asset repair, forwarded from the foundation's sole DATA_SYNC handler (the notification
     * app owns the `ASSET` sub-kind). Applies its own own-mesh gate, since only own devices repair.
     */
    fun onAssetSync(msg: InboundMessage, sync: DataSync) {
        if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.ASSET, msg.senderOwnDevice)) return
        val asset = sync.asset ?: return
        val resolver = assetResolver ?: return
        when (asset.kind) {
            AssetSyncKind.ASSET_MISSING -> scope.launch { repairAndReply(msg.senderId, asset.items, resolver) }
            AssetSyncKind.ASSET_READY -> scope.launch { applyRepaired(asset.items, resolver) }
        }
    }

    /** Provider side: re-upload each requested asset under its existing id and reply ASSET_READY. */
    private suspend fun repairAndReply(requester: ClientId, items: List<AssetSyncItem>, resolver: AssetResolver) {
        val ready = ArrayList<PrivateAssetRef>(items.size)
        for (item in items) resolver.repair(item.assetHash, channel.clientId)?.let { ready.add(it) }
        if (ready.isEmpty()) return
        sendAssetSync(requester, AssetSync(AssetSyncKind.ASSET_READY, ready.map { AssetSyncItem(it.assetHash, it.assetId, it) }))
        activityLog.add(
            ActivityEvent.Kind.SENT,
            activityText.assetRepairTitle(),
            activityText.assetRepairDetail(ready.size, requester.shortForm()),
            now(),
        )
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
        channel.send(
            MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.ASSET, asset = sync)),
            Recipients.Only(recipientId), Urgency.NORMAL,
        )
    }

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
}

/** Every private graphic referenced by a notification body (large icon, big picture, app icon, avatars). */
private fun CapturedNotification.privateRefs(): List<PrivateAssetRef> =
    listOfNotNull(largeIcon, bigPicture, appIcon) + messages.mapNotNull { it.avatar }
