package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.analytics.PerfSpan
import net.extrawdw.apps.notisync.channel.InboundMessage
import net.extrawdw.apps.notisync.channel.Recipients
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.apps.notisync.data.NotificationFilterStore
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
/** Which render in a mirror's lifecycle this is — the segmentation axis for `mirror_render` latency: an
 *  ENRICH re-render (after an asset arrives) or ICON_UPGRADE has a very different `latency_ms` than the
 *  INITIAL post, so averaging them together would be meaningless. */
enum class RenderPhase { INITIAL, ENRICH, REPLAY, ICON_UPGRADE }

interface MirrorRenderer {
    /**
     * Post (or update) the mirror for [notif]. [silent] suppresses this one post's sound/heads-up without
     * touching the channel — set for a re-render that only refreshes graphics on an already-posted
     * notification (an asset finished downloading), which must never re-alert. The first render of a post
     * leaves it false and lets the source's own [CapturedNotification.onlyAlertOnce] decide. [phase] is
     * instrumentation only — it tags the `mirror_render` trace and does not affect behaviour.
     */
    fun render(
        notif: CapturedNotification,
        silent: Boolean = false,
        phase: RenderPhase = RenderPhase.INITIAL,
    )
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

/** Why an [AssetResolver.ensureLocal] ran — separates the initial delivery fetch from a post-repair fetch
 *  (which already paid an ASSET_MISSING mesh round-trip). Instrumentation only; does not affect behaviour. */
enum class AssetTrigger { INITIAL, REPAIR }

/** Fetches/repairs private graphics for the mirror engine (consumer + provider sides). */
interface AssetResolver {
    /** Fetch + decrypt + verify any [refs] not already cached (consumer side). */
    suspend fun ensureLocal(
        refs: List<PrivateAssetRef>,
        trigger: AssetTrigger = AssetTrigger.INITIAL,
    ): ResolveResult

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
    /** Peer notification-suppression filters: consulted when forwarding a capture (drop a peer that asked not
     *  to receive it) and updated by an inbound `FILTER` ([onFilterSync]). Null disables filtering entirely. */
    private val notificationFilters: NotificationFilterStore? = null,
) {
    @Volatile
    var originalCanceler: OriginalCanceler? = null

    /**
     * Clears the *origin* notification on a bridged iOS device when its mirror is dismissed — the iPhone-side
     * analogue of [originalCanceler] (which cancels a local Android original). Unlike the Android case, a *local*
     * swipe of an iOS mirror does NOT remove the notification from the iPhone, so this fires from BOTH
     * [dismissLocal] (local swipe) and [onDismissal] (a peer's dismissal relayed over the mesh). Its
     * implementation ignores non-ANCS keys; null on devices that run no iOS bridge.
     */
    @Volatile
    var iosOriginCanceler: OriginalCanceler? = null

    /** Notifications awaiting a repaired asset, keyed by the missing assetHash (bounded LRU). */
    private val pendingAssetRenders: MutableMap<String, CapturedNotification> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, CapturedNotification>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, CapturedNotification>): Boolean =
                    size > 128
            }
        )

    /**
     * Activity titles of notifications we've shown, keyed by ([ClientId], sourceKey), so a later dismissal row
     * reads the same as its posted row — e.g. "WhatsApp (Dingwen's iPhone)". A [DismissEvent] carries only the
     * opaque source key, which for a bridged iPhone notification names the iPhone id (not the app), so resolving
     * the title from the key alone would surface that raw id. Bounded LRU; lost on a restart, after which a
     * dismissal row falls back to [dismissedAppName].
     */
    private val originTitles: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 256
            }
        )

    /** Register inbound handlers. Call during graph construction, before the channel starts delivering. */
    fun register() {
        channel.onMessage(MessageType.NOTIFICATION, ::onNotification)
        channel.onMessage(MessageType.DISMISSAL, ::onDismissal)
    }

    /** Seal [notif] to the own mesh; returns the number of peer devices it was sealed to. A peer that asked
     *  (over a DATA_SYNC FILTER) not to receive a matching notification is dropped from the recipient set. */
    suspend fun captureLocal(notif: CapturedNotification, span: PerfSpan? = null): Int {
        val excluded = notificationFilters?.recipientsToExclude(notif).orEmpty()
        val scope = if (excluded.isEmpty()) Recipients.OwnMesh else Recipients.OwnMeshExcluding(excluded)
        val payload = ProtocolCodec.encodeToCbor(notif)
        span?.metric("payload_bytes", payload.size.toLong())
        span?.metric("asset_count", notif.privateRefs().size.toLong())
        val sendStartNanos = System.nanoTime()
        val n = channel.send(MessageType.NOTIFICATION, payload, scope, Urgency.HIGH)
        span?.metric("send_ms", (System.nanoTime() - sendStartNanos) / 1_000_000)
        span?.metric("recipient_count", n.toLong())
        rememberTitle(notif)
        if (n > 0) activityLog.add(
            ActivityEvent.Kind.SENT,
            originTitle(notif),
            activityText.mirroredToDevices(n),
            now()
        )
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
        // Best-effort broadcast: on a broker failure (socket timeout, attestation cooldown) peers simply
        // keep their mirror — a swipe must never throw. The local-first work around the send still runs
        // either way: the relay ack above is already queued, and the ANCS origin-clear below is local BLE,
        // independent of broker reachability.
        val n = runCatching {
            channel.send(
                MessageType.DISMISSAL,
                ProtocolCodec.encodeToCbor(event),
                Recipients.OwnMesh,
                Urgency.NORMAL
            )
        }.getOrDefault(0)
        if (n > 0) activityLog.add(
            ActivityEvent.Kind.DISMISSED,
            dismissedTitle(sourceClientId, sourceKey),
            activityText.syncedToDevices(n),
            now()
        )
        // A bridged iOS notification isn't removed from the iPhone when its mirror is swiped here — propagate
        // the dismissal back over ANCS so it clears on the source device too (no-op for non-ANCS keys).
        iosOriginCanceler?.cancel(sourceKey)
    }

    private fun onNotification(msg: InboundMessage) {
        if (!SendPolicy.mayAccept(msg.typ, null, msg.senderOwnDevice)) return
        val notif = ProtocolCodec.decodeFromCbor<CapturedNotification>(msg.body)
        // Remember which relay message delivered this mirror, so a later local dismissal can ack it
        // (drop the still-queued copy) instead of letting it be redelivered and reappear.
        ackIndex?.recordMirror(notif.sourceClientId, notif.sourceKey, msg.messageId)
        renderer.render(notif) // text + any already-cached graphics, posted immediately
        rememberTitle(notif)
        activityLog.add(
            ActivityEvent.Kind.RECEIVED,
            originTitle(notif),
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
                // Guarded: this whole tail is best-effort broker I/O (asset fetch + ASSET_MISSING send) and
                // the notification was already posted above — a failure only costs the enrichment/repair
                // round, and must not escape the launch (the graphics stay repairable via a later delivery).
                runCatching {
                    // The asset fetch + enriched re-render is timed by the `asset_resolve` trace inside ensureLocal
                    // (split cached vs fetched metrics there); it runs OFF the inline deliver path, so it is not
                    // part of `envelope_delivery` (which ends at the immediate cached-content render). The ENRICH
                    // re-render's own latency is captured by `mirror_render{phase=enrich}`.
                    val result = resolver.ensureLocal(refs)
                    // Silent: the notification was already posted above; this only attaches the now-downloaded
                    // graphics, so it must refresh in place without a second alert.
                    if (result.newlyAvailable) renderer.render(notif, silent = true, phase = RenderPhase.ENRICH)
                    if (result.stillMissing.isNotEmpty()) {
                        result.stillMissing.forEach { pendingAssetRenders[it.assetHash] = notif }
                        sendAssetSync(
                            notif.sourceClientId,
                            AssetSync(
                                AssetSyncKind.ASSET_MISSING,
                                result.stillMissing.map { AssetSyncItem(it.assetHash, it.assetId) }),
                        )
                    }
                }
            }
        }
    }

    private fun onDismissal(msg: InboundMessage) {
        if (!SendPolicy.mayAccept(msg.typ, null, msg.senderOwnDevice)) return
        val event = ProtocolCodec.decodeFromCbor<DismissEvent>(msg.body)
        renderer.clear(event.sourceClientId, event.sourceKey)
        originalCanceler?.cancel(event.sourceKey)
        // A peer dismissed this notification: cancel the local Android original (above) and, if it was bridged
        // from an iPhone, clear it on that iPhone too — mirroring how an Android original is cancelled on a
        // remote dismissal (no-op for non-ANCS keys).
        iosOriginCanceler?.cancel(event.sourceKey)
        activityLog.add(
            ActivityEvent.Kind.DISMISSED,
            dismissedTitle(event.sourceClientId, event.sourceKey),
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
        // Both branches are best-effort broker I/O (re-upload + ASSET_READY reply / fetch + re-render):
        // guarded so a network failure drops just this repair round instead of escaping the launch — the
        // requester's pending render survives, and a later exchange retries.
        when (asset.kind) {
            AssetSyncKind.ASSET_MISSING -> scope.launch {
                runCatching {
                    repairAndReply(
                        msg.senderId,
                        asset.items,
                        resolver
                    )
                }
            }

            AssetSyncKind.ASSET_READY -> scope.launch { runCatching { applyRepaired(asset.items, resolver) } }
        }
    }

    /**
     * A peer's notification-suppression request, forwarded from the foundation's sole DATA_SYNC handler. The
     * peer (chiefly the iOS client) asks this device — a notification source — to stop delivering matching
     * captures to it; we save the snapshot keyed by the requester and honor it on the next [captureLocal].
     * Own-mesh only (the same gate as ASSET, since notifications only flow within the own mesh), and a no-op
     * without a filter store.
     */
    fun onFilterSync(msg: InboundMessage, sync: DataSync) {
        if (!SendPolicy.mayAccept(msg.typ, DataSyncKind.FILTER, msg.senderOwnDevice)) return
        val filter = sync.filter ?: return
        val store = notificationFilters ?: return
        // Log a RECEIVED row (with how it was delivered) only on a real change — the source re-announces the
        // same filter periodically, and those idempotent repeats must not spam the activity feed.
        if (!store.apply(msg.senderId, filter)) return
        activityLog.add(
            ActivityEvent.Kind.RECEIVED,
            peerNameResolver(msg.senderId),
            if (filter.rules.isEmpty()) activityText.filtersCleared()
            else activityText.filtersUpdated(filter.rules.size),
            now(),
            deliveryMode = msg.deliveryMode.ifKnown(),
        )
    }

    /** Provider side: re-upload each requested asset under its existing id and reply ASSET_READY. */
    private suspend fun repairAndReply(
        requester: ClientId,
        items: List<AssetSyncItem>,
        resolver: AssetResolver
    ) {
        val ready = ArrayList<PrivateAssetRef>(items.size)
        for (item in items) resolver.repair(item.assetHash, channel.clientId)?.let { ready.add(it) }
        if (ready.isEmpty()) return
        sendAssetSync(
            requester,
            AssetSync(
                AssetSyncKind.ASSET_READY,
                ready.map { AssetSyncItem(it.assetHash, it.assetId, it) })
        )
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
            if (resolver.ensureLocal(listOf(ref), AssetTrigger.REPAIR).newlyAvailable) {
                // Silent: a repaired asset arriving for a notification that was already posted (render now,
                // re-render when the graphic lands) — refresh it in place, don't re-alert.
                pendingAssetRenders.remove(ref.assetHash)?.let {
                    renderer.render(it, silent = true, phase = RenderPhase.ENRICH)
                }
            }
        }
    }

    /** Seal an [AssetSync] to a single own-mesh peer over DATA_SYNC (FCM NORMAL priority). */
    private suspend fun sendAssetSync(recipientId: ClientId, sync: AssetSync) {
        channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.ASSET, asset = sync)),
            Recipients.Only(recipientId),
            Urgency.NORMAL,
        )
    }

    /**
     * Best-effort app name for a dismissal Activity row when no posted title was remembered (cold cache).
     * A [DismissEvent] carries only the opaque source key. An Android key is `userId|package|id|tag|uid`,
     * so the app is the package (index 1). A bridged iPhone key is `ancs|iphoneId|bundleId|uid`, so the app
     * is the bundle id (index 2) — index 1 there is the *iPhone id*, which must never surface as the "app".
     * We resolve a friendly label when that app is installed here, else fall back to the field, then the key.
     */
    private fun dismissedAppName(sourceKey: String): String {
        val parts = sourceKey.split('|')
        val appField = if (parts.firstOrNull() == "ancs") parts.getOrNull(2) else parts.getOrNull(1)
        val pkg = appField?.takeIf { it.isNotBlank() } ?: return sourceKey
        return appLabelResolver(pkg)
    }

    /**
     * An Activity row's app title: the app label, suffixed with the originating device's name for a bridged
     * capture (an iPhone notification relayed over ANCS) — "WhatsApp (Dingwen's iPhone)" — to match the
     * mirror's notification group label. Just the app label for a local Android capture ([originDeviceName] null).
     */
    private fun originTitle(notif: CapturedNotification): String =
        notif.originDeviceName?.takeIf { it.isNotBlank() }
            ?.let { "${notif.appLabel} ($it)" } ?: notif.appLabel

    /** Remember [notif]'s Activity title so its eventual dismissal row matches its posted row. */
    private fun rememberTitle(notif: CapturedNotification) {
        originTitles[titleKey(notif.sourceClientId, notif.sourceKey)] = originTitle(notif)
    }

    /** The dismissal row's title: the remembered posted title, else a best-effort app name from the key. */
    private fun dismissedTitle(sourceClientId: ClientId, sourceKey: String): String =
        originTitles[titleKey(sourceClientId, sourceKey)] ?: dismissedAppName(sourceKey)

    private fun titleKey(sourceClientId: ClientId, sourceKey: String): String =
        "${sourceClientId.value}|$sourceKey"
}

/** Every private asset referenced by a notification body (icons, pictures, avatars, inline media). */
private fun CapturedNotification.privateRefs(): List<PrivateAssetRef> =
    listOfNotNull(largeIcon, bigPicture, appIcon) +
        messages.flatMap { listOfNotNull(it.avatar, it.data) }
