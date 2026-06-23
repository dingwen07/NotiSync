package net.extrawdw.apps.notisync

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.AndroidOperationalSigner
import net.extrawdw.apps.notisync.crypto.EpochHpkeKeyManager
import net.extrawdw.apps.notisync.crypto.KeyFingerprint
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.crypto.KeyVaultAuthTokenStore
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.apps.notisync.data.AndroidActivityText
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.apps.notisync.assets.TicketStore
import net.extrawdw.apps.notisync.channel.DeliveryOutcome
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.MessageStore
import net.extrawdw.apps.notisync.domain.MirrorEngine
import net.extrawdw.apps.notisync.foundation.FoundationEngine
import net.extrawdw.apps.notisync.foundation.RotationManager
import net.extrawdw.apps.notisync.foundation.TrustPeerDirectory
import net.extrawdw.apps.notisync.integrity.PlayIntegrityAttestor
import net.extrawdw.apps.notisync.notification.GraphicsExtractor
import net.extrawdw.apps.notisync.notification.GraphicsPipeline
import net.extrawdw.apps.notisync.notification.MirrorChannels
import net.extrawdw.apps.notisync.notification.NotificationRuleEngine
import net.extrawdw.apps.notisync.notification.RemoteNotificationPoster
import net.extrawdw.apps.notisync.transport.BrokerClient
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.apps.notisync.transport.ifKnown
import net.extrawdw.apps.notisync.trust.TrustActionReceiver
import net.extrawdw.apps.notisync.work.EpochMaintenanceWorker
import net.extrawdw.apps.notisync.work.RelayDrainWorker
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.RouteCapabilities
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.crypto.OperationalSigner

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore("notisync")

/** Manual dependency graph. Built once in [NotiSyncApp.onCreate]; everything hangs off this. */
/**
 * Diagnostics view of the live epoch's public key fingerprints + rotation schedule (see [AppGraph.rotationKeyInfo]).
 * [pendingTargetEpoch] is non-null while a rotation is in flight; [nextEventAtMillis] is the next scheduled
 * instant to count down to — the pending activation/retirement when rotating, else when the next rotation is due.
 */
data class RotationKeyInfo(
    val epoch: Int,
    val signingKey: String,
    val encryptionKey: String,
    val pendingTargetEpoch: Int?,
    val pendingActivated: Boolean,
    val nextEventAtMillis: Long,
)

