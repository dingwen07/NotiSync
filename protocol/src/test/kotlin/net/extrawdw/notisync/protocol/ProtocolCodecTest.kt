package net.extrawdw.notisync.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun capturedNotification_withPrivateAssetsCborRoundTrips() {
        val largeIcon = PrivateAssetRef(
            role = AssetRole.LARGE_ICON,
            assetHash = "abc123",
            mimeType = "image/webp",
            sizeBytes = 4096,
            sourceClientId = ClientId("phone"),
            assetId = "qkx3opaqueid",
            assetKey = ByteArray(32) { it.toByte() },
        )
        val avatar = largeIcon.copy(role = AssetRole.AVATAR, assetHash = "def456", assetId = "zzz9other")
        val notif = CapturedNotification(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            packageName = "com.example.chat",
            appLabel = "Chat",
            largeIcon = largeIcon,
            style = NotifStyle.MESSAGING,
            messages = listOf(ConversationMessage(sender = "Alice", text = "Hi", timestamp = 1L, avatar = avatar)),
            postTime = 1_750_000_000_000L,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(notif))

        assertEquals(AssetRole.LARGE_ICON, decoded.largeIcon?.role)
        assertEquals("abc123", decoded.largeIcon?.assetHash)
        assertEquals("qkx3opaqueid", decoded.largeIcon?.assetId)
        assertEquals(CipherSuite.CURRENT_ID, decoded.largeIcon?.suite) // default carried
        assertArrayEquals(largeIcon.assetKey, decoded.largeIcon?.assetKey)
        assertEquals(AssetRole.AVATAR, decoded.messages[0].avatar?.role)
        assertEquals("def456", decoded.messages[0].avatar?.assetHash)
    }

    @Test
    fun assetSync_cborRoundTrips() {
        val sync = AssetSync(
            kind = AssetSyncKind.ASSET_READY,
            items = listOf(
                AssetSyncItem(assetHash = "h1", assetId = "old1"),
                AssetSyncItem(
                    assetHash = "h2",
                    ref = PrivateAssetRef(
                        role = AssetRole.BIG_PICTURE, assetHash = "h2", mimeType = "image/webp",
                        sizeBytes = 9, sourceClientId = ClientId("phone"), assetId = "new2",
                        assetKey = ByteArray(32),
                    ),
                ),
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<AssetSync>(ProtocolCodec.encodeToCbor(sync))
        assertEquals(AssetSyncKind.ASSET_READY, decoded.kind)
        assertEquals("old1", decoded.items[0].assetId)
        assertEquals("new2", decoded.items[1].ref?.assetId)
        assertEquals(AssetRole.BIG_PICTURE, decoded.items[1].ref?.role)
    }

    @Test
    fun dataSync_profileRoundTrips() {
        val body = DataSync(
            kind = DataSyncKind.PROFILE,
            profile = ProfileUpdate(
                clientId = ClientId("phone"),
                displayName = "Living Room Pixel",
                platform = "android",
                capabilities = listOf(Capability.CAPTURE, Capability.DISPLAY),
                updatedAt = 1_750_000_000_000L,
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.PROFILE, decoded.kind)
        assertEquals("Living Room Pixel", decoded.profile?.displayName)
        assertEquals(ClientId("phone"), decoded.profile?.clientId)
        assertEquals(listOf(Capability.CAPTURE, Capability.DISPLAY), decoded.profile?.capabilities)
        assertEquals(1_750_000_000_000L, decoded.profile?.updatedAt)
        assertNull("asset sub-body must be absent for a profile update", decoded.asset)
    }

    @Test
    fun dataSync_assetWrapperRoundTrips() {
        val body = DataSync(
            kind = DataSyncKind.ASSET,
            asset = AssetSync(AssetSyncKind.ASSET_MISSING, listOf(AssetSyncItem(assetHash = "h1", assetId = "old1"))),
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.ASSET, decoded.kind)
        assertEquals(AssetSyncKind.ASSET_MISSING, decoded.asset?.kind)
        assertEquals("old1", decoded.asset?.items?.get(0)?.assetId)
        assertNull("profile sub-body must be absent for an asset sync", decoded.profile)
    }

    @Test
    fun dataSync_discriminatorOnlyBodyDecodesToNulls() {
        // Forward-compat contract: a body carrying only the discriminator must decode cleanly, not throw.
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE)))
        assertEquals(DataSyncKind.PROFILE, decoded.kind)
        assertNull(decoded.asset)
        assertNull(decoded.profile)
    }

    @Test
    fun dataSync_trustTableRoundTrips() {
        val body = DataSync(
            kind = DataSyncKind.TRUST,
            trust = TrustTable(
                listOf(
                    TrustTableEntry(ClientId("a"), TrustStatus.TRUSTED, 100L, keyAvailable = true),
                    TrustTableEntry(ClientId("b"), TrustStatus.REVOKED, 200L, keyAvailable = false),
                ),
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.TRUST, decoded.kind)
        assertEquals(2, decoded.trust?.entries?.size)
        assertEquals(TrustStatus.TRUSTED, decoded.trust?.entries?.get(0)?.status)
        assertTrue(decoded.trust?.entries?.get(0)?.keyAvailable == true)
        assertEquals(200L, decoded.trust?.entries?.get(1)?.updatedAt)
        assertNull(decoded.card)
    }

    @Test
    fun dataSync_cardDeliveryRoundTripsAndDecodesInnerCard() {
        val card = ClientCard(
            clientId = ClientId("c"),
            identityPublicKey = byteArrayOf(1, 2, 3),
            hpkePublicKeyset = byteArrayOf(4, 5, 6),
            displayName = "Tablet",
            platform = "android",
            capabilities = listOf(Capability.DISPLAY),
            createdAt = 1L,
        )
        val blob = SignedBlob(
            typ = SignedType.CLIENT_CARD,
            signerId = ClientId("c"),
            payload = ProtocolCodec.encodeToCbor(card),
            sig = byteArrayOf(9),
        )
        val body = DataSync(kind = DataSyncKind.CARD, card = CardDelivery(ClientId("c"), blob))
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.CARD, decoded.kind)
        assertEquals(ClientId("c"), decoded.card?.clientId)
        assertEquals("Tablet", decoded.card?.card?.decode<ClientCard>()?.displayName)
        assertNull(decoded.trust)
    }
}
