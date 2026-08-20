package net.extrawdw.apps.notisync.messaging

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundMessageRouterTest {
    @Test
    fun dataSyncIsDecodedAndClassifiedOnceForItsOwner() {
        val router = InboundMessageRouter()
        val body = DataSync(
            kind = DataSyncKind.FILTER,
            filter = FilterSync(rules = emptyList(), updatedAt = 42),
        )

        val planned = assertType<InboundPlanningResult.Planned>(
            router.plan(message(MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(body), ownDevice = false)),
        ).command

        assertEquals(ProtocolLeaf.Filter, planned.descriptor.leaf)
        assertEquals(body, assertType<DecodedInboundPayload.Data>(planned.payload).value)
    }

    @Test
    fun mismatchedDataSyncBodyIsTerminallyRejected() {
        val malformed = DataSync(
            kind = DataSyncKind.PROFILE,
            filter = FilterSync(rules = emptyList(), updatedAt = 42),
        )

        val result = InboundMessageRouter().plan(
            message(MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(malformed), ownDevice = false),
        )

        assertEquals(
            "malformed_data_sync",
            assertType<InboundPlanningResult.TerminalRejected>(result).reasonCode,
        )
    }

    @Test
    fun senderAndSignerPoliciesBlockBeforeDispatch() {
        val quietNotification = DataSync(
            kind = DataSyncKind.NOTIFICATION,
            notification = testNotification(),
        )
        val router = InboundMessageRouter()

        val otherDevice = router.plan(
            message(MessageType.DATA_SYNC, ProtocolCodec.encodeToCbor(quietNotification), ownDevice = false),
        )
        assertEquals(
            "own_device_required",
            assertType<InboundPlanningResult.SecurityBlocked>(otherDevice).reasonCode,
        )

        val identitySigned = router.plan(
            message(
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(quietNotification),
                ownDevice = true,
                signerEpoch = 0,
            ),
        )
        assertEquals(
            "operational_signer_required",
            assertType<InboundPlanningResult.SecurityBlocked>(identitySigned).reasonCode,
        )
    }

    private fun message(
        type: MessageType,
        body: ByteArray,
        ownDevice: Boolean,
        signerEpoch: Int = 1,
    ) = AuthenticatedInboundDelivery(
        messageId = "message-1",
        messageType = type,
        senderId = ClientId("sender"),
        senderOwnDevice = ownDevice,
        signerEpoch = signerEpoch,
        signedSequence = 1,
        signedCreatedAt = 2,
        recipientEpoch = 1,
        encodedBody = body,
        acceptedAt = 3,
        deliveryMode = InboundDeliveryMode.WEBSOCKET,
        forceSilent = false,
    )

    private fun testNotification() = CapturedNotification(
        sourceClientId = ClientId("source"),
        sourceKey = "source-key",
        packageName = "example.app",
        appLabel = "Example",
        postTime = 42,
    )

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
        return value as T
    }
}
