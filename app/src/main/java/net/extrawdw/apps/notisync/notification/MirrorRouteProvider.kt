package net.extrawdw.apps.notisync.notification

import android.content.Context
import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.protocol.ClientId
import java.util.concurrent.Executor

/**
 * Names the SOURCE device on a mirrored media card's output chip ("Dingwen's iPhone") instead of the
 * default — this phone's own audio route ("Phone speaker"), which claims a playback that is actually
 * happening on the source device.
 *
 * SystemUI only swaps the chip for a named remote target when BOTH hold:
 *  1. the card's media session reports PLAYBACK_TYPE_REMOTE, and
 *  2. that session matches a live MediaRouter2 routing session of this package — matched by the session's
 *     `VolumeProvider.volumeControlId` equalling the routing session's provider-chosen id.
 *
 * [MirrorMediaSessions] supplies (1) and stamps [volumeControlIdFor] into every mirror session; this class
 * supplies (2): it publishes ONE MediaRoute2 route named after the elected source device — served by the
 * in-process [MirrorRouteProviderService] — and transfers this app's own media routing onto it, creating
 * the matching routing session. The Output Switcher then shows the source (smartphone icon, its name) as
 * the active output, with a fixed volume (no misleading slider for this phone's media stream).
 *
 * Deliberate non-goals:
 *  - Hiding the phone's own routes: SystemUI always appends system routes to the switcher so the user is
 *    never stranded (a RouteListingPreference cannot remove them either); they merely demote to transfer
 *    targets below the truthfully-named active device. Tapping one just releases our routing session —
 *    no audio moves, the mirror plays none — and the next media update re-elects the source route, so the
 *    chip self-heals instead of fighting the user's tap in real time.
 *  - Naming several sources at once: an app holds one routing session, so when multiple sources play
 *    simultaneously only [chooseTarget]'s pick gets the named chip (the rest degrade to the system's
 *    disabled "Other device" chip — still truer than "this phone"). All cards of the SAME source share
 *    the one session and are all named correctly.
 *
 * The route is feature-gated by [FEATURE_MIRROR_MEDIA] and visibility-restricted to this package, so it
 * never surfaces in another app's picker (SystemUI reads routes as a MANAGER, which visibility does not
 * filter). Everything runs on the main thread — [MirrorMediaSessions] already calls from it, and
 * MediaRoute2ProviderService delivers its callbacks there.
 */
class MirrorRouter(context: Context) {

    /** A mirrored media source with a live media card. */
    data class Source(val clientId: ClientId, val name: String, val playing: Boolean)

