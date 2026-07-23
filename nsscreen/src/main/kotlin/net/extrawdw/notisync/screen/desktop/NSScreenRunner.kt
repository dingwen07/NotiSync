package net.extrawdw.notisync.screen.desktop

import java.io.BufferedReader
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.screen.GeneratedSessionSecrets
import net.extrawdw.notisync.screen.LanSessionListener
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.SessionDescriptor

internal class NSScreenRunner(
    private val daemonConnector: () -> DaemonLocalApi,
    private val helper: (net.extrawdw.notisync.screen.SecureChannelPair, ScreenMirrorCodec, String) -> Unit,
    private val listenerFactory: () -> ScreenSessionListener = { LanSessionListener.open() },
    private val dnsAdvertiser: (String, ScreenConnectionCandidate) -> DnsSdAdvertisement? = { name, endpoint ->
        runCatching { JmDnsAdvertiser().advertise(name, endpoint) }.getOrNull()
    },
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    constructor(
        daemonConnector: () -> DaemonLocalApi,
        helper: NativeHelperBridge,
    ) : this(daemonConnector, helper::run)

    fun listDevices(output: Appendable) {
        val devices = eligibleSources(daemonConnector().devices().devices)
        if (devices.isEmpty()) {
            output.appendLine("No trusted own screen sources are available.")
            return
        }
        devices.forEach { device ->
            val codecs = availableCodecs(device).joinToString(",") { it.name.lowercase() }
            val features = buildList {
                if (Capability.SCREEN_MIRROR_CONTROL_V1.name in device.capabilities) add("control")
                if (Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1.name in device.capabilities) add("clipboard")
            }.joinToString(",").ifBlank { "view-only" }
            output.appendLine("${device.clientId}\t${device.name}\tcodecs=$codecs\t$features")
        }
    }

    fun connect(
        options: ConnectOptions,
        output: Appendable,
        input: BufferedReader,
        interactive: Boolean,
    ) {
        val api = daemonConnector()
        val bridge = ScreenApplicationBridge(api)
        val requesterId = bridge.register()
        val sources = eligibleSources(api.devices().devices)
        val source = selectSource(sources, options.deviceId, output, input, interactive)
        val codec = selectCodec(source, options.codec, output, input, interactive)
        validateFeatures(source, options)

        val issuedAt = clock.millis()
        val expiresAt = issuedAt + REQUEST_LIFETIME.toMillis()
        val sessionId = "screen:${UUID.randomUUID()}"
        val descriptor = SessionDescriptor(
            sessionId = sessionId,
            sourcePeerId = source.clientId,
            requesterPeerId = requesterId.value,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = expiresAt,
            codec = codec.name.lowercase(),
            controlEnabled = options.control,
            clipboardEnabled = options.clipboard,
            maxDimension = options.maxDimension,
            maxFps = options.maxFps,
            videoBitrateBps = options.bitrateBps,
        )

        PskRegistry(clock).use { registry ->
            listenerFactory().use { listener ->
                val serviceName = "notisync-${UUID.randomUUID()}"
                val advertisement = listener.candidates.firstOrNull()?.let { dnsAdvertiser(serviceName, it) }
                try {
                    GeneratedSessionSecrets.generate(random).use { generated ->
                        val token = generated.routingToken.copy()
                        val masterPsk = generated.masterPsk.copy()
                        registry.register(descriptor, token, masterPsk)
                        val request = ScreenMirrorSync(
                            action = ScreenMirrorAction.REQUEST,
                            sessionId = sessionId,
                            requesterPeerId = requesterId,
                            sourcePeerId = ClientId(source.clientId),
                            issuedAt = issuedAt,
                            expiresAt = expiresAt,
                            routingToken = token,
                            masterPsk = masterPsk,
                            codec = codec,
                            requestControl = options.control,
                            requestClipboard = options.clipboard,
                            maxDimension = options.maxDimension,
                            maxFps = options.maxFps,
                            videoBitrateBps = options.bitrateBps,
                            candidates = (listener.candidates + listOfNotNull(advertisement?.candidate))
                                .map { it.toProtocol() },
                        )
                        bridge.openSessionStream(sessionId).use { statuses ->
                            val lifecycle = SessionLifecycle(
                                bridge = bridge,
                                request = request,
                                listener = listener,
                                cleanupSecrets = { registry.cancel(sessionId) },
                            )
                            val shutdownHook = Thread(
                                { lifecycle.finish("desktop process shutting down") },
                                "nsscreen-session-shutdown",
                            )
                            var hookInstalled = false
                            try {
                                Runtime.getRuntime().addShutdownHook(shutdownHook)
                                hookInstalled = true
                                // The receive interest, LAN sockets, and PSK registry all exist before
                                // the wake request can reach A.
                                bridge.sendRequest(request)
                                lifecycle.markRequestSent()
                                token.fill(0)
                                masterPsk.fill(0)
                                output.appendLine("Waiting for ${source.name} (${source.clientId})…")
                                val pair = awaitChannels(listener, registry, statuses, request)
                                lifecycle.markActive(pair)
                                try {
                                    pair.use {
                                        output.appendLine("Connected using ${codec.name.lowercase()}.")
                                        helper(pair, codec, "${source.name} — NotiSync")
                                    }
                                } finally {
                                    lifecycle.releasePair(pair)
                                }
                            } finally {
                                token.fill(0)
                                masterPsk.fill(0)
                                // A hook may already be running during JVM shutdown; in that case the
                                // lifecycle's one-shot terminal guard still prevents duplicate sends.
                                if (hookInstalled) {
                                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                                }
                                lifecycle.finish("desktop viewer closed")
                            }
                        }
                    }
                } finally {
                    advertisement?.close()
                }
            }
        }
    }

    private fun awaitChannels(
        listener: ScreenSessionListener,
        registry: PskRegistry,
        statuses: SessionReceiveStream,
        request: ScreenMirrorSync,
    ): net.extrawdw.notisync.screen.SecureChannelPair {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pairFuture = executor.submit<net.extrawdw.notisync.screen.SecureChannelPair> {
                listener.acceptPair(request.sessionId, registry, REQUEST_LIFETIME)
            }
            val statusFuture = executor.submit<ScreenMirrorSync?> {
                while (!Thread.currentThread().isInterrupted) {
                    val status = statuses.next(request.sourcePeerId) ?: return@submit null
                    if (status.sessionId != request.sessionId ||
                        status.requesterPeerId != request.requesterPeerId ||
                        status.sourcePeerId != request.sourcePeerId
                    ) continue
                    when (status.action) {
                        ScreenMirrorAction.STATUS -> if (
                            status.status !in setOf(ScreenMirrorStatus.CONNECTING, ScreenMirrorStatus.READY)
                        ) return@submit status
                        ScreenMirrorAction.CANCEL, ScreenMirrorAction.END -> return@submit status
                        ScreenMirrorAction.REQUEST -> continue
                    }
                }
                null
            }
            while (true) {
                try {
                    return pairFuture.get(200, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    if (statusFuture.isDone) {
                        val status = statusFuture.get()
                            ?: throw IOException("screen source closed its response stream")
                        listener.close()
                        val detail = status.detail?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                        throw IOException("screen source rejected the session (${status.status ?: status.action})$detail")
                    }
                } catch (error: ExecutionException) {
                    throw (error.cause as? Exception ?: error)
                }
            }
        } finally {
            statuses.close()
            executor.shutdownNow()
            runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
        }
    }

    private fun selectSource(
        sources: List<DeviceView>,
        requestedId: String?,
        output: Appendable,
        input: BufferedReader,
        interactive: Boolean,
    ): DeviceView {
        if (requestedId != null) return sources.singleOrNull { it.clientId == requestedId }
            ?: throw ScreenCliException("device is not an available trusted own screen source: $requestedId")
        if (sources.isEmpty()) throw ScreenCliException("no trusted own screen sources are available")
        if (!interactive) {
            if (sources.size == 1) return sources.single()
            throw ScreenCliException("multiple screen sources are available; specify DEVICE_ID")
        }
        output.appendLine("Select a screen source:")
        sources.forEachIndexed { index, device -> output.appendLine("  ${index + 1}. ${device.name} (${device.clientId})") }
        output.append("Device: ")
        val selected = input.readLine()?.trim()?.toIntOrNull()
            ?: throw ScreenCliException("invalid device selection")
        return sources.getOrNull(selected - 1) ?: throw ScreenCliException("invalid device selection")
    }

    private fun selectCodec(
        source: DeviceView,
        requested: ScreenMirrorCodec?,
        output: Appendable,
        input: BufferedReader,
        interactive: Boolean,
    ): ScreenMirrorCodec {
        val codecs = availableCodecs(source)
        if (codecs.isEmpty()) throw ScreenCliException("${source.name} has no advertised hardware encoder")
        requested?.let {
            if (it !in codecs) throw ScreenCliException("${source.name} does not advertise ${it.name.lowercase()} hardware encoding")
            return it
        }
        if (!interactive) {
            if (ScreenMirrorCodec.H264 in codecs) return ScreenMirrorCodec.H264
            throw ScreenCliException("h264 is unavailable; select one of ${codecs.joinToString { it.name.lowercase() }} with --codec")
        }
        val default = ScreenMirrorCodec.H264.takeIf(codecs::contains)
        output.appendLine("Select a hardware codec:")
        codecs.forEachIndexed { index, codec -> output.appendLine("  ${index + 1}. ${codec.name.lowercase()}") }
        output.append(if (default == null) "Codec: " else "Codec [h264]: ")
        val response = input.readLine()?.trim() ?: throw ScreenCliException("invalid codec selection")
        if (response.isEmpty() && default != null) return default
        val selected = response.toIntOrNull() ?: throw ScreenCliException("invalid codec selection")
        return codecs.getOrNull(selected - 1) ?: throw ScreenCliException("invalid codec selection")
    }

    private fun validateFeatures(source: DeviceView, options: ConnectOptions) {
        if (options.control && Capability.SCREEN_MIRROR_CONTROL_V1.name !in source.capabilities) {
            throw ScreenCliException("${source.name} does not advertise screen control; use --no-control --no-clipboard")
        }
        if (options.clipboard && Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1.name !in source.capabilities) {
            throw ScreenCliException("${source.name} does not advertise clipboard synchronization; use --no-clipboard")
        }
    }

    private fun eligibleSources(devices: List<DeviceView>): List<DeviceView> = devices.filter { device ->
        device.classification == DeviceClassification.OWN &&
            device.trustStatus == DeviceTrustStatus.TRUSTED &&
            device.keyAvailable && device.verified &&
            Capability.CAPABILITY_ROUTING_V1.name in device.capabilities &&
            Capability.SCREEN_MIRROR_SOURCE_V1.name in device.capabilities &&
            Capability.SCREEN_MIRROR_CONTROL_V1.name in device.capabilities &&
            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1.name in device.capabilities &&
            availableCodecs(device).isNotEmpty()
    }.sortedWith { left, right ->
        String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name).takeIf { it != 0 }
            ?: left.clientId.compareTo(right.clientId)
    }

    private fun availableCodecs(device: DeviceView): List<ScreenMirrorCodec> = ScreenMirrorCodec.entries.filter {
        it.requiredEncoderCapability().name in device.capabilities
    }

    private fun ScreenConnectionCandidate.toProtocol(): ScreenMirrorConnectionCandidate =
        ScreenMirrorConnectionCandidate(kind, host, port, serviceName, interfaceName)

    /** Coordinates the ordinary and JVM-shutdown paths without emitting two terminal messages. */
    private class SessionLifecycle(
        private val bridge: ScreenApplicationBridge,
        private val request: ScreenMirrorSync,
        private val listener: ScreenSessionListener,
        private val cleanupSecrets: () -> Unit,
    ) {
        private val requestSent = AtomicBoolean()
        private val active = AtomicBoolean()
        private val terminalSent = AtomicBoolean()
        private val finished = AtomicBoolean()
        private val pair = AtomicReference<net.extrawdw.notisync.screen.SecureChannelPair?>()

        fun markRequestSent() {
            requestSent.set(true)
            if (finished.get()) sendTerminal("desktop process shutting down")
        }

        fun markActive(value: net.extrawdw.notisync.screen.SecureChannelPair) {
            active.set(true)
            if (finished.get()) {
                value.close()
            } else if (!pair.compareAndSet(null, value)) {
                value.close()
                error("screen session already has an active channel pair")
            }
            // Cover a shutdown racing between the first finished check and publication.
            if (finished.get()) pair.getAndSet(null)?.close()
        }

        fun releasePair(value: net.extrawdw.notisync.screen.SecureChannelPair) {
            pair.compareAndSet(value, null)
        }

        fun finish(detail: String) {
            finished.set(true)
            runCatching { listener.close() }
            runCatching { pair.getAndSet(null)?.close() }
            runCatching { cleanupSecrets() }
            sendTerminal(detail)
        }

        private fun sendTerminal(detail: String) {
            if (!requestSent.get() || !terminalSent.compareAndSet(false, true)) return
            runCatching {
                if (active.get()) {
                    bridge.sendEnd(request, detail)
                } else {
                    bridge.sendCancel(request, detail)
                }
            }
        }
    }

    private companion object {
        val REQUEST_LIFETIME: Duration = Duration.ofMinutes(5)
    }
}
