package net.extrawdw.apps.notisync

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.analytics.AnalyticsController
import net.extrawdw.apps.notisync.analytics.AndroidPeerTelemetry
import net.extrawdw.apps.notisync.analytics.PerfSpan
import net.extrawdw.apps.notisync.analytics.perfSpan
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.apps.notisync.composition.app.ApplicationBootstrapCoordinator
import net.extrawdw.apps.notisync.composition.app.ApplicationBootstrapFailure
import net.extrawdw.apps.notisync.composition.app.ApplicationBootstrapFailureKind
import net.extrawdw.apps.notisync.composition.app.ApplicationBootstrapOutcome
import net.extrawdw.apps.notisync.composition.app.ApplicationBootstrapState
import net.extrawdw.apps.notisync.composition.app.FcmRouteRegistration
import net.extrawdw.apps.notisync.composition.app.ReadyServices
import net.extrawdw.apps.notisync.composition.app.RoomMigrationCompletion
import net.extrawdw.apps.notisync.composition.bootstrap.StorageBootstrapFailure
import net.extrawdw.apps.notisync.composition.bootstrap.StorageBootstrapFailureDisposition
import net.extrawdw.apps.notisync.composition.messaging.BrokerCustodyRelayRuntimeComponents
import net.extrawdw.apps.notisync.composition.messaging.assembleBrokerCustodyInboundRuntime
import net.extrawdw.apps.notisync.composition.storage.AndroidOperationalPayloadVaultPort
import net.extrawdw.apps.notisync.composition.storage.LegacyV51StorageSources
import net.extrawdw.apps.notisync.composition.storage.OperationalPreferencesCutoverDefaults
import net.extrawdw.apps.notisync.composition.storage.StorageBootstrapDependencies
import net.extrawdw.apps.notisync.composition.storage.StorageClock
import net.extrawdw.apps.notisync.composition.storage.StorageContainerDependencies
import net.extrawdw.apps.notisync.composition.storage.StorageContainerFactory
import net.extrawdw.apps.notisync.composition.runtime.CoreRoomRuntime
import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.crypto.AndroidOperationalSigner
import net.extrawdw.apps.notisync.crypto.EpochHpkeKeyring
import net.extrawdw.apps.notisync.crypto.KeyFingerprint
import net.extrawdw.apps.notisync.data.storage.protection.AndroidKeystoreProtectedPayloadVault
import net.extrawdw.apps.notisync.data.activity.ActivityRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.BrokerEndpointChangeResult
import net.extrawdw.apps.notisync.data.storage.core.RouteAdvanceResult
import net.extrawdw.apps.notisync.data.storage.core.RouteUpdate
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyEnsurer
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.data.AppConfigRepository
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.NotificationFilterStore
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.data.incomingfilter.RoomIncomingFilterRepository
import net.extrawdw.apps.notisync.data.iosregistry.RoomIosAppRegistryRepository
import net.extrawdw.apps.notisync.data.notificationpolicy.RoomAndroidNotificationPolicyFacade
import net.extrawdw.apps.notisync.data.notificationpolicy.RoomAndroidNotificationPolicyRepository
import net.extrawdw.apps.notisync.data.profile.RoomProfileCaptureFacade
import net.extrawdw.apps.notisync.ios.IosBridgeManager
import net.extrawdw.apps.notisync.ios.IosBridgeService
import net.extrawdw.apps.notisync.ios.IosCompanion
import net.extrawdw.apps.notisync.ios.IosAppRegistry
import net.extrawdw.apps.notisync.ios.RoomBackedIosAppRegistry
import net.extrawdw.apps.notisync.ios.IosDeviceRepository
import net.extrawdw.apps.notisync.appicon.AppStoreIconCache
import net.extrawdw.apps.notisync.appicon.AppStoreIconClient
import net.extrawdw.apps.notisync.appicon.AppStoreIconProvider
import net.extrawdw.apps.notisync.appicon.IconResolver
import net.extrawdw.apps.notisync.appicon.ShippedIcons
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.apps.notisync.assets.TicketStore
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.apps.notisync.domain.MirrorEngine
import net.extrawdw.apps.notisync.domain.RenderPhase
import net.extrawdw.apps.notisync.domain.OriginalActionPerformer
import net.extrawdw.apps.notisync.domain.OriginalCanceler
import net.extrawdw.apps.notisync.foundation.FoundationEngine
import net.extrawdw.notisync.peer.foundation.RotationManager
import net.extrawdw.notisync.peer.foundation.TrustPeerDirectory
import net.extrawdw.apps.notisync.integrity.AppCheckAttestor
import net.extrawdw.apps.notisync.notification.capture.GraphicsExtractor
import net.extrawdw.apps.notisync.notification.capture.GraphicsPipeline
import net.extrawdw.apps.notisync.notification.capture.NotificationRuleEngine
import net.extrawdw.apps.notisync.notification.mirror.CallRinger
import net.extrawdw.apps.notisync.notification.mirror.MirrorChannels
import net.extrawdw.apps.notisync.notification.mirror.MirrorMediaSessions
import net.extrawdw.apps.notisync.notification.mirror.MirrorRouter
import net.extrawdw.apps.notisync.notification.mirror.RemoteNotificationPoster
import net.extrawdw.apps.notisync.pairing.automaticTimeEnabled
import net.extrawdw.apps.notisync.run.RunEngine
import net.extrawdw.apps.notisync.run.RunNotificationPresenter
import net.extrawdw.apps.notisync.data.run.RunRepository
import net.extrawdw.apps.notisync.data.seal.RoomSealRepository
import net.extrawdw.apps.notisync.data.ssh.RoomSshProviderRepository
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundDeliveryMode
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.inbound.InboundEffectResult
import net.extrawdw.apps.notisync.messaging.inbound.OperationalInboundCompatibilityEffects
import net.extrawdw.apps.notisync.messaging.inbound.OperationalInboundEffects
import net.extrawdw.apps.notisync.seal.OpenKeychainSigningProvider
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollmentRepository
import net.extrawdw.apps.notisync.seal.OpenPgpSignEngine
import net.extrawdw.apps.notisync.seal.OpenPgpSignNotificationPresenter
import net.extrawdw.apps.notisync.seal.OpenPgpSignRepository
import net.extrawdw.apps.notisync.seal.OpenPgpSigningProvider
import net.extrawdw.apps.notisync.sshagent.SshAgentManagementRepository
import net.extrawdw.apps.notisync.sshagent.SshAgentNotificationPresenter
import net.extrawdw.apps.notisync.sshagent.SshAgentProviderEngine
import net.extrawdw.apps.notisync.sshagent.SshAgentProviderRepository
import net.extrawdw.apps.notisync.screen.AndroidLanScreenSessionTransport
import net.extrawdw.apps.notisync.screen.AndroidScreenDecoderCapabilities
import net.extrawdw.apps.notisync.screen.AndroidScreenDecoderSupport
import net.extrawdw.apps.notisync.screen.AndroidScreenMirrorRequester
import net.extrawdw.apps.notisync.screen.AndroidScreenRequesterSessionHost
import net.extrawdw.apps.notisync.screen.AndroidScreenSource
import net.extrawdw.apps.notisync.screen.AndroidScreenSourceResolver
import net.extrawdw.apps.notisync.screen.ScreenMirrorAuthorizationStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorCapabilityProvider
import net.extrawdw.apps.notisync.screen.ScreenMirrorCodecPreferenceStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorForegroundService
import net.extrawdw.apps.notisync.screen.ScreenMirrorSessionController
import net.extrawdw.apps.notisync.screen.ScreenMirrorShizukuManager
import net.extrawdw.apps.notisync.screen.ScreenViewerToolbarPreferenceStore
import net.extrawdw.apps.notisync.screen.ShizukuScreenStatus
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.peer.transport.ifKnown
import net.extrawdw.apps.notisync.trust.DurableTrustMutations
import net.extrawdw.apps.notisync.trust.TrustActionReceiver
import net.extrawdw.apps.notisync.work.EpochMaintenanceWorker
import net.extrawdw.apps.notisync.work.RelayDrainWorker
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.RouteCapabilities
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.apps.notisync.work.RelayWorkerRuntimeAvailability
import net.extrawdw.apps.notisync.work.RelayWorkerRuntimeProvider
import java.time.ZonedDateTime
import kotlin.io.path.toPath

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore("notisync")