class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val activityLog = ActivityLog()
    val activityText: ActivityText = AndroidActivityText(app)

    /** Durable receive-path bookkeeping: cross-restart dedup, the pending relay-ack queue, and the
     *  mirror→message map for dismissal-ack. Constructing it is cheap (the db opens lazily, off-main). */
    val messageStore = MessageStore(app)

    lateinit var identity: AndroidIdentitySigner
        private set
    lateinit var epochHpke: EpochHpkeKeyManager
        private set

    /**
     * The CURRENT operational signer (the epoch the device signs envelopes/requests with). A volatile
     * holder so [RotationManager] can swap the active epoch at activation without rebuilding the channel or
     * transport — both read it through a `{ operational }` provider. NS2 hot-path key; the identity root
     * stays cold.
     */
    @Volatile
    lateinit var operational: OperationalSigner

    lateinit var settings: SettingsRepository
        private set
    lateinit var trust: TrustStore
        private set
    lateinit var transport: BrokerClient
        private set
    lateinit var poster: RemoteNotificationPoster
        private set

    var appSelection: AppSelectionRepository? = null
        private set
    var secureChannel: SecureChannel? = null
        private set
    var mirrorEngine: MirrorEngine? = null
        private set
    var foundationEngine: FoundationEngine? = null
        private set
    var graphicsPipeline: GraphicsPipeline? = null
        private set

    /** NS2 rotation state machine — non-null ONLY when `BuildConfig.ENABLE_ROTATION` is set (else the device
     *  stays at epoch 1 forever and never mints a second epoch). Driven by [tickRotation]. */
    var rotationManager: RotationManager? = null
        private set

    val clientId: ClientId? get() = if (::identity.isInitialized) identity.clientId else null

    fun init() {
        identity = AndroidIdentitySigner.loadOrCreate()
        val vault = KeyVault()
        val ds = app.dataStore
        settings = SettingsRepository(ds, scope)
        trust = TrustStore(ds, scope, identity)
        appSelection = AppSelectionRepository(ds, scope)
        // NS2 operational layer (always on — the ENABLE_ROTATION flag only gates *minting a second* epoch).
        // The self epoch lives in the signed TrustStore section #4 (≥1); ensure it, then materialise this
        // epoch's TEE operational signing key + HPKE keyset. Rotation (Phase 6) advances the epoch + swaps
        // [operational]; here we simply pin epoch 1 (or whatever the floor recovered to).
        val selfEpoch = trust.advanceSelfEpoch(1)
        epochHpke = EpochHpkeKeyManager(app, vault).apply { loadOrCreate(selfEpoch) }
        operational = AndroidOperationalSigner.loadOrCreate(identity.clientId, selfEpoch)
        transport = BrokerClient(
            signer = identity,
            operationalSigner = { operational },
            baseUrlProvider = { settings.brokerUrl.value },
            integrity = PlayIntegrityAttestor(app, BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER),
            clientKeyEpochProvider = ::buildClientKeyEpochBlob,
            debugKey = BuildConfig.DEBUG_KEY,
            tokenStore = KeyVaultAuthTokenStore(app, vault),
            scope = scope,
        )
        val assetsDir = java.io.File(app.filesDir, "assets")
        val assetCache = AssetCache(assetsDir)
        val assetManager = AssetManager(transport, assetCache, TicketStore(assetsDir))
        poster = RemoteNotificationPoster(app, assetCache, deviceNameOf = { id -> trust.displayName(id) })
        graphicsPipeline = GraphicsPipeline(NotificationRuleEngine(), GraphicsExtractor(app), assetManager)
        // The generic secure-messaging substrate: seal/sign/dedup/verify/open + per-MessageType routing.
        // It depends only on the read-only TrustPeerDirectory port (keys flow foundation → channel).
        val channel = SecureChannel(
            signer = identity,
            operationalSigner = { operational },
            myHpkePrivate = { epoch -> epochHpke.privateKeyset(epoch) },
            transport = transport,
            directory = TrustPeerDirectory(trust),
            log = { msg -> Log.w("SecureChannel", msg) },
            dedup = messageStore, // persisted dedup so a redelivery after restart isn't re-posted
            onBadSignature = { id, at, deliveryMode ->
                activityLog.add(
                    ActivityEvent.Kind.ERROR,
                    activityText.rejectedTitle(),
                    activityText.badSignatureFrom(trust.displayName(id) ?: id.shortForm()),
                    at,
                    deliveryMode = deliveryMode.ifKnown(),
                )
            },
            // Can't resolve a (trusted) sender's key for the epoch it signed with → fetch its key-epoch (and
            // fall back to a roster broadcast) so the gap self-heals. foundationEngine is read at call time.
            onUnresolvedSender = { id -> scope.launch { runCatching { foundationEngine?.onUnresolvedSender(id) } } },
        )
        secureChannel = channel
        // Notification-mirroring application: NOTIFICATION/DISMISSAL + private-asset repair.
        val mirror = MirrorEngine(
            channel = channel,
            renderer = poster,
            activityLog = activityLog,
            scope = scope,
            assetResolver = assetManager,
            appLabelResolver = ::appLabelFor,
            peerNameResolver = { id -> trust.displayName(id) ?: id.shortForm() },
            activityText = activityText,
            ackIndex = messageStore, // dismissing a mirror queues its relay copy for ack
        )
        mirrorEngine = mirror
        // Trust/device/profile foundation: trust-table + card + profile wire I/O, backed by TrustStore.
        val foundation = FoundationEngine(
            channel = channel,
            trust = trust,
            activityLog = activityLog,
            scope = scope,
            onTrustPrompt = ::onTrustPrompt,
            onAsset = mirror::onAssetSync, // ASSET DataSync forwarded to the notification app
            activityText = activityText,
            // Self-announce our current key-epoch in each trust broadcast (E2E convergence without polling),
            // and pull a peer's key-epoch when its advertised epoch outruns the one we hold.
            selfKeyEpoch = { runCatching { buildClientKeyEpochBlob() }.getOrNull() },
            fetchKeyEpoch = { id, epoch -> transport.fetchKeyEpoch(id, epoch) },
        )
        foundationEngine = foundation
        // Register all handlers synchronously now — BEFORE the lifecycle observer or an FCM wake can
        // reach the channel — or an early cold-start delivery to an unregistered type is dropped.
        foundation.register() // DATA_SYNC (TRUST/CARD/PROFILE; forwards ASSET)
        mirror.register()      // NOTIFICATION + DISMISSAL

        // NS2 rotation (Phase 6) — constructed ONLY behind the flag; the key generation, live-signer swap,
        // and key destruction are the Android-specific bits injected here, keeping the state machine itself
        // Keystore-free and unit-testable. With the flag OFF this is never built: epoch 1 forever.
        if (BuildConfig.ENABLE_ROTATION) {
            rotationManager = RotationManager(
                clientId = identity.clientId,
                identitySpki = identity.publicKeySpki,
                identitySign = identity::sign,
                trust = trust,
                mintOperational = { epoch -> AndroidOperationalSigner.loadOrCreate(identity.clientId, epoch) },
                mintHpke = { epoch -> epochHpke.loadOrCreate(epoch) },
                onActivate = { signer, epoch ->
                    // Swap the live signer + advance the epoch counter ONLY. RotationManager.tick() republishes
                    // the activated epoch to the broker with the correct rotation windows immediately after this
                    // returns; publishing buildClientKeyEpochBlob() here would race it and corrupt the schedule.
                    operational = signer
                    trust.advanceSelfEpoch(epoch)
                    // Restart the epoch-age clock so the NEXT scheduled rotation is measured from this activation.
                    scope.launch { runCatching { settings.setSelfEpochActivatedAt(System.currentTimeMillis()) } }
                    scope.launch { runCatching { foundationEngine?.broadcastTrust() } } // E2E-announce the new active epoch to own-mesh
                },
                onRetire = { retired, keep ->
                    AndroidOperationalSigner.destroy(retired)
                    epochHpke.prune(keep)
                },
                publish = { blob -> transport.publishKeyEpoch(blob) },
                pushE2E = { blob -> foundationEngine?.announceKeyEpoch(blob) },
            )
            // Resume any rotation that was pre-warmed before this process started (activate/retire on time).
            scope.launch { runCatching { rotationManager?.tick() } }
            // Seed the epoch-age clock for epoch 1 on first run with rotation enabled (never resets an existing
            // stamp), anchoring the first scheduled rotation one interval out rather than firing immediately.
            scope.launch { runCatching { settings.seedSelfEpochActivatedAt(System.currentTimeMillis()) } }
        }

        // Prune mirrored channels/groups for devices that are no longer trusted peers — at startup and
        // again on every trust change (a revoke drops the peer from activePeers). StateFlow re-emits its
        // current value on subscription, so this also performs the launch-time sweep.
        trust.activePeers
            .onEach { peers ->
                // Skip pruning while quarantined: activePeers is forced empty then, and we must not nuke
                // the user's mirrored channels over a freeze that may be Approved back to the same roster.
                if (!trust.quarantined.value) runCatching { MirrorChannels.gc(app, peers.map { it.clientId.value }.toSet()) }
            }
            .launchIn(scope)

        // Surface a tamper quarantine the instant it's detected — StateFlow replays its current value on
        // subscription, so this also fires at launch when load() flagged the persisted roster — and clear
        // the alert once the user resolves it (approve/clear flips quarantined back to false).
        trust.quarantined
            .onEach { tampered -> if (tampered) postTamperAlert() else cancelTamperAlert() }
            .launchIn(scope)

        // Battery-efficient transport policy:
        //  * Background delivery is via FCM only (Google's shared push connection — no app socket),
        //    so neither the provider nor the consumer holds a network connection while idle.
        //  * The live WebSocket is opened ONLY while the app UI is in the foreground (instant
        //    bidirectional updates), and closed the moment the app is backgrounded.
        publishSelf()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startLiveConnection()
            override fun onStop(owner: LifecycleOwner) = onAppBackgrounded()
        })
        observeProfileChanges()
        // Low-frequency safety net: sweep the broker relay for anything FCM deferred or whose wake
        // fetch failed. The FCM wake + foreground WS remain the primary, prompt delivery paths.
        RelayDrainWorker.schedulePeriodic(app)
        // Time-driven epoch upkeep (converge peer key-epochs; with ENABLE_ROTATION also initiate + advance
        // rotation) — the message-independent guarantee a quiet device still rotates. See the worker doc.
        EpochMaintenanceWorker.schedulePeriodic(app)
        // Trim handled-message history past its retention window on each start — bounds the dedup db on
        // long-lived processes and rarely-opened devices alike (off-main; the db opens lazily here).
        scope.launch { runCatching { messageStore.prune() } }

        Log.i(TAG, "graph ready clientId=${identity.clientId.shortForm()} backing=${identity.backing}")
    }

    /** Friendly application label for a package, falling back to the package id when not installed. */
    private fun appLabelFor(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    @Volatile
    private var liveJob: Job? = null

    /** Publish our key-epoch (the broker's canonical key store), then our FCM route (key-epoch first so the
     *  broker can resolve the route claim's signer). The profile never reaches the broker (QR + E2E only). */
    private fun publishSelf() {
        scope.launch {
            publishKeyEpochUnlessRotating()
            // Pull trusted peers' key-epochs from the broker so an upgraded/keyless device becomes sealable
            // without an E2E round-trip or a re-pair (breaks the bootstrap deadlock; see convergeKeyEpochs).
            runCatching { foundationEngine?.convergeKeyEpochs() }
            registerFcmRoute()
        }
    }

    /** Foreground only: refresh our key-epoch on the broker (self-heals stale broker state) and stream live updates. */
    private fun startLiveConnection() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            publishKeyEpochUnlessRotating()
            // Converge peer key-epochs BEFORE the live loop blocks, so a just-upgraded peer is sealable and
            // our broadcast actually reaches it (the pull is the bootstrap; the broadcast is anti-entropy).
            runCatching { foundationEngine?.convergeKeyEpochs() }
            runCatching { foundationEngine?.broadcastTrust() } // anti-entropy: re-announce our trust roster + key-epoch
            transport.runLiveDelivery { secureChannel?.deliver(it, DeliveryMode.WEBSOCKET) }
        }
        tickRotation() // advance any staged rotation across an activation/retirement boundary
    }

    /**
     * Publish our current key-epoch to the broker — but stand down while a rotation is in flight. During a
     * rotation [RotationManager] owns broker publishing (it stages N+1's future-notBefore + N's finite
     * notAfter windows); a permanent-window republish here would clobber that schedule. With no rotation
     * pending this is the normal self-heal/refresh.
     */
    private suspend fun publishKeyEpochUnlessRotating() {
        if (trust.pendingRotation() != null) return
        runCatching { transport.publishKeyEpoch(buildClientKeyEpochBlob()) }
    }

    /** Advance any in-flight epoch rotation (no-op unless `ENABLE_ROTATION`). Safe to call on any cadence. */
    fun tickRotation() {
        val rm = rotationManager ?: return
        scope.launch { runCatching { rm.tick() } }
    }

    /**
     * Diagnostics: force a single rotation right now, bypassing the 30-day schedule. Uses a zero pre-warm lead
     * so the immediately-following [RotationManager.tick] activates N+1 at once — the days-long overlap still
     * covers peers that haven't cached N+1 yet (they keep accepting N and pull N+1 lazily). Retirement of N
     * (and destruction of its private keys) still waits the full overlap + relay-TTL grace. Returns the new
     * (target) epoch, or null if rotation is disabled or one is already in flight. Off the main thread —
     * operational keygen + broker publish run on IO.
     */
    suspend fun rotateNowDiagnostic(): Int? = withContext(Dispatchers.IO) {
        val rm = rotationManager ?: return@withContext null
        val target = rm.beginRotation(leadMillisOverride = 0L) ?: return@withContext null
        rm.tick() // notBefore = now → activate immediately
        target
    }

    /** Diagnostics snapshot of the live epoch's public key material + rotation schedule — short fingerprints of
     *  the current operational (signing) key and HPKE (encryption) public keyset, plus any pending rotation and
     *  the next scheduled instant. Computed off-main (hashing + a file read + a DataStore read). Public keys
     *  only; private material never leaves the Keystore/vault. */
    suspend fun rotationKeyInfo(): RotationKeyInfo = withContext(Dispatchers.Default) {
        val epoch = trust.selfEpoch()
        val pending = trust.pendingRotation()
        val activated = pending != null && epoch >= pending.targetEpoch
        val nextAt = when {
            pending != null -> if (activated) pending.retireRetiredAt else pending.notBefore
            else -> runCatching { settings.selfEpochActivatedAt() }.getOrDefault(0L)
                .let { if (it > 0L) it + RotationManager.DEFAULT_ROTATION_INTERVAL_MS else 0L }
        }
        RotationKeyInfo(
            epoch = epoch,
            signingKey = KeyFingerprint.short(operational.operationalPublicKeySpki),
            encryptionKey = runCatching { epochHpke.publicKeyset(epoch) }.getOrNull()?.let { KeyFingerprint.short(it) } ?: "—",
            pendingTargetEpoch = pending?.targetEpoch,
            pendingActivated = activated,
            nextEventAtMillis = nextAt,
        )
    }

    /**
     * Forward-secrecy backstop GC: when no rotation is in flight, destroy every operational + HPKE key strictly
     * below the live epoch. This covers a retirement whose on-device delete was skipped (device offline at the
     * `notAfter + grace` boundary) or silently failed — so a retired private key is guaranteed gone within a
     * maintenance cycle rather than merely best-effort at retirement. No-op while a rotation is pending (the
     * retiring epoch is still needed through the overlap), and `< live` only, so a freshly pre-warmed N+1 minted
     * by a racing [RotationManager.beginRotation] is never touched. Called from [EpochMaintenanceWorker].
     */
    fun gcStaleEpochs() {
        if (rotationManager == null || trust.pendingRotation() != null) return
        val live = trust.selfEpoch()
        AndroidOperationalSigner.retainedEpochs().filter { it < live }.forEach { AndroidOperationalSigner.destroy(it) }
        epochHpke.prune(epochHpke.retainedEpochs().filter { it >= live }.toSet())
    }


    /**
     * Going to the background: drop the live socket, then do the work that matters precisely *because*
     * we're about to go dark — refresh the FCM wake route (the only delivery path while idle) and, if
     * we've been renamed, re-announce our profile so peers that were offline at rename time still
     * converge via the broker's store-and-forward relay. Both run here rather than on every foreground:
     * while foregrounded the live WebSocket already delivers, so a per-resume FCM repair is wasted work.
     */
    private fun onAppBackgrounded() {
        stopLiveConnection()
        registerFcmRoute()
        scope.launch {
            if (settings.deviceNameUpdatedAt.value > 0L) runCatching { foundationEngine?.broadcastProfile(buildProfileUpdate()) }
            runCatching { foundationEngine?.broadcastTrust() }
        }
    }

    /** Re-broadcast our trust roster now (call after a local trust change that should propagate at once). */
    fun broadcastTrust() {
        scope.launch { runCatching { foundationEngine?.broadcastTrust() } }
    }

    /** Surface a pending trust decision as a local notification: tap opens Devices; add/re-add carry Approve/Reject. */
    private fun onTrustPrompt(clientId: ClientId, prompt: TrustPrompt, byName: String) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val channelId = "notisync.trust"
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(channelId, app.getString(R.string.trust_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val subject = trust.displayName(clientId) ?: clientId.shortForm()
            val (title, text) = when (prompt) {
                TrustPrompt.NEW_TRUST -> app.getString(R.string.trust_new_title) to app.getString(R.string.trust_new_text, byName, subject)
                TrustPrompt.RE_TRUST -> app.getString(R.string.trust_retrust_title) to app.getString(R.string.trust_retrust_text, byName, subject)
                TrustPrompt.NEW_REVOKE -> app.getString(R.string.trust_revoke_title) to app.getString(R.string.trust_revoke_text, byName, subject)
                TrustPrompt.CONFLICT -> app.getString(R.string.trust_conflict_title) to app.getString(R.string.trust_conflict_text, subject)
                // "Other" devices are already applied — these prompts just keep the user informed.
                TrustPrompt.OTHER_ADDED -> app.getString(R.string.trust_other_added_title) to app.getString(R.string.trust_other_added_text, byName, subject)
                TrustPrompt.OTHER_REMOVED -> app.getString(R.string.trust_other_removed_title) to app.getString(R.string.trust_other_removed_text, byName, subject)
            }
            val showFingerprint = prompt == TrustPrompt.NEW_TRUST || prompt == TrustPrompt.RE_TRUST
            // Expanded text carries the safety number so an inline Approve is still a real check, not a blind tap.
            val bigText = if (showFingerprint) {
                app.getString(R.string.trust_expanded_verification, text, clientId.value)
            } else {
                text
            }
            val notifId = "trust-${clientId.value}".hashCode()
            val open = PendingIntent.getActivity(
                app, notifId,
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_DEVICES, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(open)
                .setAutoCancel(true)
            // Add/re-add carry Approve/Reject; a removal carries Remove/Keep; a conflict or an already-
            // applied "other" change just opens the app (no decision to make).
            when (prompt) {
                TrustPrompt.NEW_TRUST, TrustPrompt.RE_TRUST -> {
                    builder.addAction(0, app.getString(R.string.action_approve), trustActionPi(TrustActionReceiver.ACTION_APPROVE, clientId, notifId))
                    builder.addAction(0, app.getString(R.string.action_reject), trustActionPi(TrustActionReceiver.ACTION_REJECT, clientId, notifId))
                }
                TrustPrompt.NEW_REVOKE -> {
                    builder.addAction(0, app.getString(R.string.action_remove), trustActionPi(TrustActionReceiver.ACTION_CONFIRM_REVOKE, clientId, notifId))
                    builder.addAction(0, app.getString(R.string.action_keep), trustActionPi(TrustActionReceiver.ACTION_KEEP, clientId, notifId))
                }
                TrustPrompt.CONFLICT, TrustPrompt.OTHER_ADDED, TrustPrompt.OTHER_REMOVED -> Unit
            }
            NotificationManagerCompat.from(app).notify(notifId, builder.build())
        }
    }

    private fun trustActionPi(action: String, clientId: ClientId, notifId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            app, (clientId.value + action).hashCode(),
            Intent(app, TrustActionReceiver::class.java).apply {
                this.action = action
                putExtra(TrustActionReceiver.EXTRA_CLIENT_ID, clientId.value)
                putExtra(TrustActionReceiver.EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Tamper-quarantine alert: the persisted trust roster failed its identity signature, so sealing and
     * accepting are frozen until the user reviews it. One high-importance notification whose only action
     * opens the Devices banner — Approve (re-sign) and Clear (wipe) live there by design, not as
     * notification actions, since a stray tap must not bless or erase trust.
     */
    private fun postTamperAlert() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val channelId = "notisync.security"
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(channelId, app.getString(R.string.security_channel_name), NotificationManager.IMPORTANCE_HIGH),
            )
            val open = PendingIntent.getActivity(
                app, TAMPER_NOTIF_ID,
                Intent(app, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_OPEN_DEVICES, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(app.getString(R.string.tamper_alert_title))
                .setContentText(app.getString(R.string.tamper_alert_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.tamper_alert_text)))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(open)
                .setAutoCancel(false)
                .addAction(0, app.getString(R.string.tamper_alert_review), open)
            NotificationManagerCompat.from(app).notify(TAMPER_NOTIF_ID, builder.build())
        }
    }

    private fun cancelTamperAlert() {
        NotificationManagerCompat.from(app).cancel(TAMPER_NOTIF_ID)
    }

    /** Background: drop the socket; FCM carries everything from here. */
    private fun stopLiveConnection() {
        liveJob?.cancel()
        liveJob = null
    }

    /** This device's advertised capabilities — shared by the published card and profile updates. */
    private fun selfCapabilities(): List<Capability> = listOf(
        Capability.CAPTURE, Capability.DISPLAY, Capability.DISMISS_SYNC,
        Capability.BACKGROUND_WAKE, Capability.FOREGROUND_CONNECTION,
    )

    /**
     * Build this device's self-signed client card — the QR/E2E-only pairing bundle (identity anchor +
     * current HPKE keyset + human profile). NEVER uploaded to the broker in NS2; it travels in the pairing
     * [net.extrawdw.notisync.protocol.CardDelivery] alongside the key-epoch.
     */
    fun buildClientCardBlob(): SignedBlob {
        val card = ClientCard(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            displayName = settings.deviceName.value,
            platform = "android",
            capabilities = selfCapabilities(),
            createdAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    /**
     * Build this device's self-contained, identity-signed [ClientKeyEpoch] for the current epoch — the
     * broker's canonical key record and the peer-pull/E2E-push unit. Carries the identity anchor (so it
     * self-verifies), the current epoch's operational signing key + HPKE keyset, and the validity window.
     * With rotation OFF the window is open-ended ([notBefore]=0, [notAfter]=MAX); [minEpoch] asserts the
     * floor at this epoch. Profile fields are deliberately absent — they never reach the broker (§3.5).
     */
    fun buildClientKeyEpochBlob(stripIdentity: Boolean = false): SignedBlob {
        val epoch = trust.selfEpoch()
        // The floor we assert is the RETIRED epoch while a rotation is in flight (the old epoch is still
        // valid through the overlap, §7) — NOT the live epoch — so a self-publish or E2E announce mid-overlap
        // can never raise the floor early and strand in-flight envelopes still sealed to the old epoch. With
        // no rotation pending, the floor is simply the current epoch.
        val minEpoch = trust.pendingRotation()?.retiredEpoch ?: epoch
        val keyEpoch = ClientKeyEpoch(
            clientId = identity.clientId,
            // [stripIdentity] omits the (~91-byte) identity anchor — ONLY for the pairing QR, where the
            // accompanying ClientCard supplies it, shrinking the code. Every other copy (broker publish, E2E
            // announce/repair) stays self-contained: the broker + a card-less peer have no other identity source.
            identityPublicKey = if (stripIdentity) ByteArray(0) else identity.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = operational.operationalPublicKeySpki,
            hpkePublicKeyset = epochHpke.loadOrCreate(epoch),
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = Long.MAX_VALUE,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(keyEpoch)
        return SignedBlob(SignedType.KEY_EPOCH, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    /** This device's current mutable profile, stamped with when the name last changed (no key material). */
    fun buildProfileUpdate(): ProfileUpdate = ProfileUpdate(
        clientId = identity.clientId,
        displayName = settings.deviceName.value,
        platform = "android",
        capabilities = selfCapabilities(),
        updatedAt = settings.deviceNameUpdatedAt.value,
    )

    /**
     * Propagate device-profile changes (today: a rename). The Settings field commits only when editing
     * is done (focus loss / IME action), so this normally fires once per edit; the debounce just
     * coalesces any rapid back-to-back commits. Each change does both halves of convergence: refresh
     * the broker's card cache (so a *future* pairing resolves the new name) and push a DATA_SYNC
     * profile update to *existing* peers (the broker never sees the profile in NS2 — it is QR + E2E only).
     */
    @OptIn(FlowPreview::class) // debounce() is a preview API; used only to coalesce rapid renames
    private fun observeProfileChanges() {
        settings.deviceName
            .drop(1) // skip the eager StateFlow seed; only react to real edits
            .debounce(PROFILE_BROADCAST_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach {
                // The profile is not key material and never goes to the broker; converge peers over E2E only.
                runCatching { foundationEngine?.broadcastProfile(buildProfileUpdate()) }
            }
            .launchIn(scope)
    }

    private fun signedRouteClaim(routeRef: String, epoch: Int): SignedBlob {
        val claim = RouteClaim(
            clientId = identity.clientId,
            transport = TransportType.FCM,
            environment = RouteEnvironment.PRODUCTION,
            routeRef = routeRef,
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3072),
            epoch = epoch,
            issuedAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        return SignedBlob(SignedType.ROUTE_CLAIM, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    /**
     * Register this app instance with FCM. The result is delivered through
     * [NotiSyncMessagingService.onRegistered], which provides the current direct-send target.
     */
    fun registerFcmRoute() {
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener { e ->
                activityLog.add(
                    ActivityEvent.Kind.ERROR,
                    activityText.fcmRouteTitle(),
                    activityText.fcmRouteRegistrationFailed(e.message ?: e.javaClass.simpleName),
                    System.currentTimeMillis(),
                )
            }
    }

    fun onFcmRegistered(routeRef: String) {
        scope.launch {
            val epoch = settings.epochForFcmRoute(routeRef)
            runCatching {
                transport.publishRoutes(listOf(signedRouteClaim(routeRef, epoch)))
                activityLog.add(
                    ActivityEvent.Kind.ROUTE_REPAIR,
                    activityText.fcmRouteTitle(),
                    activityText.fcmRouteRegistered(),
                    System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * Handle an FCM wake pointer: the broker had a notification too large to inline, so it pushed only
     * the message id. Pull exactly that envelope from the relay and deliver it now — otherwise it would
     * wait, undelivered, until the next foreground WebSocket flush (minutes/hours while backgrounded).
     * Returns true iff the envelope was fetched and delivered; the FCM service enqueues a retry worker
     * on false (e.g. no network in this wake window).
     *
     * Called from [net.extrawdw.apps.notisync.fcm.NotiSyncMessagingService.onMessageReceived] on FCM's
     * background thread, so we block (within a timeout) to keep the process alive until delivery
     * completes. Delivery is idempotent — [SecureChannel] dedups by message id — so a later foreground
     * flush or drain re-delivering the same id is harmless, and the broker acks/drops the item on fetch.
     */
    fun fetchWakeMessage(messageId: String): Boolean {
        val channel = secureChannel ?: return false
        return runBlocking {
            withTimeoutOrNull(WAKE_FETCH_TIMEOUT_MS) {
                val envelope = runCatching { transport.fetchRelayMessage(messageId) }.getOrNull()
                    ?: return@withTimeoutOrNull false
                channel.deliver(envelope, DeliveryMode.FCM_RELAY_FETCH)
                true
            } ?: false
        }
        // No relay-ack queued here: the GET /v2/relay/{id} fetch already dropped the item server-side.
    }

    /**
     * After an FCM-inline delivery, queue the message for batch relay-ack — UNLESS it was dropped
     * unhandled (unknown sender / bad signature / decrypt fail), which must stay queued so it can still
     * deliver once trust/keys converge. The inline path is the one delivery the broker can't observe
     * being consumed (the envelope rode in the push, so it's never fetched), so without this the item
     * lingers in the relay until TTL and is the backlog that re-posts after a restart. Local write only
     * (no network) — the actual ack is one batched request from the relay worker.
     *
     * Ack only what is durably handled: HANDLED and DUPLICATE both are (the channel records before
     * returning either). IN_FLIGHT (a racing thread is still handling this id and hasn't committed) and
     * DROPPED are not — acking IN_FLIGHT could drop the item before that thread commits. The channel's
     * outcome already encodes this, so no second dedup read is needed here. Erring toward "don't ack"
     * only costs a later, deduped redelivery — never a lost notification.
     */
    fun onInlineDelivered(messageId: String, outcome: DeliveryOutcome) {
        val ackable = when (outcome) {
            DeliveryOutcome.HANDLED, DeliveryOutcome.DUPLICATE -> true
            DeliveryOutcome.IN_FLIGHT, DeliveryOutcome.DROPPED -> false
        }
        if (ackable) runCatching { messageStore.enqueueAck(messageId) }
    }

    /**
     * Diagnostics: mirror a deliberately oversized notification — simulating a capture from an app
     * "net.extrawdw.notifly" — to this account's other devices. The sealed envelope is far larger than
     * the FCM inline budget, so a receiving device cannot get it inline and must pull it via the
     * wake → relay-fetch path: the exact flow the large-notification fix added. Returns the number of
     * peer devices it was sealed to (0 if none are paired, in which case nothing is sent).
     */
    suspend fun sendOversizedDiagnostic(): Int {
        val mirror = mirrorEngine ?: return 0
        // ~8 KB of body text: comfortably past the 3 KB base64 inline budget once sealed + encoded.
        val filler = "NotiSync oversized diagnostic — this payload is deliberately too large to inline in " +
            "an FCM data message, so the receiver must pull it from the broker relay over the wake path. "
        val bigText = buildString { while (length < 8_000) append(filler) }
        val notif = CapturedNotification(
            sourceClientId = identity.clientId,
            sourceKey = "diag|net.extrawdw.notifly|${System.currentTimeMillis()}",
            packageName = "net.extrawdw.notifly",
            appLabel = "Notifly",
            title = "Oversized test notification",
            text = "Oversized NotiSync diagnostic (delivered via wake + relay fetch).",
            bigText = bigText,
            style = NotifStyle.BIG_TEXT,
            importance = MirrorImportance.HIGH,
            postTime = System.currentTimeMillis(),
            channelId = "notisync_test",
            channelName = "NotiSync Test"
        )
        return mirror.captureLocal(notif)
    }

    companion object {
        private const val TAG = "AppGraph"

        /** Stable id for the singleton tamper-quarantine notification (re-post replaces it, resolve cancels it). */
        private val TAMPER_NOTIF_ID = "notisync.tamper-alert".hashCode()

        /** Collapse per-keystroke renames in the Settings field into a single broadcast. */
        private const val PROFILE_BROADCAST_DEBOUNCE_MS = 800L

        /** Upper bound on a background FCM-wake fetch — within FCM's high-priority wakelock window. */
        private const val WAKE_FETCH_TIMEOUT_MS = 15_000L
    }
}

/** Application entry point: builds the dependency graph. */
class NotiSyncApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.init() }
    }
}
