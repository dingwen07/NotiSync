package net.extrawdw.apps.notisync.messaging

import java.io.File
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.TrustTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedInboundDeliveryRouterBoundaryTest {
    @Test
    fun bodyIsDefensivelyCopiedAndDecoderRunsExactlyOnce() {
        val original = ProtocolCodec.encodeToCbor(notification())
        val callerBuffer = original.copyOf()
        val delivery = delivery(MessageType.NOTIFICATION, callerBuffer)
        callerBuffer.fill(0)

        val firstRead = delivery.encodedBody
        assertArrayEquals(original, firstRead)
        firstRead.fill(0)
        assertArrayEquals(original, delivery.encodedBody)

        var decodeCalls = 0
        val decoder = InboundPayloadDecoder { type, body ->
            decodeCalls += 1
            assertEquals(MessageType.NOTIFICATION, type)
            assertArrayEquals(original, body)
            body.fill(0)
            DecodedInboundMessage(
                ProtocolLeaf.Notification,
                DecodedInboundPayload.Notification(notification()),
            )
        }
        val router = InboundMessageRouter(decoder)

        assertType<InboundPlanningResult.Planned>(router.plan(delivery))

        assertEquals(1, decodeCalls)
        assertArrayEquals(original, delivery.encodedBody)
    }

    @Test
    fun authenticatedMetadataValidationStopsBeforeDecode() {
        var decodeCalls = 0
        val decoder = InboundPayloadDecoder { _, _ ->
            decodeCalls += 1
            throw AssertionError("invalid metadata reached decoder")
        }
        val router = InboundMessageRouter(decoder)

        assertTerminal(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), messageId = " ")),
            "missing_authenticated_message_id",
        )
        assertTerminal(
            router.plan(
                delivery(
                    MessageType.DATA_SYNC,
                    byteArrayOf(1),
                    messageId = "m".repeat(MAX_AUTHENTICATED_MESSAGE_ID_CHARS + 1),
                ),
            ),
            "invalid_authenticated_message_id",
        )
        assertSecurity(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), senderId = ClientId(" "))),
            "invalid_authenticated_sender_id",
        )
        assertSecurity(
            router.plan(
                delivery(
                    MessageType.DATA_SYNC,
                    byteArrayOf(1),
                    senderId = ClientId("s".repeat(MAX_AUTHENTICATED_CLIENT_ID_CHARS + 1)),
                ),
            ),
            "invalid_authenticated_sender_id",
        )
        assertSecurity(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), signerEpoch = -1)),
            "invalid_authenticated_signer_epoch",
        )
        assertSecurity(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), recipientEpoch = -1)),
            "invalid_authenticated_recipient_epoch",
        )
        assertTerminal(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), signedSequence = -1)),
            "invalid_signed_sequence",
        )
        assertTerminal(
            router.plan(delivery(MessageType.DATA_SYNC, byteArrayOf(1), signedCreatedAt = 0)),
            "invalid_signed_created_at",
        )

        assertEquals(0, decodeCalls)
    }

    @Test
    fun localAcceptedTimeAndExactTextBoundsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            delivery(MessageType.DATA_SYNC, byteArrayOf(1), acceptedAt = 0)
        }

        val result = InboundMessageRouter().plan(
            delivery(
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(filter()),
                messageId = "m".repeat(MAX_AUTHENTICATED_MESSAGE_ID_CHARS),
                senderId = ClientId("s".repeat(MAX_AUTHENTICATED_CLIENT_ID_CHARS)),
                ownDevice = false,
            ),
        )
        assertType<InboundPlanningResult.Planned>(result)
    }

    @Test
    fun planPreservesSignedAndLocalMetadata() {
        val body = ProtocolCodec.encodeToCbor(filter())
        val delivery = delivery(
            type = MessageType.DATA_SYNC,
            body = body,
            messageId = "signed-message",
            senderId = ClientId("signed-sender"),
            ownDevice = false,
            signerEpoch = 7,
            signedSequence = 1234,
            signedCreatedAt = 5678,
            recipientEpoch = 9,
            acceptedAt = 6789,
            deliveryMode = InboundDeliveryMode.FCM_RELAY_FETCH,
            forceSilent = true,
        )

        val command = assertType<InboundPlanningResult.Planned>(
            InboundMessageRouter().plan(delivery),
        ).command

        assertSame(delivery, command.delivery)
        assertEquals("signed-message", command.delivery.messageId)
        assertEquals(MessageType.DATA_SYNC, command.delivery.messageType)
        assertEquals(ClientId("signed-sender"), command.delivery.senderId)
        assertFalse(command.delivery.senderOwnDevice)
        assertEquals(7, command.delivery.signerEpoch)
        assertEquals(1234L, command.delivery.signedSequence)
        assertEquals(5678L, command.delivery.signedCreatedAt)
        assertEquals(9, command.delivery.recipientEpoch)
        assertArrayEquals(body, command.delivery.encodedBody)
        assertEquals(6789L, command.delivery.acceptedAt)
        assertEquals(InboundDeliveryMode.FCM_RELAY_FETCH, command.delivery.deliveryMode)
        assertTrue(command.delivery.forceSilent)
    }

    @Test
    fun malformedUnknownAndCatalogMismatchPathsFailClosed() {
        val normalRouter = InboundMessageRouter()
        assertTerminal(
            normalRouter.plan(delivery(MessageType.NOTIFICATION, byteArrayOf(1))),
            "malformed_notification",
        )

        val unknownAction = ProtocolCodec.encodeToCbor(
            ActionEvent(
                sourceClientId = ClientId("source"),
                sourceKey = "key",
                kind = ActionKind.PERFORM,
                actedAt = 42,
            ),
        ).replacingAscii("PERFORM", "UNKNOWN")
        assertTerminal(
            normalRouter.plan(delivery(MessageType.ACTION, unknownAction)),
            "malformed_action",
        )

        val mismatchingDecoder = InboundPayloadDecoder { _, _ ->
            DecodedInboundMessage(
                ProtocolLeaf.Notification,
                DecodedInboundPayload.Notification(notification()),
            )
        }
        val mismatchedRouter = InboundMessageRouter(mismatchingDecoder)
        assertSecurity(
            mismatchedRouter.plan(
                delivery(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(filter()),
                    ownDevice = false,
                ),
            ),
            "catalog_message_type_mismatch",
        )
    }

    @Test
    fun identityAndAnyVerifiedSignerPoliciesAreExplicit() {
        val router = InboundMessageRouter()
        val trustBody = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.TRUST, trust = TrustTable(emptyList())),
        )

        assertSecurity(
            router.plan(
                delivery(
                    MessageType.DATA_SYNC,
                    trustBody,
                    signerEpoch = 1,
                ),
            ),
            "identity_signer_required",
        )
        assertType<InboundPlanningResult.Planned>(
            router.plan(
                delivery(
                    MessageType.DATA_SYNC,
                    trustBody,
                    signerEpoch = 0,
                ),
            ),
        )

        val cardBody = ProtocolCodec.encodeToCbor(
            DataSync(
                DataSyncKind.CARD,
                card = CardDelivery(ClientId("card-subject")),
            ),
        )
        listOf(0, 4).forEach { epoch ->
            assertType<InboundPlanningResult.Planned>(
                router.plan(
                    delivery(
                        MessageType.DATA_SYNC,
                        cardBody,
                        signerEpoch = epoch,
                    ),
                ),
            )
        }
    }

    @Test
    fun persistedFacingTokensAndReasonCodesAreStable() {
        assertEquals(
            listOf("unknown", "websocket", "fcm_inline", "fcm_relay_fetch", "relay_drain"),
            InboundDeliveryMode.entries.map { it.token },
        )
        InboundDeliveryMode.entries.forEach { mode ->
            assertEquals(mode, InboundDeliveryMode.decode(mode.token))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InboundDeliveryMode.decode("future_mode")
        }
        listOf("", "UPPER", "contains space", "a".repeat(129)).forEach { reason ->
            assertThrows(IllegalArgumentException::class.java) {
                InboundPlanningResult.TerminalRejected(reason)
            }
        }
    }

    @Test
    fun appBoundaryDoesNotEmbedPeerCoreInboundMessage() {
        val forbidden = "net.extrawdw.notisync.peer.channel.InboundMessage"
        val classes = listOf(
            AuthenticatedInboundDelivery::class.java,
            PlannedInboundCommand::class.java,
            InboundMessageRouter::class.java,
        )
        val signatures = classes.flatMap { type ->
            buildList {
                type.declaredFields.forEach { add(it.genericType.typeName) }
                type.declaredConstructors.forEach { constructor ->
                    constructor.genericParameterTypes.forEach { add(it.typeName) }
                }
                type.declaredMethods.forEach { method ->
                    add(method.genericReturnType.typeName)
                    method.genericParameterTypes.forEach { add(it.typeName) }
                }
            }
        }
        assertTrue(signatures.none { it.contains(forbidden) })

        val source = listOf(
            File("src/main/java/net/extrawdw/apps/notisync/messaging/InboundMessageRouter.kt"),
            File("app/src/main/java/net/extrawdw/apps/notisync/messaging/InboundMessageRouter.kt"),
        ).firstOrNull(File::isFile)
        assertTrue(source != null)
        assertFalse(requireNotNull(source).readText().contains(forbidden))
    }

    private fun delivery(
        type: MessageType,
        body: ByteArray,
        messageId: String = "message-1",
        senderId: ClientId = ClientId("sender"),
        ownDevice: Boolean = true,
        signerEpoch: Int = 1,
        signedSequence: Long = 10,
        signedCreatedAt: Long = 20,
        recipientEpoch: Int = 2,
        acceptedAt: Long = 30,
        deliveryMode: InboundDeliveryMode = InboundDeliveryMode.WEBSOCKET,
        forceSilent: Boolean = false,
    ) = AuthenticatedInboundDelivery(
        messageId = messageId,
        messageType = type,
        senderId = senderId,
        senderOwnDevice = ownDevice,
        signerEpoch = signerEpoch,
        signedSequence = signedSequence,
        signedCreatedAt = signedCreatedAt,
        recipientEpoch = recipientEpoch,
        encodedBody = body,
        acceptedAt = acceptedAt,
        deliveryMode = deliveryMode,
        forceSilent = forceSilent,
    )

    private fun filter() = DataSync(
        DataSyncKind.FILTER,
        filter = FilterSync(emptyList(), updatedAt = 42),
    )

    private fun notification() = CapturedNotification(
        sourceClientId = ClientId("source"),
        sourceKey = "source-key",
        packageName = "example.app",
        appLabel = "Example",
        postTime = 42,
    )

    private fun assertTerminal(result: InboundPlanningResult, reason: String) {
        assertEquals(
            reason,
            assertType<InboundPlanningResult.TerminalRejected>(result).reasonCode,
        )
    }

    private fun assertSecurity(result: InboundPlanningResult, reason: String) {
        assertEquals(
            reason,
            assertType<InboundPlanningResult.SecurityBlocked>(result).reasonCode,
        )
    }

    private fun ByteArray.replacingAscii(from: String, to: String): ByteArray {
        require(from.length == to.length)
        val source = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        val index = indices.firstOrNull { candidate ->
            candidate + source.size <= size && source.indices.all { offset ->
                this[candidate + offset] == source[offset]
            }
        }
        require(index != null) { "encoded fixture is missing $from" }
        return copyOf().also { replacement.copyInto(it, destinationOffset = index) }
    }

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue(value is T)
        return value as T
    }
}
