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
            Recipients.OnlyCapable(
                ClientId("screen-source"),
                setOf(Capability.SCREEN_MIRROR_SOURCE_V1, Capability.SCREEN_MIRROR_ENCODER_H264_HW),
            ),
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
    fun screenCapabilities_areAppendedInPermanentWireOrder() {
        assertEquals(
            listOf(
                Capability.SCREEN_MIRROR_SOURCE_V1,
                Capability.SCREEN_MIRROR_CONTROL_V1,
                Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                Capability.SCREEN_MIRROR_ENCODER_H264_HW,
                Capability.SCREEN_MIRROR_ENCODER_H265_HW,
                Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
                Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1,
            ),
            Capability.entries.drop(12),
        )
    }
}
