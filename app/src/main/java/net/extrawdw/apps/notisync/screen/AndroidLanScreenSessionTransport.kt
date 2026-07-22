package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayJoin
import net.extrawdw.notisync.protocol.ScreenRelayRole
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.peer.transport.BrokerRelayConnection
import net.extrawdw.notisync.screen.PskTlsClient
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.SecureSessionChannel
import net.extrawdw.notisync.screen.SessionDescriptor

/** Android source transport: direct LAN/Aware first, with an explicitly requested broker relay. */
class AndroidLanScreenSessionTransport(
    private val context: Context,
    private val broker: BrokerClient? = null,
) : AndroidScreenSessionTransport {
    private val appContext = context.applicationContext

    override suspend fun run(
        request: ScreenMirrorSync,
        startCapture: suspend () -> PrivilegedSessionPipes,
        onReady: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val descriptor = request.toSessionDescriptor()
        val token = requireNotNull(request.routingToken)
        val psk = requireNotNull(request.masterPsk)
        val expiresAt = requireNotNull(request.expiresAt)
        val connectivity = requireNotNull(appContext.getSystemService(ConnectivityManager::class.java))
        var lastFailure: Throwable? = null
        var endpointAttempts = 0
        var videoAuthenticated = false
        Log.i(
            LOG_TAG,
            "Received screen endpoints: " + request.candidates.groupingBy { it.kind }.eachCount()
                .entries.sortedBy { it.key }
                .joinToString { (kind, count) -> "$kind=$count" },
        )

        val network = if (hasLocalNetworkPermission()) requireLanNetworkOrNull() else null
        if (network != null) {
            try {
                ExactLanNetworkGuard(connectivity, network).use { guard ->
                    guard.requireActive()
                    suspend fun attempt(endpoints: List<Endpoint>): Boolean {
                        for (endpoint in endpoints) {
                            guard.requireActive()
                            if (endpointAttempts >= MAX_ENDPOINT_ATTEMPTS) break
                            endpointAttempts++
                            if (System.currentTimeMillis() >= expiresAt) {
                                throw ScreenRequestExpiredException()
                            }
                            var video: SecureSessionChannel? = null
                            var control: SecureSessionChannel? = null
                            var pipes: PrivilegedSessionPipes? = null
                            try {
                                video = connect(
                                    network, endpoint, descriptor, token, psk,
                                    ScreenChannel.VIDEO, expiresAt, guard,
                                )
                                videoAuthenticated = true
                                control = connect(
                                    network, endpoint, descriptor, token, psk,
                                    ScreenChannel.CONTROL, expiresAt, guard,
                                )
                                destroyRendezvousSecrets(token, psk)
                                pipes = guard.track(startCapture())
                                proxy(request, video, control, pipes, onReady)
                                guard.requireActive()
                                return true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                if (error is ScreenRequestExpiredException) throw error
                                // A successful VIDEO handshake consumes its one-shot identity. Never
                                // cross transports after that point or split VIDEO and CONTROL paths.
                                if (!ScreenEndpointRetryPolicy.canRetry(videoAuthenticated)) throw error
                                lastFailure = error
                            } finally {
                                pipes?.let { guard.untrack(it); it.close() }
                                control?.let { guard.untrack(it); it.close() }
                                video?.let { guard.untrack(it); it.close() }
                            }
                        }
                        return false
                    }

                    // Do not spend the DNS-SD timeout when a signed direct address works.
                    if (attempt(resolveDirectEndpoints(request, network))) return@withContext
                    guard.requireActive()
                    if (attempt(resolveDnsSdEndpoints(request, network))) return@withContext
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error is ScreenRequestExpiredException || videoAuthenticated) throw error
                lastFailure = error
            }
        }

        // The request is signed and encrypted, so this candidate list is authority. Aware is only
        // attempted after every LAN path failed before either one-shot TLS identity was consumed.
        val awareCandidates = filterValidWifiAwareCandidates(request.candidates)
        if (awareCandidates.isNotEmpty() &&
            !AndroidWifiAwarePlatform.hasRequiredPermissions(appContext)
        ) {
            lastFailure = SecurityException(
                "nearby Wi-Fi and local-network permissions are required for Wi-Fi Aware",
            )
        } else {
            val subscriber = AndroidWifiAwareScreenSubscriber(appContext)
            for (candidate in awareCandidates) {
                if (endpointAttempts >= MAX_ENDPOINT_ATTEMPTS) break
                endpointAttempts++
                val remaining = expiresAt - System.currentTimeMillis()
                if (remaining <= 0) throw ScreenRequestExpiredException()
                var endpoint: AndroidWifiAwareScreenEndpoint? = null
                var video: SecureSessionChannel? = null
                var control: SecureSessionChannel? = null
                var pipes: PrivilegedSessionPipes? = null
                try {
                    endpoint = subscriber.resolve(
                        candidate = candidate,
                        descriptor = descriptor,
                        routingToken = token,
                        masterPsk = psk,
                        timeout = Duration.ofMillis(minOf(remaining, WIFI_AWARE_RESOLVE_TIMEOUT_MS)),
                    )
                    video = connect(
                        endpoint, descriptor, token, psk, ScreenChannel.VIDEO, expiresAt,
                    )
                    videoAuthenticated = true
                    control = connect(
                        endpoint, descriptor, token, psk, ScreenChannel.CONTROL, expiresAt,
                    )
                    destroyRendezvousSecrets(token, psk)
                    pipes = endpoint.track(startCapture())
                    proxy(request, video, control, pipes, onReady)
                    endpoint.requireActive()
                    return@withContext
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (error is ScreenRequestExpiredException || videoAuthenticated) throw error
                    Log.w(LOG_TAG, "Wi-Fi Aware screen endpoint failed before TLS authentication", error)
                    lastFailure = error
                } finally {
                    pipes?.let { endpoint?.untrack(it); it.close() }
                    control?.let { endpoint?.untrack(it); it.close() }
                    video?.let { endpoint?.untrack(it); it.close() }
                    endpoint?.close()
                }
            }
        }

        val relayCandidates = request.candidates.filter(::validBrokerRelayCandidate)
        for (candidate in relayCandidates) {
            val relayBroker = broker ?: break
            var videoRelay: BrokerRelayConnection? = null
            var controlRelay: BrokerRelayConnection? = null
            var video: SecureSessionChannel? = null
            var control: SecureSessionChannel? = null
            var pipes: PrivilegedSessionPipes? = null
            try {
                val relayId = requireNotNull(candidate.serviceName)
                videoRelay = relayBroker.openScreenRelay(
                    request.relayJoin(relayId, ScreenRelayChannel.VIDEO),
                )
                video = PskTlsClient.connect(
                    input = videoRelay.input,
                    output = videoRelay.output,
                    closeTransport = videoRelay::close,
                    descriptor = descriptor,
                    routingToken = token,
                    masterPsk = psk,
                    channel = ScreenChannel.VIDEO,
                    handshakeTimeout = Duration.ofMillis(
                        remainingTimeout(expiresAt, HANDSHAKE_TIMEOUT_MS).toLong(),
                    ),
                )
                videoAuthenticated = true
                controlRelay = relayBroker.openScreenRelay(
                    request.relayJoin(relayId, ScreenRelayChannel.CONTROL),
                )
                control = PskTlsClient.connect(
                    input = controlRelay.input,
                    output = controlRelay.output,
                    closeTransport = controlRelay::close,
                    descriptor = descriptor,
                    routingToken = token,
                    masterPsk = psk,
                    channel = ScreenChannel.CONTROL,
                    handshakeTimeout = Duration.ofMillis(
                        remainingTimeout(expiresAt, HANDSHAKE_TIMEOUT_MS).toLong(),
                    ),
                )
                destroyRendezvousSecrets(token, psk)
                pipes = startCapture()
                proxy(request, video, control, pipes, onReady)
                return@withContext
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error is ScreenRequestExpiredException || videoAuthenticated) throw error
                Log.w(LOG_TAG, "Broker screen relay failed before TLS authentication", error)
                lastFailure = error
            } finally {
                runCatching { pipes?.close() }
                runCatching { control?.close() }
                runCatching { video?.close() }
                runCatching { controlRelay?.close() }
                runCatching { videoRelay?.close() }
            }
        }

        if (System.currentTimeMillis() >= expiresAt) throw ScreenRequestExpiredException()
        val detail = lastFailure?.deepestTransportMessage()
        throw IllegalStateException(
            buildString {
                append("could not authenticate a direct or broker-relayed screen endpoint")
                if (detail != null) append(": ").append(detail)
            },
            lastFailure,
        )
    }

    private fun connect(
        network: Network,
        endpoint: Endpoint,
        descriptor: SessionDescriptor,
        token: ByteArray,
        psk: ByteArray,
        channel: ScreenChannel,
        expiresAt: Long,
        guard: ExactLanNetworkGuard,
    ): SecureSessionChannel {
        val socket = guard.track(network.socketFactory.createSocket())
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val connectTimeout = remainingTimeout(expiresAt, CONNECT_TIMEOUT_MS)
            socket.connect(InetSocketAddress(endpoint.address, endpoint.port), connectTimeout)
            val secure = PskTlsClient.connect(
                socket = socket,
                descriptor = descriptor,
                routingToken = token,
                masterPsk = psk,
                channel = channel,
                handshakeTimeout = Duration.ofMillis(
                    remainingTimeout(expiresAt, HANDSHAKE_TIMEOUT_MS).toLong(),
                ),
            )
            guard.track(secure)
            guard.untrack(socket)
            secure
        } catch (error: Throwable) {
            guard.untrack(socket)
            runCatching { socket.close() }
            throw error
        }
    }

    private fun connect(
        endpoint: AndroidWifiAwareScreenEndpoint,
        descriptor: SessionDescriptor,
        token: ByteArray,
        psk: ByteArray,
        channel: ScreenChannel,
        expiresAt: Long,
    ): SecureSessionChannel {
        val socket = endpoint.openSocket(
            Duration.ofMillis(remainingTimeout(expiresAt, CONNECT_TIMEOUT_MS).toLong()),
        )
        return try {
            val secure = PskTlsClient.connect(
                socket = socket,
                descriptor = descriptor,
                routingToken = token,
                masterPsk = psk,
                channel = channel,
                handshakeTimeout = Duration.ofMillis(
                    remainingTimeout(expiresAt, HANDSHAKE_TIMEOUT_MS).toLong(),
                ),
            )
            endpoint.track(secure)
            endpoint.untrack(socket)
            secure
        } catch (error: Throwable) {
            endpoint.untrack(socket)
            runCatching { socket.close() }
            throw error
        }
    }

    private fun remainingTimeout(expiresAt: Long, maximumMs: Int): Int {
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) throw ScreenRequestExpiredException()
        return remaining.coerceAtMost(maximumMs.toLong()).coerceAtLeast(1L).toInt()
    }

    private suspend fun proxy(
        request: ScreenMirrorSync,
        video: SecureSessionChannel,
        control: SecureSessionChannel,
        pipes: PrivilegedSessionPipes,
        onReady: () -> Unit,
    ) = coroutineScope {
        var videoInput: ParcelFileDescriptor.AutoCloseInputStream? = null
        var deviceMessages: ParcelFileDescriptor.AutoCloseInputStream? = null
        var controls: ParcelFileDescriptor.AutoCloseOutputStream? = null
        val terminated = CompletableDeferred<Unit>()
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            val preamble = try {
                readAndValidatePreamble(pipes.videoRead, request)
            } catch (error: Throwable) {
                throw ScreenCaptureStartException("screen encoder did not produce a valid stream", error)
            }
            val controlInputFd = ParcelFileDescriptor.dup(pipes.control.fileDescriptor)
            val controlOutputFd = try {
                ParcelFileDescriptor.dup(pipes.control.fileDescriptor)
            } catch (error: Throwable) {
                controlInputFd.closeQuietly()
                throw error
            }
            pipes.control.closeQuietly()
            val videoSource = ParcelFileDescriptor.AutoCloseInputStream(pipes.videoRead)
            val deviceSource = ParcelFileDescriptor.AutoCloseInputStream(controlInputFd)
            val controlSink = ParcelFileDescriptor.AutoCloseOutputStream(controlOutputFd)
            videoInput = videoSource
            deviceMessages = deviceSource
            controls = controlSink
            video.output.write(preamble)
            video.output.flush()
            // READY means both authenticated channels and the privileged encoder/control pipes are usable.
            onReady()
            jobs += launch(Dispatchers.IO) { copyUntilClosed(videoSource, video.output, terminated) }
            jobs += launch(Dispatchers.IO) { copyUntilClosed(control.input, controlSink, terminated) }
            jobs += launch(Dispatchers.IO) { copyUntilClosed(deviceSource, control.output, terminated) }
            terminated.await()
        } finally {
            runCatching { video.close() }
            runCatching { control.close() }
            runCatching { videoInput?.close() }
            runCatching { controls?.close() }
            runCatching { deviceMessages?.close() }
            jobs.forEach { if (it.isActive) it.cancel() }
            jobs.forEach { runCatching { it.cancelAndJoin() } }
            pipes.close()
        }
    }

    /** scrcpy stream metadata: codec id (4 bytes) followed by the first session record (12 bytes). */
    private fun readAndValidatePreamble(
        videoFd: ParcelFileDescriptor,
        request: ScreenMirrorSync,
    ): ByteArray {
        val bytes = ByteArray(ScrcpyVideoPreamble.SIZE_BYTES)
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_START_TIMEOUT_MS
        var offset = 0
        while (offset < bytes.size) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remaining == 0L) throw SocketTimeoutException("screen encoder start timed out")
            val descriptor = StructPollfd().apply {
                fd = videoFd.fileDescriptor
                events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
            }
            if (Os.poll(arrayOf(descriptor), remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) <= 0) {
                throw SocketTimeoutException("screen encoder start timed out")
            }
            val count = Os.read(videoFd.fileDescriptor, bytes, offset, bytes.size - offset)
            if (count <= 0) throw java.io.EOFException("screen encoder closed before stream metadata")
            offset += count
        }
        ScrcpyVideoPreamble.validate(bytes, requireNotNull(request.codec))
        return bytes
    }

    private fun copyUntilClosed(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        terminated: CompletableDeferred<Unit>,
    ) {
        try {
            input.copyTo(output, COPY_BUFFER_BYTES)
            output.flush()
        } finally {
            terminated.complete(Unit)
        }
    }

    private fun resolveDirectEndpoints(request: ScreenMirrorSync, network: Network): List<Endpoint> {
        val direct = mutableListOf<Endpoint>()
        for (candidate in request.candidates) {
            if (candidate.kind != ScreenMirrorConnectionCandidate.LAN_TCP) continue
            val host = candidate.host ?: continue
            val port = candidate.port ?: continue
            // A candidate's interfaceName describes the requester's host and is never a valid
            // Android scope identifier.  Scope link-local IPv6 using the selected Android Network.
            runCatching { network.getAllByName(host.substringBefore('%')) }.getOrDefault(emptyArray())
                .mapNotNull { scopeForSelectedNetwork(network, it) }
                .filter { isOnLink(network, it) }
                .forEach { direct += Endpoint(it, port) }
        }
        return direct.distinct()
    }

    private suspend fun resolveDnsSdEndpoints(
        request: ScreenMirrorSync,
        network: Network,
    ): List<Endpoint> {
        val discovered = mutableListOf<Endpoint>()
        for (candidate in request.candidates) {
            if (candidate.kind != ScreenMirrorConnectionCandidate.DNS_SD) continue
            val name = candidate.serviceName ?: continue
            val remaining = requireNotNull(request.expiresAt) - System.currentTimeMillis()
            if (remaining <= 0) throw ScreenRequestExpiredException()
            withTimeoutOrNull(minOf(DNS_SD_TIMEOUT_MS, remaining)) {
                resolveDnsSd(name, network)
            }?.let(discovered::add)
        }
        return discovered.distinct()
    }

    private suspend fun resolveDnsSd(serviceName: String, network: Network): Endpoint? =
        suspendCancellableCoroutine { continuation ->
            val manager = appContext.getSystemService(NsdManager::class.java)
                ?: return@suspendCancellableCoroutine continuation.resume(null)
            val finished = AtomicBoolean(false)
            val resolving = AtomicBoolean(false)
            lateinit var discovery: NsdManager.DiscoveryListener
            var serviceInfoCallback: NsdManager.ServiceInfoCallback? = null
            fun finish(endpoint: Endpoint?) {
                if (!finished.compareAndSet(false, true)) return
                serviceInfoCallback?.let { runCatching { manager.unregisterServiceInfoCallback(it) } }
                runCatching { manager.stopServiceDiscovery(discovery) }
                if (continuation.isActive) continuation.resume(endpoint)
            }
            discovery = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceName != serviceName || !resolving.compareAndSet(false, true)) return
                    serviceInfoCallback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(null)
                        override fun onServiceInfoCallbackUnregistered() = Unit
                        override fun onServiceLost() = finish(null)
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
                                ?: return finish(null)
                            if (connectivity.getNetworkCapabilities(network)?.isUsableLan() != true) {
                                return finish(null)
                            }
                            val address = info.hostAddresses.firstOrNull { isOnLink(network, it) }
                                ?: return finish(null)
                            finish(Endpoint(address, info.port))
                        }
                    }
                    runCatching {
                        manager.registerServiceInfoCallback(
                            serviceInfo,
                            appContext.mainExecutor,
                            requireNotNull(serviceInfoCallback),
                        )
                    }.onFailure { finish(null) }
                }
            }
            continuation.invokeOnCancellation { finish(null) }
            runCatching {
                manager.discoverServices(
                    DNS_SD_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    network,
                    appContext.mainExecutor,
                    discovery,
                )
            }.onFailure { finish(null) }
        }

    private fun hasLocalNetworkPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED

    private fun requireLanNetworkOrNull(): Network? {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = connectivity.activeNetwork
        val activeCapabilities = active?.let(connectivity::getNetworkCapabilities)
        val candidates = LinkedHashSet<Network>()
        // Prefer the default network when it is already physical. Android does not expose a public
        // VPN-to-underlying-Network mapping to client apps, so allNetworks is the best-effort path
        // when a VPN is default; only non-VPN Wi-Fi/Ethernet candidates survive the filter below.
        if (active != null && activeCapabilities?.isUsableLan() == true) candidates += active
        connectivity.allNetworks.forEach(candidates::add)
        return candidates.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.isUsableLan() == true &&
                connectivity.getLinkProperties(network) != null
        }
    }

    /** Reject internet/default-route targets even when they are reachable through the active Wi-Fi network. */
    private fun isOnLink(network: Network, address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val links = connectivity.getLinkProperties(network) ?: return false
        return links.routes.any { route ->
            !route.hasGateway() && runCatching { route.destination.contains(address) }.getOrDefault(false)
        }
    }

    private fun scopeForSelectedNetwork(network: Network, address: InetAddress): InetAddress? {
        if (address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId != 0) return address
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val interfaceName = connectivity.getLinkProperties(network)?.interfaceName ?: return null
        val localInterface = runCatching { NetworkInterface.getByName(interfaceName) }.getOrNull() ?: return null
        return runCatching { Inet6Address.getByAddress(null, address.address, localInterface) }.getOrNull()
    }

    private fun ScreenMirrorSync.toSessionDescriptor(): SessionDescriptor = SessionDescriptor(
        sessionId = sessionId,
        sourcePeerId = sourcePeerId.value,
        requesterPeerId = requesterPeerId.value,
        issuedAtEpochMillis = issuedAt,
        expiresAtEpochMillis = requireNotNull(expiresAt),
        codec = requireNotNull(codec).name.lowercase(),
        controlEnabled = requestControl,
        clipboardEnabled = requestClipboard,
        maxDimension = maxDimension ?: 1920,
        maxFps = maxFps ?: 60,
        videoBitrateBps = videoBitrateBps ?: 8_000_000,
    )

    private fun ScreenMirrorSync.relayJoin(
        relayId: String,
        channel: ScreenRelayChannel,
    ) = ScreenRelayJoin(
        relayId = relayId,
        requesterPeerId = requesterPeerId,
        sourcePeerId = sourcePeerId,
        role = ScreenRelayRole.SOURCE,
        channel = channel,
        expiresAt = requireNotNull(expiresAt),
    )

    private data class Endpoint(val address: InetAddress, val port: Int)

    private companion object {
        const val DNS_SD_SERVICE_TYPE = "_notisync-screen._tcp."
        const val CONNECT_TIMEOUT_MS = 8_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val DNS_SD_TIMEOUT_MS = 5_000L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CAPTURE_START_TIMEOUT_MS = 10_000L
        const val WIFI_AWARE_RESOLVE_TIMEOUT_MS = 10_000L
        const val MAX_ENDPOINT_ATTEMPTS = 8
        const val LOG_TAG = "NotiSyncScreenTransport"
    }
}

