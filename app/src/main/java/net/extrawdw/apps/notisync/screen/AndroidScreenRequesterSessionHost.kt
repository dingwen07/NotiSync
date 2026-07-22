package net.extrawdw.apps.notisync.screen

import android.view.KeyEvent
import android.view.Surface
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

internal enum class AndroidScreenHostPhase {
    IDLE,
    PREPARING,
    CONNECTING,
    CONNECTED,
    ENDED,
    ERROR,
}

internal data class AndroidScreenHostState(
    val phase: AndroidScreenHostPhase = AndroidScreenHostPhase.IDLE,
    val attemptId: String? = null,
    val sessionId: String? = null,
    val sourceId: ClientId? = null,
    val sourceName: String? = null,
    val codec: ScreenMirrorCodec? = null,
    val connectionType: AndroidScreenConnectionType? = null,
    val dimensions: ScrcpySessionDimensions? = null,
    val detail: String? = null,
    val surfaceAttached: Boolean = false,
    val connectionMode: AndroidScreenConnectionMode = AndroidScreenConnectionMode.DIRECT,
)

/**
 * Process-local owner of the Android requester/viewer session.
 *
 * The foreground service keeps this host's process alive; neither the viewer Activity nor its
 * Surface owns the authenticated channels. Exactly one attempt may exist. A Surface attachment is
 * separately exact-owner scoped so a destroyed Activity cannot detach its replacement's Surface.
 */
