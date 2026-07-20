package net.extrawdw.notisync.protocol

import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSelectorSerializationTest {
    @Test
    fun recipientSelectors_roundTripThroughJson() {
        val selectors = listOf<Recipients>(
            Recipients.OwnMesh,
            Recipients.AllTrusted,
            Recipients.OwnMeshFiltered(
                excluded = setOf(ClientId("phone")),
                excludedPlatforms = setOf("ios"),
                legacyExcludedPlatforms = setOf("desktop"),
                requiredCapabilities = setOf(Capability.DISPLAY, Capability.RECEIVE_RUNS),
                forbiddenCapabilities = setOf(Capability.PUSH_FILTERING),
                requireCapabilityRoutingV1 = true,
            ),
            Recipients.Only(ClientId("tablet")),
        )

        selectors.forEach { selector ->
            assertEquals(
                selector,
                ProtocolCodec.decodeFromJson<Recipients>(ProtocolCodec.encodeToJson(selector)),
            )
        }
    }

    @Test
    fun signerSelection_roundTripsThroughJsonUsingEnumNames() {
        SignerSelection.entries.forEach { selection ->
            val encoded = ProtocolCodec.encodeToJson(selection)
            assertEquals("\"${selection.name}\"", encoded)
            assertEquals(selection, ProtocolCodec.decodeFromJson<SignerSelection>(encoded))
        }
    }

    @Test
    fun runCapabilities_areAppendedInDeclarationOrder() {
        assertEquals(Capability.PUBLISH_RUNS, Capability.entries[Capability.entries.lastIndex - 1])
        assertEquals(Capability.RECEIVE_RUNS, Capability.entries.last())
    }
}
