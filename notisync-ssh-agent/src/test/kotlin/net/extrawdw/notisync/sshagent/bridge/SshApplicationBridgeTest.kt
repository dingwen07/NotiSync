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
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshSignCancellationReason
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignRequestCancelled
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
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

    @Test
    fun signRequestIsHighForEveryKeyProviderWithoutPushFiltering() {
        val api = FakeApi(requester, listOf(provider(high, push = true), provider(normal, push = false)))
        val bridge = SshApplicationBridge(api, ProviderRoster(api), now = { 1_000 })
        val request = SshSignRequest(
            requestId = "1".repeat(32),
            requesterClientId = requester,
            requestedAt = 1_000,
            expiresAt = 121_000,
            publicKeyBlob = byteArrayOf(1),
            data = byteArrayOf(2),
            flags = 0,
            requestedSignatureAlgorithm = SshSignatureAlgorithm.SSH_ED25519,
            eligibleProviderClientIds = listOf(high, normal),
            authorizationGeneration = "2".repeat(32),
            authorizationEpoch = 0,
            processContext = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
            destinationContext = SshDestinationContext(
                SshDestinationProvenance.UNKNOWN,
                SshConnectionDirection.UNKNOWN,
            ),
            connectionId = "3".repeat(32),
        )

        bridge.sendSignRequest(request)

        val send = api.sends.single()
        assertEquals(Urgency.HIGH, send.urgency)
        val scope = send.scope as Recipients.OnlyCapableSet
        assertEquals(setOf(high, normal), scope.ids)
        assertEquals(SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES, scope.requiredCapabilities)
        assertFalse(Capability.PUSH_FILTERING in scope.requiredCapabilities)
        assertEquals(request.requestId, decode(send).sshAgent?.signRequest?.requestId)
    }

    @Test
    fun importRequestIsHighForOneKeyProviderWithoutPushFiltering() {
        val api = FakeApi(requester, listOf(provider(high, push = true), provider(normal, push = false)))
        val bridge = SshApplicationBridge(api, ProviderRoster(api), now = { 1_000 })
        val request = SshImportRequest(
            requestId = "4".repeat(32),
            requesterClientId = requester,
            requestedAt = 1_000,
            expiresAt = 301_000,
            sourceType = SshImportSourceType.AGENT_IDENTITY,
            agentIdentity = byteArrayOf(1, 2, 3),
            suggestedName = "Imported key",
        )

        bridge.sendImportRequest(request, normal)

        val send = api.sends.single()
        assertEquals(Urgency.HIGH, send.urgency)
        val scope = send.scope as Recipients.OnlyCapableSet
        assertEquals(setOf(normal), scope.ids)
        assertEquals(SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES, scope.requiredCapabilities)
        assertFalse(Capability.PUSH_FILTERING in scope.requiredCapabilities)
        val decoded = decode(send).sshAgent
        assertEquals(SshAgentSyncKind.IMPORT_REQUEST, decoded?.kind)
        assertEquals(request.requestId, decoded?.importRequest?.requestId)
    }

    @Test
    fun signCancellationIsHighOnlyForProvidersWithPushFiltering() {
        val api = FakeApi(requester, listOf(provider(high, push = true), provider(normal, push = false)))
        val bridge = SshApplicationBridge(api, ProviderRoster(api), now = { 1_000 })
        val cancellation = SshSignRequestCancelled(
            requestId = "5".repeat(32),
            requesterClientId = requester,
            cancelledAt = 2_000,
            reason = SshSignCancellationReason.SIGNED_ELSEWHERE,
            targetProviderClientIds = listOf(high, normal),
        )

        bridge.sendSignRequestCancelled(cancellation)

        assertEquals(2, api.sends.size)
        val highSend = api.sends.single { it.urgency == Urgency.HIGH }
        val normalSend = api.sends.single { it.urgency == Urgency.NORMAL }
        val highScope = highSend.scope as Recipients.OnlyCapableSet
        val normalScope = normalSend.scope as Recipients.OnlyCapableSet
        assertEquals(setOf(high), highScope.ids)
        assertEquals(setOf(normal), normalScope.ids)
        assertEquals(SshAgentLimits.HIGH_FILTERING_PROVIDER_CAPABILITIES, highScope.requiredCapabilities)
        assertEquals(SshAgentLimits.NORMAL_PROVIDER_CAPABILITIES, normalScope.requiredCapabilities)
        assertEquals(
            listOf(high),
            decode(highSend).sshAgent?.signRequestCancelled?.targetProviderClientIds,
        )
        assertEquals(
            listOf(normal),
            decode(normalSend).sshAgent?.signRequestCancelled?.targetProviderClientIds,
        )
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