internal const val STALE_RELAY_AGE_MS = 2L * 60 * 60 * 1000

internal fun shouldDeferRelayNotification(
    acceptedAt: Long?,
    now: Long,
    notificationProducing: Boolean,
    rejectedByCallFreshnessGuard: Boolean = false,
): Boolean = notificationProducing && !rejectedByCallFreshnessGuard &&
    acceptedAt != null && acceptedAt <= now - STALE_RELAY_AGE_MS

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

/** A self card plus the wall-clock state captured at the same generation boundary. */
internal data class GeneratedClientCard(
    val blob: SignedBlob,
    val automaticTimeEnabled: Boolean?,
    val createdAt: Long,
    val timeZoneId: String,
)

/** Complete Android declaration. Card and profile builders must both use this exact list. */
internal val ANDROID_SELF_CAPABILITIES = listOf(
    Capability.CAPTURE,
    Capability.DISPLAY,
    Capability.DISMISS_SYNC,
    Capability.PROVIDE_ASSETS,
    Capability.BACKGROUND_WAKE,
    Capability.FOREGROUND_CONNECTION,
    Capability.CAPABILITY_ROUTING_V1,
    Capability.PUSH_FILTERING,
    Capability.DISPLAY_NOTIFICATION_UPDATES,
    Capability.DISPLAY_ANDROID_GROUP_SUMMARIES,
    Capability.RECEIVE_RUNS,
    Capability.OPENPGP_SIGN_V1,
    Capability.SSH_KEY_PROVIDER_V1,
)

