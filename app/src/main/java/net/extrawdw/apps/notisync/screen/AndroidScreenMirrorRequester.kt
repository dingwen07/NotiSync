package net.extrawdw.apps.notisync.screen

import android.media.MediaCodecList
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
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
import net.extrawdw.notisync.screen.ScreenSessionProtocol
import net.extrawdw.notisync.screen.SecureChannelPair
import net.extrawdw.notisync.screen.SessionDescriptor

internal enum class AndroidScreenRequesterPhase { IDLE, PREPARING, WAITING, CONNECTED, FAILED }

internal data class AndroidScreenRequesterState(
    val phase: AndroidScreenRequesterPhase = AndroidScreenRequesterPhase.IDLE,
    val sessionId: String? = null,
    val sourceId: ClientId? = null,
    val sourceName: String? = null,
    val codec: ScreenMirrorCodec? = null,
    val detail: String? = null,
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

/**
 * Authenticated viewer-side channels. The Activity owns decoding and control, while [close] delegates
 * lifecycle ownership back to the requester so the source receives exactly one CANCEL or END.
 */
internal class AndroidViewerSession internal constructor(
    val sessionId: String,
    val sourceId: ClientId,
    val sourceName: String,
    val codec: ScreenMirrorCodec,
    val descriptor: SessionDescriptor,
    private val pair: SecureChannelPair,
    private val closeOwner: (String, String) -> Unit,
) : AutoCloseable {
    private val transportClosed = AtomicBoolean()

    val videoInput: InputStream get() = pair.video.input
    val controlInput: InputStream get() = pair.control.input
    val controlOutput: OutputStream get() = pair.control.output

    override fun close() = closeOwner(sessionId, "Android viewer closed")

    internal fun closeTransport() {
        if (transportClosed.compareAndSet(false, true)) pair.close()
    }
}

/** Android requester rendezvous and one-session owner. Video parsing remains in the foreground Activity. */
internal class AndroidScreenMirrorRequester(
    context: android.content.Context,
    private val ownClientId: ClientId,
    private val channel: SecureChannel,
    private val sourceResolver: AndroidScreenSourceResolver,
    private val scope: CoroutineScope,
    private val supportedDecoderCodecs: () -> Set<ScreenMirrorCodec> =
        AndroidScreenDecoderCapabilities::supportedCodecs,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var active: RequestContext? = null
    private val _state = MutableStateFlow(AndroidScreenRequesterState())
    val state: StateFlow<AndroidScreenRequesterState> = _state.asStateFlow()

    /**
     * Opens the listener before sending the HIGH request and suspends until both authenticated channels
     * arrive. Only one local viewer session may be opening or active at once.
     */
    suspend fun open(sourceId: ClientId, ownerToken: String): AndroidViewerSession = withContext(Dispatchers.IO) {
        require(ownerToken.isNotBlank() && ownerToken.length <= 128) { "invalid viewer owner token" }
        val source = requireNotNull(sourceResolver.resolve(sourceId)) {
            "screen source is not a trusted own device"
        }
        val codec = selectAndroidScreenCodec(source.capabilities, supportedDecoderCodecs())
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
        val requestContext = RequestContext(source, descriptor, codec, ownerToken)
        synchronized(lock) {
            check(active == null) { "another Android screen viewer is already active" }
            active = requestContext
            _state.value = requestContext.state(AndroidScreenRequesterPhase.PREPARING)
        }

        var lan: AndroidScreenRequesterLan? = null
        var listener: LanSessionListener? = null
        var advertisement: AutoCloseable? = null
        var registry: PskRegistry? = null
        var transferred = false
        try {
            lan = AndroidScreenRequesterLan.open(appContext).also { requestContext.lan = it }
            requestContext.requireOpen()
            listener = lan.track(LanSessionListener.open(lan.addressProvider)).also {
                requestContext.listener = it
            }
            val firstCandidate = listener.candidates.firstOrNull()
                ?: error("selected LAN has no listenable address")
            advertisement = withTimeoutOrNull(NSD_REGISTRATION_TIMEOUT.toMillis()) {
                lan.advertise("notisync-${UUID.randomUUID()}", requireNotNull(firstCandidate.port))
            }?.also(lan::track)
            requestContext.requireOpen()

            registry = PskRegistry(clock)
            GeneratedSessionSecrets.generate(random).use { generated ->
                val routingToken = generated.routingToken.copy()
                val masterPsk = generated.masterPsk.copy()
                try {
                    registry.register(descriptor, routingToken, masterPsk)
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
                        candidates = (listener.candidates + listOfNotNull(
                            (advertisement as? net.extrawdw.notisync.screen.ServiceAdvertisement)?.candidate,
                        )).map(ScreenConnectionCandidate::toProtocolCandidate),
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
            val pair = listener.acceptPair(descriptor.sessionId, registry, Duration.ofMillis(remaining))
            lan.track(pair)
            val session = AndroidViewerSession(
                sessionId = descriptor.sessionId,
                sourceId = source.clientId,
                sourceName = source.displayName,
                codec = codec,
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
            requestContext.listener = null
            lan.untrack(listener)
            listener.close()
            advertisement?.let {
                lan.untrack(it)
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
                listener?.let { resource ->
                    lan?.untrack(resource)
                    runCatching { resource.close() }
                }
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
        context.listener?.close()
        context.session?.closeTransport()
        context.lan?.close()
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
        context.listener?.close()
        context.session?.closeTransport()
        context.lan?.close()
        sendTerminalOnce(context, context.closeDetail)
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

    private class RequestContext(
        val source: AndroidScreenSource,
        val descriptor: SessionDescriptor,
        val codec: ScreenMirrorCodec,
        val ownerToken: String,
    ) {
        val requestSent = AtomicBoolean()
        val connected = AtomicBoolean()
        val terminalSent = AtomicBoolean()
        val closed = AtomicBoolean()
        val remoteClosed = AtomicBoolean()
        @Volatile var closeDetail: String? = null
        @Volatile var remoteDetail: String? = null
        @Volatile var lan: AndroidScreenRequesterLan? = null
        @Volatile var listener: LanSessionListener? = null
        @Volatile var session: AndroidViewerSession? = null

        fun requireOpen() {
            if (closed.get() || remoteClosed.get()) throw AndroidScreenRequesterClosedException()
            lan?.requireActive()
        }

        fun state(phase: AndroidScreenRequesterPhase, detail: String? = null) =
            AndroidScreenRequesterState(
                phase = phase,
                sessionId = descriptor.sessionId,
                sourceId = source.clientId,
                sourceName = source.displayName,
                codec = codec,
                detail = detail,
            )
    }

    private companion object {
        val REQUEST_LIFETIME: Duration = Duration.ofMinutes(5)
        val NSD_REGISTRATION_TIMEOUT: Duration = Duration.ofMillis(1_500)
        const val DEFAULT_MAX_DIMENSION = 1_920
        const val DEFAULT_MAX_FPS = 60
        const val DEFAULT_BITRATE_BPS = 8_000_000
        const val DETAIL_LIMIT = 160
    }
}

internal object AndroidScreenDecoderCapabilities {
    fun supportedCodecs(): Set<ScreenMirrorCodec> {
        val supportedTypes = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .map(String::lowercase)
                .toSet()
        }.getOrDefault(emptySet())
        return buildSet {
            if (MIME_H264 in supportedTypes) add(ScreenMirrorCodec.H264)
            if (MIME_H265 in supportedTypes) add(ScreenMirrorCodec.H265)
            if (MIME_AV1 in supportedTypes) add(ScreenMirrorCodec.AV1)
        }
    }

    private const val MIME_H264 = "video/avc"
    private const val MIME_H265 = "video/hevc"
    private const val MIME_AV1 = "video/av01"
}

/** H.264 first for broad decoder stability, then H.265 and AV1. */
internal fun selectAndroidScreenCodec(
    sourceCapabilities: Set<Capability>,
    decoderCodecs: Set<ScreenMirrorCodec>,
): ScreenMirrorCodec? {
    val base = setOf(
        Capability.CAPABILITY_ROUTING_V1,
        Capability.SCREEN_MIRROR_SOURCE_V1,
        Capability.SCREEN_MIRROR_CONTROL_V1,
        Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
    )
    if (!sourceCapabilities.containsAll(base)) return null
    return listOf(ScreenMirrorCodec.H264, ScreenMirrorCodec.H265, ScreenMirrorCodec.AV1)
        .firstOrNull { codec ->
            codec in decoderCodecs && codec.requiredEncoderCapability() in sourceCapabilities
        }
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