    /** The single route the provider should have published right now. */
    data class DesiredRoute(val routeId: String, val sessionId: String, val name: String, val clientKey: String)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> onMain { r.run() } }

    /** tag -> source, least recently updated first (re-inserted on every update). Main thread only. */
    private val actives = LinkedHashMap<String, Source>()

    private var service: MirrorRouteProviderService? = null
    private var router: MediaRouter2? = null
    private var routeCallback: MediaRouter2.RouteCallback? = null
    private var transferCallback: MediaRouter2.TransferCallback? = null

    /** The source elected to hold the (single) named routing session. */
    private var target: Source? = null

    /** Source key confirmed selected by the last onTransfer / being transferred to right now. */
    private var routedClientKey: String? = null
    private var transferInFlightKey: String? = null

    init {
        shared = this
    }

    /** A media card for [tag] rendered/updated: record its source and reconcile the route + routing. */
    fun activate(tag: String, clientId: ClientId, deviceName: String?, playing: Boolean) {
        val name = deviceName?.takeIf { it.isNotBlank() } ?: return // unnamed source -> leave routing alone
        onMain {
            actives.remove(tag)
            actives[tag] = Source(clientId, name, playing) // re-insert so map order tracks recency
            reconcile()
        }
    }

    /** The media card for [tag] is gone: forget it and re-target or tear down the routing. */
    fun deactivate(tag: String) {
        onMain { if (actives.remove(tag) != null) reconcile() }
    }

    /** The system (re)bound the in-process provider: hand it the route it should be publishing. */
    fun attachService(s: MirrorRouteProviderService) {
        onMain {
            if (s.destroyed) return@onMain
            service = s
            s.publish(target?.let(::desiredRouteFor))
        }
    }

    fun detachService(s: MirrorRouteProviderService) {
        onMain { if (service === s) service = null }
    }

    private fun reconcile() {
        target = chooseTarget(target, actives.values.toList())
        val t = target
        if (t == null) {
            teardown()
            return
        }
        ensureRouterRegistered()
        service?.publish(desiredRouteFor(t))
        maybeTransfer()
    }

    /** Transfer our routing onto the target's route once it is visible; idempotent. */
    private fun maybeTransfer() {
        val want = target?.clientId?.value ?: return
        if (routedClientKey == want || transferInFlightKey == want) return
        val r = router ?: return
        val route = r.routes.firstOrNull { clientKeyOf(it) == want } ?: return // not published yet
        transferInFlightKey = want
        runCatching { r.transferTo(route) }.onFailure {
            transferInFlightKey = null
            Log.w(TAG, "transferTo failed", it)
        }
    }

    private fun ensureRouterRegistered() {
        if (routeCallback != null) return
        val r = MediaRouter2.getInstance(appContext)
        val rc = object : MediaRouter2.RouteCallback() {
            override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
                maybeTransfer() // our just-published route becoming visible is the transfer trigger
            }
        }
        val tc = object : MediaRouter2.TransferCallback() {
            override fun onTransfer(
                oldController: MediaRouter2.RoutingController,
                newController: MediaRouter2.RoutingController,
            ) {
                // Null when the user moved output to a system route ("This phone"): record it and let the
                // NEXT media update re-elect the source route, rather than overriding the tap right away.
                routedClientKey = newController.selectedRoutes.firstNotNullOfOrNull { clientKeyOf(it) }
                if (transferInFlightKey == routedClientKey) transferInFlightKey = null
                maybeTransfer() // course-correct if the target moved while this transfer was in flight
            }

            override fun onTransferFailure(requestedRoute: MediaRoute2Info) {
                if (transferInFlightKey == clientKeyOf(requestedRoute)) transferInFlightKey = null
                Log.w(TAG, "route transfer rejected for ${requestedRoute.name}")
            }

            override fun onStop(controller: MediaRouter2.RoutingController) {
                routedClientKey = null
            }
        }
        r.registerRouteCallback(
            mainExecutor, rc,
            RouteDiscoveryPreference.Builder(listOf(FEATURE_MIRROR_MEDIA), /* activeScan = */ false).build(),
        )
        r.registerTransferCallback(mainExecutor, tc)
        router = r
        routeCallback = rc
        transferCallback = tc
    }

    /** No active media cards remain: release the session, unpublish the route, drop the router client. */
    private fun teardown() {
        service?.publish(null)
        router?.let { r ->
            runCatching { r.stop() }
            transferCallback?.let { runCatching { r.unregisterTransferCallback(it) } }
            routeCallback?.let { runCatching { r.unregisterRouteCallback(it) } }
        }
        router = null
        routeCallback = null
        transferCallback = null
        routedClientKey = null
        transferInFlightKey = null
    }

    private fun desiredRouteFor(s: Source) =
        DesiredRoute(ROUTE_ID_PREFIX + s.clientId.value, sessionIdFor(s.clientId), s.name, s.clientId.value)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    companion object {
        private const val TAG = "MirrorRouter"

        /** Route feature only this app ever requests — keeps the route out of all other apps' discovery. */
        internal const val FEATURE_MIRROR_MEDIA = "net.extrawdw.apps.notisync.feature.MIRROR_MEDIA"

        /** Route extra carrying the source's ClientId: the system prefixes provider route ids with its own
         *  namespace on the router side, so the raw route id can't be compared there. */
        internal const val EXTRA_CLIENT_KEY = "net.extrawdw.apps.notisync.extra.CLIENT_KEY"

        private const val ROUTE_ID_PREFIX = "notisync_mirror_route_"
        private const val SESSION_ID_PREFIX = "notisync_mirror_session_"

        /** The instance built by AppGraph; how the system-instantiated service finds its state. */
        @Volatile
        internal var shared: MirrorRouter? = null

        private fun sessionIdFor(clientId: ClientId) = SESSION_ID_PREFIX + clientId.value

        /** What [MirrorMediaSessions] must stamp into a mirror session's VolumeProvider so SystemUI can
         *  match the session to this source's routing session (equality with the session's chosen id). */
        fun volumeControlIdFor(clientId: ClientId): String = sessionIdFor(clientId)

        internal fun clientKeyOf(route: MediaRoute2Info): String? = route.extras?.getString(EXTRA_CLIENT_KEY)

        /**
         * Elect which source holds the named routing session. Sticky: the current holder keeps it while it
         * still has a card and is playing; otherwise the most recently updated playing source, then the
         * (paused) current holder, then the most recently updated source. Stability wins over freshness so
         * two concurrently playing sources don't flip the chip name on every alternating update.
         */
        internal fun chooseTarget(current: Source?, actives: List<Source>): Source? {
            val currentLive = current?.let { c -> actives.lastOrNull { it.clientId == c.clientId } }
            if (currentLive?.playing == true) return currentLive
            return actives.lastOrNull { it.playing } ?: currentLive ?: actives.lastOrNull()
        }
    }
}

