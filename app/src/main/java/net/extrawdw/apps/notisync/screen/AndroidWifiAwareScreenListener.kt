package net.extrawdw.apps.notisync.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.PskTlsServer
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.SecureChannelPair
import net.extrawdw.notisync.screen.SecureSessionChannel
import net.extrawdw.notisync.screen.SessionDescriptor

/**
 * Viewer-side Wi-Fi Aware publisher. The listener owns the Aware request for the full lifetime of
 * channels returned by [acceptPair]: closing it tears down the NDP and those channels immediately.
 */
internal class AndroidWifiAwareScreenListener private constructor(
    private val serverSocket: ServerSocket,
    private val networkState: PublishedAwareNetworkState,
    private val lifetime: AndroidWifiAwareLifetime,
    serviceName: String,
) : ScreenSessionListener {
    private val closed = AtomicBoolean()
    private val accepting = AtomicBoolean()

    override val candidates: List<ScreenConnectionCandidate> = listOf(
        ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.WIFI_AWARE,
            port = serverSocket.localPort,
            serviceName = serviceName,
        ),
    )

    override fun acceptPair(
        sessionId: String,
        registry: PskRegistry,
        timeout: Duration,
        handshakeTimeout: Duration,
        maximumAcceptedSockets: Int,
    ): SecureChannelPair {
        require(sessionId.isNotBlank() && sessionId.encodeToByteArray().size <= 128) {
            "invalid screen session id"
        }
        require(!timeout.isNegative && !timeout.isZero)
        require(!handshakeTimeout.isNegative && !handshakeTimeout.isZero)
        require(maximumAcceptedSockets in 2..MAX_ACCEPTED_SOCKETS)
        check(accepting.compareAndSet(false, true)) {
            "Wi-Fi Aware screen listener already has an active accept"
        }

        val deadline = deadlineAfter(timeout)
        val channels = mutableMapOf<ScreenChannel, SecureSessionChannel>()
        var pairReturned = false
        try {
            requireOpen()
            val awareNetwork = networkState.await(remaining(deadline))
            var attempts = 0
            while (channels.size < ScreenChannel.entries.size) {
                requireOpen()
                if (attempts >= maximumAcceptedSockets) {
                    throw IOException("too many unauthenticated Wi-Fi Aware screen sockets")
                }
                val remaining = remaining(deadline)
                if (remaining.isZero || remaining.isNegative) {
                    throw SocketTimeoutException("Wi-Fi Aware screen listener timed out")
                }
                serverSocket.soTimeout = remaining.asTimeoutMillis()
                val socket = try {
                    serverSocket.accept()
                } catch (error: SocketTimeoutException) {
                    throw SocketTimeoutException("Wi-Fi Aware screen listener timed out").apply {
                        initCause(error)
                    }
                }
                attempts++
                if (!AndroidWifiAwareEndpointPolicy.acceptsSocket(
                        expectedPeer = awareNetwork.peerAddress,
                        awareLocalAddresses = awareNetwork.localAddresses,
                        remoteAddress = socket.inetAddress,
                        localAddress = socket.localAddress,
                    )
                ) {
                    runCatching { socket.close() }
                    continue
                }

                val secure = authenticate(
                    socket = socket,
                    registry = registry,
                    requestedTimeout = handshakeTimeout,
                    deadline = deadline,
                ) ?: continue
                if (secure.descriptor.sessionId != sessionId ||
                    channels.putIfAbsent(secure.channel, secure) != null
                ) {
                    lifetime.untrack(secure)
                    secure.close()
                }
            }

            pairReturned = true
            return SecureChannelPair(
                video = requireNotNull(channels[ScreenChannel.VIDEO]),
                control = requireNotNull(channels[ScreenChannel.CONTROL]),
            )
        } finally {
            accepting.set(false)
            if (!pairReturned) {
                channels.values.forEach { channel ->
                    lifetime.untrack(channel)
                    runCatching { channel.close() }
                }
                // A failed or timed-out one-shot accept must release the NDP and discovery sessions.
                close()
            }
        }
    }

    private fun authenticate(
        socket: Socket,
        registry: PskRegistry,
        requestedTimeout: Duration,
        deadline: Long,
    ): SecureSessionChannel? {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            lifetime.track(socket)
        } catch (error: Exception) {
            runCatching { socket.close() }
            return null
        }

        return try {
            val remaining = remaining(deadline)
            if (remaining.isZero || remaining.isNegative) {
                throw SocketTimeoutException("Wi-Fi Aware screen listener timed out")
            }
            val secure = PskTlsServer.accept(socket, registry, minOf(requestedTimeout, remaining))
            lifetime.track(secure)
            lifetime.untrack(socket)
            secure
        } catch (_: Exception) {
            lifetime.untrack(socket)
            runCatching { socket.close() }
            null
        }
    }

    private fun requireOpen() {
        if (closed.get()) throw IOException("Wi-Fi Aware screen listener is closed")
        lifetime.requireActive()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.close()
    }

    companion object {
        /** Opens the TCP server first, then advertises its positive port on an encrypted Aware NDP. */
        suspend fun open(
            context: Context,
            descriptor: SessionDescriptor,
            routingToken: ByteArray,
            masterPsk: ByteArray,
            setupTimeout: Duration = Duration.ofSeconds(10),
            backlog: Int = 8,
            random: SecureRandom = SecureRandom(),
        ): AndroidWifiAwareScreenListener {
            require(!setupTimeout.isNegative && !setupTimeout.isZero)
            require(backlog > 0)
            val appContext = context.applicationContext
            val lifetime = AndroidWifiAwareLifetime()
            // This ordering is load-bearing: the signed candidate and NDP metadata use this bound port.
            val serverSocket = lifetime.track(ServerSocket(0, backlog))
            check(serverSocket.localPort in 1..65_535) { "failed to allocate a Wi-Fi Aware TCP port" }
            val serviceName = AndroidWifiAwareServiceNames.random(random)
            val pmk = AndroidWifiAwarePmkDeriver.derive(masterPsk, routingToken, descriptor)
            try {
                return withTimeout(setupTimeout.asCoroutineTimeoutMillis()) {
                    AndroidWifiAwarePlatform.requirePermissions(appContext)
                    val manager = AndroidWifiAwarePlatform.requireManager(appContext)
                    AndroidWifiAwarePlatform.watchAvailability(appContext, manager, lifetime)
                    val awareSession = AndroidWifiAwarePlatform.attach(manager, lifetime)
                    AndroidWifiAwarePlatform.requireCharacteristics(manager, serviceName)
                    val securityConfig = AndroidWifiAwarePlatform.securityConfig(pmk)
                    val publication = AndroidWifiAwarePlatform.publish(
                        awareSession,
                        serviceName,
                        securityConfig,
                        lifetime,
                    )
                    val connectivity = requireNotNull(
                        appContext.getSystemService(ConnectivityManager::class.java),
                    ) { "connectivity service is unavailable" }
                    val networkState = requestPublishedNetwork(
                        connectivity,
                        publication,
                        securityConfig,
                        serverSocket.localPort,
                        lifetime,
                    )
                    AndroidWifiAwareScreenListener(serverSocket, networkState, lifetime, serviceName)
                }
            } catch (error: Throwable) {
                lifetime.close()
                throw error
            } finally {
                pmk.fill(0)
            }
        }

        private fun requestPublishedNetwork(
            connectivity: ConnectivityManager,
            publication: PublishDiscoverySession,
            securityConfig: WifiAwareDataPathSecurityConfig,
            port: Int,
            lifetime: AndroidWifiAwareLifetime,
        ): PublishedAwareNetworkState {
            val specifier = WifiAwareNetworkSpecifier.Builder(publication)
                .setDataPathSecurityConfig(securityConfig)
                .setPort(port)
                .setTransportProtocol(AndroidWifiAwareEndpointPolicy.TCP_PROTOCOL_NUMBER)
                .build()
            val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
            val state = PublishedAwareNetworkState(connectivity, lifetime)
            val callback = state.callback
            val registration = AndroidWifiAwareNetworkCallbackRegistration {
                connectivity.unregisterNetworkCallback(callback)
            }
            try {
                connectivity.requestNetwork(request, callback)
            } catch (error: Throwable) {
                registration.close()
                throw error
            }
            // requestNetwork() must happen first. If the lifetime fails in this narrow interval,
            // tryTrack() immediately unregisters the callback instead of leaking an unowned NDP.
            if (!lifetime.tryTrack(registration)) lifetime.requireActive()
            return state
        }

        private const val MAX_ACCEPTED_SOCKETS = 32
    }
}

