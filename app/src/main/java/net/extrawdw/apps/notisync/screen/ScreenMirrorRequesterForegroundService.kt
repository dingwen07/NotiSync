package net.extrawdw.apps.notisync.screen

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.notisync.protocol.ClientId

/**
 * Requester-side owner of one authenticated Android screen session.
 *
 * This service is promoted while the viewer Activity is still visible. The Activity and its PiP
 * window are replaceable render clients; dismissing either never tears down the authenticated
 * channels. The notification's Stop action (or the viewer's explicit close button) ends them.
 */
class ScreenMirrorRequesterForegroundService : Service() {
    private val scope = CoroutineScope(
        // Service commands, lease changes and StateFlow callbacks must be serialized. In
        // particular, cancelling source A and installing source B may not race an A coroutine
        // between its ownership check and activeAttemptId assignment.
        SupervisorJob() + Dispatchers.Main.immediate +
            crashGuard("ScreenMirrorRequesterForegroundService"),
    )
    private val ownership = ScreenMirrorRequesterForegroundOwnership()
    private var stateJob: Job? = null
    private var activeAttemptId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val current = ownership.noteCommand(startId)
            if (current == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
            val leaseId = intent.getStringExtra(EXTRA_LEASE_ID)
            if (sourceId == current.sourceId && leaseId == current.leaseId) {
                finishOwned(sourceId, leaseId)
            }
            return START_NOT_STICKY
        }

