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
import kotlinx.coroutines.flow.combine
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
    private val repo get() = (application as NotiSyncApp).graph.iosDeviceRepo
    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(repo.status.value, repo.deviceName.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        observeStatus()
        runCatching { (application as NotiSyncApp).graph.ancsManager?.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { (application as NotiSyncApp).graph.ancsManager?.stop() }
        super.onDestroy()
    }

    /** Re-post the FGS notification (same id → in-place update) whenever the bridge status or iPhone name changes. */
    private fun observeStatus() {
        scope.launch {
            combine(repo.status, repo.deviceName) { status, name -> status to name }
                .distinctUntilChanged()
                .collect { (status, name) ->
                    notificationManager?.notify(NOTIF_ID, buildNotification(status, name))
                }
        }
    }

    private fun ensureChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.ancs_service_channel), NotificationManager.IMPORTANCE_LOW),
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

        /** Permissions the `connectedDevice` FGS needs to start without throwing on API 34+. Resume paths
         *  (cold start, AncsBootReceiver) must gate [start] on this — a missing-permission start throws. */
        fun hasPermissions(context: Context): Boolean =
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AncsBridgeService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, AncsBridgeService::class.java))
        }
    }
}
