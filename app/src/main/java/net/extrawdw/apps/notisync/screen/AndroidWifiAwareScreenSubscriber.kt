package net.extrawdw.apps.notisync.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.screen.SessionDescriptor

/** Resolves a signed viewer candidate to one exact, secure Wi-Fi Aware data-path endpoint. */
internal class AndroidWifiAwareScreenSubscriber(context: Context) {
    private val appContext = context.applicationContext

    suspend fun resolve(
        candidate: ScreenMirrorConnectionCandidate,
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
        timeout: Duration = Duration.ofSeconds(10),
    ): AndroidWifiAwareScreenEndpoint {
        require(candidate.kind == ScreenMirrorConnectionCandidate.WIFI_AWARE) {
            "candidate is not Wi-Fi Aware"
        }
        require(AndroidWifiAwareEndpointPolicy.validSignedCandidate(candidate.serviceName, candidate.port)) {
            "invalid signed Wi-Fi Aware candidate"
        }
        require(!timeout.isNegative && !timeout.isZero)
        val serviceName = requireNotNull(candidate.serviceName)
        val signedPort = requireNotNull(candidate.port)
        val lifetime = AndroidWifiAwareLifetime()
        val pmk = AndroidWifiAwarePmkDeriver.derive(masterPsk, routingToken, descriptor)
        try {
            return withTimeout(timeout.asAwareCoroutineTimeoutMillis()) {
                AndroidWifiAwarePlatform.requirePermissions(appContext)
                val manager = AndroidWifiAwarePlatform.requireManager(appContext)
                AndroidWifiAwarePlatform.watchAvailability(appContext, manager, lifetime)
                val awareSession = AndroidWifiAwarePlatform.attach(manager, lifetime)
                AndroidWifiAwarePlatform.requireCharacteristics(manager, serviceName)
                val securityConfig = AndroidWifiAwarePlatform.securityConfig(pmk)
                val discovery = AndroidWifiAwarePlatform.discover(awareSession, serviceName, lifetime)
                val connectivity = requireNotNull(
                    appContext.getSystemService(ConnectivityManager::class.java),
                ) { "connectivity service is unavailable" }
                requestEndpoint(
                    connectivity = connectivity,
                    discovery = discovery,
                    securityConfig = securityConfig,
                    signedPort = signedPort,
                    lifetime = lifetime,
                ).also { lifetime.requireActive() }
            }
        } catch (error: Throwable) {
            lifetime.close()
            throw error
        } finally {
            pmk.fill(0)
        }
    }

    private suspend fun requestEndpoint(
        connectivity: ConnectivityManager,
        discovery: AndroidWifiAwareDiscovery,
        securityConfig: WifiAwareDataPathSecurityConfig,
        signedPort: Int,
        lifetime: AndroidWifiAwareLifetime,
    ): AndroidWifiAwareScreenEndpoint {
        val specifier = WifiAwareNetworkSpecifier.Builder(discovery.session, discovery.peer)
            .setDataPathSecurityConfig(securityConfig)
            // Only the publisher/server is allowed to supply port and transport metadata.
            .build()
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val state = SubscribedAwareNetworkState(connectivity, signedPort, lifetime)
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
        // If the Aware lifetime fails between registration and ownership transfer, tryTrack()
        // unregisters immediately; no callback or NDP outlives this one-shot session.
        if (!lifetime.tryTrack(registration)) lifetime.requireActive()
        return state.endpoint.await()
    }
}

/**
 * Keeps the ConnectivityManager request, discovery sessions, and sockets alive as one unit. Callers
 * may use [track] for the TLS channels created from [openSocket], then close this endpoint at session end.
 */
internal class AndroidWifiAwareScreenEndpoint internal constructor(
    val network: Network,
    val peerAddress: Inet6Address,
    val port: Int,
    private val lifetime: AndroidWifiAwareLifetime,
) : AutoCloseable {
    val socketAddress: InetSocketAddress get() = InetSocketAddress(peerAddress, port)

    fun openSocket(connectTimeout: Duration = Duration.ofSeconds(8)): Socket {
        require(!connectTimeout.isNegative && !connectTimeout.isZero)
        lifetime.requireActive()
        val socket = lifetime.track(network.socketFactory.createSocket())
        return try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(socketAddress, connectTimeout.asAwareSocketTimeoutMillis())
            lifetime.requireActive()
            socket
        } catch (error: Throwable) {
            lifetime.untrack(socket)
            runCatching { socket.close() }
            throw error
        }
    }

    fun <T : AutoCloseable> track(resource: T): T = lifetime.track(resource)

    fun untrack(resource: AutoCloseable) = lifetime.untrack(resource)

    fun requireActive() = lifetime.requireActive()

    override fun close() = lifetime.close()
}

private class SubscribedAwareNetworkState(
    private val connectivity: ConnectivityManager,
    private val signedPort: Int,
    private val lifetime: AndroidWifiAwareLifetime,
) {
    val endpoint = CompletableDeferred<AndroidWifiAwareScreenEndpoint>()
    private val observedNetwork = AtomicReference<Network?>()
    private val selectedNetwork = AtomicReference<Network?>()

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
        lifetime.observeFailure { error -> endpoint.completeExceptionally(error) }
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
        val info = capabilities.transportInfo as? WifiAwareNetworkInfo
        when (AndroidWifiAwareEndpointPolicy.evaluate(
            signedPort = signedPort,
            hasAwareTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE),
            discoveredPort = info?.port,
            transportProtocol = info?.transportProtocol,
            peerAddress = info?.peerIpv6Addr,
            interfaceName = links?.interfaceName,
            localAddresses = links?.linkAddresses?.map { it.address },
        )) {
            AndroidWifiAwareEndpointDecision.WAIT -> return
            AndroidWifiAwareEndpointDecision.REJECT -> {
                lifetime.fail(IOException("Wi-Fi Aware endpoint did not match signed TCP metadata"))
            }
            AndroidWifiAwareEndpointDecision.ACCEPT -> {
                val peer = requireNotNull(requireNotNull(info).peerIpv6Addr)
                if (selectedNetwork.compareAndSet(null, network) || selectedNetwork.get() == network) {
                    endpoint.complete(
                        AndroidWifiAwareScreenEndpoint(network, peer, signedPort, lifetime),
                    )
                }
            }
        }
    }
}

private fun Duration.asAwareSocketTimeoutMillis(): Int {
    val nanos = toAwareNanosSaturated()
    val millis = nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L
    return millis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

private fun Duration.asAwareCoroutineTimeoutMillis(): Long {
    val nanos = toAwareNanosSaturated()
    return (nanos / 1_000_000L + if (nanos % 1_000_000L == 0L) 0L else 1L).coerceAtLeast(1L)
}

private fun Duration.toAwareNanosSaturated(): Long = try {
    toNanos()
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}