internal fun validBrokerRelayCandidate(candidate: ScreenMirrorConnectionCandidate): Boolean =
    candidate.kind == ScreenMirrorConnectionCandidate.BROKER_RELAY &&
        candidate.host == null && candidate.port == null && candidate.interfaceName == null &&
        candidate.serviceName?.matches(Regex("[A-Za-z0-9_-]{32}")) == true

internal fun filterValidWifiAwareCandidates(
    candidates: List<ScreenMirrorConnectionCandidate>,
): List<ScreenMirrorConnectionCandidate> = candidates.filter { candidate ->
    candidate.kind == ScreenMirrorConnectionCandidate.WIFI_AWARE &&
        AndroidWifiAwareEndpointPolicy.validSignedCandidate(
            candidate.serviceName,
            candidate.port,
        )
}

private fun destroyRendezvousSecrets(token: ByteArray, psk: ByteArray) {
    token.fill(0)
    psk.fill(0)
}

private fun Throwable.deepestTransportMessage(): String? {
    var current: Throwable? = this
    var selected: String? = null
    repeat(8) {
        val value = current ?: return@repeat
        value.message?.takeIf(String::isNotBlank)?.let { selected = it }
        current = value.cause?.takeUnless { cause -> cause === value }
    }
    return sanitizeScreenTransportDetail(selected)
}

