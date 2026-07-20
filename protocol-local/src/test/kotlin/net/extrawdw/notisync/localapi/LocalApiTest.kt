package net.extrawdw.notisync.localapi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiTest {
    @Test
    fun `unknown fields preserve rolling compatibility`() {
        val status = LocalApiJson.decodeFromString<DaemonStatus>(
            """{"version":"1","connectionState":"CONNECTED","future":42}""",
        )
        assertEquals(DaemonConnectionState.CONNECTED, status.connectionState)
    }

    @Test
    fun `generic send round trips protocol selectors without custom serial names`() {
        val request = SendRequest(
            applicationId = "nsrun",
            messageType = MessageType.DATA_SYNC,
            body = "AQID",
            scope = Recipients.OwnMeshFiltered(
                excluded = setOf(ClientId("desktop")),
                requiredCapabilities = setOf(Capability.DISPLAY, Capability.RECEIVE_RUNS),
                forbiddenCapabilities = setOf(Capability.PUSH_FILTERING),
                requireCapabilityRoutingV1 = true,
            ),
            urgency = Urgency.HIGH,
            signWith = SignerSelection.OPERATIONAL,
            submissionId = "run-17",
            queuePolicy = QueuePolicy(
                streamKey = "run/state",
                sequence = 17,
                coalesceKey = "run/native",
                supersedeKeys = setOf("run/starting"),
            ),
        )

        val encoded = LocalApiJson.encodeToString(request)

        assertTrue(
            encoded.contains(
                "\"type\":\"net.extrawdw.notisync.peer.channel.Recipients.OwnMeshFiltered\"",
            ),
        )
        assertFalse(encoded.contains("\"type\":\"ownMeshFiltered\""))
        assertEquals(request, LocalApiJson.decodeFromString<SendRequest>(encoded))
    }

    @Test
    fun `receive filters round trip string number and boolean scalars`() {
        val request = ReceiveRequest(
            applicationId = "nsrun",
            messageTypes = listOf(MessageType.NOTIFICATION, MessageType.DATA_SYNC),
            filters = listOf(
                MessageFilter(
                    messageType = MessageType.DATA_SYNC,
                    path = "/kind",
                    acceptedValues = listOf(JsonPrimitive("RUN")),
                ),
                MessageFilter(
                    messageType = MessageType.DATA_SYNC,
                    path = "/run/revision",
                    acceptedValues = listOf(JsonPrimitive(17), JsonPrimitive(18)),
                ),
                MessageFilter(
                    messageType = MessageType.DATA_SYNC,
                    path = "/run/active",
                    acceptedValues = listOf(JsonPrimitive(true)),
                ),
            ),
        )

        val encoded = LocalApiJson.encodeToString(request)
        val decoded = LocalApiJson.decodeFromString<ReceiveRequest>(encoded)

        assertEquals(request, decoded)
        assertTrue(encoded.contains("\"acceptedValues\":[\"RUN\"]"))
        assertTrue(encoded.contains("\"acceptedValues\":[17,18]"))
        assertTrue(encoded.contains("\"acceptedValues\":[true]"))
    }

    @Test
    fun `legacy semantic local API classes are absent`() {
        val legacySimpleNames = listOf(
            "CreateSessionRequest",
            "NotificationRequest",
            "RunStateRequest",
            "LocalEvent",
            "DismissalRequest",
            "ActionSendRequest",
        )

        legacySimpleNames.forEach { simpleName ->
            assertTrue(
                "$simpleName must not remain in protocol-local",
                runCatching {
                    Class.forName("net.extrawdw.notisync.localapi.$simpleName")
                }.isFailure,
            )
        }
    }
}
