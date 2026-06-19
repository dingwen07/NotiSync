package net.extrawdw.notisync.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun base32_matchesRfc4648Vector() {
        // RFC 4648 base32("foobar") = "MZXW6YTBOI"; we emit lowercase, no padding.
        assertEquals("mzxw6ytboi", Base32.encode("foobar".toByteArray()))
        assertEquals("my", Base32.encode("f".toByteArray()))
        assertEquals("", Base32.encode(ByteArray(0)))
    }

    @Test
    fun clientId_serializesAsBareString() {
        val id = ClientId("a3b2f8c1deadbeef")
        val json = ProtocolCodec.encodeToJson(id)
        assertEquals("\"a3b2f8c1deadbeef\"", json)
        assertEquals(id, ProtocolCodec.decodeFromJson<ClientId>(json))
    }

    @Test
    fun clientCard_cborRoundTripsAndIsDeterministic() {
        val card = ClientCard(
            clientId = ClientId("client-aaa"),
            identityPublicKey = byteArrayOf(1, 2, 3, 4, 5),
            hpkePublicKeyset = byteArrayOf(9, 8, 7),
            displayName = "Pixel 10 Pro XL",
            platform = "android",
            capabilities = listOf(Capability.CAPTURE, Capability.DISPLAY, Capability.DISMISS_SYNC),
            createdAt = 1_750_000_000_000L,
        )
        val a = ProtocolCodec.encodeToCbor(card)
        val b = ProtocolCodec.encodeToCbor(card)
        assertArrayEquals("CBOR encoding must be deterministic for signing", a, b)

        val decoded = ProtocolCodec.decodeFromCbor<ClientCard>(a)
        assertEquals(card.clientId, decoded.clientId)
        assertEquals(card.displayName, decoded.displayName)
        assertEquals(card.capabilities, decoded.capabilities)
        assertArrayEquals(card.identityPublicKey, decoded.identityPublicKey)
    }

    @Test
    fun envelope_cborRoundTrips() {
        val env = Envelope(
            typ = MessageType.NOTIFICATION,
            signerId = ClientId("sender-1"),
            messageId = "01J0ABCXYZ",
            seq = 42L,
            createdAt = 1_750_000_000_000L,
            bodyCiphertext = ByteArray(64) { it.toByte() },
            recipients = listOf(
                PerRecipientKey(ClientId("rcpt-a"), byteArrayOf(10, 11, 12)),
                PerRecipientKey(ClientId("rcpt-b"), byteArrayOf(20, 21, 22)),
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<Envelope>(ProtocolCodec.encodeToCbor(env))
        assertEquals(env.messageId, decoded.messageId)
        assertEquals(env.seq, decoded.seq)
        assertEquals(listOf(ClientId("rcpt-a"), ClientId("rcpt-b")), decoded.recipientIds())
        assertArrayEquals(env.bodyCiphertext, decoded.bodyCiphertext)
    }

    @Test
    fun capturedNotification_messagingStyleRoundTrips() {
        val notif = CapturedNotification(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            packageName = "com.example.chat",
            appLabel = "Chat",
            title = "Alice",
            text = "See you soon",
            style = NotifStyle.MESSAGING,
            conversationTitle = "Alice",
            messages = listOf(
                ConversationMessage(sender = "Alice", text = "Hey", timestamp = 1L),
                ConversationMessage(sender = null, text = "Hi!", timestamp = 2L),
            ),
            category = MirrorCategory.MESSAGE,
            importance = MirrorImportance.HIGH,
            postTime = 1_750_000_000_000L,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(notif))
        assertEquals(notif, decoded)
        assertEquals(2, decoded.messages.size)
        assertTrue(decoded.messages[1].sender == null)
    }

    @Test
    fun signedBlob_roundTripsAndDecodesInner() {
        val claim = RouteClaim(
            clientId = ClientId("client-aaa"),
            transport = TransportType.FCM,
            environment = RouteEnvironment.PRODUCTION,
            routeRef = "fake-fcm-token",
            capabilities = RouteCapabilities(inlinePayloadLimitBytes = 3072),
            epoch = 1,
            issuedAt = 1_750_000_000_000L,
        )
        val payload = ProtocolCodec.encodeToCbor(claim)
        val signed = SignedBlob(
            typ = SignedType.ROUTE_CLAIM,
            signerId = ClientId("client-aaa"),
            payload = payload,
            sig = byteArrayOf(0xD, 0xE, 0xA, 0xD),
        )
        val decoded = ProtocolCodec.decodeFromCbor<SignedBlob>(ProtocolCodec.encodeToCbor(signed))
        assertEquals(SignedType.ROUTE_CLAIM, decoded.typ)
        val innerClaim = decoded.decode<RouteClaim>()
        assertEquals("fake-fcm-token", innerClaim.routeRef)
        assertEquals(TransportType.FCM, innerClaim.transport)
    }
}
