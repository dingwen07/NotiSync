package net.extrawdw.notisync.screen

import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

data class ScreenConnectionCandidate(
    val kind: String,
    val host: String? = null,
    val port: Int? = null,
    val serviceName: String? = null,
    val interfaceName: String? = null,
) {
    init {
        requireUtf8(kind, 1, 64, "candidate kind")
        host?.let { requireUtf8(it, 1, 512, "candidate host") }
        serviceName?.let { requireUtf8(it, 1, 255, "candidate serviceName") }
        interfaceName?.let { requireUtf8(it, 1, 64, "candidate interfaceName") }
        port?.let { require(it in 1..65_535) { "candidate port is out of range" } }
        when (kind) {
            LAN_TCP -> require(host != null && port != null) { "LAN_TCP requires host and port" }
            DNS_SD -> require(serviceName != null) { "DNS_SD requires serviceName" }
            WIFI_AWARE -> require(serviceName != null && port != null) {
                "WIFI_AWARE requires serviceName and port"
            }
        }
    }

    companion object {
        const val LAN_TCP: String = "LAN_TCP"
        const val DNS_SD: String = "DNS_SD"
        const val WIFI_AWARE: String = "WIFI_AWARE"

        /** Known candidates first; preserves source order within each candidate kind. */
        fun connectionOrder(candidates: List<ScreenConnectionCandidate>): List<ScreenConnectionCandidate> =
            candidates.withIndex().sortedWith(
                compareBy<IndexedValue<ScreenConnectionCandidate>> {
                    when (it.value.kind) {
                        LAN_TCP -> 0
                        DNS_SD -> 1
                        WIFI_AWARE -> 2
                        else -> 3
                    }
                }.thenBy { it.index },
            ).map(IndexedValue<ScreenConnectionCandidate>::value)
    }
}

data class LanAddress(
    val address: InetAddress,
    val interfaceName: String,
    val prefixLength: Int,
) {
    init {
        val addressBits = address.address.size * Byte.SIZE_BITS
        require(prefixLength in 1..addressBits) { "LAN prefix length is out of range" }
        requireUtf8(interfaceName, 1, 64, "LAN interface name")
        if (address is Inet6Address && address.isLinkLocalAddress) {
            require(address.scopeId != 0 || address.scopedInterface?.name == interfaceName) {
                "IPv6 link-local LAN address must be scoped to its interface"
            }
        }
    }

    /** True only when [remote] is on the directly attached prefix for this exact binding. */
    internal fun admits(remote: InetAddress): Boolean {
        if (remote.isAnyLocalAddress || remote.isMulticastAddress) return false
        if (remote.address.size != address.address.size) return false
        if (remote.isLoopbackAddress != address.isLoopbackAddress) return false

        if (address.isLinkLocalAddress || remote.isLinkLocalAddress) {
            if (address !is Inet6Address || remote !is Inet6Address) return false
            if (!address.isLinkLocalAddress || !remote.isLinkLocalAddress) return false
            if (!sameIpv6Scope(address, remote, interfaceName)) return false
        }

        return prefixMatches(address.address, remote.address, prefixLength)
    }
}

fun interface LanAddressProvider {
    @Throws(SocketException::class)
    fun addresses(): List<LanAddress>
}

class SystemLanAddressProvider : LanAddressProvider {
    override fun addresses(): List<LanAddress> {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let(Collections::list).orEmpty()
        return interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
            .filter { network -> isPlausibleLanInterface(network.name) }
            .flatMap { network ->
                network.interfaceAddresses.asSequence().mapNotNull { interfaceAddress ->
                    val address = interfaceAddress.address ?: return@mapNotNull null
                    val prefixLength = interfaceAddress.networkPrefixLength.toInt()
                    val addressBits = address.address.size * Byte.SIZE_BITS
                    if (prefixLength !in 1..addressBits) return@mapNotNull null
                    if (address.isLinkLocalAddress && !isUsableIpv6LinkLocal(address, network)) {
                        return@mapNotNull null
                    }
                    LanAddress(address, network.name, prefixLength)
                }
            }
            .filter { (address) ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress
            }
            .distinctBy { Triple(it.address.hostAddress, it.interfaceName, it.prefixLength) }
            .toList()
    }
}

private fun isUsableIpv6LinkLocal(address: InetAddress, network: NetworkInterface): Boolean {
    if (address !is Inet6Address || !address.isLinkLocalAddress) return false
    return address.scopedInterface?.name == network.name ||
        (address.scopeId != 0 && network.index >= 0 && address.scopeId == network.index)
}

private fun sameIpv6Scope(local: Inet6Address, remote: Inet6Address, interfaceName: String): Boolean {
    val remoteInterface = remote.scopedInterface?.name
    if (remoteInterface != null) return remoteInterface == interfaceName
    if (remote.scopeId == 0) return false

    val localInterface = local.scopedInterface?.name
    if (localInterface != null && localInterface != interfaceName) return false
    return local.scopeId != 0 && remote.scopeId == local.scopeId
}

private fun prefixMatches(local: ByteArray, remote: ByteArray, prefixLength: Int): Boolean {
    val completeBytes = prefixLength / Byte.SIZE_BITS
    for (index in 0 until completeBytes) {
        if (local[index] != remote[index]) return false
    }

    val remainingBits = prefixLength % Byte.SIZE_BITS
    if (remainingBits == 0) return true
    val mask = (0xff shl (Byte.SIZE_BITS - remainingBits)) and 0xff
    return (local[completeBytes].toInt() and mask) == (remote[completeBytes].toInt() and mask)
}

internal fun isPlausibleLanInterface(name: String): Boolean {
    val normalized = name.lowercase()
    if (BLOCKED_INTERFACE_PREFIXES.any(normalized::startsWith)) return false
    return PHYSICAL_LAN_PREFIXES.any(normalized::startsWith)
}

private val PHYSICAL_LAN_PREFIXES = listOf("en", "eth", "em", "wlan", "wl")
private val BLOCKED_INTERFACE_PREFIXES = listOf(
    "lo", "utun", "tun", "tap", "wg", "vpn", "ppp",
    "docker", "veth", "virbr", "br-", "bridge",
    "awdl", "llw", "p2p", "tailscale", "ham", "zt", "vmnet", "vbox",
)

interface ServiceAdvertisement : AutoCloseable {
    val candidate: ScreenConnectionCandidate
}

/**
 * Pluggable DNS-SD boundary. JVM builds may provide an Avahi/JmDNS implementation and Android may
 * provide NSD; direct LAN candidates do not depend on a discovery library.
 */
fun interface ServiceAdvertiser {
    fun advertise(serviceName: String, port: Int): ServiceAdvertisement
}