private data class PublishedAwareNetwork(
    val network: Network,
    val peerAddress: Inet6Address,
    val localAddresses: List<InetAddress>,
)

private class PublishedAwareNetworkState(
    private val connectivity: ConnectivityManager,
    private val lifetime: AndroidWifiAwareLifetime,
) {
    private val ready = CountDownLatch(1)
    private val observedNetwork = AtomicReference<Network?>()
    private val selected = AtomicReference<PublishedAwareNetwork?>()
    private val failure = AtomicReference<IOException?>()

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = consider(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            consider(network, capabilities, connectivity.getLinkProperties(network))

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            consider(network, connectivity.getNetworkCapabilities(network), linkProperties)

        override fun onLost(network: Network) {
            if (observedNetwork.get() == network) {
                lifetime.fail(IOException("selected Wi-Fi Aware data path was lost"))
            }
        }

        override fun onUnavailable() {
            lifetime.fail(IOException("Wi-Fi Aware data path is unavailable"))
        }
    }

    init {
        lifetime.observeFailure { error ->
            failure.compareAndSet(null, error)
            ready.countDown()
        }
    }

    fun await(timeout: Duration): PublishedAwareNetwork {
        if (timeout.isZero || timeout.isNegative ||
            !ready.await(timeout.toNanosSaturated(), TimeUnit.NANOSECONDS)
        ) {
            throw SocketTimeoutException("Wi-Fi Aware data path timed out")
        }
        failure.get()?.let { throw it }
        return requireNotNull(selected.get()) { "Wi-Fi Aware data path completed without endpoint" }
    }

    private fun consider(network: Network) = consider(
        network,
        connectivity.getNetworkCapabilities(network),
        connectivity.getLinkProperties(network),
    )

    private fun consider(
        network: Network,
        capabilities: NetworkCapabilities?,
        links: LinkProperties?,
    ) {
        if (!observedNetwork.compareAndSet(null, network) && observedNetwork.get() != network) return
        if (capabilities == null) return
        val hasAwareTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        if (!hasAwareTransport) {
            lifetime.fail(IOException("selected network is no longer Wi-Fi Aware"))
            return
        }
        val info = capabilities.transportInfo as? WifiAwareNetworkInfo
        when (AndroidWifiAwareEndpointPolicy.evaluateScopedPeer(
            peerAddress = info?.peerIpv6Addr,
            interfaceName = links?.interfaceName,
            localAddresses = links?.linkAddresses?.map { it.address },
        )) {
            AndroidWifiAwareEndpointDecision.WAIT -> return
            AndroidWifiAwareEndpointDecision.REJECT -> {
                lifetime.fail(IOException("Wi-Fi Aware data path returned an unsafe peer endpoint"))
            }
            AndroidWifiAwareEndpointDecision.ACCEPT -> {
                val endpoint = PublishedAwareNetwork(
                    network = network,
                    peerAddress = requireNotNull(requireNotNull(info).peerIpv6Addr),
                    localAddresses = requireNotNull(links).linkAddresses.map { it.address },
                )
                if (selected.compareAndSet(null, endpoint) || selected.get()?.network == network) {
                    ready.countDown()
                }
            }
        }
    }
}

private fun deadlineAfter(timeout: Duration): Long {
    val now = System.nanoTime()
    return try {
        Math.addExact(now, timeout.toNanosSaturated())
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private fun remaining(deadline: Long): Duration =
    if (deadline == Long.MAX_VALUE) Duration.ofNanos(Long.MAX_VALUE)
    else Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0L))

private fun Duration.toNanosSaturated(): Long = try {
    toNanos()
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private fun Duration.asTimeoutMillis(): Int {
    val nanos = toNanosSaturated()
    val millis = nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L
    return millis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

private fun Duration.asCoroutineTimeoutMillis(): Long {
    val nanos = toNanosSaturated()
    return (nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L).coerceAtLeast(1L)
}
