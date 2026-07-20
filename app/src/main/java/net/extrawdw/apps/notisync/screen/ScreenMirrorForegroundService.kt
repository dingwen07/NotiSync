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
    private var connectedSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!ownership.noteCommand(startId)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val id = intent.getStringExtra(EXTRA_SESSION_ID)
            if (id == null || !ownership.isOwnedBy(id)) return START_NOT_STICKY
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController?.stop(id)
            finishOwnedSession(id)
            return START_NOT_STICKY
        }
        val requestedId = intent?.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: run {
                // A malformed/stale command must not use its (now latest) startId to stop an
                // unrelated active foreground session.
                if (!ownership.noteCommand(startId)) stopSelf(startId)
                return START_NOT_STICKY
            }
        when (ownership.onStart(requestedId, startId)) {
            ScreenMirrorForegroundOwnership.StartDecision.DUPLICATE,
            ScreenMirrorForegroundOwnership.StartDecision.CONFLICT,
            -> {
                // Duplicate notification taps/START redelivery are idempotent. A stale intent for
                // a different session must never replace or tear down the active session.
                return START_NOT_STICKY
            }
            ScreenMirrorForegroundOwnership.StartDecision.ACQUIRED -> Unit
        }
        val controllerLabel = ScreenControllerLabel.fromIntent(intent.getStringExtra(EXTRA_CONTROLLER_LABEL))
            ?: run {
                finishOwnedSession(requestedId)
                return START_NOT_STICKY
            }
        val channelId = ScreenMirrorNotificationChannels.ensureSourceConnecting(this)
        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(
                    id = requestedId,
                    controllerLabel = controllerLabel,
                    channelId = channelId,
                    connected = false,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (!foregroundStarted) {
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController
                ?.onForegroundStartFailed(requestedId)
            finishOwnedSession(requestedId)
            return START_NOT_STICKY
        }
        ownership.markForegroundOwned(requestedId)

        val app = application as NotiSyncApp
        scope.launch {
            val graph = app.awaitGraphReady() ?: run {
                finishOwnedSession(requestedId)
                return@launch
            }
            val controller = graph.screenMirrorController ?: run {
                finishOwnedSession(requestedId)
                return@launch
            }
            if (!controller.runPendingSession(requestedId) { finishOwnedSession(requestedId) }) {
                controller.stop(requestedId)
                finishOwnedSession(requestedId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeService?.get() === this) activeService = null
        connectedSessionId = null
        val abandoned = ownership.abandon()
        if (abandoned?.foregroundOwned == true) {
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorController
                ?.stop(abandoned.sessionId)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun finishOwnedSession(sessionId: String) {
        val finished = ownership.finish(sessionId) ?: return
        // Use the newest startId observed while this session owned the Service. If another session
        // is delivered after finish() releases the lease, Android assigns it a newer startId and
        // this stop becomes a no-op instead of tearing the replacement down.
        stopSelf(finished.latestStartId)
    }

    private fun markConnectedIfOwned(sessionId: String, controllerLabel: String) {
        if (!ownership.isOwnedBy(sessionId) || connectedSessionId == sessionId) return
        val channelId = ScreenMirrorNotificationChannels.ensureSource(this)
        val updated = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(
                    id = sessionId,
                    controllerLabel = controllerLabel,
                    channelId = channelId,
                    connected = true,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.isSuccess
        if (updated) connectedSessionId = sessionId
    }

    private fun buildNotification(
        id: String,
        controllerLabel: String,
        channelId: String,
        connected: Boolean,
    ): Notification {
        val stop = PendingIntent.getService(
            this,
            ("stop:$id").hashCode(),
            Intent(this, ScreenMirrorForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SESSION_ID, id)
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
            .setSmallIcon(R.drawable.ic_notisync_mirror)
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
        private const val EXTRA_CONTROLLER_LABEL = "controller_label"

        @Volatile
        private var activeService: WeakReference<ScreenMirrorForegroundService>? = null

        fun start(context: Context, sessionId: String, controllerLabel: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenMirrorForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_CONTROLLER_LABEL, ScreenControllerLabel.fromIntent(controllerLabel))
                },
            )
        }

        fun pendingIntent(context: Context, sessionId: String, controllerLabel: String): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                ("screen:$sessionId").hashCode(),
                Intent(context, ScreenMirrorForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_CONTROLLER_LABEL, ScreenControllerLabel.fromIntent(controllerLabel))
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Promotes the quiet preparing notification only for the exact FGS-owned ready session. */
        fun markConnected(context: Context, sessionId: String, controllerLabel: String) {
            val safeLabel = ScreenControllerLabel.fromIntent(controllerLabel) ?: return
            ContextCompat.getMainExecutor(context).execute {
                activeService?.get()?.markConnectedIfOwned(sessionId, safeLabel)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorForegroundService::class.java))
        }
    }
}

internal class ScreenMirrorForegroundOwnership {
    enum class StartDecision { ACQUIRED, DUPLICATE, CONFLICT }

    data class Lease(
        val sessionId: String,
        val latestStartId: Int,
        val foregroundOwned: Boolean,
    )

    private var lease: Lease? = null

    @Synchronized
    fun onStart(sessionId: String, startId: Int): StartDecision {
        val current = lease
        if (current == null) {
            lease = Lease(sessionId, startId, foregroundOwned = false)
            return StartDecision.ACQUIRED
        }
        // Every delivered command advances Service's latest startId, including a stale command.
        // The eventual owner cleanup must cover that command without using unscoped stopSelf().
        lease = current.copy(latestStartId = maxOf(current.latestStartId, startId))
        return if (current.sessionId == sessionId) StartDecision.DUPLICATE else StartDecision.CONFLICT
    }

    /** Records a delivered non-START or malformed command; true means an owner absorbed it. */
    @Synchronized
    fun noteCommand(startId: Int): Boolean {
        val current = lease ?: return false
        lease = current.copy(latestStartId = maxOf(current.latestStartId, startId))
        return true
    }

    @Synchronized
    fun markForegroundOwned(sessionId: String): Boolean {
        val current = lease?.takeIf { it.sessionId == sessionId } ?: return false
        lease = current.copy(foregroundOwned = true)
        return true
    }

    @Synchronized
    fun isOwnedBy(sessionId: String): Boolean = lease?.sessionId == sessionId

    @Synchronized
    fun finish(sessionId: String): Lease? {
        val current = lease?.takeIf { it.sessionId == sessionId } ?: return null
        lease = null
        return current
    }

    @Synchronized
    fun abandon(): Lease? = lease.also { lease = null }
}
