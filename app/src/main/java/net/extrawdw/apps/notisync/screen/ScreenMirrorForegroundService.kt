package net.extrawdw.apps.notisync.screen

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.analytics.crashGuard

/** Normal-process connectedDevice FGS owning one authenticated LAN screen session. */
class ScreenMirrorForegroundService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + crashGuard("ScreenMirrorForegroundService"),
    )
    private val ownership = ScreenMirrorForegroundOwnership()
    private var connectedLease: ScreenMirrorForegroundLeaseKey? = null

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val key = intent.foregroundLeaseKey()
            if (!ownership.noteCommand(startId)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (key == null || !ownership.isOwnedBy(key)) return START_NOT_STICKY
            // This only claims/cancels the exact controller lease. Privileged Binder teardown is
            // acknowledged on the controller's IO cleanup lane, so Service main never waits for
            // it. Keep foreground ownership (and its disclosure) until that acknowledgement calls
            // release(); only an unmatched/stale command can be finished immediately here.
            val accepted = (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController
                ?.stopForegroundSession(key.sessionId, key.leaseId) == true
            if (!accepted) finishOwnedSession(key)
            return START_NOT_STICKY
        }
        val requestedLease = intent?.foregroundLeaseKey()
        val controllerLabel = ScreenControllerLabel.fromIntent(
            intent?.getStringExtra(EXTRA_CONTROLLER_LABEL),
        )
        val controller = (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController
        if (
            requestedLease == null || controllerLabel == null || controller == null ||
            !controller.isForegroundStartCurrent(
                requestedLease.sessionId,
                requestedLease.leaseId,
            )
        ) {
            // Android assigns a new startId to every delivered command. Absorb the command into an
            // existing owner so stopSelf(startId) cannot tear it down, but never let an invalid or
            // stale START replace that owner.
            if (!ownership.noteCommand(startId)) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (ownership.onStart(requestedLease, startId)) {
            ScreenMirrorForegroundOwnership.StartDecision.DUPLICATE -> return START_NOT_STICKY
            ScreenMirrorForegroundOwnership.StartDecision.SWITCHED -> connectedLease = null
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED -> Unit
        }
        val channelId = ScreenMirrorNotificationChannels.ensureSourceConnecting(this)
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(
                    lease = requestedLease,
                    controllerLabel = controllerLabel,
                    channelId = channelId,
                    connected = false,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!foregroundStarted) {
            controller.onForegroundStartFailed(
                requestedLease.sessionId,
                requestedLease.leaseId,
            )
            finishOwnedSession(requestedLease)
            return START_NOT_STICKY
        }
        ownership.markForegroundOwned(requestedLease)

        scope.launch {
            if (
                !controller.runPendingSession(
                    requestedLease.sessionId,
                    requestedLease.leaseId,
                ) {
                    release(
                        this@ScreenMirrorForegroundService,
                        requestedLease.sessionId,
                        requestedLease.leaseId,
                    )
                }
            ) {
                val accepted = controller.stopForegroundSession(
                    requestedLease.sessionId,
                    requestedLease.leaseId,
                )
                // An accepted exact lease owns asynchronous teardown and releases this FGS only
                // after the privileged backend acknowledges stop. A stale start has no such owner.
                if (!accepted) {
                    release(
                        this@ScreenMirrorForegroundService,
                        requestedLease.sessionId,
                        requestedLease.leaseId,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeService?.get() === this) activeService = null
        connectedLease = null
        val abandoned = ownership.abandon()
        if (abandoned?.foregroundOwned == true) {
            // onDestroy is a main-thread callback; controller stop must remain non-blocking here.
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController
                ?.stopForegroundSession(
                    abandoned.key.sessionId,
                    abandoned.key.leaseId,
                )
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun finishOwnedSession(key: ScreenMirrorForegroundLeaseKey) {
        val finished = ownership.finish(key) ?: return
        if (connectedLease == key) connectedLease = null
        // Use the newest startId observed while this session owned the Service. If another session
        // is delivered after finish() releases the lease, Android assigns it a newer startId and
        // this stop becomes a no-op instead of tearing the replacement down.
        stopSelf(finished.latestStartId)
    }

    private fun markConnectedIfOwned(
        key: ScreenMirrorForegroundLeaseKey,
        controllerLabel: String,
    ) {
        val controller = (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController ?: return
        controller.runIfForegroundLeaseCurrent(key.sessionId, key.leaseId) {
            if (!ownership.isOwnedBy(key) || connectedLease == key) return@runIfForegroundLeaseCurrent
            val channelId = ScreenMirrorNotificationChannels.ensureSource(this)
            val updated = runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(
                        lease = key,
                        controllerLabel = controllerLabel,
                        channelId = channelId,
                        connected = true,
                    ),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }.isSuccess
            if (updated && ownership.isOwnedBy(key)) connectedLease = key
        }
    }

    private fun buildNotification(
        lease: ScreenMirrorForegroundLeaseKey,
        controllerLabel: String,
        channelId: String,
        connected: Boolean,
    ): Notification {
        val stop = PendingIntent.getService(
            this,
            ("stop:${lease.sessionId}:${lease.leaseId}").hashCode(),
            Intent(this, ScreenMirrorForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SESSION_ID, lease.sessionId)
                putExtra(EXTRA_LEASE_ID, lease.leaseId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_screen_share)
            .setContentTitle(
                getString(
                    if (connected) {
                        R.string.screen_mirror_service_title
                    } else {
                        R.string.screen_mirror_service_connecting_title
                    },
                ),
            )
            .setContentText(
                getString(
                    if (connected) {
                        R.string.screen_mirror_service_body
                    } else {
                        R.string.screen_mirror_service_connecting_body
                    },
                    controllerLabel,
                ),
            )
            .setContentIntent(open)
            .setOngoing(true)
            // The first notification is deliberately quiet. The channel transition at READY is
            // the one update that must be allowed to alert.
            .setOnlyAlertOnce(!connected)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(
                if (connected) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW,
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.screen_mirror_stop), stop)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4E53 // "NS"
        private const val ACTION_START = "net.extrawdw.apps.notisync.screen.START"
        private const val ACTION_STOP = "net.extrawdw.apps.notisync.screen.STOP"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_LEASE_ID = "lease_id"
        private const val EXTRA_CONTROLLER_LABEL = "controller_label"

        @Volatile
        private var activeService: WeakReference<ScreenMirrorForegroundService>? = null

        fun start(
            context: Context,
            sessionId: String,
            leaseId: String,
            controllerLabel: String,
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenMirrorForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_LEASE_ID, leaseId)
                    putExtra(EXTRA_CONTROLLER_LABEL, ScreenControllerLabel.fromIntent(controllerLabel))
                },
            )
        }

        fun pendingIntent(
            context: Context,
            sessionId: String,
            leaseId: String,
            controllerLabel: String,
        ): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                ("screen:$sessionId:$leaseId").hashCode(),
                Intent(context, ScreenMirrorForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_LEASE_ID, leaseId)
                    putExtra(EXTRA_CONTROLLER_LABEL, ScreenControllerLabel.fromIntent(controllerLabel))
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Promotes the quiet preparing notification only for the exact FGS-owned ready session. */
        fun markConnected(
            context: Context,
            sessionId: String,
            leaseId: String,
            controllerLabel: String,
        ) {
            val safeLabel = ScreenControllerLabel.fromIntent(controllerLabel) ?: return
            val key = ScreenMirrorForegroundLeaseKey.create(sessionId, leaseId) ?: return
            ContextCompat.getMainExecutor(context).execute {
                activeService?.get()?.markConnectedIfOwned(key, safeLabel)
            }
        }

        /** Releases only the exact local FGS lease; stale completion cannot stop its successor. */
        fun release(context: Context, sessionId: String, leaseId: String) {
            val key = ScreenMirrorForegroundLeaseKey.create(sessionId, leaseId) ?: return
            ContextCompat.getMainExecutor(context).execute {
                activeService?.get()?.finishOwnedSession(key)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorForegroundService::class.java))
        }
    }

    private fun Intent.foregroundLeaseKey(): ScreenMirrorForegroundLeaseKey? =
        ScreenMirrorForegroundLeaseKey.create(
            getStringExtra(EXTRA_SESSION_ID),
            getStringExtra(EXTRA_LEASE_ID),
        )
}

internal data class ScreenMirrorForegroundLeaseKey(
    val sessionId: String,
    val leaseId: String,
) {
    companion object {
        fun create(sessionId: String?, leaseId: String?): ScreenMirrorForegroundLeaseKey? {
            val safeSessionId = sessionId?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: return null
            val safeLeaseId = leaseId?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: return null
            return ScreenMirrorForegroundLeaseKey(safeSessionId, safeLeaseId)
        }
    }
}

internal class ScreenMirrorForegroundOwnership {
    enum class StartDecision { ACQUIRED, DUPLICATE, SWITCHED }

    data class Lease(
        val key: ScreenMirrorForegroundLeaseKey,
        val latestStartId: Int,
        val foregroundOwned: Boolean,
    )

    private var lease: Lease? = null

    @Synchronized
    fun onStart(key: ScreenMirrorForegroundLeaseKey, startId: Int): StartDecision {
        val current = lease
        if (current == null) {
            lease = Lease(key, startId, foregroundOwned = false)
            return StartDecision.ACQUIRED
        }
        if (current.key == key) {
            lease = current.copy(latestStartId = maxOf(current.latestStartId, startId))
            return StartDecision.DUPLICATE
        }
        // The caller has already asked the controller whether this exact local lease is current.
        // Replacing the key here makes controller-authorized handoff atomic from the Service's
        // perspective: completion from the previous lease becomes a harmless no-op.
        lease = Lease(key, startId, foregroundOwned = false)
        return StartDecision.SWITCHED
    }

    /** Records a delivered command without ever changing ownership. */
    @Synchronized
    fun noteCommand(startId: Int): Boolean {
        val current = lease ?: return false
        lease = current.copy(latestStartId = maxOf(current.latestStartId, startId))
        return true
    }

    @Synchronized
    fun markForegroundOwned(key: ScreenMirrorForegroundLeaseKey): Boolean {
        val current = lease?.takeIf { it.key == key } ?: return false
        lease = current.copy(foregroundOwned = true)
        return true
    }

    @Synchronized
    fun isOwnedBy(key: ScreenMirrorForegroundLeaseKey): Boolean = lease?.key == key

    @Synchronized
    fun finish(key: ScreenMirrorForegroundLeaseKey): Lease? {
        val current = lease?.takeIf { it.key == key } ?: return null
        lease = null
        return current
    }

    @Synchronized
    fun abandon(): Lease? = lease.also { lease = null }
}
