package net.extrawdw.apps.notisync.screen

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.screen.GeneratedSessionSecrets
import net.extrawdw.notisync.screen.LanSessionListener
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.ScreenSessionProtocol
import net.extrawdw.notisync.screen.SecureChannelPair
import net.extrawdw.notisync.screen.SessionDescriptor

internal enum class AndroidScreenRequesterPhase { IDLE, PREPARING, WAITING, CONNECTED, FAILED }
internal enum class AndroidScreenConnectionMode { DIRECT, BROKER_RELAY }
internal enum class AndroidScreenConnectionType { LOCAL_NETWORK, WIFI_AWARE, BROKER_RELAY }

internal data class AndroidScreenRequesterState(
    val phase: AndroidScreenRequesterPhase = AndroidScreenRequesterPhase.IDLE,
    val sessionId: String? = null,
    val sourceId: ClientId? = null,
    val sourceName: String? = null,
    val codec: ScreenMirrorCodec? = null,
    val detail: String? = null,
    val connectionMode: AndroidScreenConnectionMode = AndroidScreenConnectionMode.DIRECT,
)

/** A resolver result is authority: implementations return only a currently trusted own device. */
internal data class AndroidScreenSource(
    val clientId: ClientId,
    val displayName: String,
    val capabilities: Set<Capability>,
)

internal fun interface AndroidScreenSourceResolver {
    fun resolve(clientId: ClientId): AndroidScreenSource?
}

/** Narrow channel contract consumed by the requester-side foreground session host. */
internal interface AndroidScreenViewerSession : AutoCloseable {
    val sessionId: String
    val sourceId: ClientId
    val sourceName: String
    val sourceCapabilities: Set<Capability>
    val codec: ScreenMirrorCodec
    val connectionType: AndroidScreenConnectionType
    val videoInput: InputStream
    val controlInput: InputStream
    val controlOutput: OutputStream
}

/**
 * Authenticated viewer-side channels. The requester-side session host owns decoding and control,
 * while [close] delegates lifecycle ownership back to the requester so the source receives exactly
 * one CANCEL or END.
 */
internal class AndroidViewerSession internal constructor(
    override val sessionId: String,
    override val sourceId: ClientId,
    override val sourceName: String,
    override val sourceCapabilities: Set<Capability>,
    override val codec: ScreenMirrorCodec,
    override val connectionType: AndroidScreenConnectionType,
    val descriptor: SessionDescriptor,
    private val pair: SecureChannelPair,
    private val closeOwner: (String, String) -> Unit,
) : AndroidScreenViewerSession {
    private val transportClosed = AtomicBoolean()

    override val videoInput: InputStream get() = pair.video.input
    override val controlInput: InputStream get() = pair.control.input
    override val controlOutput: OutputStream get() = pair.control.output

    override fun close() = closeOwner(sessionId, "Android viewer closed")

    internal fun closeTransport() {
        if (transportClosed.compareAndSet(false, true)) pair.close()
    }
}