internal fun sanitizeScreenTransportDetail(value: String?): String? = value
    ?.substringBefore("Remote stack trace:")
    ?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.take(200)

internal object ScreenEndpointRetryPolicy {
    fun canRetry(videoAuthenticated: Boolean): Boolean = !videoAuthenticated
}

private class LanNetworkLostException : Exception("selected LAN network was lost")

/** Closes every live socket/pipe as soon as the exact selected physical LAN ceases to be usable. */
private class ExactLanNetworkGuard(
    private val connectivity: ConnectivityManager,
    private val selected: Network,
) : Closeable {
    private val lock = Any()
    private val resources = LinkedHashSet<AutoCloseable>()
    private var invalid = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == selected && !capabilities.isUsableLan()) invalidate()
        }
    }

    init {
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().clearCapabilities().build(),
            callback,
        )
        val capabilities = connectivity.getNetworkCapabilities(selected)
        if (capabilities?.isUsableLan() != true) invalidate()
    }

    fun requireActive() {
        synchronized(lock) {
            if (invalid) throw LanNetworkLostException()
        }
    }

    fun <T : AutoCloseable> track(resource: T): T {
        val reject = synchronized(lock) {
            if (invalid) true else {
                resources += resource
                false
            }
        }
        if (reject) {
            runCatching { resource.close() }
            throw LanNetworkLostException()
        }
        return resource
    }

    fun untrack(resource: AutoCloseable) {
        synchronized(lock) { resources -= resource }
    }

    private fun invalidate() {
        val closing = synchronized(lock) {
            if (invalid) return
            invalid = true
            resources.toList().also { resources.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    override fun close() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        invalidate()
    }
}

private fun NetworkCapabilities.isUsableLan(): Boolean =
    !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