/**
 * The in-process MediaRoute2 provider behind [MirrorRouter]. Instantiated and bound BY THE SYSTEM (the
 * binding is triggered by MirrorRouter's own route discovery, or an Output Switcher scan), so it finds its
 * state through the app graph once that is up. Publishes at most one route — the elected mirrored source —
 * answers this app's self-transfer with the routing session SystemUI matches the media session against,
 * and rejects everything else. Fixed volume throughout: the mirror emits no audio and relays no volume.
 * All callbacks arrive on the main thread.
 */
class MirrorRouteProviderService : MediaRoute2ProviderService() {

    /** The route [MirrorRouter] currently wants live (null = none). Main thread only. */
    private var current: MirrorRouter.DesiredRoute? = null

    /** Set in onDestroy (main thread); checked by [MirrorRouter.attachService]'s main-thread hop so a
     *  late graph-ready attach can't resurrect a dead instance. */
    var destroyed = false
        private set

    override fun onCreate() {
        super.onCreate()
        // The graph builds off-main at cold start and this service can be bound before it's ready (e.g. by
        // an Output Switcher scan for some other app). Attach whenever it lands; publish() pushes state.
        (application as? NotiSyncApp)?.runWhenGraphReady { graph -> graph.mirrorRouter?.attachService(this) }
    }

    override fun onDestroy() {
        destroyed = true
        MirrorRouter.shared?.detachService(this)
        super.onDestroy()
    }

    /** Reflect [route] to the system: publish/rename/withdraw the route and keep sessions consistent. */
    fun publish(route: MirrorRouter.DesiredRoute?) {
        if (destroyed) return
        current = route
        getAllSessionInfo().forEach { session ->
            if (route == null || session.id != route.sessionId) {
                // Source switched or media gone: drop the stale session. The app side re-transfers onto
                // the replacement route once it lands (or has already stopped routing entirely).
                notifySessionReleased(session.id)
            } else if (session.name?.toString() != route.name) {
                // Same source, renamed device: rename in place — no session churn, no re-transfer.
                notifySessionUpdated(RoutingSessionInfo.Builder(session).setName(route.name).build())
            }
        }
        notifyRoutes(listOfNotNull(route?.let(::routeInfoFor)))
    }

    override fun onCreateSession(
        requestId: Long,
        clientPackageName: String,
        routeId: String,
        sessionHints: Bundle?,
    ) {
        val route = current
        // Only this app's own self-transfer may land on the mirror route.
        if (route == null || route.routeId != routeId || clientPackageName != packageName) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        // One session at a time: a leftover predecessor (source switch) dies with the new election.
        getAllSessionInfo().forEach { notifySessionReleased(it.id) }
        notifySessionCreated(
            requestId,
            RoutingSessionInfo.Builder(route.sessionId, clientPackageName)
                .setName(route.name)
                .addSelectedRoute(routeId)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
                .build(),
        )
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) {
        if (getSessionInfo(sessionId) != null) notifySessionReleased(sessionId)
    }

    // Single-route sessions: there is nothing to (de)select or transfer to within a session.
    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) =
        notifyRequestFailed(requestId, REASON_REJECTED)

    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) =
        notifyRequestFailed(requestId, REASON_REJECTED)

    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) =
        notifyRequestFailed(requestId, REASON_REJECTED)

    // Fixed volume: the mirror plays nothing and doesn't relay volume (yet).
    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) = Unit

    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) = Unit

    private fun routeInfoFor(route: MirrorRouter.DesiredRoute): MediaRoute2Info =
        MediaRoute2Info.Builder(route.routeId, route.name)
            .addFeature(MirrorRouter.FEATURE_MIRROR_MEDIA)
            .setType(MediaRoute2Info.TYPE_REMOTE_SMARTPHONE)
            .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
            // Belt and braces with the custom feature: invisible to every other app's router. SystemUI
            // reads routes as a MANAGER, which visibility does not filter.
            .setVisibilityRestricted(setOf(packageName))
            .setExtras(Bundle().apply { putString(MirrorRouter.EXTRA_CLIENT_KEY, route.clientKey) })
            .build()
}
