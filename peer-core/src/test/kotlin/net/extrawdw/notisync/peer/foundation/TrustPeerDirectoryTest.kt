package net.extrawdw.notisync.peer.foundation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.trust.IncomingTrustResult
import net.extrawdw.notisync.peer.trust.Peer
import net.extrawdw.notisync.peer.trust.TrustState
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class TrustPeerDirectoryTest {
    @Test
    fun strictCapabilityRoutingRejectsLegacyPeers() {
        val legacy = peer(
            "legacy",
            setOf(
                Capability.DISPLAY,
                Capability.PUSH_FILTERING,
                Capability.DISPLAY_NOTIFICATION_UPDATES,
            ),
        )
        val routed = peer(
            "routed",
            setOf(
                Capability.DISPLAY,
                Capability.CAPABILITY_ROUTING_V1,
                Capability.PUSH_FILTERING,
                Capability.DISPLAY_NOTIFICATION_UPDATES,
            ),
        )
        val directory = TrustPeerDirectory(FakeTrustState(listOf(legacy, routed)))
        val required = setOf(
            Capability.DISPLAY,
            Capability.CAPABILITY_ROUTING_V1,
            Capability.PUSH_FILTERING,
            Capability.DISPLAY_NOTIFICATION_UPDATES,
        )

        val recipients = directory.recipients(
            Recipients.OwnMeshFiltered(
                requiredCapabilities = required,
                requireCapabilityRoutingV1 = true,
            )
        )

        assertEquals(listOf(routed.clientId), recipients.map { it.clientId })
    }

    @Test
    fun forbiddenCapabilityExcludesOtherwiseMatchingPeer() {
        val compatibility = peer(
            "compatibility",
            setOf(
                Capability.DISPLAY,
                Capability.BACKGROUND_WAKE,
                Capability.CAPABILITY_ROUTING_V1,
            ),
        )
        val filtering = peer(
            "filtering",
            setOf(
                Capability.DISPLAY,
                Capability.BACKGROUND_WAKE,
                Capability.CAPABILITY_ROUTING_V1,
                Capability.PUSH_FILTERING,
            ),
        )
        val directory = TrustPeerDirectory(FakeTrustState(listOf(compatibility, filtering)))

        val recipients = directory.recipients(
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(Capability.DISPLAY, Capability.BACKGROUND_WAKE),
                forbiddenCapabilities = setOf(Capability.PUSH_FILTERING),
                requireCapabilityRoutingV1 = true,
            )
        )

        assertEquals(listOf(compatibility.clientId), recipients.map { it.clientId })
    }

    @Test
    fun ownMeshRepairsOnlyKeylessOwnDevices() {
        val own = keylessPeer("own", ownDevice = true)
        val other = keylessPeer("other", ownDevice = false)
        val directory = TrustPeerDirectory(FakeTrustState(emptyList(), listOf(own, other)))

        assertEquals(
            setOf(own.clientId),
            directory.unsealableRecipients(Recipients.OwnMesh),
        )
    }

    @Test
    fun filteredOwnMeshRepairsOnlyKeylessOwnDevicesMatchingItsCapabilityScope() {
        val required = setOf(
            Capability.DISPLAY,
            Capability.BACKGROUND_WAKE,
            Capability.CAPABILITY_ROUTING_V1,
        )
        val matchingOwn = keylessPeer("matching-own", ownDevice = true, capabilities = required)
        val mismatchedOwn = keylessPeer(
            "mismatched-own",
            ownDevice = true,
            capabilities = setOf(Capability.DISPLAY, Capability.CAPABILITY_ROUTING_V1),
        )
        val matchingOther = keylessPeer("matching-other", ownDevice = false, capabilities = required)
        val directory = TrustPeerDirectory(
            FakeTrustState(emptyList(), listOf(matchingOwn, mismatchedOwn, matchingOther)),
        )

        assertEquals(
            setOf(matchingOwn.clientId),
            directory.unsealableRecipients(
                Recipients.OwnMeshFiltered(
                    requiredCapabilities = required,
                    requireCapabilityRoutingV1 = true,
                ),
            ),
        )
    }

    @Test
    fun unicastRepairsAKeylessTargetOnlyWhenItIsAnOwnDevice() {
        val own = keylessPeer("own", ownDevice = true)
        val other = keylessPeer("other", ownDevice = false)
        val directory = TrustPeerDirectory(FakeTrustState(emptyList(), listOf(own, other)))

        assertEquals(
            setOf(own.clientId),
            directory.unsealableRecipients(Recipients.Only(own.clientId)),
        )
        assertEquals(
            emptySet<ClientId>(),
            directory.unsealableRecipients(Recipients.Only(other.clientId)),
        )
    }

    @Test
    fun allTrustedStillRepairsKeylessOwnAndOtherDevicesRegardlessOfCapabilities() {
        val own = keylessPeer(
            "own",
            ownDevice = true,
            capabilities = setOf(Capability.DISPLAY),
        )
        val other = keylessPeer("other", ownDevice = false)
        val directory = TrustPeerDirectory(FakeTrustState(emptyList(), listOf(own, other)))

        assertEquals(
            setOf(own.clientId, other.clientId),
            directory.unsealableRecipients(Recipients.AllTrusted),
        )
    }

    private fun peer(id: String, capabilities: Set<Capability>) = Peer(
        clientId = clientId(id),
        displayName = id,
        platform = "linux",
        identityPublicKeyB64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
        hpkePublicKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
        addedAt = 1L,
        capabilities = capabilities.toList(),
        currentEpoch = 1,
    )

    private fun keylessPeer(
        id: String,
        ownDevice: Boolean,
        capabilities: Set<Capability> = emptySet(),
        platform: String = "linux",
    ) = KeylessPeerMetadata(clientId(id), ownDevice, platform, capabilities.toList())

    private fun clientId(id: String) = ClientId(id.padEnd(52, 'a'))

    private data class KeylessPeerMetadata(
        val clientId: ClientId,
        val ownDevice: Boolean,
        val platform: String,
        val capabilities: List<Capability>,
    )

    private class FakeTrustState(
        peers: List<Peer>,
        keylessPeers: List<KeylessPeerMetadata> = emptyList(),
    ) : TrustState {
        private val keylessPeers = keylessPeers.associateBy(KeylessPeerMetadata::clientId)

        override val activePeers: StateFlow<List<Peer>> = MutableStateFlow(peers)
        override fun displayName(clientId: ClientId): String? = null
        override fun peerPlatform(clientId: ClientId): String? = keylessPeers[clientId]?.platform
        override fun peerCapabilities(clientId: ClientId): List<Capability> =
            keylessPeers[clientId]?.capabilities.orEmpty()
        override fun peerOwnDevice(clientId: ClientId): Boolean? = keylessPeers[clientId]?.ownDevice
        override fun peersNeedingKeyEpoch(now: Long): List<ClientId> = keylessPeers.keys.toList()
        override fun buildTrustTable() = TrustTable(emptyList())
        override fun trustedCards(): List<SignedBlob> = emptyList()
        override fun applyProfile(update: ProfileUpdate) = false
        override fun applyIncomingTable(sender: ClientId, table: TrustTable) =
            IncomingTrustResult(emptyList(), emptyList())
        override fun applyCard(clientId: ClientId, cardBlob: SignedBlob) = false
    }
}