/** Android requester rendezvous and one-session transport owner used by the requester host/FGS. */
internal class AndroidScreenMirrorRequester(
    context: android.content.Context,
    private val ownClientId: ClientId,
    private val channel: SecureChannel,
    private val broker: BrokerClient,
    private val sourceResolver: AndroidScreenSourceResolver,
    private val scope: CoroutineScope,
    private val decoderSupport: () -> AndroidScreenDecoderSupport =
        AndroidScreenDecoderCapabilities::detect,
    private val preferredCodec: (ClientId) -> ScreenMirrorCodec? = { null },
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var active: RequestContext? = null
    private val _state = MutableStateFlow(AndroidScreenRequesterState())
    val state: StateFlow<AndroidScreenRequesterState> = _state.asStateFlow()

    fun supportsBrokerRelay(sourceId: ClientId): Boolean =
        sourceResolver.resolve(sourceId)?.capabilities
            ?.contains(Capability.SCREEN_MIRROR_BROKER_RELAY_V1) == true

    /**
     * Opens the listener before sending the HIGH request and suspends until both authenticated channels
     * arrive. Only one local viewer session may be opening or active at once.
     */
    suspend fun open(
        sourceId: ClientId,
        ownerToken: String,
        connectionMode: AndroidScreenConnectionMode = AndroidScreenConnectionMode.DIRECT,
    ): AndroidViewerSession = withContext(Dispatchers.IO) {
        require(ownerToken.isNotBlank() && ownerToken.length <= 128) { "invalid viewer owner token" }
        val source = requireNotNull(sourceResolver.resolve(sourceId)) {
            "screen source is not a trusted own device"
        }
        if (connectionMode == AndroidScreenConnectionMode.BROKER_RELAY) {
            require(Capability.SCREEN_MIRROR_BROKER_RELAY_V1 in source.capabilities) {
                "screen source does not support broker relay"
            }
        }
        val codec = selectAndroidScreenCodec(
            sourceCapabilities = source.capabilities,
            decoderSupport = decoderSupport(),
            preferredCodec = preferredCodec(source.clientId),
        )
            ?: error("source and this Android device have no common screen codec")
        val issuedAt = clock.millis()
        val expiresAt = issuedAt + REQUEST_LIFETIME.toMillis()
        val descriptor = SessionDescriptor(
            sessionId = "screen:${UUID.randomUUID()}",
            sourcePeerId = source.clientId.value,
            requesterPeerId = ownClientId.value,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAt,
            codec = codec.name.lowercase(),
            controlEnabled = true,
            clipboardEnabled = false,
            maxDimension = DEFAULT_MAX_DIMENSION,
            maxFps = DEFAULT_MAX_FPS,
            videoBitrateBps = DEFAULT_BITRATE_BPS,
        )
        val requestContext = RequestContext(source, descriptor, codec, ownerToken, connectionMode)
        synchronized(lock) {
            check(active == null) { "another Android screen viewer is already active" }
            active = requestContext
            _state.value = requestContext.state(AndroidScreenRequesterPhase.PREPARING)
        }

        var lan: AndroidScreenRequesterLan? = null
        var lanListener: LanSessionListener? = null
        var awareListener: AndroidWifiAwareScreenListener? = null
        var relayListener: BrokerRelayScreenSessionListener? = null
        var advertisement: AutoCloseable? = null
        var registry: PskRegistry? = null
        var transferred = false
        try {
            var lanFailure: Throwable? = null
            if (connectionMode == AndroidScreenConnectionMode.DIRECT) try {
                lan = AndroidScreenRequesterLan.openOrNull(appContext).also { requestContext.lan = it }
                requestContext.requireOpen()
                lan?.let { availableLan ->
                    lanListener = availableLan.track(
                        LanSessionListener.open(availableLan.addressProvider),
                    ).also { requestContext.lanListener = it }
                    val firstCandidate = lanListener?.candidates?.firstOrNull()
                        ?: error("selected LAN has no listenable address")
                    advertisement = withTimeoutOrNull(NSD_REGISTRATION_TIMEOUT.toMillis()) {
                        availableLan.advertise(
                            "notisync-${UUID.randomUUID()}",
                            requireNotNull(firstCandidate.port),
                        )
                    }?.also(availableLan::track)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lanFailure = error
                advertisement?.let { runCatching { it.close() } }
                advertisement = null
                lanListener?.let { runCatching { it.close() } }
                lanListener = null
                requestContext.lanListener = null
                runCatching { lan?.close() }
                lan = null
                requestContext.lan = null
            }
            requestContext.requireOpen()

            registry = PskRegistry(clock)
            GeneratedSessionSecrets.generate(random).use { generated ->
                val routingToken = generated.routingToken.copy()
                val masterPsk = generated.masterPsk.copy()
                try {
                    registry.register(descriptor, routingToken, masterPsk)
                    var awareFailure: Throwable? = null
                    awareListener = if (connectionMode == AndroidScreenConnectionMode.DIRECT) try {
                        AndroidWifiAwareScreenListener.open(
                            context = appContext,
                            descriptor = descriptor,
                            routingToken = routingToken,
                            masterPsk = masterPsk,
                            setupTimeout = if (lanListener == null) {
                                WIFI_AWARE_ONLY_SETUP_TIMEOUT
                            } else {
                                WIFI_AWARE_FALLBACK_SETUP_TIMEOUT
                            },
                            random = random,
                        ).also { requestContext.awareListener = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        awareFailure = error
                        Log.w(LOG_TAG, "Wi-Fi Aware screen listener unavailable", error)
                        null
                    } else null
                    if (connectionMode == AndroidScreenConnectionMode.BROKER_RELAY) {
                        relayListener = BrokerRelayScreenSessionListener(
                            broker = broker,
                            relayId = BrokerRelayScreenSessionListener.randomRelayId(random),
                            requesterId = ownClientId,
                            sourceId = source.clientId,
                            expiresAt = expiresAt,
                        ).also { requestContext.relayListener = it }
                    }
                    requestContext.requireOpen()
                    if (lanListener == null && awareListener == null && relayListener == null) {
                        val failure = awareFailure ?: lanFailure
                        val detail = failure?.deepestRequesterTransportMessage()
                        throw IllegalStateException(
                            buildString {
                                append("no usable LAN or Wi-Fi Aware listener is available")
                                if (detail != null) append(": ").append(detail)
                            },
                            failure,
                        )
                    }
                    val candidates = requestCandidates(
                        connectionMode = connectionMode,
                        lanListener = lanListener,
                        advertisement = advertisement,
                        awareListener = awareListener,
                        relayListener = relayListener,
                    )
                    Log.i(
                        LOG_TAG,
                        "Advertising screen endpoints: " +
                            ScreenMirrorConnectionCandidate.entriesSummary(candidates),
                    )
                    val request = ScreenMirrorSync(
                        action = ScreenMirrorAction.REQUEST,
                        protocolVersion = ScreenSessionProtocol.VERSION,
                        sessionId = descriptor.sessionId,
                        requesterPeerId = ownClientId,
                        sourcePeerId = source.clientId,
                        issuedAt = issuedAt,
                        expiresAt = expiresAt,
                        routingToken = routingToken,
                        masterPsk = masterPsk,
                        codec = codec,
                        requestControl = true,
                        requestClipboard = false,
                        maxDimension = descriptor.maxDimension,
                        maxFps = descriptor.maxFps,
                        videoBitrateBps = descriptor.videoBitrateBps,
                        candidates = candidates,
                    )
                    val recipients = channel.send(
                        typ = MessageType.DATA_SYNC,
                        body = ProtocolCodec.encodeToCbor(
                            DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = request),
                        ),
                        scope = Recipients.OnlyCapable(source.clientId, request.requiredSourceCapabilities()),
                        urgency = Urgency.HIGH,
                    )
                    requestContext.requestSent.set(recipients == 1)
                    check(recipients == 1) { "screen source is no longer routable" }
                } finally {
                    routingToken.fill(0)
                    masterPsk.fill(0)
                }
            }
            synchronized(lock) {
                if (active !== requestContext || requestContext.closed.get() || requestContext.remoteClosed.get()) {
                    throw AndroidScreenRequesterClosedException()
                }
                _state.value = requestContext.state(AndroidScreenRequesterPhase.WAITING)
            }

            val remaining = (expiresAt - clock.millis()).coerceAtLeast(1L)
            val accepted = acceptFirstPair(
                listeners = listOfNotNull(lanListener, awareListener, relayListener),
                sessionId = descriptor.sessionId,
                registry = registry,
                timeout = Duration.ofMillis(remaining),
            )
            val pair = accepted.pair
            val connectionType = when {
                accepted.listener === lanListener -> AndroidScreenConnectionType.LOCAL_NETWORK
                accepted.listener === awareListener -> AndroidScreenConnectionType.WIFI_AWARE
                accepted.listener === relayListener -> AndroidScreenConnectionType.BROKER_RELAY
                else -> error("screen session was accepted by an unknown listener")
            }
            if (accepted.listener === lanListener) {
                requireNotNull(lan).track(pair)
                awareListener?.let { resource ->
                    requestContext.awareListener = null
                    runCatching { resource.close() }
                    awareListener = null
                }
            } else {
                lanListener?.let { resource ->
                    requestContext.lanListener = null
                    lan?.untrack(resource)
                    runCatching { resource.close() }
                    lanListener = null
                }
                advertisement?.let { resource ->
                    lan?.untrack(resource)
                    runCatching { resource.close() }
                    advertisement = null
                }
                runCatching { lan?.close() }
                lan = null
                requestContext.lan = null
            }
            val session = AndroidViewerSession(
                sessionId = descriptor.sessionId,
                sourceId = source.clientId,
                sourceName = source.displayName,
                sourceCapabilities = source.capabilities.toSet(),
                codec = codec,
                connectionType = connectionType,
                descriptor = descriptor,
                pair = pair,
                closeOwner = ::close,
            )
            synchronized(lock) {
                if (active !== requestContext || requestContext.closed.get() || requestContext.remoteClosed.get()) {
                    session.closeTransport()
                    throw AndroidScreenRequesterClosedException()
                }
                requestContext.connected.set(true)
                requestContext.session = session
                _state.value = requestContext.state(AndroidScreenRequesterPhase.CONNECTED)
            }
            lanListener?.let { resource ->
                requestContext.lanListener = null
                lan?.untrack(resource)
                resource.close()
                lanListener = null
            }
            advertisement?.let {
                lan?.untrack(it)
                it.close()
                advertisement = null
            }
            registry.close()
            registry = null
            transferred = true
            session
        } catch (error: Throwable) {
            val locallyClosed = requestContext.closed.get()
            val remotelyClosed = requestContext.remoteClosed.get()
            if (!remotelyClosed) sendTerminalOnce(requestContext, requestContext.closeDetail ?: error.message)
            synchronized(lock) {
                if (active === requestContext) {
                    active = null
                    _state.value = if (locallyClosed) {
                        AndroidScreenRequesterState()
                    } else {
                        requestContext.state(
                            AndroidScreenRequesterPhase.FAILED,
                            requestContext.remoteDetail ?: error.message ?: "screen session failed",
                        )
                    }
                }
            }
            if (locallyClosed && error !is CancellationException) {
                throw AndroidScreenRequesterClosedException()
            }
            throw error
        } finally {
            if (!transferred) {
                requestContext.session?.closeTransport()
                advertisement?.let { resource ->
                    lan?.untrack(resource)
                    runCatching { resource.close() }
                }
                lanListener?.let { resource ->
                    lan?.untrack(resource)
                    runCatching { resource.close() }
                }
                runCatching { awareListener?.close() }
                runCatching { relayListener?.close() }
                runCatching { registry?.close() }
                runCatching { lan?.close() }
            }
        }
    }

    /** Called from the shared authenticated DATA_SYNC dispatch before its relay item is acknowledged. */
    fun onScreenMirrorSync(message: InboundMessage, sync: DataSync) {
        if (sync.kind != DataSyncKind.SCREEN_MIRRORING) return
        val screen = sync.screenMirror ?: return
        if (screen.action == ScreenMirrorAction.REQUEST) return
        val context = synchronized(lock) { active } ?: return
        if (!AndroidScreenRequesterStatusPolicy.accepts(
                screen = screen,
                senderId = message.senderId,
                senderOwnDevice = message.senderOwnDevice,
                envelopeCreatedAt = message.createdAt,
                expectedSessionId = context.descriptor.sessionId,
                expectedSourceId = context.source.clientId,
                ownClientId = ownClientId,
                now = clock.millis(),
            )
        ) return

        val terminalStatus = when (screen.action) {
            ScreenMirrorAction.CANCEL, ScreenMirrorAction.END -> screen.status ?: ScreenMirrorStatus.ENDED
            ScreenMirrorAction.STATUS -> screen.status?.takeUnless {
                it == ScreenMirrorStatus.CONNECTING || it == ScreenMirrorStatus.READY
            }
            ScreenMirrorAction.REQUEST -> null
        } ?: return
        context.remoteDetail = screen.detail?.take(DETAIL_LIMIT)
            ?: terminalStatus.name.lowercase().replace('_', ' ')
        context.remoteClosed.set(true)
        synchronized(lock) {
            if (active === context) {
                active = null
                _state.value = if (terminalStatus == ScreenMirrorStatus.ENDED) {
                    AndroidScreenRequesterState()
                } else {
                    context.state(AndroidScreenRequesterPhase.FAILED, context.remoteDetail)
                }
            }
        }
        scheduleContextCleanup(context)
    }

    /** Idempotent local terminal path. Before pair acceptance it sends CANCEL; after it sends END. */
    fun close(sessionId: String, detail: String = "Android viewer closed") {
        val context = synchronized(lock) {
            active?.takeIf { it.descriptor.sessionId == sessionId }?.also {
                active = null
                _state.value = AndroidScreenRequesterState()
            }
        } ?: return
        context.closeDetail = detail.take(DETAIL_LIMIT)
        context.closed.set(true)
        // Terminal delivery is logically ordered before any OEM network or TLS cleanup. The
        // status lane is non-blocking and the physical resources are retired independently.
        sendTerminalOnce(context, context.closeDetail)
        scheduleContextCleanup(context)
    }

    /** Close only the attempt owned by one viewer generation; a stale callback is a no-op. */
    fun closeOwner(ownerToken: String, detail: String = "Android viewer closed") {
        val sessionId = synchronized(lock) {
            active?.takeIf { it.ownerToken == ownerToken }?.descriptor?.sessionId
        } ?: return
        close(sessionId, detail)
    }

    override fun close() {
        val sessionId = synchronized(lock) { active?.descriptor?.sessionId } ?: return
        close(sessionId)
    }

    private fun sendTerminalOnce(context: RequestContext, detail: String?) {
        if (!context.requestSent.get() || !context.terminalSent.compareAndSet(false, true)) return
        val action = if (context.connected.get()) ScreenMirrorAction.END else ScreenMirrorAction.CANCEL
        val status = ScreenMirrorStatus.ENDED.takeIf { action == ScreenMirrorAction.END }
        val terminal = ScreenMirrorSync(
            action = action,
            protocolVersion = ScreenSessionProtocol.VERSION,
            sessionId = context.descriptor.sessionId,
            requesterPeerId = ownClientId,
            sourcePeerId = context.source.clientId,
            issuedAt = clock.millis(),
            status = status,
            detail = detail?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")?.take(DETAIL_LIMIT),
        )
        scope.launch {
            runCatching {
                channel.send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = terminal),
                    ),
                    Recipients.Only(context.source.clientId),
                    Urgency.NORMAL,
                )
            }
        }
    }

    private fun scheduleContextCleanup(context: RequestContext) {
        if (!context.cleanupStarted.compareAndSet(false, true)) return
        runCatching {
            Thread(
                {
                    // Release authenticated streams first so decoder/control readers never wait
                    // behind NSD, Wi-Fi Aware, or OEM network-lifecycle teardown.
                    runCatching { context.session?.closeTransport() }
                    runCatching { context.lanListener?.close() }
                    runCatching { context.awareListener?.close() }
                    runCatching { context.relayListener?.close() }
                    runCatching { context.lan?.close() }
                },
                "notisync-screen-requester-cleanup",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private class RequestContext(
        val source: AndroidScreenSource,
        val descriptor: SessionDescriptor,
        val codec: ScreenMirrorCodec,
        val ownerToken: String,
        val connectionMode: AndroidScreenConnectionMode,
    ) {
        val requestSent = AtomicBoolean()
        val connected = AtomicBoolean()
        val terminalSent = AtomicBoolean()
        val closed = AtomicBoolean()
        val remoteClosed = AtomicBoolean()
        val cleanupStarted = AtomicBoolean()
        @Volatile var closeDetail: String? = null
        @Volatile var remoteDetail: String? = null
        @Volatile var lan: AndroidScreenRequesterLan? = null
        @Volatile var lanListener: LanSessionListener? = null
        @Volatile var awareListener: AndroidWifiAwareScreenListener? = null
        @Volatile var relayListener: BrokerRelayScreenSessionListener? = null
        @Volatile var session: AndroidViewerSession? = null

        fun requireOpen() {
            if (closed.get() || remoteClosed.get()) throw AndroidScreenRequesterClosedException()
        }

        fun state(phase: AndroidScreenRequesterPhase, detail: String? = null) =
            AndroidScreenRequesterState(
                phase = phase,
                sessionId = descriptor.sessionId,
                sourceId = source.clientId,
                sourceName = source.displayName,
                codec = codec,
                detail = detail,
                connectionMode = connectionMode,
            )
    }

    private companion object {
        val REQUEST_LIFETIME: Duration = Duration.ofMinutes(5)
        val NSD_REGISTRATION_TIMEOUT: Duration = Duration.ofMillis(1_500)
        val WIFI_AWARE_FALLBACK_SETUP_TIMEOUT: Duration = Duration.ofSeconds(3)
        val WIFI_AWARE_ONLY_SETUP_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val DEFAULT_MAX_DIMENSION = 1_920
        const val DEFAULT_MAX_FPS = 60
        const val DEFAULT_BITRATE_BPS = 8_000_000
        const val DETAIL_LIMIT = 160
        const val LOG_TAG = "NotiSyncScreenRequester"
    }
}

internal data class AcceptedScreenPair(
    val listener: ScreenSessionListener,
    val pair: SecureChannelPair,
)

private data class ListenerCompletion(
    val listener: ScreenSessionListener,
    val pair: SecureChannelPair? = null,
    val error: Throwable? = null,
)

/**
 * Waits for the first listener to authenticate both channels; a failed path does not cancel fallback.
 *
 * Every completed pair has exactly one owner while the listener race is active: either its producer,
 * the completion queue, or this function after receipt. That ownership is released to the caller only
 * after all losing producers have stopped and their queued results have been drained.
 */
internal suspend fun acceptFirstPair(
    listeners: List<ScreenSessionListener>,
    sessionId: String,
    registry: PskRegistry,
    timeout: Duration,
): AcceptedScreenPair {
    var transferPending: AcceptedScreenPair? = null
    try {
        val accepted = coroutineScope {
            require(listeners.isNotEmpty()) { "no screen session listeners" }
            val completions = Channel<ListenerCompletion>(listeners.size)
            val jobs = listeners.map { listener ->
                launch(Dispatchers.IO) {
                    var completion: ListenerCompletion? = null
                    var queued = false
                    try {
                        completion = try {
                            ListenerCompletion(
                                listener = listener,
                                pair = listener.acceptPair(sessionId, registry, timeout),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            ListenerCompletion(listener = listener, error = error)
                        }
                        // Teardown closes the queue before stopping losing listeners. A loser may
                        // finish in that window, so a failed handoff is normal and must not throw a
                        // ClosedSendChannelException that cancels the already-selected winner.
                        queued = completions.trySend(completion).isSuccess
                    } finally {
                        // A successful send moves pair ownership to the completion queue. If the
                        // race is cancelled or the channel closes first, the producer still owns it.
                        if (!queued) completion?.pair?.let { pair -> runCatching { pair.close() } }
                    }
                }
            }
            var selected: AcceptedScreenPair? = null
            var lastFailure: Throwable? = null
            try {
                repeat(listeners.size) {
                    val completion = completions.receive()
                    val pair = completion.pair
                    if (pair != null) {
                        val winner = AcceptedScreenPair(completion.listener, pair)
                        selected = winner
                        closeListenerRace(
                            listeners = listeners,
                            preservedListener = completion.listener,
                            jobs = jobs,
                            completions = completions,
                        )
                        currentCoroutineContext().ensureActive()
                        transferPending = winner
                        return@coroutineScope winner
                    }
                    lastFailure = completion.error ?: lastFailure
                }
                throw IOException("all screen session listeners failed", lastFailure)
            } catch (error: Throwable) {
                closeListenerRace(
                    listeners = listeners,
                    preservedListener = null,
                    jobs = jobs,
                    completions = completions,
                )
                selected?.pair?.let { pair -> runCatching { pair.close() } }
                throw error
            }
        }
        // There are no suspension points between clearing this guard and returning. From here the
        // caller owns both the authenticated pair and the winning listener's required lifetime.
        transferPending = null
        return accepted
    } finally {
        // coroutineScope has a prompt-cancellation exit. If it withholds an already selected result,
        // this guard closes the pair and its listener instead of leaking an unobservable session.
        transferPending?.let { pending ->
            runCatching { pending.listener.close() }
            runCatching { pending.pair.close() }
        }
    }
}

private suspend fun closeListenerRace(
    listeners: List<ScreenSessionListener>,
    preservedListener: ScreenSessionListener?,
    jobs: List<Job>,
    completions: Channel<ListenerCompletion>,
) = withContext(NonCancellable) {
    // Closing the channel first ensures a producer that finishes during teardown retains and closes
    // its own pair instead of placing another resource into the queue after the final drain.
    completions.close()
    listeners.forEach { listener ->
        if (listener !== preservedListener) runCatching { listener.close() }
    }
    jobs.forEach { job -> runCatching { job.cancel() } }
    jobs.forEach { job -> runCatching { job.join() } }
    while (true) {
        val completion = completions.tryReceive().getOrNull() ?: break
        completion.pair?.let { pair -> runCatching { pair.close() } }
    }
}

private fun requestCandidates(
    connectionMode: AndroidScreenConnectionMode,
    lanListener: LanSessionListener?,
    advertisement: AutoCloseable?,
    awareListener: AndroidWifiAwareScreenListener?,
    relayListener: BrokerRelayScreenSessionListener?,
): List<ScreenMirrorConnectionCandidate> {
    val dns = (advertisement as? net.extrawdw.notisync.screen.ServiceAdvertisement)?.candidate
    return selectScreenMirrorRequestCandidates(
        lanCandidates = lanListener?.candidates.orEmpty(),
        dnsCandidate = dns,
        awareCandidates = awareListener?.candidates.orEmpty(),
        relayCandidates = relayListener?.candidates.orEmpty(),
        connectionMode = connectionMode,
    ).map(ScreenConnectionCandidate::toProtocolCandidate)
}

internal fun selectScreenMirrorRequestCandidates(
    lanCandidates: List<ScreenConnectionCandidate>,
    dnsCandidate: ScreenConnectionCandidate?,
    awareCandidates: List<ScreenConnectionCandidate>,
    relayCandidates: List<ScreenConnectionCandidate> = emptyList(),
    connectionMode: AndroidScreenConnectionMode = AndroidScreenConnectionMode.DIRECT,
): List<ScreenConnectionCandidate> {
    val aware = awareCandidates.singleOrNull()
    val relay = relayCandidates.singleOrNull()
    return when (connectionMode) {
        AndroidScreenConnectionMode.DIRECT -> {
            require(relayCandidates.isEmpty()) { "direct screen request must not advertise relay" }
            val reserved = listOfNotNull(dnsCandidate, aware)
            val direct = lanCandidates
                .take((SCREEN_MIRROR_MAX_CANDIDATES - reserved.size).coerceAtLeast(0))
            (direct + reserved).distinct().take(SCREEN_MIRROR_MAX_CANDIDATES)
        }
        AndroidScreenConnectionMode.BROKER_RELAY -> {
            require(lanCandidates.isEmpty() && dnsCandidate == null && awareCandidates.isEmpty()) {
                "relay screen request must not advertise direct candidates"
            }
            listOfNotNull(relay)
        }
    }
}

private const val SCREEN_MIRROR_MAX_CANDIDATES = 8

private fun ScreenMirrorConnectionCandidate.Companion.entriesSummary(
    candidates: List<ScreenMirrorConnectionCandidate>,
): String = candidates.groupingBy { it.kind }.eachCount().entries
    .sortedBy { it.key }
    .joinToString { (kind, count) -> "$kind=$count" }

private fun Throwable.deepestRequesterTransportMessage(): String? {
    var current: Throwable? = this
    var selected: String? = null
    repeat(8) {
        val value = current ?: return@repeat
        value.message?.takeIf(String::isNotBlank)?.let { selected = it }
        current = value.cause?.takeUnless { cause -> cause === value }
    }
    return sanitizeScreenTransportDetail(selected)
}

internal object AndroidScreenRequesterStatusPolicy {
    fun accepts(
        screen: ScreenMirrorSync,
        senderId: ClientId,
        senderOwnDevice: Boolean,
        envelopeCreatedAt: Long,
        expectedSessionId: String,
        expectedSourceId: ClientId,
        ownClientId: ClientId,
        now: Long,
    ): Boolean =
        senderOwnDevice &&
            senderId == expectedSourceId &&
            screen.action != ScreenMirrorAction.REQUEST &&
            screen.protocolVersion == ScreenSessionProtocol.VERSION &&
            screen.sessionId == expectedSessionId &&
            screen.sourcePeerId == expectedSourceId &&
            screen.requesterPeerId == ownClientId &&
            screen.issuedAt > 0 && envelopeCreatedAt > 0 &&
            abs(screen.issuedAt - envelopeCreatedAt) <= MAX_SIGNED_TIMESTAMP_DELTA_MS &&
            envelopeCreatedAt <= now + MAX_SIGNED_TIMESTAMP_DELTA_MS &&
            now - envelopeCreatedAt <= MAX_STATUS_AGE_MS

    private const val MAX_SIGNED_TIMESTAMP_DELTA_MS = 2L * 60 * 1_000
    private const val MAX_STATUS_AGE_MS = 5L * 60 * 1_000
}

internal class AndroidScreenRequesterClosedException : Exception("Android screen viewer was closed")

private fun ScreenConnectionCandidate.toProtocolCandidate(): ScreenMirrorConnectionCandidate =
    ScreenMirrorConnectionCandidate(kind, host, port, serviceName, interfaceName)