internal class AndroidScreenRequesterSessionHost(
    private val scope: CoroutineScope,
    private val hardwareDecoderName: (ScreenMirrorCodec) -> String?,
    private val openSession: suspend (ClientId, String) -> AndroidScreenViewerSession,
    private val openRelaySession: suspend (ClientId, String) -> AndroidScreenViewerSession = openSession,
    private val closeOwner: (String, String) -> Unit,
    private val requesterState: () -> AndroidScreenRequesterState,
) : Closeable {
    constructor(
        requester: AndroidScreenMirrorRequester,
        scope: CoroutineScope,
        hardwareDecoderName: (ScreenMirrorCodec) -> String?,
    ) : this(
        scope = scope,
        hardwareDecoderName = hardwareDecoderName,
        openSession = { sourceId, ownerToken -> requester.open(sourceId, ownerToken) },
        openRelaySession = { sourceId, ownerToken ->
            requester.open(sourceId, ownerToken, AndroidScreenConnectionMode.BROKER_RELAY)
        },
        closeOwner = requester::closeOwner,
        requesterState = { requester.state.value },
    )

    private data class SurfaceLease(val viewerToken: String, val surface: Surface)

    private class Attempt(
        val id: String,
        val sourceId: ClientId,
        val connectionMode: AndroidScreenConnectionMode,
    ) {
        val cleanupStarted = AtomicBoolean()
        val ownerCloseStarted = AtomicBoolean()
        var job: Job? = null
        var session: AndroidScreenViewerSession? = null
        var decoder: AndroidScreenVideoDecoder? = null
        var dispatcher: AndroidScreenControlDispatcher? = null
        var surface: SurfaceLease? = null
        var supportsVideoVisibility = false
        var relayReconnectAttempts = 0
        var relayConnectedAtNanos = Long.MIN_VALUE
        var stopping = false

        @Volatile
        var closeDetail = DEFAULT_CLOSE_DETAIL
    }

    private val lock = Any()
    private var active: Attempt? = null
    private var closed = false
    private val _state = MutableStateFlow(AndroidScreenHostState())
    val state: StateFlow<AndroidScreenHostState> = _state.asStateFlow()

    /**
     * Starts one viewer attempt, or returns the existing attempt id for the same source.
     * Selecting a second source while one is active is deliberately not an implicit disconnect.
     */
    fun start(
        sourceId: ClientId,
        connectionMode: AndroidScreenConnectionMode = AndroidScreenConnectionMode.DIRECT,
    ): String {
        while (true) {
            val switchAttempt = synchronized(lock) {
                active?.let { current ->
                    check(current.sourceId == sourceId) {
                        "another Android screen viewer is already active"
                    }
                    if (current.connectionMode == connectionMode) {
                        return current.id
                    }
                    current.id
                }
            }
            if (switchAttempt == null) break
            stopIfAttempt(switchAttempt, "switching screen transport")
        }
        val attempt: Attempt
        val job: Job
        synchronized(lock) {
            check(!closed) { "screen requester session host is closed" }
            active?.let { current -> return current.id }
            attempt = Attempt(UUID.randomUUID().toString(), sourceId, connectionMode)
            _state.value = AndroidScreenHostState(
                phase = AndroidScreenHostPhase.PREPARING,
                attemptId = attempt.id,
                sourceId = sourceId,
                connectionMode = connectionMode,
            )
            job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runAttempt(attempt)
            }
            attempt.job = job
            active = attempt
        }
        job.start()
        return attempt.id
    }

    /** Attach or replace the Surface for one Activity/view generation. */
    fun attachSurface(viewerToken: String, surface: Surface): Boolean {
        require(viewerToken.isNotBlank() && viewerToken.length <= MAX_TOKEN_LENGTH) {
            "invalid screen viewer token"
        }
        if (!surface.isValid) return false

        synchronized(lock) {
            val attempt = active?.takeUnless { it.stopping } ?: return false
            val wasDetached = attempt.surface == null
            attempt.surface = SurfaceLease(viewerToken, surface)
            // Keep the host lease and decoder request in one ordering domain. Otherwise an older
            // Activity can return from this method late and overwrite a replacement Activity's
            // decoder Surface after the host lease has already moved to the replacement token.
            if (attempt.decoder?.attachSurface(viewerToken, surface) == false) {
                attempt.surface = null
                _state.value = _state.value.copy(surfaceAttached = false)
                return false
            }
            _state.value = _state.value.copy(surfaceAttached = true)
            if (wasDetached && attempt.supportsVideoVisibility) {
                // Enqueue visibility while holding the same lock so rapid attach/detach operations
                // reach the ordered control dispatcher in host-lease order as well.
                attempt.dispatcher?.setVideoVisible(true)
            }
        }
        return true
    }

    /** Detach only the Surface owned by [viewerToken]; transport and control remain alive. */
    fun detachSurface(viewerToken: String): Boolean {
        synchronized(lock) {
            val attempt = active ?: return false
            if (attempt.surface?.viewerToken != viewerToken) return false
            attempt.surface = null
            attempt.decoder?.detachSurface(viewerToken)
            _state.value = _state.value.copy(surfaceAttached = false)
            if (attempt.supportsVideoVisibility) {
                attempt.dispatcher?.setVideoVisible(false)
            }
        }
        return true
    }

    /** Borrowed live dispatcher for the viewer UI; callers must never close it. */
    fun controlDispatcher(): AndroidScreenControlDispatcher? = synchronized(lock) {
        active?.takeUnless { it.stopping }?.dispatcher
    }

    fun sendKeyPress(keyCode: Int): Boolean {
        require(keyCode in HOST_ALLOWED_KEYS) { "unsupported screen control key" }
        return controlDispatcher()?.sendKeyPress(keyCode) == true
    }

    fun sendText(text: String): Boolean = controlDispatcher()?.sendText(text) == true

    fun sendTouches(touches: List<AndroidScreenTouch>): Boolean =
        controlDispatcher()?.sendTouches(touches) == true

    fun togglePower(): Boolean = controlDispatcher()?.togglePower() == true

    fun stop(detail: String = DEFAULT_CLOSE_DETAIL) {
        val attemptId = synchronized(lock) { active?.id } ?: return
        stopIfAttempt(attemptId, detail)
    }

    /** Stale-safe terminal entry point for foreground-service commands and destruction. */
    fun stopIfAttempt(
        attemptId: String,
        detail: String = DEFAULT_CLOSE_DETAIL,
    ): Boolean {
        val terminalState = synchronized(lock) {
            val current = active?.takeIf { it.id == attemptId && !it.stopping }
                ?: return@synchronized null
            val snapshot = _state.value
            AndroidScreenHostState(
                phase = AndroidScreenHostPhase.ENDED,
                attemptId = current.id,
                sessionId = current.session?.sessionId ?: snapshot.sessionId,
                sourceId = current.sourceId,
                sourceName = current.session?.sourceName ?: snapshot.sourceName,
                codec = current.session?.codec ?: snapshot.codec,
                connectionType = current.session?.connectionType ?: snapshot.connectionType,
                dimensions = snapshot.dimensions,
                detail = detail,
                surfaceAttached = false,
                connectionMode = current.connectionMode,
            )
        } ?: return false
        return terminateAttempt(
            attemptId = attemptId,
            terminalState = terminalState,
            detail = detail,
        )
    }

    override fun close() {
        val attemptId = synchronized(lock) {
            if (closed) return
            closed = true
            active?.id
        }
        if (attemptId != null) {
            stopIfAttempt(attemptId, DEFAULT_CLOSE_DETAIL)
        }
    }

    private suspend fun runAttempt(attempt: Attempt) {
        var session: AndroidScreenViewerSession? = null
        var decoder: AndroidScreenVideoDecoder? = null
        var dispatcher: AndroidScreenControlDispatcher? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(attempt.id)) return
            updateCurrent(attempt.id) {
                it.copy(phase = AndroidScreenHostPhase.CONNECTING, detail = null)
            }
            session = if (attempt.connectionMode == AndroidScreenConnectionMode.BROKER_RELAY) {
                openRelaySession(attempt.sourceId, attempt.id)
            } else {
                openSession(attempt.sourceId, attempt.id)
            }
            val connectedSession = session
            val writer = AndroidScreenControlWriter(connectedSession.controlOutput)
            dispatcher = AndroidScreenControlDispatcher(writer) { error ->
                if (attempt.connectionMode == AndroidScreenConnectionMode.BROKER_RELAY) {
                    // Closing the shared requester session makes the decoder observe the same
                    // transient relay loss and enter the single reconnect path below.
                    connectedSession.close()
                } else {
                    failAttempt(
                        attempt.id,
                        error.message?.takeIf(String::isNotBlank)
                            ?: "screen control channel failed",
                    )
                }
            }
            val connectedDispatcher = dispatcher
            decoder = AndroidScreenVideoDecoder(
                input = connectedSession.videoInput,
                expectedCodec = connectedSession.codec,
                hardwareDecoderName = hardwareDecoderName(connectedSession.codec),
                onDimensionsChanged = { dimensions ->
                    updateCurrent(attempt.id) { it.copy(dimensions = dimensions) }
                },
            )
            val connectedDecoder = decoder

            synchronized(lock) {
                check(active === attempt && !attempt.stopping) {
                    "screen viewer attempt was replaced"
                }
                attempt.session = connectedSession
                attempt.dispatcher = connectedDispatcher
                attempt.decoder = connectedDecoder
                attempt.supportsVideoVisibility =
                    Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1 in
                    connectedSession.sourceCapabilities
                if (attempt.connectionMode == AndroidScreenConnectionMode.BROKER_RELAY) {
                    attempt.relayConnectedAtNanos = System.nanoTime()
                }
                val initialSurface = attempt.surface
                val surfaceAttached = initialSurface?.let {
                    connectedDecoder.attachSurface(it.viewerToken, it.surface)
                } == true
                if (initialSurface != null && !surfaceAttached) {
                    attempt.surface = null
                }
                _state.value = _state.value.copy(
                    phase = AndroidScreenHostPhase.CONNECTED,
                    sessionId = connectedSession.sessionId,
                    sourceName = connectedSession.sourceName,
                    codec = connectedSession.codec,
                    connectionType = connectedSession.connectionType,
                    detail = null,
                    surfaceAttached = surfaceAttached,
                )
                if (attempt.supportsVideoVisibility && !surfaceAttached) {
                    connectedDispatcher.setVideoVisible(false)
                }
            }

            withContext(Dispatchers.IO) { connectedDecoder.decode() }
            if (isCurrent(attempt.id)) {
                val terminalRequesterState = requesterState()
                val failed = terminalRequesterState.phase == AndroidScreenRequesterPhase.FAILED
                completeAttempt(
                    attempt.id,
                    AndroidScreenHostState(
                        phase = if (failed) {
                            AndroidScreenHostPhase.ERROR
                        } else {
                            AndroidScreenHostPhase.ENDED
                        },
                        attemptId = attempt.id,
                        sessionId = connectedSession.sessionId,
                        sourceId = connectedSession.sourceId,
                        sourceName = connectedSession.sourceName,
                        codec = connectedSession.codec,
                        connectionType = connectedSession.connectionType,
                        dimensions = state.value.dimensions,
                        detail = terminalRequesterState.detail,
                        connectionMode = attempt.connectionMode,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            if (isCurrent(attempt.id)) {
                failAttempt(attempt.id, "screen viewer was cancelled")
            }
            throw cancelled
        } catch (error: Throwable) {
            if (isCurrent(attempt.id)) {
                awaitRemoteRelayTerminal(attempt, error)?.let { terminalRequesterState ->
                    val failed = terminalRequesterState.phase == AndroidScreenRequesterPhase.FAILED
                    val hostSnapshot = state.value
                    completeAttempt(
                        attempt.id,
                        hostSnapshot.copy(
                            phase = if (failed) {
                                AndroidScreenHostPhase.ERROR
                            } else {
                                AndroidScreenHostPhase.ENDED
                            },
                            sessionId = session?.sessionId ?: hostSnapshot.sessionId,
                            sourceName = session?.sourceName ?: hostSnapshot.sourceName,
                            codec = session?.codec ?: hostSnapshot.codec,
                            connectionType = session?.connectionType ?: hostSnapshot.connectionType,
                            detail = terminalRequesterState.detail,
                        ),
                    )
                    return
                }
                if (reconnectRelay(attempt, error, session, decoder, dispatcher)) return
                failAttempt(
                    attempt.id,
                    requesterState().detail
                        ?: error.message?.takeIf(String::isNotBlank)
                        ?: "screen session failed",
                )
            }
        } finally {
            // Release the transport before closing its consumers. ScrcpyVideoStreamReader.close()
            // delegates to Bouncy Castle's TLS stream, whose close may contend with a blocked read;
            // closing the exact requester owner first releases the underlying sockets.
            closeAttemptOwner(attempt)
            runCatching { session?.close() }
            runCatching { decoder?.close() }
            runCatching { dispatcher?.close() }
            synchronized(lock) {
                if (active === attempt) {
                    active = null
                    if (_state.value.phase !in TERMINAL_PHASES) {
                        _state.value = AndroidScreenHostState()
                    }
                }
                attempt.decoder = null
                attempt.dispatcher = null
                attempt.session = null
                attempt.surface = null
            }
        }
    }

    /**
     * Source Stop publishes an authenticated END immediately before it tears down the relay.
     * The independent relay socket can reach TLS EOF first, so briefly allow the status lane to
     * retire the requester before classifying the EOF as a disconnect and reconnecting.
     */
    private suspend fun awaitRemoteRelayTerminal(
        attempt: Attempt,
        error: Throwable,
    ): AndroidScreenRequesterState? {
        if (attempt.connectionMode != AndroidScreenConnectionMode.BROKER_RELAY ||
            !isReconnectableRelayFailure(error)
        ) return null

        requesterState().remoteTerminalOrNull()?.let { return it }
        delay(RELAY_REMOTE_TERMINAL_GRACE_MS)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(attempt.id)) return null
        return requesterState().remoteTerminalOrNull()
    }

    private suspend fun reconnectRelay(
        attempt: Attempt,
        error: Throwable,
        session: AndroidScreenViewerSession?,
        decoder: AndroidScreenVideoDecoder?,
        dispatcher: AndroidScreenControlDispatcher?,
    ): Boolean {
        if (attempt.connectionMode != AndroidScreenConnectionMode.BROKER_RELAY ||
            !isReconnectableRelayFailure(error)
        ) return false
        val retry = synchronized(lock) {
            if (active !== attempt || attempt.stopping) return@synchronized 0
            if (attempt.relayConnectedAtNanos != Long.MIN_VALUE &&
                System.nanoTime() - attempt.relayConnectedAtNanos >= RELAY_STABLE_RESET_NANOS
            ) {
                attempt.relayReconnectAttempts = 0
            }
            if (attempt.relayReconnectAttempts >= MAX_RELAY_RECONNECT_ATTEMPTS) return@synchronized 0
            (++attempt.relayReconnectAttempts).also { number ->
                attempt.session = null
                attempt.decoder = null
                attempt.dispatcher = null
                attempt.supportsVideoVisibility = false
                _state.value = _state.value.copy(
                    phase = AndroidScreenHostPhase.CONNECTING,
                    sessionId = null,
                    dimensions = null,
                    detail = "Relay disconnected; reconnecting ($number/$MAX_RELAY_RECONNECT_ATTEMPTS)",
                    surfaceAttached = attempt.surface != null,
                )
            }
        }
        if (retry == 0) return false

        attempt.closeDetail = "reconnecting broker relay"
        closeAttemptOwner(attempt)
        runCatching { session?.close() }
        runCatching { decoder?.close() }
        runCatching { dispatcher?.close() }
        attempt.ownerCloseStarted.set(false)
        delay(RELAY_RECONNECT_BASE_DELAY_MS shl (retry - 1).coerceAtMost(4))
        currentCoroutineContext().ensureActive()
        if (!isCurrent(attempt.id)) return true
        runAttempt(attempt)
        return true
    }

    private fun failAttempt(attemptId: String, detail: String) {
        val snapshot = synchronized(lock) {
            val current = active?.takeIf { it.id == attemptId } ?: return
            AndroidScreenHostState(
                phase = AndroidScreenHostPhase.ERROR,
                attemptId = current.id,
                sessionId = current.session?.sessionId,
                sourceId = current.sourceId,
                sourceName = current.session?.sourceName ?: _state.value.sourceName,
                codec = current.session?.codec,
                connectionType = current.session?.connectionType ?: _state.value.connectionType,
                dimensions = _state.value.dimensions,
                detail = detail,
                connectionMode = current.connectionMode,
            )
        }
        terminateAttempt(attemptId, snapshot, detail)
    }

    private fun completeAttempt(attemptId: String, terminalState: AndroidScreenHostState) {
        terminateAttempt(attemptId, terminalState, terminalState.detail ?: "screen sharing ended")
    }

    private fun terminateAttempt(
        attemptId: String,
        terminalState: AndroidScreenHostState,
        detail: String,
    ): Boolean {
        val attempt = synchronized(lock) {
            active?.takeIf { it.id == attemptId && !it.stopping }?.also {
                it.stopping = true
                it.closeDetail = detail
                active = null
                _state.value = terminalState
            }
        } ?: return false

        // AndroidScreenMirrorRequester.closeOwner atomically retires logical ownership and queues
        // terminal delivery and raw-socket cleanup without waiting for it. Do this before Stop
        // returns so a fast retry cannot race the old requester's active slot.
        closeAttemptOwner(attempt)
        // Even Job.cancel() can synchronously invoke arbitrary cancellation handlers. Run it with
        // all physical consumers on the disposable cleanup lane so Activity/FGS main never owns a
        // potentially blocking teardown operation.
        scheduleCleanup(attempt)
        return true
    }

    private fun scheduleCleanup(attempt: Attempt) {
        if (!attempt.cleanupStarted.compareAndSet(false, true)) return
        Thread(
            {
                runCatching { attempt.job?.cancel() }
                runCatching { attempt.session?.close() }
                runCatching { attempt.decoder?.close() }
                runCatching { attempt.dispatcher?.close() }
            },
            "notisync-screen-cleanup",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun closeAttemptOwner(attempt: Attempt) {
        if (!attempt.ownerCloseStarted.compareAndSet(false, true)) return
        runCatching { closeOwner(attempt.id, attempt.closeDetail) }
    }

    private fun isCurrent(attemptId: String): Boolean = synchronized(lock) {
        active?.let { it.id == attemptId && !it.stopping } == true
    }

    private inline fun updateCurrent(
        attemptId: String,
        transform: (AndroidScreenHostState) -> AndroidScreenHostState,
    ) {
        synchronized(lock) {
            if (active?.let { it.id == attemptId && !it.stopping } == true) {
                _state.value = transform(_state.value)
            }
        }
    }

    private companion object {
        const val MAX_TOKEN_LENGTH = 128
        const val DEFAULT_CLOSE_DETAIL = "Android viewer closed"
        const val MAX_RELAY_RECONNECT_ATTEMPTS = 6
        const val RELAY_RECONNECT_BASE_DELAY_MS = 250L
        const val RELAY_REMOTE_TERMINAL_GRACE_MS = 750L
        const val RELAY_STABLE_RESET_NANOS = 30_000_000_000L
        val TERMINAL_PHASES = setOf(AndroidScreenHostPhase.ENDED, AndroidScreenHostPhase.ERROR)
        val HOST_ALLOWED_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
        )
    }
}

private fun AndroidScreenRequesterState.remoteTerminalOrNull(): AndroidScreenRequesterState? =
    takeIf {
        it.phase == AndroidScreenRequesterPhase.IDLE ||
            it.phase == AndroidScreenRequesterPhase.FAILED
    }

internal fun isReconnectableRelayFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(10) {
        val value = current ?: return false
        val message = value.message.orEmpty().lowercase()
        if (RECONNECTABLE_RELAY_FAILURES.any(message::contains)) return true
        current = value.cause?.takeUnless { cause -> cause === value }
    }
    return false
}

private val RECONNECTABLE_RELAY_FAILURES = listOf(
    "no close_notify alert",
    "broker relay closed",
    "websocket",
    "connection reset",
    "broken pipe",
    "decoder input stalled",
    "video decoder stalled",
    "unexpected end",
    "end of stream",
    "eof",
)
