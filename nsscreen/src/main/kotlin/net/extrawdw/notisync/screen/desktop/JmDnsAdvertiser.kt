package net.extrawdw.notisync.screen.desktop

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import net.extrawdw.notisync.screen.ScreenConnectionCandidate

internal interface DnsSdAdvertisement : AutoCloseable {
    val candidate: ScreenConnectionCandidate
}

internal class JmDnsAdvertiser {
    fun advertise(instanceName: String, endpoint: ScreenConnectionCandidate): DnsSdAdvertisement {
        require(endpoint.kind == ScreenConnectionCandidate.LAN_TCP)
        val address = InetAddress.getByName(requireNotNull(endpoint.host))
        val jmdns = JmDNS.create(address)
        val service = ServiceInfo.create(
            SERVICE_TYPE,
            instanceName,
            requireNotNull(endpoint.port),
            0,
            0,
            mapOf("version" to "1"),
        )
        try {
            jmdns.registerService(service)
        } catch (error: Throwable) {
            jmdns.close()
            throw error
        }
        return object : DnsSdAdvertisement {
            override val candidate = ScreenConnectionCandidate(
                kind = ScreenConnectionCandidate.DNS_SD,
                serviceName = instanceName,
                interfaceName = endpoint.interfaceName,
            )

            override fun close() {
                runCatching { jmdns.unregisterService(service) }
                runCatching { jmdns.close() }
            }
        }
    }

    companion object {
        const val SERVICE_TYPE: String = "_notisync-screen._tcp.local."
    }
}
