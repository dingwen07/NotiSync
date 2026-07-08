package net.extrawdw.notisync.protocol

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract behind [ProtocolCodec.cbor]'s `encodeDefaults = false` (see [Codec]):
 *  - default-valued fields are omitted (the size win that feeds the FCM/APNs inline-push budget);
 *  - the version/suite discriminators are still emitted, via `@EncodeDefault(ALWAYS)`;
 *  - a peer on the old `encodeDefaults = true` config and a peer on the new one interoperate BOTH
 *    directions (a mixed fleet, and any blob persisted at rest under the old config);
 *  - [EnvelopeAuth] and [AssetAad] — the only structs re-encoded independently on each end, for the
 *    envelope signature and the asset AEAD AAD — are byte-identical under both configs, so signatures
 *    and asset decryption are unaffected. This last property is the safety core of the change.
 *
 * [legacyFat] simulates a peer (or a persisted blob) produced by the pre-change config.
 */
class CborWireCompatTest {

    private val codec = ProtocolCodec.cbor // production: encodeDefaults = false
    private val legacyFat = Cbor { ignoreUnknownKeys = true; encodeDefaults = true } // pre-change peer

    /** The CBOR map-key header for a short (<24-char) text key: 0x60+len, then the UTF-8 name. */
    private fun cborKey(name: String): ByteArray {
        require(name.length < 24)
        return byteArrayOf((0x60 + name.length).toByte()) + name.toByteArray()
    }

