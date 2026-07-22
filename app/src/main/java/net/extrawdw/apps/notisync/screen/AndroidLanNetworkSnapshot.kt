package net.extrawdw.apps.notisync.screen

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class AndroidLanNetworkSnapshot(
    val network: Network,
    val capabilities: NetworkCapabilities,
    val linkProperties: LinkProperties,
)

internal fun awaitAndroidLanNetworkSnapshot(
    connectivity: ConnectivityManager,
    accepts: (NetworkCapabilities, LinkProperties) -> Boolean,
): AndroidLanNetworkSnapshot? {
    val active = connectivity.activeNetwork
    if (active != null) {
        val capabilities = connectivity.getNetworkCapabilities(active)
        val linkProperties = connectivity.getLinkProperties(active)
        if (capabilities != null && linkProperties != null && accepts(capabilities, linkProperties)) {
            return AndroidLanNetworkSnapshot(active, capabilities, linkProperties)
        }
    }

    val lock = Any()
    val snapshots = LinkedHashMap<Network, PendingAndroidLanNetworkSnapshot>()
    val available = CountDownLatch(1)

    fun acceptedSnapshot(): AndroidLanNetworkSnapshot? = synchronized(lock) {
        snapshots.entries.firstNotNullOfOrNull { (network, pending) ->
            val capabilities = pending.capabilities ?: return@firstNotNullOfOrNull null
            val linkProperties = pending.linkProperties ?: return@firstNotNullOfOrNull null
            AndroidLanNetworkSnapshot(network, capabilities, linkProperties)
                .takeIf { accepts(capabilities, linkProperties) }
        }
    }

    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) {
                snapshots.getOrPut(network, ::PendingAndroidLanNetworkSnapshot).capabilities = capabilities
            }
            if (acceptedSnapshot() != null) available.countDown()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                snapshots.getOrPut(network, ::PendingAndroidLanNetworkSnapshot).linkProperties = linkProperties
            }
            if (acceptedSnapshot() != null) available.countDown()
        }

        override fun onLost(network: Network) {
            synchronized(lock) { snapshots.remove(network) }
        }
    }

    connectivity.registerNetworkCallback(
        NetworkRequest.Builder().clearCapabilities().build(),
        callback,
    )
    return try {
        available.await(NETWORK_SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        acceptedSnapshot()
    } finally {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}

private class PendingAndroidLanNetworkSnapshot {
    var capabilities: NetworkCapabilities? = null
    var linkProperties: LinkProperties? = null
}

private const val NETWORK_SNAPSHOT_TIMEOUT_MS = 500L
