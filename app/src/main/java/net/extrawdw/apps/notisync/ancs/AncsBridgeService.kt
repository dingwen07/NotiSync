package net.extrawdw.apps.notisync.ancs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R

/**
 * Foreground service (type `connectedDevice`) that hosts the ANCS bridge, so the BLE link to the iPhone
 * survives while the app is backgrounded. Started/stopped by the iOS tab's master switch (and, optionally,
 * by [AncsCompanionService] on device presence). The actual BLE work lives in [AncsBleManager]; this service
 * just owns its lifecycle and keeps the process alive.
 *
 * Its notification tracks the live bridge state ([IosDeviceRepository.status]) so the shade always reflects
 * what the bridge is doing (advertising / pairing / sharing / error), and is posted as the summary of its
 * own private notification group so the OS never auto-bundles this persistent entry with mirrored posts.
 */
class AncsBridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** Service-lifetime scope for the status collector; cancelled in [onDestroy]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [observeStatus]: repeated [onStartCommand] (multiple start sources, START_STICKY redelivery)
     *  must not stack duplicate collectors that each re-post the notification on every state change. */
    @Volatile
    private var collecting = false
    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasPermissions(this)) {
            (application as? NotiSyncApp)?.graphIfReady?.iosDeviceRepo?.setStatus(AncsStatus.ERROR)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ensureChannel()
        val app = application as NotiSyncApp
        val graph = app.graphIfReady
        val repo = graph?.iosDeviceRepo
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(
                    repo?.status?.value ?: AncsStatus.CONNECTING,
                    repo?.deviceName?.value
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!foregroundStarted) {
            repo?.setStatus(AncsStatus.ERROR)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (graph != null && repo != null) {
            startBridge(graph, repo)
        } else {
            scope.launch {
                val ready = app.awaitGraphReady() ?: run {
                    stopSelf(startId)
                    return@launch
                }
                startBridge(ready, ready.iosDeviceRepo)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { (application as? NotiSyncApp)?.graphIfReady?.ancsManager?.stop() }
        super.onDestroy()
    }

    private fun startBridge(graph: net.extrawdw.apps.notisync.AppGraph, repo: IosDeviceRepository) {
        if (!collecting) {
            collecting = true
            observeStatus(repo)
        }
        runCatching { graph.ancsManager?.start() }
    }

    /**
     * Re-post the FGS notification (same id → in-place update) whenever the bridge status or iPhone name
     * changes, throttled with a *leading* edge: the first change after a quiet period posts immediately —
     * so a notification the user dismissed (allowed for FGS on Android 13+) reappears at once on a state
     * change — while a burst of rapid transitions is coalesced to one post per [STATUS_NOTIFY_THROTTLE_MS].
     * That cap keeps us under the system's per-package notification enqueue-rate limit, which would
     * otherwise silently drop an update and, because the source is a conflated StateFlow that never
     * re-emits, leave the shade stuck on a stale state.
     *
     * The throttle is the [conflate] + trailing [delay] idiom: `collect` posts the value, then sleeps the
     * window; emissions arriving mid-sleep are conflated to the latest and posted next. So the leading post
     * is immediate (unlike `debounce`, which delays every post), the final value is never lost, and posts
     * stay ≥ a window apart (≤ ~4/sec) regardless of how fast the bridge churns.
     */
    private fun observeStatus(repo: IosDeviceRepository) {
        scope.launch {
            combine(repo.status, repo.deviceName) { status, name -> status to name }
                .distinctUntilChanged()
                .conflate()
                .collect { (status, name) ->
                    notificationManager?.notify(NOTIF_ID, buildNotification(status, name))
                    delay(STATUS_NOTIFY_THROTTLE_MS)
                }
        }
    }

    private fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ancs_service_channel),
                NotificationManager.IMPORTANCE_LOW
            ),
        )
    }

    private fun buildNotification(status: AncsStatus, deviceName: String?): Notification {
        // While sharing we know the iPhone's name — surface it as the title; otherwise a static title.
        val title = deviceName?.takeIf { status == AncsStatus.SHARING && it.isNotBlank() }
            ?: getString(R.string.ancs_service_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ancs_bridge)
            .setContentTitle(title)
            .setContentText(getString(statusText(status)))
            .setOngoing(true)
            .setShowWhen(false)
            // Private singleton group summary -> SystemUI keeps this FGS out of mirrored notification bundles.
            .setGroup(GROUP_ID)
            .setGroupSummary(true)
            .build()
    }

    @StringRes
    private fun statusText(status: AncsStatus): Int = when (status) {
        AncsStatus.OFF -> R.string.ios_status_off
        AncsStatus.UNSUPPORTED -> R.string.ios_status_unsupported
        AncsStatus.ADVERTISING -> R.string.ios_status_advertising
        AncsStatus.CONNECTING -> R.string.ios_status_connecting
        AncsStatus.NEEDS_PAIRING -> R.string.ios_status_needs_pairing
        AncsStatus.SHARING -> R.string.ios_status_sharing
        AncsStatus.NO_ANCS -> R.string.ios_status_no_ancs
        AncsStatus.ERROR -> R.string.ios_status_error
    }

    companion object {
        private const val CHANNEL_ID = "notisync.ancs"
        private const val GROUP_ID = "notisync.ancs.bridge"
        private const val NOTIF_ID = 0x4E43 // "NC" — Notification Consumer

        /** Leading-edge throttle window for status→notification reposts: the first change posts at once,
         *  then a burst is coalesced to one post per window — keeping us under the system's per-package
         *  notification enqueue-rate limit (default ~5/sec). */
        private const val STATUS_NOTIFY_THROTTLE_MS = 250L

        /** Permissions the `connectedDevice` FGS needs to start without throwing on API 34+. Resume paths
         *  (cold start, AncsBootReceiver) must gate [start] on this — a missing-permission start throws. */
        fun hasPermissions(context: Context): Boolean =
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun start(context: Context) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, AncsBridgeService::class.java)
            )

        fun stop(context: Context) {
            context.stopService(Intent(context, AncsBridgeService::class.java))
        }
    }
}