    private fun ByteArray.containsKey(name: String): Boolean {
        val needle = cborKey(name)
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private val asset = PrivateAssetRef(
        role = AssetRole.AVATAR, assetHash = "h", mimeType = "image/webp", sizeBytes = 4096,
        sourceClientId = ClientId("phone"), assetId = "id", assetKey = ByteArray(32) { it.toByte() },
    )

    private fun simpleNotif() = CapturedNotification(
        sourceClientId = ClientId("a3b2f8c1deadbeef"), sourceKey = "0|com.whatsapp|1|null",
        packageName = "com.whatsapp", appLabel = "WhatsApp", title = "Alice", text = "See you at 6!",
        category = MirrorCategory.MESSAGE, importance = MirrorImportance.HIGH, postTime = 1_750_000_000_000L,
        channelId = "messages", channelName = "Messages",
    )

    @Test
    fun discriminators_v_and_suite_stayOnWire() {
        // `@EncodeDefault(ALWAYS)`: these must survive even when equal to their default. Letting
        // encodeDefaults omit them would couple the wire to CipherSuite.CURRENT_ID at encode time and
        // break the next suite bump — including blobs persisted at rest. This guards the annotation.
        val env = ProtocolCodec.encodeToCbor(
            Envelope(
                typ = MessageType.NOTIFICATION, signerId = ClientId("a"), messageId = "m", seq = 0L,
                createdAt = 0L, bodyCiphertext = ByteArray(4), recipients = emptyList(),
            ),
        )
        assertTrue("Envelope.v must stay on the wire", env.containsKey("v"))
        assertTrue("Envelope.suite must stay on the wire", env.containsKey("suite"))

        assertTrue(
            "SignedBlob.suite must stay on the wire",
            ProtocolCodec.encodeToCbor(
                SignedBlob(SignedType.KEY_EPOCH, signerId = ClientId("a"), payload = byteArrayOf(1), sig = byteArrayOf(2)),
            ).containsKey("suite"),
        )
        assertTrue(
            "ClientCard.suite must stay on the wire",
            ProtocolCodec.encodeToCbor(
                ClientCard(
                    clientId = ClientId("a"), identityPublicKey = byteArrayOf(1), displayName = "d",
                    platform = "android", capabilities = emptyList(), createdAt = 0L,
                ),
            ).containsKey("suite"),
        )
        assertTrue(
            "ClientKeyEpoch.suite must stay on the wire",
            ProtocolCodec.encodeToCbor(
                ClientKeyEpoch(
                    clientId = ClientId("a"), identityPublicKey = byteArrayOf(1), epoch = 1,
                    operationalSigningKey = byteArrayOf(1), hpkePublicKey = byteArrayOf(1),
                    purposes = emptyList(), notBefore = 0L, notAfter = 1L, minEpoch = 1,
                ),
            ).containsKey("suite"),
        )
        assertTrue("PrivateAssetRef.suite must stay on the wire", ProtocolCodec.encodeToCbor(asset).containsKey("suite"))
    }

    @Test
    fun defaultValuedFields_areOmitted() {
        val notif = simpleNotif()
        val bytes = ProtocolCodec.encodeToCbor(notif)
        // Fields left at their default must not appear on the wire — this is the size win.
        assertFalse(bytes.containsKey("isGroupConversation"))
        assertFalse(bytes.containsKey("sensitiveRedacted"))
        assertFalse(bytes.containsKey("isClearable"))
        assertFalse(bytes.containsKey("groupAlertBehavior"))
        assertFalse(bytes.containsKey("onlyAlertOnce"))
        assertFalse(bytes.containsKey("actions"))
        assertFalse(bytes.containsKey("hasContentIntent"))
        // Self-calibrating regression guard: flipping encodeDefaults back to true roughly triples the size.
        val lean = bytes.size
        val fat = legacyFat.encodeToByteArray(notif).size
        assertTrue("lean ($lean B) should be well under the old fat encoding ($fat B)", lean < fat * 0.6)
    }

    @Test
    fun mixedFleet_decodesBothDirections() {
        val notif = simpleNotif() // asset-free → safe for value equality (no ByteArray fields)
        // old peer → new reader
        assertEquals(notif, codec.decodeFromByteArray<CapturedNotification>(legacyFat.encodeToByteArray(notif)))
        // new peer → old reader
        assertEquals(notif, legacyFat.decodeFromByteArray<CapturedNotification>(codec.encodeToByteArray(notif)))
    }

    @Test
    fun mixedFleet_actionsSurviveBothConfigs() {
        val withActions = simpleNotif().copy(
            actions = listOf(
                NotificationAction(index = 0, title = "Mark as read", semanticAction = 2),
                NotificationAction(index = 1, title = "Reply", remoteInput = true, remoteInputLabel = "Message"),
            ),
            hasContentIntent = true,
        )
        assertEquals(withActions, codec.decodeFromByteArray<CapturedNotification>(legacyFat.encodeToByteArray(withActions)))
        assertEquals(withActions, legacyFat.decodeFromByteArray<CapturedNotification>(codec.encodeToByteArray(withActions)))
    }

    @Test
    fun mixedFleet_withAssets_preservesAllFields() {
        // CapturedNotification.equals can't be used here (PrivateAssetRef.assetKey is a ByteArray with
        // reference equality), so prove field preservation by re-encoding under a fixed codec.
        val rich = simpleNotif().copy(
            largeIcon = asset, style = NotifStyle.MESSAGING,
            messages = listOf(ConversationMessage("Alice", "hi", 1L, avatar = asset)),
        )
        val viaLegacy = codec.decodeFromByteArray<CapturedNotification>(legacyFat.encodeToByteArray(rich))
        assertArrayEquals(codec.encodeToByteArray(rich), codec.encodeToByteArray(viaLegacy))
    }

    @Test
    fun cryptoStructs_areByteIdenticalAcrossConfigs() {
        val auth = EnvelopeAuth(
            v = 1, suite = "NS2", typ = MessageType.NOTIFICATION, signerId = ClientId("a"), signerEpoch = 1,
            messageId = "m", seq = 1L, createdAt = 1L, bodyCiphertextSha256 = ByteArray(32) { it.toByte() },
            recipientIds = listOf(ClientId("b")), recipientEpochs = listOf(1),
        )
        assertArrayEquals(legacyFat.encodeToByteArray(auth), codec.encodeToByteArray(auth))

        val aad = AssetAad("NS2", ClientId("a"), "id", "image/png", 99, AssetRole.AVATAR)
        assertArrayEquals(legacyFat.encodeToByteArray(aad), codec.encodeToByteArray(aad))
    }

    @Test
    fun signedBlob_innerPayloadAndSigArePreserved() {
        // A wrapper re-encode (broker relay / persist-at-rest) must never touch the opaque, signed payload.
        val blob = SignedBlob(
            SignedType.CLIENT_CARD, signerId = ClientId("a"),
            payload = ByteArray(40) { it.toByte() }, sig = ByteArray(16) { (it + 1).toByte() },
        )
        val rt = codec.decodeFromByteArray<SignedBlob>(codec.encodeToByteArray(blob))
        assertArrayEquals(blob.payload, rt.payload)
        assertArrayEquals(blob.sig, rt.sig)
        assertEquals(blob.suite, rt.suite)
    }
}
