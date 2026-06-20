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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.HpkeKeyManager
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.apps.notisync.assets.TicketStore
import net.extrawdw.apps.notisync.domain.MirrorEngine
import net.extrawdw.apps.notisync.notif.GraphicsExtractor
import net.extrawdw.apps.notisync.notif.GraphicsPipeline
import net.extrawdw.apps.notisync.notif.MirrorChannels
import net.extrawdw.apps.notisync.notif.NotificationRuleEngine
import net.extrawdw.apps.notisync.notif.RemoteNotificationPoster
import net.extrawdw.apps.notisync.transport.BrokerClient
import net.extrawdw.apps.notisync.trust.TrustActionReceiver
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RouteCapabilities
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TransportType

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore("notisync")

/** Manual dependency graph. Built once in [NotiSyncApp.onCreate]; everything hangs off this. */
class AppGraph(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val activityLog = ActivityLog()

    lateinit var identity: AndroidIdentitySigner
        private set
    lateinit var hpke: HpkeKeyManager
        private set
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
    var mirrorEngine: MirrorEngine? = null
        private set
    var graphicsPipeline: GraphicsPipeline? = null
        private set

    val clientId: ClientId? get() = if (::identity.isInitialized) identity.clientId else null

    fun init() {
        identity = AndroidIdentitySigner.loadOrCreate()
        hpke = HpkeKeyManager(app, KeyVault()).apply { loadOrCreate() }
        val ds = app.dataStore
        settings = SettingsRepository(ds, scope)
        trust = TrustStore(ds, scope, identity.clientId)
        appSelection = AppSelectionRepository(ds, scope)
        transport = BrokerClient(identity) { settings.brokerUrl.value }
        val assetsDir = java.io.File(app.filesDir, "assets")
        val assetCache = AssetCache(assetsDir)
        val assetManager = AssetManager(transport, assetCache, TicketStore(assetsDir))
        poster = RemoteNotificationPoster(app, assetCache)
        graphicsPipeline = GraphicsPipeline(NotificationRuleEngine(), GraphicsExtractor(app), assetManager)
        mirrorEngine = MirrorEngine(
            signer = identity,
            myHpkePrivateKeyset = hpke.privateKeyset,
            transport = transport,
            peersProvider = { trust.activePeers.value },
            renderer = poster,
            activityLog = activityLog,
            scope = scope,
            assetResolver = assetManager,
            profileUpdater = { trust.applyProfile(it) },
            trustTableProvider = { trust.buildTrustTable() },
            trustedCardsProvider = { trust.trustedCards() },
            onTrustTable = { sender, table -> trust.applyIncomingTable(sender, table) },
            onCardDelivery = { id, card -> trust.applyCard(id, card) },
            onTrustPrompt = ::onTrustPrompt,
            appLabelResolver = ::appLabelFor,
        )

        // Prune mirrored channels/groups for devices that are no longer trusted peers.
        runCatching { MirrorChannels.gc(app, trust.activePeers.value.map { it.clientId.value }.toSet()) }

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

        Log.i(TAG, "graph ready clientId=${identity.clientId.shortForm()} backing=${identity.backing}")
    }

    /** Friendly application label for a package, falling back to the package id when not installed. */
    private fun appLabelFor(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    @Volatile
    private var liveJob: Job? = null

    /** Publish our signed card, then our FCM route (card first so the broker can verify the route). */
    private fun publishSelf() {
        scope.launch {
            runCatching { transport.publishCard(buildClientCardBlob()) }
            registerFcmRoute()
        }
    }

    /** Foreground only: refresh our card on the broker (self-heals stale broker state) and stream live updates. */
    private fun startLiveConnection() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            runCatching { transport.publishCard(buildClientCardBlob()) }
            runCatching { mirrorEngine?.broadcastTrust() } // anti-entropy: re-announce our trust roster
            transport.incoming().collect { mirrorEngine?.handleEnvelope(it) }
        }
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
            if (settings.deviceNameUpdatedAt.value > 0L) runCatching { mirrorEngine?.broadcastProfile(buildProfileUpdate()) }
            runCatching { mirrorEngine?.broadcastTrust() }
        }
    }

    /** Re-broadcast our trust roster now (call after a local trust change that should propagate at once). */
    fun broadcastTrust() {
        scope.launch { runCatching { mirrorEngine?.broadcastTrust() } }
    }

    /** Surface a pending trust decision as a local notification: tap opens Devices; add/re-add carry Approve/Reject. */
    private fun onTrustPrompt(clientId: ClientId, prompt: TrustPrompt, byName: String) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val channelId = "notisync.trust"
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(channelId, "Device trust", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val subject = trust.displayName(clientId) ?: clientId.shortForm()
            val (title, text) = when (prompt) {
                TrustPrompt.NEW_TRUST -> "Review a new device" to "$byName wants to add $subject."
                TrustPrompt.RE_TRUST -> "A device wants to rejoin" to "$byName wants to re-add $subject, which was previously removed."
                TrustPrompt.NEW_REVOKE -> "A device was removed" to "$byName removed $subject. Confirm in the app."
                TrustPrompt.CONFLICT -> "Trust conflict" to "$subject was re-added while being removed. Resolve in the app."
                // "Other" devices are already applied — these prompts just keep the user informed.
                TrustPrompt.OTHER_ADDED -> "Device added" to "$byName added $subject to your other devices."
                TrustPrompt.OTHER_REMOVED -> "Device removed" to "$byName removed $subject from your other devices."
            }
            val showFingerprint = prompt == TrustPrompt.NEW_TRUST || prompt == TrustPrompt.RE_TRUST
            // Expanded text carries the safety number so an inline Approve is still a real check, not a blind tap.
            val bigText = if (showFingerprint) {
                "$text\n\nSafety number\n${clientId.value}\n\nApprove only if it matches the device's own safety number."
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
                    builder.addAction(0, "Approve", trustActionPi(TrustActionReceiver.ACTION_APPROVE, clientId, notifId))
                    builder.addAction(0, "Reject", trustActionPi(TrustActionReceiver.ACTION_REJECT, clientId, notifId))
                }
                TrustPrompt.NEW_REVOKE -> {
                    builder.addAction(0, "Remove", trustActionPi(TrustActionReceiver.ACTION_CONFIRM_REVOKE, clientId, notifId))
                    builder.addAction(0, "Keep", trustActionPi(TrustActionReceiver.ACTION_KEEP, clientId, notifId))
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

    /** Build this device's self-signed client card. */
    fun buildClientCardBlob(): SignedBlob {
        val card = ClientCard(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            hpkePublicKeyset = hpke.publicKeyset,
            displayName = settings.deviceName.value,
            platform = "android",
            capabilities = selfCapabilities(),
            createdAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
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
     * profile update to *existing* peers (the broker never pushes card changes on its own).
     */
    private fun observeProfileChanges() {
        settings.deviceName
            .drop(1) // skip the eager StateFlow seed; only react to real edits
            .debounce(PROFILE_BROADCAST_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach {
                runCatching { transport.publishCard(buildClientCardBlob()) }
                runCatching { mirrorEngine?.broadcastProfile(buildProfileUpdate()) }
            }
            .launchIn(scope)
    }

    private fun signedRouteClaim(token: String, epoch: Int): SignedBlob {
        val claim = RouteClaim(
            clientId = identity.clientId,
            transport = TransportType.FCM,
            environment = RouteEnvironment.PRODUCTION,
            routeRef = token,
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3072),
            epoch = epoch,
            issuedAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        return SignedBlob(SignedType.ROUTE_CLAIM, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    fun registerFcmRoute() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            scope.launch {
                runCatching {
                    transport.publishRoutes(listOf(signedRouteClaim(token, settings.routeEpoch())))
                    activityLog.add(ActivityEvent.Kind.ROUTE_REPAIR, "FCM route", "registered", System.currentTimeMillis())
                }
            }
        }
    }

    fun onNewFcmToken(token: String) {
        scope.launch {
            val epoch = settings.bumpRouteEpoch()
            runCatching { transport.publishRoutes(listOf(signedRouteClaim(token, epoch))) }
        }
    }

    companion object {
        private const val TAG = "AppGraph"

        /** Collapse per-keystroke renames in the Settings field into a single broadcast. */
        private const val PROFILE_BROADCAST_DEBOUNCE_MS = 800L
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
