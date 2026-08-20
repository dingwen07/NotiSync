package net.extrawdw.notisync.sshagent.bridge

import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

data class ProviderPeer(val clientId: ClientId, val capabilities: Set<String>) {
    val supportsFilteredPush: Boolean get() = Capability.PUSH_FILTERING.name in capabilities
}

class ProviderRoster(private val api: DaemonLocalApi) {
    fun eligibleProviders(): List<ProviderPeer> = api.devices().devices.asSequence()
        .filter {
            it.classification == DeviceClassification.OWN &&
                it.trustStatus == DeviceTrustStatus.TRUSTED &&
                it.keyAvailable && it.verified &&
                Capability.CAPABILITY_ROUTING_V1.name in it.capabilities &&
                Capability.SSH_KEY_PROVIDER_V1.name in it.capabilities
        }
        .map { ProviderPeer(ClientId(it.clientId), it.capabilities) }
        .sortedBy { it.clientId.value }
        .toList()

    fun activeProviderIds(): Set<ClientId> = eligibleProviders().mapTo(linkedSetOf(), ProviderPeer::clientId)
}
