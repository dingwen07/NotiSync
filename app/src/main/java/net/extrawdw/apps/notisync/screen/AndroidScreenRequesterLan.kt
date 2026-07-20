package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.extrawdw.notisync.screen.LanAddress
import net.extrawdw.notisync.screen.LanAddressProvider
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ServiceAdvertisement

/**
 * One physical Android LAN selected for a requester listener. Incoming sockets are bound to the
 * addresses from this exact [Network], and every tracked resource is closed if that network is lost.
 */
internal class AndroidScreenRequesterLan private constructor(
    private val context: Context,
    private val connectivity: ConnectivityManager,
    val network: Network,
    private val linkProperties: LinkProperties,
) : AutoCloseable {
    private val lock = Any()
    private val tracked = LinkedHashSet<AutoCloseable>()
    private var invalid = false

    val addressProvider = LanAddressProvider {
        androidLanAddresses(
            interfaceName = linkProperties.interfaceName,
            addresses = linkProperties.linkAddresses.map { it.address to it.prefixLength },
        )
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            if (lost == network) invalidate()
        }

        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
            if (changed == network && !capabilities.isPhysicalLan()) invalidate()
        }

        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
            if (changed != network) return
            val stillHasBoundAddress = androidLanAddresses(
                properties.interfaceName,
                properties.linkAddresses.map { it.address to it.prefixLength },
            ).any { next ->
                addressProvider.addresses().any { current ->
                    current.interfaceName == next.interfaceName && current.address == next.address
                }
            }
            if (!stillHasBoundAddress) invalidate()
        }
    }

    init {
        connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback)
        if (connectivity.getNetworkCapabilities(network)?.isPhysicalLan() != true ||
            addressProvider.addresses().isEmpty()
        ) {
            invalidate()
        }
    }

    fun requireActive() {
        synchronized(lock) {
            check(!invalid) { "selected LAN network was lost" }
        }
    }

    fun <T : AutoCloseable> track(resource: T): T {
        val reject = synchronized(lock) {
            if (invalid) true else {
                tracked += resource
                false
            }
        }
        if (reject) {
            runCatching { resource.close() }
            error("selected LAN network was lost")
        }
        return resource
    }

    fun untrack(resource: AutoCloseable) {
        synchronized(lock) { tracked -= resource }
    }

    suspend fun advertise(serviceName: String, port: Int): ServiceAdvertisement? {
        requireActive()
        val manager = context.getSystemService(NsdManager::class.java) ?: return null
        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean()
            lateinit var listener: NsdManager.RegistrationListener
            fun finish(value: ServiceAdvertisement?) {
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(value)
                }
            }
            listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    val advertisement = AndroidNsdAdvertisement(
                        manager = manager,
                        listener = this,
                        serviceName = serviceInfo.serviceName,
                    )
                    if (!completed.compareAndSet(false, true)) {
                        advertisement.close()
                    } else if (continuation.isActive) {
                        continuation.resume(advertisement)
                    } else {
                        advertisement.close()
                    }
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    runCatching { manager.unregisterService(listener) }
                }
            }
            val info = NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = DNS_SD_SERVICE_TYPE
                this.port = port
                network = this@AndroidScreenRequesterLan.network
            }
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, context.mainExecutor, listener)
            }.onFailure { finish(null) }
        }
    }

    private fun invalidate() {
        val resources = synchronized(lock) {
            if (invalid) return
            invalid = true
            tracked.toList().also { tracked.clear() }
        }
        resources.forEach { runCatching { it.close() } }
    }

    override fun close() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        invalidate()
    }

    companion object {
        fun open(context: Context): AndroidScreenRequesterLan {
            return openOrNull(context) ?: error("no usable Wi-Fi or Ethernet LAN is available")
        }

        /** A physical LAN is optional when a direct Wi-Fi Aware rendezvous can be advertised. */
        fun openOrNull(context: Context): AndroidScreenRequesterLan? {
            val appContext = context.applicationContext
            if (android.os.Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                error("local network permission is not granted")
            }
            val connectivity = requireNotNull(appContext.getSystemService(ConnectivityManager::class.java))
            val active = connectivity.activeNetwork
            val candidates = LinkedHashSet<Network>()
            if (active != null && connectivity.getNetworkCapabilities(active)?.isPhysicalLan() == true) {
                candidates += active
            }
            // A VPN may be the default. Android has no public VPN-to-underlying-network mapping, so
            // best effort means scanning the visible non-VPN Wi-Fi/Ethernet Networks after active.
            connectivity.allNetworks.forEach(candidates::add)
            val selected = candidates.firstOrNull { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.isPhysicalLan() == true &&
                    connectivity.getLinkProperties(candidate)?.let { properties ->
                        androidLanAddresses(
                            properties.interfaceName,
                            properties.linkAddresses.map { it.address to it.prefixLength },
                        ).isNotEmpty()
                    } == true
            } ?: return null
            return AndroidScreenRequesterLan(
                context = appContext,
                connectivity = connectivity,
                network = selected,
                linkProperties = requireNotNull(connectivity.getLinkProperties(selected)),
            )
        }

        private const val DNS_SD_SERVICE_TYPE = "_notisync-screen._tcp."
    }
}

private class AndroidNsdAdvertisement(
    private val manager: NsdManager,
    private val listener: NsdManager.RegistrationListener,
    serviceName: String,
) : ServiceAdvertisement {
    private val closed = AtomicBoolean()
    override val candidate = ScreenConnectionCandidate(
        kind = ScreenConnectionCandidate.DNS_SD,
        serviceName = serviceName,
    )

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { manager.unregisterService(listener) }
    }
}

/** Converts only concrete unicast addresses from one Android LinkProperties snapshot. */
internal fun androidLanAddresses(
    interfaceName: String?,
    addresses: List<Pair<InetAddress, Int>>,
): List<LanAddress> {
    val name = interfaceName?.takeIf(String::isNotBlank) ?: return emptyList()
    val networkInterface = runCatching { NetworkInterface.getByName(name) }.getOrNull()
    return addresses.mapNotNull { (rawAddress, prefixLength) ->
        if (rawAddress.isAnyLocalAddress || rawAddress.isLoopbackAddress || rawAddress.isMulticastAddress) {
            return@mapNotNull null
        }
        val address = if (rawAddress is Inet6Address && rawAddress.isLinkLocalAddress && rawAddress.scopeId == 0) {
            networkInterface?.let {
                runCatching { Inet6Address.getByAddress(null, rawAddress.address, it) }.getOrNull()
            } ?: return@mapNotNull null
        } else {
            rawAddress
        }
        val addressBits = address.address.size * Byte.SIZE_BITS
        if (prefixLength !in 1..addressBits) return@mapNotNull null
        runCatching { LanAddress(address, name, prefixLength) }.getOrNull()
    }.distinctBy { Triple(it.address.hostAddress, it.interfaceName, it.prefixLength) }
}

private fun NetworkCapabilities.isPhysicalLan(): Boolean =
    !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
