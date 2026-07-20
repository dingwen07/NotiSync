package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.Urgency

enum class AndroidScreenSessionState { IDLE, PENDING, CONNECTING, ACTIVE, STOPPING }

/**
 * Authenticated screen transport. It must finish TLS authentication and the encrypted bootstrap before
 * calling [startCapture], then proxy video/control until either side closes. The privileged process never
 * receives a socket, endpoint, token, PSK, or peer identity.
 */
fun interface AndroidScreenSessionTransport {
    suspend fun run(
        request: ScreenMirrorSync,
        startCapture: suspend () -> PrivilegedSessionPipes,
        onReady: () -> Unit,
    )
}

/** Fail-closed adapter used only when the shared TLS component cannot be initialized. */
object UnavailableAndroidScreenSessionTransport : AndroidScreenSessionTransport {
    override suspend fun run(
        request: ScreenMirrorSync,
        startCapture: suspend () -> PrivilegedSessionPipes,
        onReady: () -> Unit,
    ): Nothing = error("authenticated screen transport unavailable")
}

class ScreenCaptureStartException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ScreenRequestExpiredException : Exception("screen request expired")

/** Android source-side request validation, replay protection, authorization, and one-session lifecycle. */
class ScreenMirrorSessionController(
    private val context: Context,
    private val ownClientId: ClientId,
    private val channel: SecureChannel,
    private val settings: SettingsRepository,
    private val authorizations: ScreenMirrorAuthorizationStore,
    private val capabilities: ScreenMirrorCapabilityProvider,
    private val shizuku: ScreenMirrorShizukuManager,
    private val scope: CoroutineScope,
    private val transport: AndroidScreenSessionTransport = UnavailableAndroidScreenSessionTransport,
    private val peerName: (ClientId) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class PendingRequest(
        val request: ScreenMirrorSync,
        var stopRequested: Boolean = false,
    ) : Closeable {
        override fun close() {
            request.routingToken?.fill(0)
            request.masterPsk?.fill(0)
        }
    }

    private val lock = Any()
    private var pending: PendingRequest? = null
    private var sessionJob: Job? = null
    private var expiryJob: Job? = null
    private val _state = MutableStateFlow(AndroidScreenSessionState.IDLE)
    val state: StateFlow<AndroidScreenSessionState> = _state.asStateFlow()

    /** Called inline by FoundationEngine after envelope authentication and the own-device policy gate. */
    fun onScreenMirrorSync(message: InboundMessage, sync: DataSync) {
        val request = sync.screenMirror ?: return
        when (request.action) {
            ScreenMirrorAction.REQUEST -> receiveRequest(message, request)
            ScreenMirrorAction.CANCEL, ScreenMirrorAction.END -> receiveStop(message, request)
            ScreenMirrorAction.STATUS -> Unit // Android is the source in MVP; requester status is informational.
        }
    }

    private fun receiveRequest(message: InboundMessage, request: ScreenMirrorSync) {
        val enabled = runCatching { kotlinx.coroutines.runBlocking { settings.screenMirroringEnabledNow() } }
            .getOrDefault(false)
        val failure = ScreenMirrorRequestValidator.validate(
            request = request,
            authenticatedSender = message.senderId,
            ownClientId = ownClientId,
            envelopeCreatedAt = message.createdAt,
            now = now(),
            authorized = enabled && message.senderOwnDevice && authorizations.isAuthorized(message.senderId),
            codecAvailable = request.codec?.let(capabilities::supports) == true,
        )
        if (failure != null) {
            // Never let a malformed signed body redirect a response to a third peer.
            val responseRequest = if (request.requesterPeerId == message.senderId) {
                request
            } else {
                request.copy(requesterPeerId = message.senderId)
            }
            rejectRequest(request, responseRequest, failure.status, failure.detail)
            return
        }
        if (shizuku.status.value != ShizukuScreenStatus.READY) {
            rejectRequest(
                request,
                request,
                ScreenMirrorStatus.SHIZUKU_UNAVAILABLE,
                shizuku.status.value.name,
            )
            return
        }
        val token = requireNotNull(request.routingToken)
        val expiry = requireNotNull(request.expiresAt)
        val consumed = try {
            authorizations.consumeRequest(request.sessionId, token, expiry, now())
        } catch (error: ScreenReplayStateUnavailableException) {
            request.destroySecrets()
            throw RetryableDeliveryException("screen replay state is unavailable", error)
        }
        if (!consumed) {
            rejectRequest(request, request, ScreenMirrorStatus.EXPIRED, "request already consumed")
            return
        }
        synchronized(lock) {
            if (_state.value != AndroidScreenSessionState.IDLE) {
                rejectRequest(request, request, ScreenMirrorStatus.BUSY, "another controller is active")
                return
            }
            pending = PendingRequest(request)
            _state.value = AndroidScreenSessionState.PENDING
            expiryJob = scope.launch {
                delay((expiry - now()).coerceAtLeast(1L))
                val stillPending = synchronized(lock) {
                    pending?.request?.sessionId == request.sessionId && sessionJob == null
                }
                if (stillPending) {
                    sendStatus(request, ScreenMirrorStatus.EXPIRED)
                    stop(request.sessionId, notifyPeer = false)
                }
            }
        }
        sendStatus(request, ScreenMirrorStatus.CONNECTING)
        if (!startForegroundSession(request)) postTapToStart(request)
    }

    private fun receiveStop(message: InboundMessage, request: ScreenMirrorSync) {
        stopInternal(
            sessionId = request.sessionId,
            notifyPeer = false,
            authenticatedStop = AuthenticatedScreenStop(
                request = request,
                senderId = message.senderId,
                senderOwnDevice = message.senderOwnDevice,
            ),
        )
    }

    /** Invoked by the connectedDevice FGS after it has entered the foreground. */
    fun runPendingSession(sessionId: String, onFinished: () -> Unit): Boolean =
        synchronized(lock) {
            if (sessionJob != null) {
                // A repeated START for the session already owned by this FGS is a no-op, not a
                // reason for its completion callback to stop the service.
                return@synchronized pending?.request?.sessionId == sessionId
            }
            if (pending?.request?.sessionId != sessionId) return@synchronized false
            val holder = pending ?: return@synchronized false
            expiryJob?.cancel()
            expiryJob = null
            if (now() >= (holder.request.expiresAt ?: 0L)) {
                pending = null
                _state.value = AndroidScreenSessionState.IDLE
                holder.close()
                sendStatus(holder.request, ScreenMirrorStatus.EXPIRED)
                return@synchronized false
            }
            _state.value = AndroidScreenSessionState.CONNECTING
            val launchedSession = scope.launch(start = CoroutineStart.LAZY) {
                var pipes: PrivilegedSessionPipes? = null
                var ready = false
                try {
                    transport.run(
                        request = holder.request,
                        startCapture = {
                            shizuku.startPrivilegedSession(holder.request).getOrThrow().also { pipes = it }
                        },
                        onReady = {
                            ready = true
                            _state.value = AndroidScreenSessionState.ACTIVE
                            sendStatus(holder.request, ScreenMirrorStatus.READY)
                        },
                    )
                    if (ready) sendStatus(holder.request, ScreenMirrorStatus.ENDED)
                    else sendStatus(holder.request, ScreenMirrorStatus.TRANSPORT_FAILED, "transport ended before capture")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    val status = if (ready) {
                        ScreenMirrorStatus.TRANSPORT_FAILED
                    } else if (error is ScreenCaptureStartException) {
                        ScreenMirrorStatus.CODEC_START_FAILED
                    } else if (error is ScreenRequestExpiredException) {
                        ScreenMirrorStatus.EXPIRED
                    } else if (error is PrivilegedCaptureStartException) {
                        when (error.backendStatus) {
                            ScreenMirrorBackendStatus.BUSY -> ScreenMirrorStatus.BUSY
                            ScreenMirrorBackendStatus.CODEC_UNAVAILABLE,
                            ScreenMirrorBackendStatus.START_FAILED,
                            ScreenMirrorBackendStatus.INVALID_ARGUMENT ->
                                ScreenMirrorStatus.CODEC_START_FAILED
                            else -> ScreenMirrorStatus.SHIZUKU_UNAVAILABLE
                        }
                    } else if (shizuku.status.value != ShizukuScreenStatus.READY) {
                        ScreenMirrorStatus.SHIZUKU_UNAVAILABLE
                    } else {
                        ScreenMirrorStatus.TRANSPORT_FAILED
                    }
                    sendStatus(holder.request, status, error.message)
                } finally {
                    synchronized(lock) {
                        if (pending === holder) {
                            _state.value = AndroidScreenSessionState.STOPPING
                        }
                    }
                    pipes?.close()
                    shizuku.stopPrivilegedSession(holder.request.sessionId)
                    shizuku.removeUserService()
                    holder.close()
                    cancelPendingNotification(holder.request.sessionId)
                    try {
                        // The FGS callback synchronously gives up ownership before IDLE is
                        // published. A request arriving immediately after IDLE can therefore be
                        // acquired by the existing service instance without an old stopSelf()
                        // tearing it down.
                        onFinished()
                    } finally {
                        synchronized(lock) {
                            if (pending === holder) {
                                pending = null
                                sessionJob = null
                                expiryJob = null
                                _state.value = AndroidScreenSessionState.IDLE
                            }
                        }
                    }
                }
            }
            sessionJob = launchedSession
            cancelPendingNotification(holder.request.sessionId)
            launchedSession.start()
            true
        }

    fun onForegroundStartFailed(sessionId: String) {
        val request = synchronized(lock) {
            pending?.request?.takeIf { it.sessionId == sessionId && sessionJob == null }
        } ?: return
        postTapToStart(request)
    }

    fun stop(sessionId: String? = null, notifyPeer: Boolean = true) {
        stopInternal(sessionId, notifyPeer, authenticatedStop = null)
    }

    private fun stopInternal(
        sessionId: String?,
        notifyPeer: Boolean,
        authenticatedStop: AuthenticatedScreenStop?,
    ) {
        val holder: PendingRequest?
        val running: Job?
        synchronized(lock) {
            holder = pending
            if (holder == null || (sessionId != null && holder.request.sessionId != sessionId)) return
            if (
                authenticatedStop != null &&
                !authenticatedStop.permits(holder.request, ownClientId)
            ) return
            if (holder.stopRequested) return
            holder.stopRequested = true
            _state.value = AndroidScreenSessionState.STOPPING
            running = sessionJob
            running?.cancel()
            expiryJob?.cancel()
            expiryJob = null
            // A running session retains its holder and job as an ownership barrier until its
            // finally block has released the foreground service. Clearing them here would allow
            // a new request to race the old FGS completion callback.
            if (running == null) pending = null
        }
        val active = requireNotNull(holder)
        shizuku.stopPrivilegedSession(active.request.sessionId)
        if (notifyPeer) sendStatus(active.request, ScreenMirrorStatus.ENDED)
        cancelPendingNotification(active.request.sessionId)
        if (running == null) {
            active.close()
            synchronized(lock) {
                if (pending == null && sessionJob == null) {
                    _state.value = AndroidScreenSessionState.IDLE
                }
            }
        }
    }

    fun onAuthorizationPolicyChanged() {
        val requester = synchronized(lock) { pending?.request?.requesterPeerId }
        if (
            !settings.screenMirroringEnabled.value ||
            shizuku.status.value != ShizukuScreenStatus.READY ||
            (requester != null && !authorizations.isAuthorized(requester))
        ) {
            stop(notifyPeer = true)
            ScreenMirrorForegroundService.stop(context)
        }
    }

    private fun startForegroundSession(request: ScreenMirrorSync): Boolean = runCatching {
        ScreenMirrorForegroundService.start(context, request.sessionId, controllerLabel(request))
        true
    }.getOrDefault(false)

    private fun postTapToStart(request: ScreenMirrorSync) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stop(request.sessionId, notifyPeer = false)
            sendStatus(request, ScreenMirrorStatus.TRANSPORT_FAILED, "foreground start unavailable")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PENDING_CHANNEL_ID,
                context.getString(R.string.screen_mirror_request_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val controllerLabel = controllerLabel(request)
        val start = ScreenMirrorForegroundService.pendingIntent(context, request.sessionId, controllerLabel)
        val notification = NotificationCompat.Builder(context, PENDING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(context.getString(R.string.screen_mirror_request_title))
            .setContentText(
                context.getString(
                    R.string.screen_mirror_request_body,
                    controllerLabel,
                ),
            )
            .setContentIntent(start)
            .setAutoCancel(true)
            .setTimeoutAfter(((request.expiresAt ?: now()) - now()).coerceAtLeast(1L))
            .addAction(0, context.getString(R.string.screen_mirror_start), start)
            .build()
        manager.notify(pendingNotificationId(request.sessionId), notification)
    }

    private fun cancelPendingNotification(sessionId: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(pendingNotificationId(sessionId))
    }

    private fun sendStatus(request: ScreenMirrorSync, status: ScreenMirrorStatus, detail: String? = null) {
        val sanitized = detail?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")?.take(160)
        val body = DataSync(
            kind = DataSyncKind.SCREEN_MIRRORING,
            screenMirror = ScreenMirrorSync(
                action = if (status == ScreenMirrorStatus.ENDED) ScreenMirrorAction.END else ScreenMirrorAction.STATUS,
                protocolVersion = request.protocolVersion,
                sessionId = request.sessionId,
                requesterPeerId = request.requesterPeerId,
                sourcePeerId = ownClientId,
                issuedAt = now(),
                status = status,
                detail = sanitized,
            ),
        )
        scope.launch {
            runCatching {
                channel.send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(body),
                    Recipients.Only(request.requesterPeerId),
                    Urgency.NORMAL,
                )
            }
        }
    }

    private fun rejectRequest(
        requestWithSecrets: ScreenMirrorSync,
        responseRequest: ScreenMirrorSync,
        status: ScreenMirrorStatus,
        detail: String? = null,
    ) {
        try {
            sendStatus(responseRequest, status, detail)
        } finally {
            requestWithSecrets.destroySecrets()
        }
    }

    private fun ScreenMirrorSync.destroySecrets() {
        routingToken?.fill(0)
        masterPsk?.fill(0)
    }

    private fun pendingNotificationId(sessionId: String): Int =
        ("screen-pending:$sessionId").hashCode()

    private fun controllerLabel(request: ScreenMirrorSync): String = ScreenControllerLabel.create(
        peerName(request.requesterPeerId),
        request.requesterPeerId.shortForm(),
    )

    private companion object {
        const val PENDING_CHANNEL_ID = "notisync.screen.request"
    }
}

internal data class AuthenticatedScreenStop(
    val request: ScreenMirrorSync,
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
) {
    /**
     * A signed stop body is not authority by itself: it must name the source and requester on the
     * currently owned session. This comparison is performed while the controller lock is held.
     */
    fun permits(activeRequest: ScreenMirrorSync, ownClientId: ClientId): Boolean =
        senderOwnDevice &&
            request.action in setOf(ScreenMirrorAction.CANCEL, ScreenMirrorAction.END) &&
            request.sessionId == activeRequest.sessionId &&
            request.sourcePeerId == ownClientId &&
            request.requesterPeerId == senderId &&
            activeRequest.sourcePeerId == ownClientId &&
            activeRequest.requesterPeerId == senderId
}
