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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Exact process-local claim ensuring only one path may wait for privileged teardown. */
internal class ScreenMirrorTeardownClaim {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}

/**
 * Starts the exact transport owner atomically, but holds its body behind a gate until the caller
 * has published [job]. Cancellation after publication but before dispatch must still enter the
 * coroutine and run cleanup; a lazy coroutine can instead be cancelled without ever starting.
 */
internal class ScreenMirrorAtomicSession internal constructor(
    val job: Job,
    private val releaseGate: () -> Unit,
) {
    fun releaseAfterInstallation() = releaseGate()
}

@OptIn(DelicateCoroutinesApi::class)
internal fun CoroutineScope.launchAtomicScreenMirrorSession(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    body: suspend () -> Unit,
    cleanup: () -> Unit,
): ScreenMirrorAtomicSession {
    val installationGate = CompletableDeferred<Unit>()
    val job = launch(dispatcher, start = CoroutineStart.ATOMIC) {
        // A cancelled parent must not let cleanup overtake publication of this job. Once the gate
        // opens, the active-context check is inside try/finally so pre-dispatch cancellation still
        // has exactly one cleanup owner.
        withContext(NonCancellable) { installationGate.await() }
        try {
            currentCoroutineContext().ensureActive()
            body()
        } finally {
            cleanup()
        }
    }
    return ScreenMirrorAtomicSession(job) {
        installationGate.complete(Unit)
        Unit
    }
}

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
    private val launchTeardown: ((() -> Unit) -> Unit) = { cleanup ->
        scope.launch(Dispatchers.IO) { cleanup() }
        Unit
    },
) {
    private data class PendingRequest(
        val request: ScreenMirrorSync,
        val foregroundLeaseId: String = UUID.randomUUID().toString(),
        var stopRequested: Boolean = false,
        var admissionAnnounced: Boolean = false,
        var foregroundState: ScreenMirrorForegroundState = ScreenMirrorForegroundState.NOT_REQUESTED,
        var foregroundWatchdog: Job? = null,
    ) : Closeable {
        val teardown = ScreenMirrorTeardownClaim()

        override fun close() {
            foregroundWatchdog?.cancel()
            foregroundWatchdog = null
            request.routingToken?.fill(0)
            request.masterPsk?.fill(0)
        }
    }

    private data class StatusDelivery(
        val requesterPeerId: ClientId,
        val encodedBody: ByteArray,
    )

    private val lock = Any()
    private val sessions = ScreenMirrorSessionSlot<PendingRequest>()
    private val pending: PendingRequest? get() = sessions.active
    private var sessionJob: Job? = null
    private var expiryJob: Job? = null
    private var replacementExpiryJob: Job? = null
    private val _state = MutableStateFlow(AndroidScreenSessionState.IDLE)
    val state: StateFlow<AndroidScreenSessionState> = _state.asStateFlow()
    private val statusDeliveries = Channel<StatusDelivery>(Channel.UNLIMITED)

    init {
        // All state transitions enqueue here synchronously. A single consumer preserves per-session
        // CONNECTING/READY/END order even when request, FGS, and transport callbacks race.
        scope.launch {
            for (delivery in statusDeliveries) {
                runCatching {
                    channel.send(
                        MessageType.DATA_SYNC,
                        delivery.encodedBody,
                        Recipients.Only(delivery.requesterPeerId),
                        Urgency.NORMAL,
                    )
                }
            }
        }
    }

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
        val incoming = PendingRequest(request)
        var previousToEnd: PendingRequest? = null
        var displacedReplacement: PendingRequest? = null
        var startNow = false
        var replacedSessionJob: Job? = null
        var lateFailure: Pair<ScreenMirrorStatus, String>? = null
        synchronized(lock) {
            // Authorization, settings, Shizuku, codec availability, and expiry are mutable. Check
            // them again in the same critical section that may evict the active controller.
            lateFailure = runtimeAdmissionFailure(incoming)
            val offer = sessions.offer(incoming, eligible = lateFailure == null)
            when (offer.disposition) {
                ScreenMirrorSessionSlot.Disposition.REJECTED -> Unit
                ScreenMirrorSessionSlot.Disposition.ACTIVE -> {
                    installActiveExpiryLocked(incoming)
                    _state.value = AndroidScreenSessionState.PENDING
                    announceConnectingLocked(incoming)
                    startNow = true
                }
                ScreenMirrorSessionSlot.Disposition.QUEUED -> {
                    displacedReplacement = offer.displacedReplacement
                    replacementExpiryJob?.cancel()
                    replacementExpiryJob = scheduleReplacementExpiryLocked(incoming, expiry)

                    val current = requireNotNull(offer.active)
                    if (!current.stopRequested) {
                        current.stopRequested = true
                        previousToEnd = current
                        _state.value = AndroidScreenSessionState.STOPPING
                        // Reserve the old controller's END while ownership is still serialized.
                        // READY can therefore only have been queued before this END, never after it.
                        sendStatus(
                            current.request,
                            ScreenMirrorStatus.ENDED,
                            "replaced by another controller",
                        )
                        expiryJob?.cancel()
                        expiryJob = null
                        replacedSessionJob = sessionJob
                    }
                }
            }
        }

        lateFailure?.let { failureAfterReplay ->
            rejectRequest(request, request, failureAfterReplay.first, failureAfterReplay.second)
            return
        }

        displacedReplacement?.let { displaced ->
            sendStatus(displaced.request, ScreenMirrorStatus.ENDED, "replaced by another controller")
            displaced.close()
        }
        previousToEnd?.let { previous ->
            val running = replacedSessionJob
            if (running == null) {
                // A locally generated foreground lease makes any late START/callback for this
                // holder stale. It is therefore safe to promote once background teardown finishes.
                finishStoppedPending(previous)
            } else {
                // Cancellation may synchronously invoke platform cleanup handlers. Never execute
                // it while holding the controller lock or on the inbound delivery thread.
                launchTeardown {
                    runCatching { running.cancel() }
                    runCatching { cancelPendingNotification(previous) }
                }
            }
        }
        if (startNow) startForegroundFor(incoming)
    }

    /** Caller holds [lock]; the call order is serialized before a successor may emit END. */
    private fun announceConnectingLocked(holder: PendingRequest) {
        if (holder.admissionAnnounced) return
        sendStatus(holder.request, ScreenMirrorStatus.CONNECTING)
        holder.admissionAnnounced = true
    }

    /** Caller holds [lock]. */
    private fun installActiveExpiryLocked(holder: PendingRequest) {
        expiryJob?.cancel()
        val expiry = requireNotNull(holder.request.expiresAt)
        expiryJob = scope.launch {
            delay((expiry - now()).coerceAtLeast(1L))
            // Claim expiry and publish its terminal status in the same critical section used by
            // takeover. Once stopRequested is set, a successor cannot also announce END for this
            // generation.
            val claimed = synchronized(lock) {
                if (pending !== holder || sessionJob != null || holder.stopRequested) {
                    false
                } else {
                    holder.stopRequested = true
                    expiryJob = null
                    _state.value = AndroidScreenSessionState.STOPPING
                    sendStatus(holder.request, ScreenMirrorStatus.EXPIRED)
                    true
                }
            }
            if (claimed) finishStoppedPending(holder)
        }
    }

    /** Caller holds [lock]. */
    private fun scheduleReplacementExpiryLocked(holder: PendingRequest, expiry: Long): Job = scope.launch {
        delay((expiry - now()).coerceAtLeast(1L))
        val removed = synchronized(lock) {
            if (!sessions.removeReplacement(holder)) return@synchronized false
            replacementExpiryJob = null
            true
        }
        if (removed) {
            sendStatus(holder.request, ScreenMirrorStatus.EXPIRED)
            holder.close()
        }
    }

    private fun startForegroundFor(holder: PendingRequest) {
        synchronized(lock) {
            if (
                pending !== holder || holder.stopRequested || !holder.admissionAnnounced ||
                holder.foregroundState != ScreenMirrorForegroundState.NOT_REQUESTED
            ) return
            holder.foregroundState = ScreenMirrorForegroundState.SUBMITTING
            holder.foregroundWatchdog = scope.launch {
                delay(FOREGROUND_START_WATCHDOG_MILLIS)
                onForegroundStartTimeout(holder)
            }
        }

        val started = startForegroundSession(holder)
        var postFallback = false
        synchronized(lock) {
            if (pending !== holder) return@synchronized
            if (holder.foregroundState == ScreenMirrorForegroundState.SUBMITTING) {
                if (started) {
                    holder.foregroundState = ScreenMirrorForegroundState.REQUESTED
                } else {
                    holder.foregroundWatchdog?.cancel()
                    holder.foregroundWatchdog = null
                    holder.foregroundState = ScreenMirrorForegroundState.UNAVAILABLE
                    postFallback = !holder.stopRequested
                }
            }
        }
        if (postFallback) postTapToStart(holder)
    }

    /** Complete a request which was stopped before a transport coroutine owned its cleanup. */
    private fun finishStoppedPending(holder: PendingRequest) {
        val ownsStoppedPending = synchronized(lock) {
            pending === holder && sessionJob == null && holder.stopRequested &&
                holder.teardown.tryClaim()
        }
        if (!ownsStoppedPending) return
        launchTeardown {
            var teardownAcknowledged = false
            try {
                runCatching { cancelPendingNotification(holder) }
                runCatching { holder.close() }
                teardownAcknowledged = runCatching {
                    shizuku.stopPrivilegedSession(holder.foregroundLeaseId)
                }.getOrDefault(false)
            } finally {
                // Keep the disclosure/Stop notification owned until privileged capture has
                // actually acknowledged shutdown. Both release and completion are independent of
                // incidental NotificationManager/resource cleanup failures.
                runCatching {
                    ScreenMirrorForegroundService.release(
                        context,
                        holder.request.sessionId,
                        holder.foregroundLeaseId,
                    )
                }
                completeAndPromote(
                    holder = holder,
                    forcedPromotionFailure = if (teardownAcknowledged) {
                        null
                    } else {
                        ScreenMirrorStatus.SHIZUKU_UNAVAILABLE to
                            "privileged capture teardown was not acknowledged"
                    },
                    // A queued replacement reuses the acknowledged backend; otherwise terminate
                    // the non-daemon privileged process even when transport never started.
                    removeUserServiceWhenIdle = true,
                )
            }
        }
    }

    /** Fail closed if Android accepted the START request but never delivers an exact FGS callback. */
    private fun onForegroundStartTimeout(holder: PendingRequest) {
        val timedOut = synchronized(lock) {
            if (
                pending !== holder || sessionJob != null || holder.stopRequested ||
                !holder.foregroundState.awaitingAcknowledgement()
            ) return@synchronized false
            holder.stopRequested = true
            holder.foregroundWatchdog = null
            _state.value = AndroidScreenSessionState.STOPPING
            true
        }
        if (!timedOut) return
        sendStatus(
            holder.request,
            ScreenMirrorStatus.TRANSPORT_FAILED,
            "foreground service did not acknowledge the screen session",
        )
        finishStoppedPending(holder)
    }

    /**
     * Give up [holder]'s exact ownership slot and, if present, promote only the newest replacement.
     * Referential identity prevents a late transport/FGS callback from clearing a newer session.
     */
    private fun completeAndPromote(
        holder: PendingRequest,
        forcedPromotionFailure: Pair<ScreenMirrorStatus, String>? = null,
        removeUserServiceWhenIdle: Boolean = false,
    ) {
        var next: PendingRequest? = null
        var rejected: Pair<ScreenMirrorStatus, String>? = null
        var rejectedHolder: PendingRequest? = null
        var effectiveForcedPromotionFailure = forcedPromotionFailure
        fun completeLocked(): Boolean {
            val completion = sessions.complete(holder)
            if (!completion.owned) return false
            sessionJob = null
            expiryJob?.cancel()
            expiryJob = null
            replacementExpiryJob?.cancel()
            replacementExpiryJob = null

            val candidate = completion.replacement
            if (candidate == null) {
                _state.value = AndroidScreenSessionState.IDLE
                return true
            }
            val failure = effectiveForcedPromotionFailure ?: runtimeAdmissionFailure(candidate)
            if (failure != null) {
                rejected = failure
                rejectedHolder = candidate
                _state.value = AndroidScreenSessionState.IDLE
                return true
            }
            check(sessions.offer(candidate, eligible = true).disposition == ScreenMirrorSessionSlot.Disposition.ACTIVE)
            installActiveExpiryLocked(candidate)
            _state.value = AndroidScreenSessionState.PENDING
            announceConnectingLocked(candidate)
            next = candidate
            return true
        }

        var owned = false
        var removeBeforeCompletion = false
        synchronized(lock) {
            if (pending !== holder) return
            val candidate = sessions.replacement
            val candidateFailure = candidate?.let {
                effectiveForcedPromotionFailure ?: runtimeAdmissionFailure(it)
            }
            // Keep the old holder installed while the potentially blocking Shizuku unbind runs.
            // A request arriving in that window can only become a queued replacement, and is
            // revalidated after removal before it is promoted into a freshly bound UserService.
            removeBeforeCompletion = removeUserServiceWhenIdle &&
                (candidate == null || candidateFailure != null)
            if (!removeBeforeCompletion) owned = completeLocked()
        }
        if (removeBeforeCompletion) {
            runCatching { shizuku.removeUserService() }.fold(
                onSuccess = {
                    // Killing the exact non-daemon UserService is the fallback acknowledgement:
                    // its capture process and descriptors are gone even if stopSession wedged.
                    // A queued replacement may now bind a fresh generation instead of inheriting
                    // the old teardown failure and remaining permanently blocked.
                    effectiveForcedPromotionFailure = null
                },
                onFailure = {
                    effectiveForcedPromotionFailure = ScreenMirrorStatus.SHIZUKU_UNAVAILABLE to
                        "failed to remove privileged screen service"
                },
            )
            synchronized(lock) {
                owned = completeLocked()
            }
        }
        if (!owned) return

        val rejectedCandidate = rejected
        if (rejectedCandidate != null) {
            val candidate = checkNotNull(rejectedHolder)
            sendStatus(candidate.request, rejectedCandidate.first, rejectedCandidate.second)
            candidate.close()
            return
        }
        next?.let(::startForegroundFor)
    }

    /** Recheck mutable runtime policy after the old controller has fully released its resources. */
    private fun runtimeAdmissionFailure(holder: PendingRequest): Pair<ScreenMirrorStatus, String>? = when {
        now() >= (holder.request.expiresAt ?: 0L) -> ScreenMirrorStatus.EXPIRED to "request expired during controller handoff"
        !settings.screenMirroringEnabled.value -> ScreenMirrorStatus.UNAUTHORIZED to "screen sharing disabled"
        !authorizations.isAuthorized(holder.request.requesterPeerId) ->
            ScreenMirrorStatus.UNAUTHORIZED to "requester authorization changed"
        shizuku.status.value != ShizukuScreenStatus.READY ->
            ScreenMirrorStatus.SHIZUKU_UNAVAILABLE to shizuku.status.value.name
        holder.request.codec?.let(capabilities::supports) != true ->
            ScreenMirrorStatus.CODEC_UNAVAILABLE to "selected codec is no longer available"
        else -> null
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
            foregroundLeaseId = null,
        )
    }

    /** A source FGS START is authoritative only for this exact, process-local request generation. */
    fun isForegroundStartCurrent(sessionId: String, leaseId: String): Boolean = synchronized(lock) {
        pending?.let {
            it.request.sessionId == sessionId &&
                it.foregroundLeaseId == leaseId &&
                !it.stopRequested
        } == true
    }

    /**
     * Run a short foreground-notification transition while this exact local lease still owns the
     * controller. Serializing the transition with takeover prevents a superseded session from
     * posting a late high-importance "connected" notification.
     */
    internal fun runIfForegroundLeaseCurrent(
        sessionId: String,
        leaseId: String,
        transition: () -> Unit,
    ): Boolean = synchronized(lock) {
        val current = pending
        if (
            current == null ||
            current.request.sessionId != sessionId ||
            current.foregroundLeaseId != leaseId ||
            current.stopRequested
        ) {
            false
        } else {
            transition()
            true
        }
    }

    /** Invoked by the connectedDevice FGS after it has entered the foreground. */
    fun runPendingSession(
        sessionId: String,
        leaseId: String,
        onFinished: () -> Unit,
    ): Boolean {
        var expired: PendingRequest? = null
        var notificationToCancel: PendingRequest? = null
        val launched = synchronized(lock) {
            val holder = pending?.takeIf {
                it.request.sessionId == sessionId &&
                    it.foregroundLeaseId == leaseId &&
                    !it.stopRequested
            } ?: return@synchronized false
            holder.foregroundWatchdog?.cancel()
            holder.foregroundWatchdog = null
            holder.foregroundState = ScreenMirrorForegroundState.ACKNOWLEDGED
            if (sessionJob != null) return@synchronized true
            if (!holder.teardown.tryClaim()) return@synchronized false

            expiryJob?.cancel()
            expiryJob = null
            if (now() >= (holder.request.expiresAt ?: 0L)) {
                holder.stopRequested = true
                _state.value = AndroidScreenSessionState.STOPPING
                expired = holder
                return@synchronized false
            }
            _state.value = AndroidScreenSessionState.CONNECTING
            // The transport and its acknowledged Shizuku teardown may block in platform/Binder
            // code. Pin this owner coroutine to IO even if a caller supplies a Main-based scope.
            var pipes: PrivilegedSessionPipes? = null
            var ready = false
            val launchedSession = scope.launchAtomicScreenMirrorSession(
                body = {
                    try {
                        transport.run(
                            request = holder.request,
                            startCapture = {
                                shizuku.startPrivilegedSession(
                                    holder.request,
                                    holder.foregroundLeaseId,
                                ).getOrThrow().also { pipes = it }
                            },
                            onReady = {
                                val stillOwned = synchronized(lock) {
                                    if (pending === holder && !holder.stopRequested) {
                                        ready = true
                                        _state.value = AndroidScreenSessionState.ACTIVE
                                        // Reserve READY in the same critical section as ownership.
                                        // A takeover can enqueue END only before this block or after
                                        // READY.
                                        sendStatus(holder.request, ScreenMirrorStatus.READY)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (stillOwned) {
                                    ScreenMirrorForegroundService.markConnected(
                                        context,
                                        holder.request.sessionId,
                                        holder.foregroundLeaseId,
                                        controllerLabel(holder.request),
                                    )
                                }
                            },
                        )
                        currentCoroutineContext().ensureActive()
                        if (ready) {
                            announceTransportTerminal(holder, ScreenMirrorStatus.ENDED)
                        } else {
                            announceTransportTerminal(
                                holder,
                                ScreenMirrorStatus.TRANSPORT_FAILED,
                                "transport ended before capture",
                            )
                        }
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
                        announceTransportTerminal(holder, status, error.message)
                    }
                },
                cleanup = {
                    synchronized(lock) {
                        if (pending === holder) {
                            _state.value = AndroidScreenSessionState.STOPPING
                        }
                    }
                    var teardownAcknowledged = false
                    try {
                        runCatching { pipes?.close() }
                        runCatching { cancelPendingNotification(holder) }
                        runCatching { holder.close() }
                        teardownAcknowledged = runCatching {
                            shizuku.stopPrivilegedSession(holder.foregroundLeaseId)
                        }.getOrDefault(false)
                        runCatching(onFinished)
                    } finally {
                        completeAndPromote(
                            holder = holder,
                            forcedPromotionFailure = if (teardownAcknowledged) {
                                null
                            } else {
                                ScreenMirrorStatus.SHIZUKU_UNAVAILABLE to
                                    "privileged capture teardown was not acknowledged"
                            },
                            // Keep the already-bound UserService across a safe, acknowledged
                            // handoff. Removing it before promotion can kill the replacement.
                            removeUserServiceWhenIdle = true,
                        )
                    }
                },
            )
            sessionJob = launchedSession.job
            // Publish the cancellable owner before opening its body. ATOMIC start guarantees that
            // even a cancellation before its first dispatch still executes the cleanup above.
            launchedSession.releaseAfterInstallation()
            notificationToCancel = holder
            true
        }

        notificationToCancel?.let { holder ->
            launchTeardown { runCatching { cancelPendingNotification(holder) } }
        }

        expired?.let { holder ->
            sendStatus(holder.request, ScreenMirrorStatus.EXPIRED)
            launchTeardown {
                var teardownAcknowledged = false
                try {
                    runCatching { cancelPendingNotification(holder) }
                    runCatching { holder.close() }
                    teardownAcknowledged = runCatching {
                        shizuku.stopPrivilegedSession(holder.foregroundLeaseId)
                    }.getOrDefault(false)
                    runCatching(onFinished)
                } finally {
                    completeAndPromote(
                        holder = holder,
                        forcedPromotionFailure = if (teardownAcknowledged) {
                            null
                        } else {
                            ScreenMirrorStatus.SHIZUKU_UNAVAILABLE to
                                "privileged capture teardown was not acknowledged"
                        },
                        removeUserServiceWhenIdle = true,
                    )
                }
            }
            return true
        }
        return launched
    }

    /**
     * Claim the terminal transition before publishing it. Besides suppressing duplicate terminal
     * messages, this invalidates any queued main-thread connected-notification promotion.
     */
    private fun announceTransportTerminal(
        holder: PendingRequest,
        status: ScreenMirrorStatus,
        detail: String? = null,
    ) {
        synchronized(lock) {
            if (pending !== holder || holder.stopRequested) return
            holder.stopRequested = true
            _state.value = AndroidScreenSessionState.STOPPING
            sendStatus(holder.request, status, detail)
        }
    }

    fun onForegroundStartFailed(sessionId: String, leaseId: String) {
        val holder = synchronized(lock) {
            pending?.takeIf {
                it.request.sessionId == sessionId &&
                    it.foregroundLeaseId == leaseId &&
                    sessionJob == null &&
                    !it.stopRequested
            }?.also {
                it.foregroundWatchdog?.cancel()
                it.foregroundWatchdog = null
                it.foregroundState = ScreenMirrorForegroundState.UNAVAILABLE
            }
        } ?: return
        postTapToStart(holder)
    }

    /** Returns true when this exact FGS lease is current, including an already-stopping lease. */
    fun stopForegroundSession(sessionId: String, leaseId: String): Boolean =
        stopInternal(
            sessionId = sessionId,
            notifyPeer = true,
            authenticatedStop = null,
            foregroundLeaseId = leaseId,
        )

    fun stop(sessionId: String? = null, notifyPeer: Boolean = true) {
        stopInternal(
            sessionId = sessionId,
            notifyPeer = notifyPeer,
            authenticatedStop = null,
            foregroundLeaseId = null,
        )
    }

    private fun stopInternal(
        sessionId: String?,
        notifyPeer: Boolean,
        authenticatedStop: AuthenticatedScreenStop?,
        foregroundLeaseId: String?,
    ): Boolean {
        var holder: PendingRequest? = null
        var running: Job? = null
        var queued: PendingRequest? = null
        var accepted = false
        synchronized(lock) {
            sessions.replacement?.let { candidate ->
                val matches = sessionId == null || candidate.request.sessionId == sessionId
                val exactForegroundLease = foregroundLeaseId == null ||
                    candidate.foregroundLeaseId == foregroundLeaseId
                val permitted = authenticatedStop == null ||
                    authenticatedStop.permits(candidate.request, ownClientId)
                if (matches && exactForegroundLease && permitted) {
                    accepted = true
                    if (sessions.removeReplacement(candidate)) {
                        queued = candidate
                        if (notifyPeer) sendStatus(candidate.request, ScreenMirrorStatus.ENDED)
                        replacementExpiryJob?.cancel()
                        replacementExpiryJob = null
                    }
                }
            }

            pending?.let { current ->
                val matches = sessionId == null || current.request.sessionId == sessionId
                val exactForegroundLease = foregroundLeaseId == null ||
                    current.foregroundLeaseId == foregroundLeaseId
                val permitted = authenticatedStop == null ||
                    authenticatedStop.permits(current.request, ownClientId)
                if (matches && exactForegroundLease && permitted) {
                    accepted = true
                    if (!current.stopRequested) {
                        current.stopRequested = true
                        holder = current
                        _state.value = AndroidScreenSessionState.STOPPING
                        if (notifyPeer) sendStatus(current.request, ScreenMirrorStatus.ENDED)
                        running = sessionJob
                        expiryJob?.cancel()
                        expiryJob = null
                    }
                }
            }
        }

        queued?.let { candidate ->
            candidate.close()
            launchTeardown { runCatching { cancelPendingNotification(candidate) } }
        }
        holder?.let { active ->
            // Job.cancel may synchronously invoke platform cancellation handlers. Keep it and the
            // NotificationManager Binder call off Service main; logical STOPPING/END was already
            // published under the controller lock above.
            launchTeardown {
                runCatching { running?.cancel() }
                runCatching { cancelPendingNotification(active) }
                if (running == null) {
                    finishStoppedPending(active)
                }
            }
        }
        return accepted
    }

    fun onAuthorizationPolicyChanged() {
        val requesters = synchronized(lock) {
            listOfNotNull(
                pending?.request?.requesterPeerId,
                sessions.replacement?.request?.requesterPeerId,
            )
        }
        if (
            !settings.screenMirroringEnabled.value ||
            shizuku.status.value != ShizukuScreenStatus.READY ||
            requesters.any { !authorizations.isAuthorized(it) }
        ) {
            stop(notifyPeer = true)
        }
    }

    private fun startForegroundSession(holder: PendingRequest): Boolean = runCatching {
        ScreenMirrorForegroundService.start(
            context,
            holder.request.sessionId,
            holder.foregroundLeaseId,
            controllerLabel(holder.request),
        )
        true
    }.getOrDefault(false)

    private fun postTapToStart(holder: PendingRequest) {
        // NotificationManager is a Binder service. Run the whole fallback path away from FGS main
        // and convert any OEM notification failure into the same exact pre-transport terminal
        // transition as an unavailable permission/service.
        launchTeardown {
            runCatching { postTapToStartOnTeardownLane(holder) }.onFailure {
                failPendingBeforeTransport(
                    holder,
                    "foreground start notification unavailable",
                )
            }
        }
    }

    private fun postTapToStartOnTeardownLane(holder: PendingRequest) {
        val request = holder.request
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failPendingBeforeTransport(holder, "foreground start unavailable")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            failPendingBeforeTransport(holder, "notification service unavailable")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                PENDING_CHANNEL_ID,
                context.getString(R.string.screen_mirror_request_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val controllerLabel = controllerLabel(request)
        val start = ScreenMirrorForegroundService.pendingIntent(
            context,
            request.sessionId,
            holder.foregroundLeaseId,
            controllerLabel,
        )
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
        val stillCurrent = synchronized(lock) {
            pending === holder && !holder.stopRequested &&
                holder.foregroundState == ScreenMirrorForegroundState.UNAVAILABLE
        }
        if (!stillCurrent) return
        val notificationId = pendingNotificationId(request.sessionId, holder.foregroundLeaseId)
        manager.notify(notificationId, notification)
        // If takeover won immediately after the pre-notify check, remove only this generation's
        // stale fallback; an identical remote session id may already belong to its replacement.
        val remainsCurrent = synchronized(lock) { pending === holder && !holder.stopRequested }
        if (!remainsCurrent) manager.cancel(notificationId)
    }

    /** Atomically owns the only terminal status for a request that never started transport. */
    private fun failPendingBeforeTransport(holder: PendingRequest, detail: String) {
        val claimed = synchronized(lock) {
            if (pending !== holder || sessionJob != null || holder.stopRequested) {
                false
            } else {
                holder.stopRequested = true
                holder.foregroundWatchdog?.cancel()
                holder.foregroundWatchdog = null
                expiryJob?.cancel()
                expiryJob = null
                _state.value = AndroidScreenSessionState.STOPPING
                sendStatus(holder.request, ScreenMirrorStatus.TRANSPORT_FAILED, detail)
                true
            }
        }
        if (claimed) finishStoppedPending(holder)
    }

    private fun cancelPendingNotification(holder: PendingRequest) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(pendingNotificationId(holder.request.sessionId, holder.foregroundLeaseId))
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
        statusDeliveries.trySend(
            StatusDelivery(
                requesterPeerId = request.requesterPeerId,
                encodedBody = ProtocolCodec.encodeToCbor(body),
            ),
        )
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

    private fun pendingNotificationId(sessionId: String, leaseId: String): Int =
        ("screen-pending:$sessionId:$leaseId").hashCode()

    private fun controllerLabel(request: ScreenMirrorSync): String = ScreenControllerLabel.create(
        peerName(request.requesterPeerId),
        request.requesterPeerId.shortForm(),
    )

    private companion object {
        const val PENDING_CHANNEL_ID = "notisync.screen.request"
        const val FOREGROUND_START_WATCHDOG_MILLIS = 10_000L
    }
}

internal enum class ScreenMirrorForegroundState {
    /** Admitted but no external service command has been reserved. */
    NOT_REQUESTED,

    /** The exact local lease is being submitted to Android. */
    SUBMITTING,

    /** Android accepted the command; its exact service callback is bounded by a watchdog. */
    REQUESTED,

    /** The service acknowledged this exact local lease and owns transport startup. */
    ACKNOWLEDGED,

    /** Background launch was unavailable; an exact-generation notification offers tap-to-start. */
    UNAVAILABLE,
}

internal fun ScreenMirrorForegroundState.awaitingAcknowledgement(): Boolean =
    this == ScreenMirrorForegroundState.SUBMITTING ||
        this == ScreenMirrorForegroundState.REQUESTED

/**
 * Identity-scoped active/replacement slots used under the controller lock.
 *
 * Eligibility is supplied only after the signed request has passed all policy and replay checks.
 * Keeping that gate explicit makes it impossible for an ineligible offer to displace either slot,
 * while referential completion prevents a stale FGS callback from clearing its successor.
 */
internal class ScreenMirrorSessionSlot<T : Any> {
    enum class Disposition { REJECTED, ACTIVE, QUEUED }

    data class Offer<T>(
        val disposition: Disposition,
        val active: T? = null,
        val displacedReplacement: T? = null,
    )

    data class Completion<T>(
        val owned: Boolean,
        val replacement: T? = null,
    )

    var active: T? = null
        private set
    var replacement: T? = null
        private set

    fun offer(candidate: T, eligible: Boolean): Offer<T> {
        if (!eligible) return Offer(Disposition.REJECTED, active = active)
        val current = active
        if (current == null) {
            check(replacement == null)
            active = candidate
            return Offer(Disposition.ACTIVE)
        }
        val displaced = replacement
        replacement = candidate
        return Offer(
            disposition = Disposition.QUEUED,
            active = current,
            displacedReplacement = displaced,
        )
    }

    fun complete(expected: T): Completion<T> {
        if (active !== expected) return Completion(owned = false)
        active = null
        val queued = replacement
        replacement = null
        return Completion(owned = true, replacement = queued)
    }

    fun removeReplacement(expected: T): Boolean {
        if (replacement !== expected) return false
        replacement = null
        return true
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
