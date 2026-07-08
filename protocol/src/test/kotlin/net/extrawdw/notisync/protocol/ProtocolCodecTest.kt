package net.extrawdw.notisync.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // NS2 defaults: an envelope without explicit epochs is identity-signed (0) to NS1-era recipients (0).
        assertEquals(0, decoded.signerEpoch)
        assertEquals(listOf(0, 0), decoded.recipientEpochs())
    }

    @Test
    fun envelope_ns2EpochFields_roundTrip() {
        val env = Envelope(
            typ = MessageType.NOTIFICATION,
            signerId = ClientId("sender-1"),
            signerEpoch = 3,
            messageId = "m",
            seq = 1L,
            createdAt = 1L,
            bodyCiphertext = byteArrayOf(1, 2),
            recipients = listOf(
                PerRecipientKey(ClientId("rcpt-a"), byteArrayOf(10), recipientEpoch = 2),
                PerRecipientKey(ClientId("rcpt-b"), byteArrayOf(20), recipientEpoch = 5),
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<Envelope>(ProtocolCodec.encodeToCbor(env))
        assertEquals(3, decoded.signerEpoch)
        assertEquals(listOf(2, 5), decoded.recipientEpochs())
    }

    @Test
    fun clientKeyEpoch_cborRoundTripsAndIsDeterministic() {
        val ke = ClientKeyEpoch(
            clientId = ClientId("client-aaa"),
            identityPublicKey = byteArrayOf(1, 2, 3),
            epoch = 2,
            operationalSigningKey = byteArrayOf(4, 5, 6),
            hpkePublicKey = byteArrayOf(7, 8, 9),
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = Long.MAX_VALUE,
            minEpoch = 1,
        )
        val a = ProtocolCodec.encodeToCbor(ke)
        assertArrayEquals("deterministic for signing", a, ProtocolCodec.encodeToCbor(ke))
        val decoded = ProtocolCodec.decodeFromCbor<ClientKeyEpoch>(a)
        assertEquals(2, decoded.epoch)
        assertEquals(1, decoded.minEpoch)
        assertEquals(ke.purposes, decoded.purposes)
        assertArrayEquals(ke.operationalSigningKey, decoded.operationalSigningKey)
    }

    @Test
    fun trustTableEntry_epochDefaultsToZero_forBackCompat() {
        // An older roster row (no epoch column) must still decode — epoch defaults to 0 (unknown).
        val legacy = ProtocolCodec.encodeToCbor(TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = true))
        assertEquals(0, ProtocolCodec.decodeFromCbor<TrustTableEntry>(legacy).epoch)
        val withEpoch = TrustTableEntry(ClientId("x"), TrustStatus.TRUSTED, 5L, keyAvailable = true, epoch = 4)
        assertEquals(4, ProtocolCodec.decodeFromCbor<TrustTableEntry>(ProtocolCodec.encodeToCbor(withEpoch)).epoch)
    }

    @Test
    fun cardDelivery_carriesCardAndOrEpochBlob() {
        val card = SignedBlob(SignedType.CLIENT_CARD, signerId = ClientId("x"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        val epoch = SignedBlob(SignedType.KEY_EPOCH, signerId = ClientId("x"), payload = byteArrayOf(3), sig = byteArrayOf(4))
        // Both populated (pairing), card-only (NS1-style), and epoch-only (NS2 push) all round-trip.
        for (delivery in listOf(CardDelivery(ClientId("x"), card, epoch), CardDelivery(ClientId("x"), card = card), CardDelivery(ClientId("x"), epochBlob = epoch))) {
            val decoded = ProtocolCodec.decodeFromCbor<CardDelivery>(ProtocolCodec.encodeToCbor(delivery))
            assertEquals(delivery.card?.typ, decoded.card?.typ)
            assertEquals(delivery.epochBlob?.typ, decoded.epochBlob?.typ)
        }
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
            groupKey = "chat-group",
            isGroupSummary = true,
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
            routeRef = "fake-fcm-route",
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
        assertEquals("fake-fcm-route", innerClaim.routeRef)
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
        val inlineImage = largeIcon.copy(
            role = AssetRole.INLINE_IMAGE,
            assetHash = "img789",
            mimeType = "image/png",
            assetId = "inline9",
        )
        val notif = CapturedNotification(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            packageName = "com.example.chat",
            appLabel = "Chat",
            largeIcon = largeIcon,
            style = NotifStyle.MESSAGING,
            messages = listOf(
                ConversationMessage(
                    sender = "Alice",
                    text = "Hi",
                    timestamp = 1L,
                    avatar = avatar,
                    dataMimeType = "image/png",
                    data = inlineImage,
                )
            ),
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
        assertEquals("image/png", decoded.messages[0].dataMimeType)
        assertEquals(AssetRole.INLINE_IMAGE, decoded.messages[0].data?.role)
        assertEquals("img789", decoded.messages[0].data?.assetHash)
    }

    @Test
    fun capturedNotification_iosAncsOriginAndAppIconRoundTrip() {
        val appIcon = PrivateAssetRef(
            role = AssetRole.APP_ICON,
            assetHash = "iconhash",
            mimeType = "image/webp",
            sizeBytes = 2048,
            sourceClientId = ClientId("bridge-phone"),
            assetId = "appiconid",
            assetKey = ByteArray(32) { (it + 1).toByte() },
        )
        val notif = CapturedNotification(
            sourceClientId = ClientId("bridge-phone"),
            sourceKey = "ancs|iphone-1|net.whatsapp.WhatsApp|42",
            packageName = "com.whatsapp", // best-match Android package
            appLabel = "WhatsApp",
            text = "Hello from iOS",
            postTime = 1_750_000_000_000L,
            appIcon = appIcon,
            originPlatform = OriginPlatform.IOS_ANCS,
            originDeviceName = "Dingwen's iPhone",
            iosBundleId = "net.whatsapp.WhatsApp",
            originDeviceId = "iph0nehash",
            shouldVibrate = true,
            onlyAlertOnce = true,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(notif))
        // PrivateAssetRef holds a ByteArray key (reference equality), so compare fields, not the whole object.
        assertEquals("com.whatsapp", decoded.packageName)
        assertEquals("WhatsApp", decoded.appLabel)
        assertEquals(OriginPlatform.IOS_ANCS, decoded.originPlatform)
        assertEquals("Dingwen's iPhone", decoded.originDeviceName)
        assertEquals("net.whatsapp.WhatsApp", decoded.iosBundleId)
        assertEquals("iph0nehash", decoded.originDeviceId)
        assertTrue(decoded.shouldVibrate)
        assertTrue(decoded.onlyAlertOnce)
        assertEquals(AssetRole.APP_ICON, decoded.appIcon?.role)
        assertEquals("appiconid", decoded.appIcon?.assetId)
        assertArrayEquals(appIcon.assetKey, decoded.appIcon?.assetKey)
    }

    @Test
    fun capturedNotification_originDefaultsForAndroidLocalCapture() {
        // A capture that omits the new fields decodes as an Android-local capture with no app-icon asset —
        // the wire-compat contract for the existing notification-listener path and older producers.
        val notif = CapturedNotification(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            packageName = "com.example",
            appLabel = "Example",
            text = "hi",
            postTime = 1L,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(notif))
        assertEquals(OriginPlatform.ANDROID_LOCAL, decoded.originPlatform)
        assertFalse(decoded.isGroupSummary)
        assertNull(decoded.appIcon)
        assertNull(decoded.originDeviceName)
        assertNull(decoded.iosBundleId)
        assertFalse(decoded.onlyAlertOnce) // older producer omitting the flag → not "only alert once"
    }

    @Test
    fun capturedNotification_actionsRoundTrip() {
        val notif = CapturedNotification(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            packageName = "com.example.chat",
            appLabel = "Chat",
            title = "Alice",
            text = "See you soon",
            postTime = 1_750_000_000_000L,
            actions = listOf(
                NotificationAction(index = 0, title = "Mark as read", semanticAction = 2),
                NotificationAction(
                    index = 2, title = "Reply", remoteInput = true,
                    remoteInputLabel = "Message", semanticAction = 1,
                ),
                NotificationAction(index = 3, title = "View", showsUserInterface = true),
            ),
            hasContentIntent = true,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(notif))
        assertEquals(notif, decoded)
        // The sparse index survives verbatim — it points into the ORIGIN's raw action array.
        assertEquals(listOf(0, 2, 3), decoded.actions.map { it.index })
        assertTrue(decoded.actions[1].remoteInput)
        assertEquals("Message", decoded.actions[1].remoteInputLabel)
        assertTrue(decoded.actions[2].showsUserInterface)
        assertTrue(decoded.hasContentIntent)
    }

    @Test
    fun capturedNotification_actionFieldsDefaultEmpty_forBackCompat() {
        // An older producer (no actions/hasContentIntent columns) must decode to "no actions, no
        // tap-open" — the consumer-side gate against sending ACTION events to a peer that cannot
        // handle MessageType.ACTION.
        val legacy = CapturedNotification(
            sourceClientId = ClientId("phone"), sourceKey = "k", packageName = "p",
            appLabel = "App", postTime = 1L,
        )
        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(ProtocolCodec.encodeToCbor(legacy))
        assertTrue(decoded.actions.isEmpty())
        assertFalse(decoded.hasContentIntent)
    }

    @Test
    fun actionEvent_performAndTapRoundTrip() {
        val perform = ActionEvent(
            sourceClientId = ClientId("phone"),
            sourceKey = "0|com.example|1|tag",
            kind = ActionKind.PERFORM,
            actionIndex = 2,
            actionTitle = "Reply",
            remoteInputText = "On my way!",
            actedAt = 1_750_000_000_000L,
        )
        assertEquals(perform, ProtocolCodec.decodeFromCbor<ActionEvent>(ProtocolCodec.encodeToCbor(perform)))

        val tap = ActionEvent(
            sourceClientId = ClientId("phone"),
            sourceKey = "ancs|iphone-1|net.whatsapp.WhatsApp|42",
            kind = ActionKind.TAP,
            actedAt = 1L,
        )
        val decoded = ProtocolCodec.decodeFromCbor<ActionEvent>(ProtocolCodec.encodeToCbor(tap))
        assertEquals(ActionKind.TAP, decoded.kind)
        assertEquals(0, decoded.actionIndex)
        assertNull(decoded.actionTitle)
        assertNull(decoded.remoteInputText)
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
                    TrustTableEntry(ClientId("a"), TrustStatus.TRUSTED, 100L, keyAvailable = true, ownDevice = false),
                    TrustTableEntry(ClientId("b"), TrustStatus.REVOKED, 200L, keyAvailable = false),
                ),
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.TRUST, decoded.kind)
        assertEquals(2, decoded.trust?.entries?.size)
        assertEquals(TrustStatus.TRUSTED, decoded.trust?.entries?.get(0)?.status)
        assertTrue(decoded.trust?.entries?.get(0)?.keyAvailable == true)
        assertFalse("the other-device category round-trips on the wire", decoded.trust?.entries?.get(0)?.ownDevice == true)
        assertTrue("a roster entry defaults to an own device", decoded.trust?.entries?.get(1)?.ownDevice == true)
        assertEquals(200L, decoded.trust?.entries?.get(1)?.updatedAt)
        assertNull(decoded.card)
    }

    @Test
    fun dataSync_filterRoundTripsAcrossOriginsAndScopes() {
        val body = DataSync(
            kind = DataSyncKind.FILTER,
            filter = FilterSync(
                rules = listOf(
                    // Android: whole-device, one app, one channel of an app.
                    NotificationFilterRule(OriginPlatform.ANDROID_LOCAL),
                    NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.whatsapp"),
                    NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.slack", channelId = "dms"),
                    // iOS (ANCS): whole bridged device, one app — never channel-scoped.
                    NotificationFilterRule(OriginPlatform.IOS_ANCS),
                    NotificationFilterRule(OriginPlatform.IOS_ANCS, appId = "net.whatsapp.WhatsApp"),
                ),
                updatedAt = 1_750_000_000_000L,
            ),
        )
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))
        assertEquals(DataSyncKind.FILTER, decoded.kind)
        assertEquals(5, decoded.filter?.rules?.size)
        assertEquals(1_750_000_000_000L, decoded.filter?.updatedAt)
        val deviceRule = decoded.filter?.rules?.get(0)
        assertEquals(OriginPlatform.ANDROID_LOCAL, deviceRule?.originPlatform)
        assertNull("a device-level rule carries no app", deviceRule?.appId)
        assertNull(deviceRule?.channelId)
        assertEquals("com.slack", decoded.filter?.rules?.get(2)?.appId)
        assertEquals("dms", decoded.filter?.rules?.get(2)?.channelId)
        assertEquals(OriginPlatform.IOS_ANCS, decoded.filter?.rules?.get(4)?.originPlatform)
        assertEquals("net.whatsapp.WhatsApp", decoded.filter?.rules?.get(4)?.appId)
        assertNull("the asset sub-body must be absent for a filter", decoded.asset)
        assertNull(decoded.card)
    }

    @Test
    fun dataSync_filterAbsentFieldDecodesNull_forBackCompat() {
        // An older producer (no `filter` column) must still decode — the field defaults to null. We assert
        // this by decoding a non-FILTER DataSync, which never populates `filter`.
        val legacy = DataSync(DataSyncKind.PROFILE, profile = ProfileUpdate(ClientId("p"), "P", "android", emptyList(), 1L))
        assertNull(ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(legacy)).filter)
        // An empty-rules FILTER (the "clear my filter" snapshot) round-trips to an empty list, not null.
        val cleared = DataSync(DataSyncKind.FILTER, filter = FilterSync(rules = emptyList(), updatedAt = 7L))
        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(cleared))
        assertEquals(0, decoded.filter?.rules?.size)
        assertEquals(7L, decoded.filter?.updatedAt)
    }

    @Test
    fun dataSync_cardDeliveryRoundTripsAndDecodesInnerCard() {
        val card = ClientCard(
            clientId = ClientId("c"),
            identityPublicKey = byteArrayOf(1, 2, 3),
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
