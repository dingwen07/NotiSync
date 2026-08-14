package net.extrawdw.notisync.sshagent.bridge

import java.util.Base64
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshApplicationBridgeTest {
    private val requester = ClientId("a".repeat(52))
    private val high = ClientId("b".repeat(52))
    private val normal = ClientId("c".repeat(52))

    @Test
    fun startupPartitionsFilteredPushWithoutBackgroundWake() {
        val api = FakeApi(requester, listOf(provider(high, push = true), provider(normal, push = false)))
        val bridge = SshApplicationBridge(api, ProviderRoster(api), now = { 1_000 })
        assertEquals(requester, bridge.register())

        bridge.requestInventory(requester, startup = true)

        assertEquals(setOf(Capability.SSH_AGENT_V1), api.registration?.capabilities)
        assertEquals(2, api.sends.size)
        val highSend = api.sends.single { it.urgency == Urgency.HIGH }
        val normalSend = api.sends.single { it.urgency == Urgency.NORMAL }
        val highScope = highSend.scope as Recipients.OnlyCapableSet
        val normalScope = normalSend.scope as Recipients.OnlyCapableSet
        assertEquals(setOf(high), highScope.ids)
        assertEquals(setOf(normal), normalScope.ids)
        assertTrue(Capability.PUSH_FILTERING in highScope.requiredCapabilities)
        assertFalse(Capability.PUSH_FILTERING in normalScope.requiredCapabilities)
        assertFalse(Capability.BACKGROUND_WAKE in highScope.requiredCapabilities)
        assertFalse(Capability.BACKGROUND_WAKE in normalScope.requiredCapabilities)
        assertEquals(listOf(high), decode(highSend).sshAgent?.keysRequest?.targetProviderClientIds)
        assertEquals(listOf(normal), decode(normalSend).sshAgent?.keysRequest?.targetProviderClientIds)
    }

    @Test
    fun periodicInventoryIsOneNormalExactSet() {
        val api = FakeApi(requester, listOf(provider(high, push = true), provider(normal, push = false)))
        val bridge = SshApplicationBridge(api, ProviderRoster(api), now = { 1_000 })

        bridge.requestInventory(requester, startup = false)

        assertEquals(1, api.sends.size)
        val send = api.sends.single()
        assertEquals(Urgency.NORMAL, send.urgency)
        assertEquals(setOf(high, normal), (send.scope as Recipients.OnlyCapableSet).ids)
        val request = decode(send).sshAgent?.keysRequest
        assertEquals(SshAgentSyncKind.KEYS_REQUEST, decode(send).sshAgent?.kind)
        assertEquals(listOf(high, normal), request?.targetProviderClientIds)
        assertFalse(requireNotNull(request).startup)
    }

    private fun decode(request: SendRequest): DataSync = ProtocolCodec.decodeFromCbor(
        Base64.getDecoder().decode(request.body),
    )

    private fun provider(id: ClientId, push: Boolean): DeviceView = DeviceView(
        clientId = id.value,
        name = id.shortForm(),
        classification = DeviceClassification.OWN,
        trustStatus = DeviceTrustStatus.TRUSTED,
        capabilities = buildSet {
            add(Capability.CAPABILITY_ROUTING_V1.name)
            add(Capability.SSH_KEY_PROVIDER_V1.name)
            if (push) add(Capability.PUSH_FILTERING.name)
        },
        identityFingerprint = "fingerprint",
        keyAvailable = true,
        verified = true,
    )

    private class FakeApi(
        private val self: ClientId,
        private val peers: List<DeviceView>,
    ) : DaemonLocalApi {
        var registration: ApplicationRegistrationRequest? = null
        val sends = mutableListOf<SendRequest>()

        override fun status() = DaemonStatus("test", self.value, connectionState = DaemonConnectionState.CONNECTED)
        override fun devices() = DeviceListResponse(peers)
        override fun putApplication(applicationId: String, request: ApplicationRegistrationRequest): ApplicationView {
            registration = request
            return ApplicationView(applicationId, request.displayName, request.version, request.capabilities.toList(), 1)
        }
        override fun listApplications() = ApplicationListResponse(emptyList(), emptyList())
        override fun deleteApplication(applicationId: String) = Unit
        override fun send(request: SendRequest): SendAccepted = sendAll(listOf(request)).single()
        override fun sendAll(requests: List<SendRequest>): List<SendAccepted> {
            sends += requests
            return requests.mapIndexed { index, request -> SendAccepted("m$index", 1, request.submissionId) }
        }
        override fun openReceive(request: ReceiveRequest): ReceiveStream = error("not used")
        override fun unregisterReceive(request: ReceiveRequest) = Unit
        override fun ack(applicationId: String, envelopeId: String) = Unit
        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) = Unit
    }
}