class AppGraph internal constructor(
    private val app: Application,
    val activityRepository: ActivityRepository,
    private val runRepository: RunRepository,
    private val coreRepository: CoreFoundationRepository,
    private val coreRuntime: CoreRoomRuntime,
    private val operationalDatabase: OperationalDatabase,
    private val protectedPayloadProtector: OperationalProtectedPayloadProtector,
    private val maintenanceGate: OperationalStorageMaintenanceGate,
    private val payloadKeyEnsurer: OperationalPayloadKeyEnsurer,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard("AppGraph.scope"))
    internal val durableTrustMutations = DurableTrustMutations(scope)

    lateinit var identity: AndroidIdentitySigner
        private set
    lateinit var epochHpke: EpochHpkeKeyring
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
    internal lateinit var profileCapture: RoomProfileCaptureFacade
        private set
    val brokerUrl: StateFlow<String> = coreRepository.transport
        .map { it?.brokerUrl ?: coreRuntime.transport.brokerUrl }
        .stateIn(scope, SharingStarted.Eagerly, coreRuntime.transport.brokerUrl)
    lateinit var trust: TrustStore
        private set
    /** Notification-suppression filters peers asked this device to apply (DATA_SYNC FILTER), keyed by requester. */
    lateinit var notificationFilters: NotificationFilterStore
        private set
    lateinit var transport: BrokerClient
        private set
    lateinit var poster: RemoteNotificationPoster
        private set

    /** Plays the incoming-call ringtone + vibration for ringing call mirrors (which are posted silent). */
    var callRinger: CallRinger? = null
        private set

    /** Per-mirror media sessions so mirrored MEDIA notifications render as real media controls (no FGS/sound). */
    var mediaSessions: MirrorMediaSessions? = null
        private set

    /** Publishes the mirrored media source as a MediaRouter2 route so the card's output chip names it. */
    var mirrorRouter: MirrorRouter? = null
        private set

    var appSelection: AppSelectionRepository? = null
        private set
    /** Per-app ongoing/channel mirroring config + observed channels (source-side, locally owned). */
    var appConfig: AppConfigRepository? = null
        private set
    var secureChannel: SecureChannel? = null
        private set
    var mirrorEngine: MirrorEngine? = null
        private set
    var foundationEngine: FoundationEngine? = null
        private set
    var runEngine: RunEngine? = null
        private set
    lateinit var openPgpProvider: OpenPgpSigningProvider
        private set
    lateinit var openPgpEnrollment: OpenPgpEnrollmentRepository
        private set
    lateinit var openPgpSignStore: OpenPgpSignRepository
        private set
    lateinit var openPgpSignNotifications: OpenPgpSignNotificationPresenter
        private set
    var openPgpSignEngine: OpenPgpSignEngine? = null
        private set
    /** Room-backed SSH provider authority; the field name remains stable for UI/notification callers. */
    internal lateinit var sshKeyProviderStore: SshAgentProviderRepository
        private set
    lateinit var sshAgentManagement: SshAgentManagementRepository
        private set
    internal lateinit var sshAgentNotifications: SshAgentNotificationPresenter
        private set
    internal var sshAgentProviderEngine: SshAgentProviderEngine? = null
        private set
    internal lateinit var brokerCustodyInbound: BrokerCustodyRelayRuntimeComponents
        private set
    var graphicsPipeline: GraphicsPipeline? = null
        private set

    /** Resolves recognizable app icons (delivered asset → installed app → iOS bundle-id map). */
    var iconResolver: IconResolver? = null
        private set

    /** Per-bundle-id opt-in + discovered iOS apps for the iOS tab. */
    var iosAppRegistry: IosAppRegistry? = null
        private set

    /** The iOS BLE bridge; hosted by [IosBridgeService] while enabled. */
    var iosBridgeManager: IosBridgeManager? = null
        private set

    /** Live iOS bridge status + bonded iPhone name for the iOS tab. */
    val iosDeviceRepo = IosDeviceRepository()

    lateinit var screenMirrorAuthorizations: ScreenMirrorAuthorizationStore
        private set
    internal lateinit var screenMirrorCodecPreferences: ScreenMirrorCodecPreferenceStore
        private set
    internal lateinit var screenViewerToolbarPreferences: ScreenViewerToolbarPreferenceStore
        private set
    internal lateinit var screenMirrorDecoderSupport: AndroidScreenDecoderSupport
        private set
    lateinit var screenMirrorShizuku: ScreenMirrorShizukuManager
        private set
    lateinit var screenMirrorCapabilities: ScreenMirrorCapabilityProvider
        private set
    var screenMirrorController: ScreenMirrorSessionController? = null
        private set
    internal var screenMirrorRequester: AndroidScreenMirrorRequester? = null
        private set
    internal var screenMirrorRequesterHost: AndroidScreenRequesterSessionHost? = null
        private set

    /** NS2 rotation state machine — non-null ONLY when `BuildConfig.ENABLE_ROTATION` is set (else the device
     *  stays at epoch 1 forever and never mints a second epoch). Driven by [tickRotation]. */
    var rotationManager: RotationManager? = null
        private set

    val clientId: ClientId? get() = if (::identity.isInitialized) identity.clientId else null

    suspend fun init(initSpan: PerfSpan) {
        val identityStartNanos = System.nanoTime()
        identity = coreRuntime.identity
        // Strict existing-key hydration happens only after the one migrator published Room authority.
        initSpan.metric("identity_load_ms", (System.nanoTime() - identityStartNanos) / 1_000_000)
        val ds = app.dataStore
        settings = SettingsRepository(ds, scope)
        profileCapture = RoomProfileCaptureFacade(operationalDatabase, scope)
        openPgpProvider = OpenKeychainSigningProvider(app)
        val sealRepository = RoomSealRepository(
            database = operationalDatabase,
            protector = protectedPayloadProtector,
            payloadKeyEnsurer = payloadKeyEnsurer,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
        )
        openPgpEnrollment = sealRepository
        openPgpSignStore = sealRepository
        openPgpSignNotifications = OpenPgpSignNotificationPresenter(app)
        val sshRepository = RoomSshProviderRepository(
            context = app,
            database = operationalDatabase,
            protector = protectedPayloadProtector,
            payloadKeyEnsurer = payloadKeyEnsurer,
        )
        sshKeyProviderStore = sshRepository
        sshAgentManagement = SshAgentManagementRepository(sshKeyProviderStore, identity.clientId, scope)
        val sshManagementStartNanos = System.nanoTime()
        sshAgentManagement.preload()
        // First-open schema/integrity checks and the screen's four reads now happen on the graph's I/O init thread.
        initSpan.metric("ssh_management_preload_ms", (System.nanoTime() - sshManagementStartNanos) / 1_000_000)
        sshAgentManagement.start()
        sshAgentNotifications = SshAgentNotificationPresenter(app, sshKeyProviderStore)
        // Opt-out analytics: mirror the user's Settings switch into Firebase Crashlytics + Performance.
        // Apply the PERSISTED value first (so an opted-out user isn't briefly re-enabled by the flow's
        // eager `true` default), then re-apply on every toggle — DataStore stays the single source of
        // truth, since both SDKs otherwise auto-collect by default.
        scope.launch {
            AnalyticsController.apply(settings.analyticsEnabledNow())
            settings.analyticsEnabled.onEach(AnalyticsController::apply).launchIn(this)
        }
        val trustStartNanos = System.nanoTime()
        trust = coreRuntime.trust
        // TrustStore opens + verifies the signed roster (SQLite-backed) — the other notable cold-start cost.
        initSpan.metric("truststore_open_ms", (System.nanoTime() - trustStartNanos) / 1_000_000)
        val notificationPolicy = RoomAndroidNotificationPolicyFacade(
            RoomAndroidNotificationPolicyRepository(operationalDatabase.notificationPolicyDao()),
            scope,
        )
        appSelection = notificationPolicy
        appConfig = notificationPolicy
        notificationFilters = NotificationFilterStore(
            RoomIncomingFilterRepository(operationalDatabase.incomingFilterDao(), scope),
            scope,
        )
        notificationPolicy.awaitHydrated()
        notificationFilters.awaitHydrated()
        screenMirrorAuthorizations = ScreenMirrorAuthorizationStore(operationalDatabase.screenDao(), scope)
        screenMirrorCodecPreferences = ScreenMirrorCodecPreferenceStore(operationalDatabase.screenDao(), scope)
        screenViewerToolbarPreferences = ScreenViewerToolbarPreferenceStore(ds)
        screenMirrorDecoderSupport = AndroidScreenDecoderCapabilities.detect()
        screenMirrorShizuku = ScreenMirrorShizukuManager(app)
        screenMirrorCapabilities = ScreenMirrorCapabilityProvider(
            authorizations = screenMirrorAuthorizations,
            scope = scope,
        )
        trust.roster
            .onEach { roster ->
                screenMirrorAuthorizations.retainTrustedOwnPeers(roster)
                runCatching { screenMirrorCodecPreferences.retainTrustedOwnPeers(roster) }
            }
            .launchIn(scope)
        trust.removeUnverifiedDevices()?.let { removed ->
            removed.forEach(notificationFilters::remove)
            if (removed.isNotEmpty()) {
                Log.i("NotiSync", "Removed ${removed.size} unverified device(s) during NS2 upgrade")
            }
        }
        // NS2 operational layer (always on — the ENABLE_ROTATION flag only gates *minting a second* epoch).
        // The self epoch lives in the signed TrustStore section #4 (≥1); ensure it, then materialise this
        // epoch's TEE operational signing key + HPKE keyset. Rotation (Phase 6) advances the epoch + swaps
        // [operational]; here we simply pin epoch 1 (or whatever the floor recovered to).
        val selfEpoch = trust.selfEpoch()
        check(selfEpoch == coreRuntime.activeEpoch) { "Core trust and active crypto epoch disagree" }
        epochHpke = coreRuntime
        operational = coreRuntime.operational
        val peerTelemetry = AndroidPeerTelemetry()
        transport = BrokerClient(
            signer = identity,
            operationalSigner = { operational },
            baseUrlProvider = { brokerUrl.value },
            integrity = AppCheckAttestor(),
            clientKeyEpochProvider = ::buildClientKeyEpochBlob,
            tokenStore = coreRuntime.authTokenStore,
            scope = scope,
            telemetry = peerTelemetry,
        )
        val assetsDir = java.io.File(app.filesDir, "assets")
        val assetCache = AssetCache(assetsDir)
        val assetManager = AssetManager(transport, assetCache, TicketStore(assetsDir))
        // App Store (iTunes Lookup) icons get their OWN cache, deliberately separate from the encrypted
        // private-asset cache: public artwork fetched per device, never delivered as an asset.
        val appStoreIcons = AppStoreIconProvider(
            AppStoreIconCache(java.io.File(app.filesDir, "appstore-icons")),
            fetch = AppStoreIconClient()::fetch,
        )
        val resolver = IconResolver(app, assetCache, ShippedIcons.fromAssets(app), appStoreIcons)
        iconResolver = resolver
        val ringer = CallRinger(app)
        callRinger = ringer
        // Turning the master switch OFF silences any ring already in progress, not just future ones.
        settings.callRingerEnabled.onEach { if (!it) ringer.stopAll() }.launchIn(scope)
        // A transport press on a mirrored media session relays a command to the origin, which replays it on the
        // real source session. mirrorEngine is read at call time (it's built just below).
        // The router publishes the mirrored source as a MediaRouter2 route so the media card's output chip /
        // Output Switcher names the origin device; MirrorRouteProviderService finds it when the system binds.
        // A switcher-side volume drag loops back through mediaSessions (read at call time), which owns the
        // shared debounced volume relay.
        val mirrorRoutes = MirrorRouter(app) { clientId, volume ->
            mediaSessions?.setVolumeFromSwitcher(clientId, volume)
        }
        mirrorRouter = mirrorRoutes
        val media = MirrorMediaSessions(app, mirrorRoutes) { clientId, sourceKey, command, seekMs, customAction, volume ->
            mirrorEngine?.let { eng ->
                scope.launch {
                    runCatching { eng.mediaCommandRemote(clientId, sourceKey, command, seekMs, customAction, volume) }
                }
            }
        }
        mediaSessions = media
        poster = RemoteNotificationPoster(
            app, assetCache, resolver,
            deviceNameOf = { id -> trust.displayName(id) },
            appStoreIcons = appStoreIcons,
            scope = scope,
            callRinger = ringer,
            // Receiver-side ring gate (read live, so a toggle applies to the next mirrored call): the global
            // master switch must be on, and defaults off; per-app "ring for calls" still defaults on.
            ringForCalls = { pkg ->
                settings.callRingerEnabled.value && (appConfig?.configFor(pkg)?.ringForCalls ?: true)
            },
            showPublicLockScreenIdentity = { settings.lockScreenPublicIdentity.value },
            mediaSessions = media,
        )
        val graphicsExtractor = GraphicsExtractor(app)
        graphicsPipeline =
            GraphicsPipeline(NotificationRuleEngine(), graphicsExtractor, assetManager)
        // The generic secure-messaging substrate: seal/sign/dedup/verify/open + per-MessageType routing.
        // It depends only on the read-only TrustPeerDirectory port (keys flow foundation → channel).
        val channel = SecureChannel(
            signer = identity,
            operationalSigner = { operational },
            myHpkePrivate = { epoch -> epochHpke.privateKeyset(epoch) },
            transport = transport,
            directory = TrustPeerDirectory(trust),
            log = { msg -> Log.w("SecureChannel", msg) },
            telemetry = peerTelemetry,
            // Envelope signing and authenticated-request signing synchronously cross Android Keystore.
            // Compose and lifecycle callers may enter on main; suspend them while the whole send runs on I/O.
            outboundDispatcher = Dispatchers.IO,
            // Can't resolve a (trusted) sender's key for the epoch it signed with → fetch its key-epoch (and
            // fall back to a roster broadcast) so the gap self-heals. foundationEngine is read at call time.
            onUnresolvedSender = { id ->
                scope.launch {
                    runCatching {
                        foundationEngine?.onUnresolvedSender(
                            id
                        )
                    }
                }
            },
        )
        secureChannel = channel
        val screenController = ScreenMirrorSessionController(
            context = app,
            ownClientId = identity.clientId,
            channel = channel,
            authorizations = screenMirrorAuthorizations,
            capabilities = screenMirrorCapabilities,
            shizuku = screenMirrorShizuku,
            scope = scope,
            transport = AndroidLanScreenSessionTransport(app, transport),
            peerName = { id -> trust.displayName(id) },
        )
        screenMirrorController = screenController
        val screenSourceResolver = AndroidScreenSourceResolver { clientId ->
            if (trust.quarantined.value) return@AndroidScreenSourceResolver null
            trust.roster.value.firstOrNull { device ->
                device.clientId == clientId &&
                    device.ownDevice &&
                    device.status == TrustStatus.TRUSTED &&
                    device.keyAvailable &&
                    device.verified &&
                    device.currentEpoch > 0
            }?.let { device ->
                AndroidScreenSource(
                    clientId = device.clientId,
                    displayName = device.displayName ?: device.clientId.shortForm(),
                    capabilities = device.capabilities.toSet(),
                )
            }
        }
        val screenRequester = AndroidScreenMirrorRequester(
            context = app,
            ownClientId = identity.clientId,
            channel = channel,
            broker = transport,
            sourceResolver = screenSourceResolver,
            scope = scope,
            decoderSupport = { screenMirrorDecoderSupport },
            preferredCodec = screenMirrorCodecPreferences::preferredCodec,
        )
        screenMirrorRequester = screenRequester
        screenMirrorRequesterHost = AndroidScreenRequesterSessionHost(
            requester = screenRequester,
            scope = scope,
            hardwareDecoderName = screenMirrorDecoderSupport::hardwareDecoderName,
        )
        trust.roster
            .onEach {
                screenRequester.state.value.sourceId?.let { sourceId ->
                    if (screenSourceResolver.resolve(sourceId) == null) {
                        screenRequester.close()
                    }
                }
            }
            .launchIn(scope)
        trust.quarantined
            .onEach { quarantined -> if (quarantined) screenRequester.close() }
            .launchIn(scope)
        screenMirrorAuthorizations.screenMirroringEnabled
            .onEach { enabled ->
                if (enabled) screenMirrorShizuku.refresh()
                else {
                    screenController.onAuthorizationPolicyChanged()
                    ScreenMirrorForegroundService.stop(app)
                }
            }
            .launchIn(scope)
        screenMirrorAuthorizations.authorizedPeerIds
            .onEach { screenController.onAuthorizationPolicyChanged() }
            .launchIn(scope)
        screenMirrorShizuku.status
            .onEach { status ->
                if (status != ShizukuScreenStatus.READY) screenController.onAuthorizationPolicyChanged()
            }
            .launchIn(scope)
        // DATA_SYNC/RUN is a first-class Android application path. It has its own durable history and
        // notification renderer; it must never be adapted into CapturedNotification/MirrorEngine.
        val runs = RunEngine(
            channel = channel,
            repository = runRepository,
            presenter = RunNotificationPresenter(
                app,
                deviceNameOf = { id -> trust.displayName(id) },
            ),
            scope = scope,
        )
        runEngine = runs
        // A process can die after a Run snapshot commits but before its stable notification posts. The store's
        // presentation checkpoint makes this startup reconciliation precise and idempotent.
        scope.launch { runs.reconcilePendingPresentations() }
        val openPgpSigning = OpenPgpSignEngine(
            context = app,
            channel = channel,
            enrollmentStore = openPgpEnrollment,
            store = openPgpSignStore,
            notifications = openPgpSignNotifications,
            deviceNameOf = { id -> trust.displayName(id) },
        )
        openPgpSignEngine = openPgpSigning
        scope.launch { openPgpSigning.reconcile() }
        val sshProvider = SshAgentProviderEngine(
            context = app,
            providerClientId = identity.clientId,
            channel = channel,
            store = sshKeyProviderStore,
            notifications = sshAgentNotifications,
            scope = scope,
            deviceNameOf = { id -> trust.displayName(id) },
        )
        sshAgentProviderEngine = sshProvider
        scope.launch { sshProvider.reconcile() }
        // Notification-mirroring application: NOTIFICATION/DISMISSAL + private-asset repair.
        val mirror = MirrorEngine(
            channel = channel,
            renderer = poster,
            scope = scope,
            assetResolver = assetManager,
            notificationFilters = notificationFilters, // honor peers' suppression requests when forwarding
        )
        mirrorEngine = mirror
        // iOS notification bridge (ANCS over BLE): a discovered-app registry (per-bundle-id opt-in) and the
        // BLE manager that turns ANCS events into CapturedNotifications, then dispatches them to local display
        // and/or the own mesh — reusing the same capture/render pipeline as a local Android capture.
        val registry = RoomBackedIosAppRegistry(
            RoomIosAppRegistryRepository(operationalDatabase.iosAppDao()),
            scope,
        )
        registry.awaitHydrated()
        iosAppRegistry = registry
        iosBridgeManager = IosBridgeManager(
            context = app,
            scope = scope,
            clientId = identity.clientId,
            iconResolver = resolver,
            appIconBytes = { pkg -> graphicsExtractor.appIcon(pkg) },
            uploadAsset = { bytes, role, mime, cid ->
                assetManager.ensureUploaded(
                    bytes,
                    role,
                    mime,
                    cid
                )
            },
            registry = registry,
            deviceRepo = iosDeviceRepo,
            localDisplayEnabled = { settings.iosLocalDisplay.value },
            meshMirrorEnabled = { settings.iosMeshMirror.value },
            mediaMirrorEnabled = { settings.iosMediaMirror.value },
            captureToMesh = { notif -> mirror.captureLocal(notif) },
            sendQuietToMesh = { notif -> mirror.sendNotificationQuiet(notif) },
            renderLocal = { notif, silent ->
                poster.render(notif, silent, if (silent) RenderPhase.REPLAY else RenderPhase.INITIAL)
            },
            clearLocal = { cid, key -> poster.clear(cid, key) },
            dismissMesh = { cid, key -> mirror.dismissLocal(cid, key) },
        )
        // Turning the iPhone-media switch OFF tears the now-playing card down live (local + mesh), like the
        // ringer master switch above — not just for future sessions.
        settings.iosMediaMirror.onEach { if (!it) iosBridgeManager?.onMediaMirrorDisabled() }.launchIn(scope)
        // Dismissing an iOS mirror — swiped here or relayed from another own device — clears it on the iPhone
        // too (best-effort ANCS negative action), so it doesn't linger on iOS or reappear on the next reconnect.
        mirror.iosOriginCanceler = OriginalCanceler { key -> iosBridgeManager?.dismissOnIphone(key) }
        // A peer pressing a mirrored ANCS action button (e.g. Answer/Decline on a call) performs the
        // matching positive/negative action on the bridged iPhone. No-op for non-ANCS keys.
        mirror.iosOriginActionPerformer = OriginalActionPerformer { event -> iosBridgeManager?.performOnIphone(event) }
        // Trust/device/profile foundation: trust-table + card + profile wire I/O, backed by TrustStore.
        val foundation = FoundationEngine(
            channel = channel,
            trust = trust,
            scope = scope,
            onTrustPrompt = ::onTrustPrompt,
            onAsset = mirror::onAssetSync, // ASSET DataSync forwarded to the notification app
            onFilter = mirror::onFilterSync, // FILTER DataSync (a peer's suppression request) forwarded too
            onNotificationSync = mirror::onQuietNotification, // NOTIFICATION DataSync (quiet ongoing update)
            onRunSync = runs::onRunSync, // RUN DataSync persists/renders through the dedicated Run application
            onOpenPgpSignSync = openPgpSigning::onOpenPgpSignSync,
            onSshAgentSync = sshProvider::onSshAgentSync,
            onScreenMirrorSync = { message, sync ->
                screenController.onScreenMirrorSync(message, sync)
                screenRequester.onScreenMirrorSync(message, sync)
            },
            // Continue announcing our own epoch with the roster; material held for a third peer is returned
            // directly when the receiving peer advertises that gap.
            selfKeyEpoch = {
                // RotationManager owns the exact staged validity window/floor. A generic current-epoch
                // certificate would overwrite those semantics on receivers while rotation is pending.
                if (trust.pendingRotation() == null) runCatching { buildClientKeyEpochBlob() }.getOrNull()
                else null
            },
            fetchKeyEpoch = { id, epoch -> transport.fetchKeyEpoch(id, epoch) },
        )
        foundationEngine = foundation
        // SecureChannel remains the outbound compatibility surface for current feature engines. Inbound traffic
        // is owned exclusively by the Room-backed authenticated coordinator assembled below; do not register the
        // legacy mutable handler map as a second consumer.

        // NS2 rotation (Phase 6) — constructed ONLY behind the flag; the key generation, live-signer swap,
        // and key destruction are the Android-specific bits injected here, keeping the state machine itself
        // Keystore-free and unit-testable. With the flag OFF this is never built: epoch 1 forever.
        if (BuildConfig.ENABLE_ROTATION) {
            rotationManager = RotationManager(
                clientId = identity.clientId,
                identitySpki = identity.publicKeySpki,
                identitySign = identity::sign,
                trust = trust,
                mintOperational = { epoch ->
                    AndroidOperationalSigner.loadOrCreate(
                        identity.clientId,
                        epoch
                    )
                },
                mintHpke = { epoch -> epochHpke.loadOrCreate(epoch) },
                onActivate = { signer, epoch ->
                    // Swap the live signer + advance the epoch counter ONLY. RotationManager.tick() republishes
                    // the activated epoch to the broker with the correct rotation windows immediately after this
                    // returns; publishing buildClientKeyEpochBlob() here would race it and corrupt the schedule.
                    operational = signer
                    trust.advanceSelfEpoch(epoch)
                    // Restart the epoch-age clock so the NEXT scheduled rotation is measured from this activation.
                    scope.launch { runCatching { settings.setSelfEpochActivatedAt(System.currentTimeMillis()) } }
                    // Re-announce the roster. N+1's correctly windowed certificate was already pre-warmed;
                    // the generic self-epoch CARD stays suppressed until rotation completes.
                    scope.launch { runCatching { foundationEngine?.broadcastTrust() } }
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
                if (!trust.quarantined.value) runCatching {
                    MirrorChannels.gc(
                        app,
                        peers.map { it.clientId.value }.toSet()
                    )
                }
            }
            .launchIn(scope)

        // Surface a tamper quarantine the instant it's detected — StateFlow replays its current value on
        // subscription, so this also fires at launch when load() flagged the persisted roster — and clear
        // the alert once the user resolves it (approve/clear flips quarantined back to false).
        trust.quarantined
            .onEach { tampered -> if (tampered) postTamperAlert() else cancelTamperAlert() }
            .launchIn(scope)

        brokerCustodyInbound = assembleBrokerCustodyInboundRuntime(
            broker = transport,
            identity = identity,
            operationalSigner = { operational },
            hpkeKeyring = epochHpke,
            trust = trust,
            coreRepository = coreRepository,
            operationalDatabase = operationalDatabase,
            maintenanceGate = maintenanceGate,
            sealRepository = sealRepository,
            sshRepository = sshRepository,
            effects = object : OperationalInboundEffects {
                override suspend fun presentNotification(
                    notification: CapturedNotification,
                    forceSilent: Boolean,
                ): InboundEffectResult {
                    mirror.presentCommitted(notification, forceSilent)
                    return InboundEffectResult.COMPLETED
                }

                override suspend fun dismissNotification(
                    event: net.extrawdw.notisync.protocol.DismissEvent,
                ): InboundEffectResult {
                    mirror.dismissCommitted(event)
                    return InboundEffectResult.COMPLETED
                }

                override suspend fun performAction(
                    event: net.extrawdw.notisync.protocol.ActionEvent,
                ): InboundEffectResult = if (mirror.performCommitted(event)) {
                    InboundEffectResult.COMPLETED
                } else {
                    InboundEffectResult.RETRY_REQUIRED
                }
            },
            compatibilityEffects = OperationalInboundCompatibilityEffects { command ->
                val data = (command.payload as? DecodedInboundPayload.Data)?.value
                if (data == null) {
                    InboundEffectResult.SECURITY_BLOCKED
                } else {
                    val message = command.toCompatibilityInboundMessage()
                    when (data.kind) {
                        DataSyncKind.ASSET -> mirror.handleAssetSync(message, data)
                        DataSyncKind.RUN -> runs.onRunSync(message, data)
                        DataSyncKind.SCREEN_MIRRORING -> {
                            if (data.screenMirror?.action == net.extrawdw.notisync.protocol.ScreenMirrorAction.REQUEST) {
                                screenController.onCommittedScreenMirrorRequest(message, data)
                            } else {
                                screenController.onScreenMirrorSync(message, data)
                                screenRequester.onScreenMirrorSync(message, data)
                            }
                        }
                        DataSyncKind.OPENPGP_SIGN -> openPgpSigning.onOpenPgpSignSync(message, data)
                        DataSyncKind.SSH_AGENT -> sshProvider.handleSshAgentSync(message, data)
                        DataSyncKind.PROFILE,
                        DataSyncKind.TRUST,
                        DataSyncKind.CARD,
                        DataSyncKind.FILTER,
                        DataSyncKind.NOTIFICATION,
                        -> return@OperationalInboundCompatibilityEffects InboundEffectResult.SECURITY_BLOCKED
                    }
                    InboundEffectResult.COMPLETED
                }
            },
        )

        // Battery-efficient transport policy:
        //  * Background delivery is via FCM only (Google's shared push connection — no app socket),
        //    so neither the provider nor the consumer holds a network connection while idle.
        //  * The live WebSocket is opened ONLY while the app UI is in the foreground (instant
        //    bidirectional updates), and closed the moment the app is backgrounded.
        publishSelf()
        observeProcessLifecycle()
        observeProfileChanges()
        announceProfileOnProcessStart()
        // Time-driven epoch upkeep (converge peer key-epochs; with ENABLE_ROTATION also initiate + advance
        // rotation) — the message-independent guarantee a quiet device still rotates. See the worker doc.
        EpochMaintenanceWorker.schedulePeriodic(app)
        // Resume the iOS bridge if the user left its switch on — covers any cold start of this process
        // (FCM wake, etc.). Reboot / app-update arrive via IosBridgeBootReceiver, which starts the same FGS inside
        // the boot exemption window; both are idempotent.
        scope.launch { resumeIosBridgeIfEnabled() }

        Log.i(
            TAG,
            "graph ready clientId=${identity.clientId.shortForm()} backing=${identity.backing}"
        )
    }

    private fun PlannedInboundCommand.toCompatibilityInboundMessage(): InboundMessage = InboundMessage(
        senderId = delivery.senderId,
        senderOwnDevice = delivery.senderOwnDevice,
        typ = delivery.messageType,
        body = delivery.encodedBody,
        signerEpoch = delivery.signerEpoch,
        messageId = delivery.messageId,
        deliveryMode = delivery.deliveryMode.toPeerDeliveryMode(),
        createdAt = delivery.signedCreatedAt,
        acceptedAt = delivery.acceptedAt,
        forceSilent = delivery.forceSilent,
    )

    private fun InboundDeliveryMode.toPeerDeliveryMode(): DeliveryMode = when (this) {
        InboundDeliveryMode.UNKNOWN -> DeliveryMode.UNKNOWN
        InboundDeliveryMode.WEBSOCKET -> DeliveryMode.WEBSOCKET
        InboundDeliveryMode.FCM_INLINE -> DeliveryMode.FCM_INLINE
        InboundDeliveryMode.FCM_RELAY_FETCH -> DeliveryMode.FCM_RELAY_FETCH
        InboundDeliveryMode.RELAY_DRAIN -> DeliveryMode.RELAY_DRAIN
    }

    @Volatile
    private var liveJob: Job? = null

    private fun observeProcessLifecycle() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startLiveConnection()
            override fun onStop(owner: LifecycleOwner) = onAppBackgrounded()
        }

        fun addObserver() {
            lifecycle.addObserver(observer)
            // AppGraph now initializes off the main thread; if the process is already foregrounded by the time
            // this observer is installed, start the foreground socket explicitly instead of waiting for a future
            // onStart event that already happened.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startLiveConnection()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            addObserver()
        } else {
            Handler(Looper.getMainLooper()).post { addObserver() }
        }
    }

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
        // Also retry after notification permission is granted while the process remains alive.
        scope.launch { runEngine?.reconcilePendingPresentations() }
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            publishKeyEpochUnlessRotating()
            // Converge peer key-epochs BEFORE the live loop blocks, so a just-upgraded peer is sealable and
            // our broadcast actually reaches it (the pull is the bootstrap; the broadcast is anti-entropy).
            runCatching { foundationEngine?.convergeKeyEpochs() }
            // Between rotations anti-entropy includes our own epoch; while staged, RotationManager owns that
            // certificate. Material for third peers is returned by targeted CARD.
            runCatching { foundationEngine?.broadcastTrust() }
            brokerCustodyInbound.liveDelivery.run()
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
    /** Diagnostics recovery: delete every mirrored notification channel so they rebuild at the right importance
     *  (the OS can't raise a channel stranded at Silent — only a delete+recreate fixes it). Returns the count. */
    fun resetNotificationChannels(): Int = MirrorChannels.deleteAll(app)

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
            // Fingerprint the raw published key (not the local Tink keyset) so this self-diagnostic matches
            // the HPKE fingerprint peers compute over the same bytes carried in our ClientKeyEpoch.
            encryptionKey = runCatching {
                epochHpke.publicKeyset(epoch)?.let { KeyFingerprint.short(Hpke.rawPublicKey(it)) }
            }.getOrNull() ?: "—",
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
        AndroidOperationalSigner.retainedEpochs().filter { it < live }
            .forEach { AndroidOperationalSigner.destroy(it) }
        epochHpke.prune(epochHpke.retainedEpochs().filter { it >= live }.toSet())
    }


    /**
     * Going to the background: drop the live socket, refresh the FCM wake route (the only delivery path
     * while idle), and re-broadcast trust. Profile anti-entropy runs once per process start and renames still
     * announce immediately, so it does not need another background-transition broadcast.
     */
    private fun onAppBackgrounded() {
        stopLiveConnection()
        registerFcmRoute()
        scope.launch {
            runCatching { foundationEngine?.broadcastTrust() }
        }
    }

    /** Re-broadcast our trust roster now (call after a local trust change that should propagate at once). */
    fun broadcastTrust() {
        scope.launch { runCatching { foundationEngine?.broadcastTrust() } }
    }

    /**
     * Toggle the iOS bridge: persist the choice and start/stop the foreground bridge
     * service that owns the BLE link. Called from the iOS tab (always foreground, so starting the
     * `connectedDevice` foreground service is permitted). With the switch on, [IosBridgeService] keeps the
     * link alive in the background; [net.extrawdw.apps.notisync.ios.IosCompanionService] can re-start it on
     * device presence after a process death.
     */
    fun setIosBridgeEnabled(enabled: Boolean) {
        // Persist FIRST, then act. The iOS tab's auto-resume effect keys on this persisted flag; a
        // fire-and-forget persist races the synchronous service stop (status -> OFF), so the effect would see
        // the stale enabled=true together with OFF and immediately restart the bridge — "off" never sticks.
        scope.launch {
            runCatching { settings.setIosBridgeEnabled(enabled) }
            if (enabled) {
                IosBridgeService.start(app)
            } else {
                IosBridgeService.stop(app)
                IosCompanion.stopObservingPresence(app) // user turned it off: don't let CDM presence re-wake us
            }
        }
    }

    /**
     * Bring the iOS bridge back after a process (re)start if the user left the switch on. Reads the
     * PERSISTED flag (the [SettingsRepository.iosBridgeEnabled] StateFlow is still its default here, before
     * DataStore loads) and gates on BT permissions so the `connectedDevice` FGS start can't throw, then
     * starts the bridge and re-arms CompanionDeviceManager presence. Guarded throughout: a background-start
     * denial (no exemption) is harmless — the iOS tab, CDM presence, and
     * [net.extrawdw.apps.notisync.ios.IosBridgeBootReceiver] are the other resume paths. Called from [init] (every
     * process spawn — cold start, FCM wake) and IosBridgeBootReceiver (reboot / app update).
     */
    suspend fun resumeIosBridgeIfEnabled() {
        if (!runCatching { settings.iosBridgeEnabledNow() }.getOrDefault(false)) return
        if (!IosBridgeService.hasPermissions(app)) return
        runCatching { IosBridgeService.start(app) }
        runCatching { IosCompanion.observePresence(app) }
    }

    /** Surface a pending trust decision as a local notification: tap opens Devices; add/re-add carry Approve/Reject. */
    private fun onTrustPrompt(clientId: ClientId, prompt: TrustPrompt, byName: String) {
        if (ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val channelId = "notisync.trust"
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    app.getString(R.string.trust_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
            )
            val subject = trust.displayName(clientId) ?: clientId.shortForm()
            val (title, text) = when (prompt) {
                TrustPrompt.NEW_TRUST -> app.getString(R.string.trust_new_title) to app.getString(
                    R.string.trust_new_text,
                    byName,
                    subject
                )

                TrustPrompt.RE_TRUST -> app.getString(R.string.trust_retrust_title) to app.getString(
                    R.string.trust_retrust_text,
                    byName,
                    subject
                )

                TrustPrompt.NEW_REVOKE -> app.getString(R.string.trust_revoke_title) to app.getString(
                    R.string.trust_revoke_text,
                    byName,
                    subject
                )

                TrustPrompt.CONFLICT -> app.getString(R.string.trust_conflict_title) to app.getString(
                    R.string.trust_conflict_text,
                    subject
                )
                // "Other" devices are already applied — these prompts just keep the user informed.
                TrustPrompt.OTHER_ADDED -> app.getString(R.string.trust_other_added_title) to app.getString(
                    R.string.trust_other_added_text,
                    byName,
                    subject
                )

                TrustPrompt.OTHER_REMOVED -> app.getString(R.string.trust_other_removed_title) to app.getString(
                    R.string.trust_other_removed_text,
                    byName,
                    subject
                )
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
                    builder.addAction(
                        0,
                        app.getString(R.string.action_approve),
                        trustActionPi(TrustActionReceiver.ACTION_APPROVE, clientId, notifId)
                    )
                    builder.addAction(
                        0,
                        app.getString(R.string.action_reject),
                        trustActionPi(TrustActionReceiver.ACTION_REJECT, clientId, notifId)
                    )
                }

                TrustPrompt.NEW_REVOKE -> {
                    builder.addAction(
                        0,
                        app.getString(R.string.action_remove),
                        trustActionPi(TrustActionReceiver.ACTION_CONFIRM_REVOKE, clientId, notifId)
                    )
                    builder.addAction(
                        0,
                        app.getString(R.string.action_keep),
                        trustActionPi(TrustActionReceiver.ACTION_KEEP, clientId, notifId)
                    )
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
        if (ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val channelId = "notisync.security"
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    app.getString(R.string.security_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ),
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
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(app.getString(R.string.tamper_alert_text))
                )
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
    private fun selfCapabilities(): List<Capability> =
        ANDROID_SELF_CAPABILITIES +
            if (::screenMirrorCapabilities.isInitialized) {
                screenMirrorCapabilities.advertisedCapabilities.value
            } else {
                emptyList()
            }

    private fun selfProfileFingerprint(): String = buildString {
        append(profileCapture.deviceName.value)
        append('\u001f')
        append("android")
        selfCapabilities().forEach {
            append('\u001f')
            append(it.name)
        }
    }

    /**
     * Build this device's self-signed client card — the QR/E2E-only pairing bundle (identity anchor +
     * human profile). NEVER uploaded to the broker in NS2; it travels in the pairing
     * [net.extrawdw.notisync.protocol.CardDelivery] alongside the key-epoch.
     */
    internal fun generateClientCard(): GeneratedClientCard {
        // Read AUTO_TIME immediately before the signed timestamp. A missing/unreadable setting is unknown,
        // not "off", so callers warn only when Android explicitly reports 0.
        val autoTimeEnabled = automaticTimeEnabled(app.contentResolver)
        val systemTime = ZonedDateTime.now()
        val createdAt = systemTime.toInstant().toEpochMilli()
        val timeZoneId = systemTime.zone.id
        val card = ClientCard(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            displayName = profileCapture.deviceName.value,
            platform = "android",
            capabilities = selfCapabilities(),
            createdAt = createdAt,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return GeneratedClientCard(
            blob = SignedBlob(
                SignedType.CLIENT_CARD,
                signerId = identity.clientId,
                payload = payload,
                sig = identity.sign(payload),
            ),
            automaticTimeEnabled = autoTimeEnabled,
            createdAt = createdAt,
            timeZoneId = timeZoneId,
        )
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
            // Publish the raw 32-byte X25519 key (not the Tink keyset) so Tink-free peers (iOS CryptoKit)
            // can seal; Android peers seal via Hpke.seal's length dispatch. The local private keyset stays Tink.
            hpkePublicKey = Hpke.rawPublicKey(epochHpke.loadOrCreate(epoch)),
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = Long.MAX_VALUE,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(keyEpoch)
        return SignedBlob(
            SignedType.KEY_EPOCH,
            signerId = identity.clientId,
            payload = payload,
            sig = identity.sign(payload)
        )
    }

    /** This device's current mutable profile, stamped when any advertised field last changed. */
    fun buildProfileUpdate(updatedAt: Long = profileCapture.selfProfileUpdatedAt.value): ProfileUpdate = ProfileUpdate(
        clientId = identity.clientId,
        displayName = profileCapture.deviceName.value,
        platform = "android",
        capabilities = selfCapabilities(),
        updatedAt = updatedAt,
    )

    /**
     * Re-announce on every process start. The revision advances only when the declaration changed, so this
     * is cheap anti-entropy for background-only starts while remaining idempotent at receivers.
     */
    private fun announceProfileOnProcessStart() {
        scope.launch {
            val revision = profileCapture.ensureSelfProfileRevision(selfProfileFingerprint())
                ?: profileCapture.selfProfileUpdatedAt.value
            runCatching { foundationEngine?.broadcastProfile(buildProfileUpdate(revision)) }
        }
    }

    /**
     * Propagate device-profile changes (today: a rename). The Settings field commits only when editing
     * is done (focus loss / IME action), so this normally fires once per edit; the debounce just
     * coalesces any rapid back-to-back commits. Each change does both halves of convergence: refresh
     * the broker's card cache (so a *future* pairing resolves the new name) and push a DATA_SYNC
     * profile update to *existing* peers (the broker never sees the profile in NS2 — it is QR + E2E only).
     */
    @OptIn(FlowPreview::class) // debounce() is a preview API; used only to coalesce rapid renames
    private fun observeProfileChanges() {
        combine(
            profileCapture.deviceName,
            screenMirrorCapabilities.advertisedCapabilities,
        ) { name, capabilities -> name to capabilities }
            .drop(1) // skip the eager StateFlow seed; only react to real profile changes
            .debounce(PROFILE_BROADCAST_DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach {
                val changedAt = profileCapture.ensureSelfProfileRevision(selfProfileFingerprint()) ?: return@onEach
                // The profile is not key material and never goes to the broker; converge peers over E2E only.
                runCatching { foundationEngine?.broadcastProfile(buildProfileUpdate(changedAt)) }
            }
            .launchIn(scope)
    }

    private fun signedRouteClaim(routeRef: String, epoch: Long): SignedBlob {
        require(epoch in 0..Int.MAX_VALUE.toLong()) { "FCM route epoch exceeds the wire contract" }
        val claim = RouteClaim(
            clientId = identity.clientId,
            transport = TransportType.FCM,
            environment = RouteEnvironment.PRODUCTION,
            routeRef = routeRef,
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3600),
            epoch = epoch.toInt(),
            issuedAt = System.currentTimeMillis(),
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        return SignedBlob(
            SignedType.ROUTE_CLAIM,
            signerId = identity.clientId,
            payload = payload,
            sig = identity.sign(payload)
        )
    }

    /**
     * Register this app instance with FCM. The result is delivered through
     * [NotiSyncMessagingService.onRegistered], which provides the current direct-send target.
     */
    fun registerFcmRoute() {
        FirebaseMessaging.getInstance().register()
    }

    fun onFcmRegistered(routeRef: String) {
        scope.launch {
            runCatching {
                val current = requireNotNull(coreRepository.transport.first()) {
                    "Core transport authority is missing"
                }
                val epoch = if (current.fcmRouteRef == routeRef) {
                    current.routeEpoch
                } else {
                    Math.addExact(current.routeEpoch, 1L)
                }
                when (
                    coreRepository.advanceRoute(
                        RouteUpdate(
                            brokerUrl = current.brokerUrl,
                            fcmRouteRef = routeRef,
                            routeEpoch = epoch,
                            expectedBrokerEndpointRevision = current.brokerEndpointRevision,
                            selfEpochActivatedAt = current.selfEpochActivatedAt,
                        ),
                    )
                ) {
                    RouteAdvanceResult.ADVANCED,
                    RouteAdvanceResult.UNCHANGED,
                    -> transport.publishRoutes(listOf(signedRouteClaim(routeRef, epoch)))
                    RouteAdvanceResult.STALE,
                    RouteAdvanceResult.CONFLICT,
                    RouteAdvanceResult.STALE_ENDPOINT,
                    RouteAdvanceResult.MISSING,
                    -> error("Core rejected the FCM route transition")
                }
            }
        }
    }

    suspend fun setBrokerUrl(url: String) {
        when (coreRepository.changeBrokerEndpoint(url)) {
            BrokerEndpointChangeResult.CHANGED -> registerFcmRoute()
            BrokerEndpointChangeResult.UNCHANGED -> Unit
            BrokerEndpointChangeResult.MISSING -> error("Core transport authority is missing")
        }
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
        val filler =
            "NotiSync oversized diagnostic — this payload is deliberately too large to inline in " +
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
            style = NotificationStyle.BIG_TEXT,
            importance = MirrorImportance.HIGH,
            postTime = System.currentTimeMillis(),
            channelId = "notisync_test",
            channelName = "NotiSync Test"
        )
        val withAppIcon = graphicsPipeline?.attachAppIcon(notif) ?: notif
        return mirror.captureLocal(withAppIcon)
    }

    companion object {
        private const val TAG = "AppGraph"

        /** Stable id for the singleton tamper-quarantine notification (re-post replaces it, resolve cancels it). */
        private val TAMPER_NOTIF_ID = "notisync.tamper-alert".hashCode()

        /** Collapse per-keystroke renames in the Settings field into a single broadcast. */
        private const val PROFILE_BROADCAST_DEBOUNCE_MS = 800L

    }
}

/** Application entry point. Storage/runtime readiness is the only path that may publish live services. */
internal class NotiSyncApp : Application(), RelayWorkerRuntimeProvider {
    private val initScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard("NotiSyncApp.initScope"))
    private val bootstrapCoordinator = ApplicationBootstrapCoordinator(
        scope = initScope,
        startRuntime = ::startRoomRuntime,
    )
    private val roomMigrationCompletion by lazy(LazyThreadSafetyMode.NONE) { RoomMigrationCompletion(this) }

    internal val bootstrapState: StateFlow<ApplicationBootstrapState<ReadyServices>> =
        bootstrapCoordinator.state
    val graphIfReady: AppGraph?
        get() = (bootstrapState.value as? ApplicationBootstrapState.Ready)?.services?.graph
    val graph: AppGraph
        get() = graphIfReady ?: error("Application runtime is not ready")

    override fun onCreate() {
        super.onCreate()
        bootstrapCoordinator.start()
        initScope.launch {
            when (val settled = bootstrapState.first { it !is ApplicationBootstrapState.Loading }) {
                is ApplicationBootstrapState.Ready -> {
                    // The broker retains messages while migration/runtime startup is incomplete. Ready publication
                    // starts one immediate backlog drain and keeps the independent periodic safety net scheduled.
                    RelayDrainWorker.enqueueNormal(this@NotiSyncApp)
                    RelayDrainWorker.schedulePeriodic(this@NotiSyncApp)
                }
                is ApplicationBootstrapState.Unavailable -> Log.w(
                    "NotiSyncApp",
                    "application bootstrap unavailable code=${settled.failure.code}",
                )
                ApplicationBootstrapState.Loading -> error("settled bootstrap state cannot be loading")
            }
        }
    }

    suspend fun awaitGraphReady(timeoutMillis: Long = GRAPH_INIT_TIMEOUT_MS): AppGraph? =
        withTimeoutOrNull(timeoutMillis) {
            when (val settled = bootstrapState.first { it !is ApplicationBootstrapState.Loading }) {
                is ApplicationBootstrapState.Ready -> settled.services.graph
                is ApplicationBootstrapState.Unavailable -> null
                ApplicationBootstrapState.Loading -> error("settled bootstrap state cannot be loading")
            }
        }

    fun awaitGraphReadyBlocking(timeoutMillis: Long = GRAPH_INIT_TIMEOUT_MS): AppGraph? =
        graphIfReady ?: kotlinx.coroutines.runBlocking { awaitGraphReady(timeoutMillis) }

    fun runWhenReadyServices(block: (ReadyServices) -> Unit) {
        val current = bootstrapState.value
        if (current is ApplicationBootstrapState.Ready) {
            block(current.services)
        } else if (current is ApplicationBootstrapState.Loading) {
            initScope.launch {
                val settled = bootstrapState.first { it !is ApplicationBootstrapState.Loading }
                if (settled is ApplicationBootstrapState.Ready) block(settled.services)
            }
        }
    }

    fun runWhenGraphReady(block: (AppGraph) -> Unit) {
        graphIfReady?.let(block) ?: initScope.launch {
            awaitGraphReady()?.let(block)
        }
    }

    override suspend fun relayWorkerRuntimeAvailability(): RelayWorkerRuntimeAvailability =
        when (val state = bootstrapCoordinator.awaitStartup()) {
            ApplicationBootstrapState.Loading -> RelayWorkerRuntimeAvailability.Unavailable
            is ApplicationBootstrapState.Ready ->
                RelayWorkerRuntimeAvailability.Ready(state.services.relayWorkerRuntime)
            is ApplicationBootstrapState.Unavailable -> RelayWorkerRuntimeAvailability.Unavailable
        }

    /** Handles an FCM wake directly; broker custody and the periodic drain recover an interrupted attempt. */
    internal fun processFcmWakeBlocking(messageId: String) {
        try {
            runBlocking(Dispatchers.IO) {
                when (val availability = relayWorkerRuntimeAvailability()) {
                    is RelayWorkerRuntimeAvailability.Ready ->
                        availability.runtime.fetchAndProcessExact(messageId)
                    RelayWorkerRuntimeAvailability.Unavailable -> Unit
                }
            }
        } catch (_: Exception) {
            // The broker still owns the unacknowledged message. The independent periodic drain retries it.
        }
    }

    private fun storageContainer() = StorageContainerFactory.get(
            context = this,
            dependencies = StorageContainerDependencies(
                ioDispatcher = Dispatchers.IO,
                clock = StorageClock(System::currentTimeMillis),
                payloadVault = AndroidOperationalPayloadVaultPort(
                    AndroidKeystoreProtectedPayloadVault(),
                    Dispatchers.IO,
                ),
                preferencesCutoverDefaults = OperationalPreferencesCutoverDefaults(
                    legacyDeviceName = android.os.Build.MODEL.takeIf(String::isNotBlank) ?: "Android",
                ),
                legacyV51Sources = LegacyV51StorageSources(
                    preferencesDataStore = dataStore,
                    coreFilesDirectory = filesDir.toPath(),
                    messageLedgerFile = getDatabasePath("message_ledger.db"),
                    runsFile = getDatabasePath("runs.db"),
                    sealHistoryFile = getDatabasePath("openpgp_signing.db"),
                    noBackupStagingDirectory = noBackupFilesDir,
                ),
                bootstrap = StorageBootstrapDependencies(SettingsRepository.DEFAULT_BROKER),
            ),
        )

    private suspend fun startRoomRuntime(): ApplicationBootstrapOutcome<ReadyServices> = try {
        val container = storageContainer()
        if (roomMigrationCompletion.isComplete()) {
            container.loadCompletedAuthority()
        } else {
            container.initialize()
            roomMigrationCompletion.markComplete()
        }
        ApplicationBootstrapOutcome.Ready(createReadyServices(container))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: StorageBootstrapFailure) {
        failure.toApplicationOutcome()
    }

    private suspend fun createReadyServices(container: net.extrawdw.apps.notisync.composition.storage.StorageContainer): ReadyServices {
        val graph = AppGraph(
            app = this,
            activityRepository = container.activityRepository,
            runRepository = container.runRepository,
            coreRepository = container.coreRepository,
            coreRuntime = container.loadCoreRuntime(),
            operationalDatabase = container.operationalDatabase,
            protectedPayloadProtector = container.protectedPayloadProtector,
            maintenanceGate = container.maintenanceGate,
            payloadKeyEnsurer = container.payloadKeyEnsurer,
        )
        val span = perfSpan("application_runtime_init")
        try {
            graph.init(span)
        } finally {
            span.stop()
        }
        return ReadyServices(
            graph = graph,
            activityRepository = container.activityRepository,
            relayWorkerRuntime = graph.brokerCustodyInbound.workerRuntime,
            fcmRouteRegistration = FcmRouteRegistration(graph::onFcmRegistered),
        )
    }

    private fun StorageBootstrapFailure.toApplicationOutcome(): ApplicationBootstrapOutcome.Unavailable =
        ApplicationBootstrapOutcome.Unavailable(
            ApplicationBootstrapFailure(
                kind = when (disposition) {
                    StorageBootstrapFailureDisposition.RETRYABLE -> ApplicationBootstrapFailureKind.RETRYABLE
                    StorageBootstrapFailureDisposition.USER_RECOVERABLE ->
                        ApplicationBootstrapFailureKind.USER_RECOVERABLE
                    StorageBootstrapFailureDisposition.SECURITY_BLOCKING ->
                        ApplicationBootstrapFailureKind.SECURITY_BLOCKING
                },
                code = errorCode,
            ),
        )

    companion object {
        const val GRAPH_INIT_TIMEOUT_MS = 30_000L
    }
}
