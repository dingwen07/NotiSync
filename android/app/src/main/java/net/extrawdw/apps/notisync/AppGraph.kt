package net.extrawdw.apps.notisync

import android.app.Application
import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.HpkeKeyManager
import net.extrawdw.apps.notisync.crypto.KeyVault
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.PeerRepository
import net.extrawdw.apps.notisync.data.SettingsRepository
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
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
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
    lateinit var peers: PeerRepository
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
        peers = PeerRepository(ds, scope)
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
            peersProvider = { peers.peers.value },
            renderer = poster,
            activityLog = activityLog,
            scope = scope,
            assetResolver = assetManager,
        )

        // Prune mirrored channels/groups for devices that are no longer trusted peers.
        runCatching { MirrorChannels.gc(app, peers.peers.value.map { it.clientId.value }.toSet()) }

        // Battery-efficient transport policy:
        //  * Background delivery is via FCM only (Google's shared push connection — no app socket),
        //    so neither the provider nor the consumer holds a network connection while idle.
        //  * The live WebSocket is opened ONLY while the app UI is in the foreground (instant
        //    bidirectional updates), and closed the moment the app is backgrounded.
        publishSelf()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startLiveConnection()
            override fun onStop(owner: LifecycleOwner) = stopLiveConnection()
        })

        Log.i(TAG, "graph ready clientId=${identity.clientId.shortForm()} backing=${identity.backing}")
    }

    @Volatile
    private var liveJob: Job? = null

    /** Publish our signed card, then our FCM route (card first so the broker can verify the route). */
    private fun publishSelf() {
        scope.launch {
            runCatching { transport.publishCard(buildClientCardBlob()) }
            registerFcmRoute()
        }
    }

    /** Foreground only: refresh registration (self-heals stale broker state) and stream live updates. */
    private fun startLiveConnection() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            runCatching { transport.publishCard(buildClientCardBlob()) }
            registerFcmRoute()
            transport.incoming().collect { mirrorEngine?.handleEnvelope(it) }
        }
    }

    /** Background: drop the socket; FCM carries everything from here. */
    private fun stopLiveConnection() {
        liveJob?.cancel()
        liveJob = null
    }

    /** Build this device's self-signed client card. */
    fun buildClientCardBlob(): SignedBlob {
        val card = ClientCard(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            hpkePublicKeyset = hpke.publicKeyset,
            displayName = settings.deviceName.value,
            platform = "android",
            capabilities = listOf(
                Capability.CAPTURE, Capability.DISPLAY, Capability.DISMISS_SYNC,
                Capability.BACKGROUND_WAKE, Capability.FOREGROUND_CONNECTION,
            ),
            createdAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
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