        val rawSourceId = intent?.getStringExtra(EXTRA_SOURCE_ID)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SOURCE_ID_LENGTH }
            ?: run {
                if (ownership.noteCommand(startId) == null) stopSelf(startId)
                return START_NOT_STICKY
            }
        val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_SOURCE_NAME_LENGTH)
            ?: ClientId(rawSourceId).shortForm()
        val requestedLeaseId = intent.getStringExtra(EXTRA_LEASE_ID)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_LEASE_ID_LENGTH }
            ?: run {
                if (ownership.noteCommand(startId) == null) stopSelf(startId)
                return START_NOT_STICKY
            }

        val startDecision = ownership.onStart(
            sourceId = rawSourceId,
            startId = startId,
            requestedLeaseId = requestedLeaseId,
        )
        val leaseId = startDecision.leaseId
        when (startDecision) {
            is ScreenMirrorRequesterForegroundOwnership.StartDecision.Duplicate -> {
                return START_NOT_STICKY
            }
            is ScreenMirrorRequesterForegroundOwnership.StartDecision.Transferred -> {
                // A replacement Activity for the same source takes over the foreground-service
                // commands and notification actions, but adopts the existing authenticated host
                // attempt. In particular, a late Close from the previous Activity lease must not
                // be able to disconnect the replacement.
                stateJob?.cancel()
            }
            is ScreenMirrorRequesterForegroundOwnership.StartDecision.Switched -> {
                // A user-initiated viewer for another source is an explicit switch, not an
                // indefinitely spinning second Activity. Retire the previous requester lease and
                // its authenticated channels. onStart() has already atomically installed the new
                // lease, so a late completion from the old StateFlow cannot finish the replacement.
                stateJob?.cancel()
                activeAttemptId?.let { attemptId ->
                    (application as? NotiSyncApp)?.graphIfReady?.screenMirrorRequesterHost
                        ?.stopIfAttempt(attemptId, "viewer switched to another source")
                }
                activeAttemptId = null
            }
            is ScreenMirrorRequesterForegroundOwnership.StartDecision.Acquired -> Unit
        }

        val foregroundStarted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(
                    rawSourceId,
                    leaseId,
                    sourceName,
                    connected = false,
                    surfaceAttached = false,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "Could not start requester foreground service", it) }.isSuccess
        if (!foregroundStarted) {
            finishOwned(rawSourceId, leaseId)
            return START_NOT_STICKY
        }
        ownership.markForegroundOwned(rawSourceId, leaseId)

        stateJob?.cancel()
        stateJob = scope.launch {
            val app = application as NotiSyncApp
            val graph = app.awaitGraphReady() ?: run {
                finishOwned(rawSourceId, leaseId)
                return@launch
            }
            val host = graph.screenMirrorRequesterHost ?: run {
                finishOwned(rawSourceId, leaseId)
                return@launch
            }
            val attemptId = runCatching { host.start(ClientId(rawSourceId)) }
                .onFailure { Log.e(TAG, "Could not start requester screen session", it) }
                .getOrElse {
                    finishOwned(rawSourceId, leaseId)
                    return@launch
                }
            if (!ownership.isOwnedBy(rawSourceId, leaseId)) {
                // A same-source Activity transfer deliberately retains this host attempt. A
                // different-source switch or an exact Stop still owns its teardown.
                if (!ownership.isSourceOwned(rawSourceId)) {
                    host.stopIfAttempt(attemptId, "requester foreground ownership was released")
                }
                return@launch
            }
            activeAttemptId = attemptId
            host.state
                .takeWhile { state ->
                    // Normal and explicit terminal transitions retain their exact attempt id. Keep
                    // bare IDLE as a defensive terminal for host shutdown before a snapshot exists.
                    val exactAttempt = state.attemptId == attemptId
                    val exactTerminal = exactAttempt &&
                        (
                            state.phase == AndroidScreenHostPhase.ENDED ||
                                state.phase == AndroidScreenHostPhase.ERROR
                        )
                    val bareIdle = state.phase == AndroidScreenHostPhase.IDLE &&
                        state.attemptId == null
                    val terminal = exactTerminal || bareIdle
                    val stillOwned = ownership.isOwnedBy(rawSourceId, leaseId) &&
                        activeAttemptId == attemptId
                    if (exactAttempt && !terminal && stillOwned) {
                        updateNotification(
                            sourceId = rawSourceId,
                            leaseId = leaseId,
                            sourceName = state.sourceName ?: sourceName,
                            connected = state.phase == AndroidScreenHostPhase.CONNECTED,
                            surfaceAttached = state.surfaceAttached,
                        )
                    }
                    if (terminal && stillOwned) {
                        finishOwned(rawSourceId, leaseId, expectedAttemptId = attemptId)
                    }
                    !terminal && stillOwned
                }
                .collect {}
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        val abandoned = ownership.abandon()
        val attemptId = activeAttemptId
        activeAttemptId = null
        if (abandoned != null && attemptId != null) {
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorRequesterHost
                ?.stopIfAttempt(attemptId, "requester foreground service stopped")
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun finishOwned(
        sourceId: String,
        leaseId: String,
        expectedAttemptId: String? = null,
    ) {
        if (expectedAttemptId != null && activeAttemptId != expectedAttemptId) return
        val finished = ownership.finish(sourceId, leaseId) ?: return
        val attemptId = activeAttemptId
        activeAttemptId = null
        if (attemptId != null) {
            (application as? NotiSyncApp)?.graphIfReady?.screenMirrorRequesterHost
                ?.stopIfAttempt(attemptId)
        }
        stopSelf(finished.latestStartId)
    }

    private fun updateNotification(
        sourceId: String,
        leaseId: String,
        sourceName: String,
        connected: Boolean,
        surfaceAttached: Boolean,
    ) {
        if (!ownership.isOwnedBy(sourceId, leaseId)) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(sourceId, leaseId, sourceName, connected, surfaceAttached),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildNotification(
        sourceId: String,
        leaseId: String,
        sourceName: String,
        connected: Boolean,
        surfaceAttached: Boolean,
    ): Notification {
        val channelId = ScreenMirrorNotificationChannels.ensureRequester(this)
        val stopIntent = PendingIntent.getService(
            this,
            ("requester-stop:$sourceId:$leaseId").hashCode(),
            Intent(this, ScreenMirrorRequesterForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SOURCE_ID, sourceId)
                putExtra(EXTRA_LEASE_ID, leaseId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            ("requester-open:$sourceId").hashCode(),
            AndroidScreenMirrorActivity.intent(this, ClientId(sourceId)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when {
            !connected -> getString(R.string.screen_viewer_service_connecting, sourceName)
            surfaceAttached -> getString(R.string.screen_viewer_service_viewing, sourceName)
            else -> getString(R.string.screen_viewer_service_background, sourceName)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(getString(R.string.screen_viewer_service_title))
            .setContentText(body)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.screen_mirror_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "ScreenRequesterFgs"
        private const val NOTIFICATION_ID = 0x4E54
        private const val ACTION_START = "net.extrawdw.apps.notisync.screen.REQUESTER_START"
        private const val ACTION_STOP = "net.extrawdw.apps.notisync.screen.REQUESTER_STOP"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_LEASE_ID = "lease_id"
        private const val MAX_SOURCE_ID_LENGTH = 128
        private const val MAX_SOURCE_NAME_LENGTH = 160
        private const val MAX_LEASE_ID_LENGTH = 128

        fun start(
            context: Context,
            sourceId: ClientId,
            sourceName: String,
            leaseId: String,
        ): Boolean = runCatching {
            require(leaseId.isNotBlank() && leaseId.length <= MAX_LEASE_ID_LENGTH) {
                "invalid requester foreground lease id"
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenMirrorRequesterForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_SOURCE_ID, sourceId.value)
                    putExtra(EXTRA_SOURCE_NAME, sourceName)
                    putExtra(EXTRA_LEASE_ID, leaseId)
                },
            )
        }.isSuccess

        /** Ends only the Activity/FGS generation identified by [sourceId] and [leaseId]. */
        fun stop(context: Context, sourceId: ClientId, leaseId: String): Boolean = runCatching {
            require(leaseId.isNotBlank() && leaseId.length <= MAX_LEASE_ID_LENGTH) {
                "invalid requester foreground lease id"
            }
            context.startService(
                Intent(context, ScreenMirrorRequesterForegroundService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SOURCE_ID, sourceId.value)
                    putExtra(EXTRA_LEASE_ID, leaseId)
                },
            )
        }.isSuccess
    }
}

/**
 * Exact per-generation foreground lease for the requester service.
 *
 * Replacing the lease is one atomic operation: callbacks and notification actions for the previous
 * Activity generation immediately lose authority. A replacement Activity for the same source
 * transfers only service ownership and adopts the existing host attempt; a different source is a
 * real session switch.
 */
internal class ScreenMirrorRequesterForegroundOwnership {
    sealed interface StartDecision {
        val leaseId: String

        data class Acquired(override val leaseId: String) : StartDecision
        data class Duplicate(override val leaseId: String) : StartDecision
        data class Transferred(
            val sourceId: String,
            val previousLeaseId: String,
            override val leaseId: String,
        ) : StartDecision
        data class Switched(
            val previousSourceId: String,
            val previousLeaseId: String,
            override val leaseId: String,
        ) : StartDecision
    }

    data class Lease(
        val sourceId: String,
        val leaseId: String,
        val latestStartId: Int,
        val foregroundOwned: Boolean,
    )

    private var lease: Lease? = null

    @Synchronized
    fun onStart(sourceId: String, startId: Int, requestedLeaseId: String): StartDecision {
        require(requestedLeaseId.isNotBlank()) { "requester foreground lease id is blank" }
        val current = lease
        if (current == null) {
            lease = Lease(sourceId, requestedLeaseId, startId, foregroundOwned = false)
            return StartDecision.Acquired(requestedLeaseId)
        }
        if (current.sourceId == sourceId && current.leaseId == requestedLeaseId) {
            lease = current.copy(latestStartId = maxOf(current.latestStartId, startId))
            return StartDecision.Duplicate(current.leaseId)
        }
        lease = Lease(sourceId, requestedLeaseId, startId, foregroundOwned = false)
        if (current.sourceId == sourceId) {
            return StartDecision.Transferred(sourceId, current.leaseId, requestedLeaseId)
        }
        return StartDecision.Switched(current.sourceId, current.leaseId, requestedLeaseId)
    }

    /** Absorb every delivered command so final stopSelf() covers Android's latest start id. */
    @Synchronized
    fun noteCommand(startId: Int): Lease? {
        val current = lease ?: return null
        return current.copy(latestStartId = maxOf(current.latestStartId, startId)).also {
            lease = it
        }
    }

    @Synchronized
    fun markForegroundOwned(sourceId: String, leaseId: String): Boolean {
        val current = lease?.takeIf {
            it.sourceId == sourceId && it.leaseId == leaseId
        } ?: return false
        lease = current.copy(foregroundOwned = true)
        return true
    }

    @Synchronized
    fun isOwnedBy(sourceId: String, leaseId: String): Boolean = lease?.let {
        it.sourceId == sourceId && it.leaseId == leaseId
    } == true

    @Synchronized
    fun isSourceOwned(sourceId: String): Boolean = lease?.sourceId == sourceId

    @Synchronized
    fun finish(sourceId: String, leaseId: String): Lease? {
        val current = lease?.takeIf {
            it.sourceId == sourceId && it.leaseId == leaseId
        } ?: return null
        lease = null
        return current
    }

    @Synchronized
    fun abandon(): Lease? = lease.also { lease = null }
}
