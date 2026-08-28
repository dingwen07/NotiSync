package net.extrawdw.apps.notisync.sshkeyprovider

import java.util.Base64
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.trust.Peer
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshAgentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyTransferPeerPolicyTest {
    @Test
    fun eligibleTargetRequiresOwnRoutableKeyProviderButNotSshAgent() {
        val eligible = peer(
            "eligible",
            capabilities = listOf(Capability.CAPABILITY_ROUTING_V1, Capability.SSH_KEY_PROVIDER_V1),
        )
        val withAgentCapability = peer(
            "also-eligible",
            capabilities = listOf(
                Capability.CAPABILITY_ROUTING_V1,
                Capability.SSH_KEY_PROVIDER_V1,
                Capability.SSH_AGENT_V1,
            ),
        )
        val otherPerson = peer(
            "other-person",
            ownDevice = false,
            capabilities = eligible.capabilities,
        )
        val notRoutable = peer("not-routable", capabilities = listOf(Capability.SSH_KEY_PROVIDER_V1))
        val notProvider = peer("not-provider", capabilities = listOf(Capability.CAPABILITY_ROUTING_V1))

        assertEquals(
            setOf(eligible.clientId, withAgentCapability.clientId),
            eligibleSshKeyTransferPeers(listOf(otherPerson, notRoutable, eligible, notProvider, withAgentCapability))
                .mapTo(linkedSetOf(), SshKeyTransferPeer::clientId),
        )
    }

    @Test
    fun transferAndDirectReplyScopesDoNotRequireSshAgentCapability() {
        val target = ClientId("target")
        val transfer = sshKeyTransferRecipients(target) as Recipients.OnlyCapable
        assertEquals(target, transfer.id)
        assertEquals(SshAgentLimits.NORMAL_PROVIDER_CAPABILITIES, transfer.requiredCapabilities)
        assertFalse(Capability.SSH_AGENT_V1 in transfer.requiredCapabilities)

        assertEquals(Recipients.Only(target), sshAgentDirectRecipients(target))
    }

    @Test
    fun inventoryAnnouncementAloneUsesSshAgentCapability() {
        val recipients = sshAgentInventoryRecipients() as Recipients.OwnMeshFiltered
        assertEquals(SSH_AGENT_INVENTORY_RECIPIENT_CAPABILITIES, recipients.requiredCapabilities)
        assertTrue(recipients.requireCapabilityRoutingV1)
        assertTrue(Capability.SSH_AGENT_V1 in recipients.requiredCapabilities)
    }

    private fun peer(
        name: String,
        ownDevice: Boolean = true,
        capabilities: List<Capability>,
    ): Peer = Peer(
        clientId = ClientId(name),
        displayName = name,
        platform = "android",
        identityPublicKeyB64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        hpkePublicKeyB64 = Base64.getEncoder().encodeToString(byteArrayOf(2)),
        addedAt = 1,
        capabilities = capabilities,
        ownDevice = ownDevice,
        currentEpoch = 1,
    )
}
