package net.extrawdw.notisync.protocol

import java.util.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins NS2's compact-only CBOR contract: definite maps, stable numeric labels and tolerant evolution. */
@OptIn(ExperimentalSerializationApi::class)
class CborWireCompatTest {

    /** Future producer shape: label 99 and capability ids unknown to this reader are both intentional. */
    @Serializable
    private data class FutureProfile(
        @CborLabel(0) val clientId: ClientId,
        @CborLabel(1) val displayName: String,
        @CborLabel(2) val platform: String,
        @CborLabel(3) val capabilities: List<Int>,
        @CborLabel(4) val updatedAt: Long,
        @CborLabel(99) val futureField: String,
    )

    /** Raw view used to pin the permanent integer capability registry without sharing its implementation. */
    @Serializable
    private data class RawCapabilityProfile(
        @CborLabel(0) val clientId: ClientId,
        @CborLabel(1) val displayName: String,
        @CborLabel(2) val platform: String,
        @CborLabel(3) val capabilities: List<Int>,
        @CborLabel(4) val updatedAt: Long,
    )

    /** Simulates the capability registry shipped before screen sharing (wire ids 0..11 only). */
    private object PreScreenCapabilityListSerializer : KSerializer<List<Capability>> {
        private val numbered = ListSerializer(Int.serializer())
        override val descriptor: SerialDescriptor = numbered.descriptor

        override fun deserialize(decoder: Decoder): List<Capability> =
            numbered.deserialize(decoder).mapNotNull { id ->
                when (id) {
                    in 0..11 -> Capability.entries[id]
                    else -> null
                }
            }.distinct()

        override fun serialize(encoder: Encoder, value: List<Capability>) =
            numbered.serialize(encoder, value.map(Capability::ordinal))
    }

    @Serializable
    private data class PreScreenProfile(
        @CborLabel(0) val clientId: ClientId,
        @CborLabel(1) val displayName: String,
        @CborLabel(2) val platform: String,
        @CborLabel(3) @Serializable(with = PreScreenCapabilityListSerializer::class)
        val capabilities: List<Capability>,
        @CborLabel(4) val updatedAt: Long,
    )

    @Serializable
    private data class PreScreenClientCard(
        @CborLabel(0) val suite: String,
        @CborLabel(1) val clientId: ClientId,
        @CborLabel(2) @ByteString val identityPublicKey: ByteArray,
        @CborLabel(3) val displayName: String,
        @CborLabel(4) val platform: String,
        @CborLabel(5) @Serializable(with = PreScreenCapabilityListSerializer::class)
        val capabilities: List<Capability>,
        @CborLabel(6) val createdAt: Long,
    )

    @Serializable
    private data class PreScreenProfileDataSync(
        @CborLabel(0) val kind: DataSyncKind,
        @CborLabel(2) val profile: PreScreenProfile? = null,
    )

    @Serializable
    private data class LegacyProfile(
        val clientId: ClientId,
        val displayName: String,
        val platform: String,
        val capabilities: List<Capability>,
        val updatedAt: Long,
    )

    @Serializable
    private data class LegacyClientCard(
        val suite: String,
        val clientId: ClientId,
        val identityPublicKey: ByteArray,
        val displayName: String,
        val platform: String,
        val capabilities: List<Capability>,
        val createdAt: Long,
    )

    @Serializable
    private data class EnvelopeHeader(
        @CborLabel(0) val v: Int? = null,
        @CborLabel(1) val suite: String? = null,
    )

    @Serializable
    private data class SuiteAtZero(@CborLabel(0) val suite: String? = null)

    @Serializable
    private data class SuiteAtOne(@CborLabel(1) val suite: String? = null)

    @Serializable
    private data class SuiteAtSeven(@CborLabel(7) val suite: String? = null)

    private val namedKeys = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = false
        useDefiniteLengthEncoding = true
        preferCborLabelsOverNames = false
    }

    private val asset = PrivateAssetRef(
        role = AssetRole.AVATAR,
        assetHash = "h",
        mimeType = "image/webp",
        sizeBytes = 4096,
        sourceClientId = ClientId("phone"),
        assetId = "id",
        assetKey = ByteArray(32) { it.toByte() },
    )

    private fun simpleNotif() = CapturedNotification(
        sourceClientId = ClientId("a3b2f8c1deadbeef"),
        sourceKey = "0|com.whatsapp|1|null",
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        title = "Alice",
        text = "See you at 6!",
        category = MirrorCategory.MESSAGE,
        importance = MirrorImportance.HIGH,
        postTime = 1_750_000_000_000L,
        channelId = "messages",
        channelName = "Messages",
    )

    @Test
    fun unknownCapabilityIdsAndUnknownFields_areIgnoredWithoutLosingKnownCapabilities() {
        val future = FutureProfile(
            clientId = ClientId("future-peer"),
            displayName = "Future peer",
            platform = "desktop",
            capabilities = listOf(1, 4000, 6, 1, 11, Int.MAX_VALUE),
            updatedAt = 42L,
            futureField = "not understood yet",
        )

        val decoded = ProtocolCodec.decodeFromCbor<ProfileUpdate>(ProtocolCodec.encodeToCbor(future))

        assertEquals(future.clientId, decoded.clientId)
        assertEquals(future.displayName, decoded.displayName)
        assertEquals(future.updatedAt, decoded.updatedAt)
        assertEquals(
            listOf(Capability.DISPLAY, Capability.CAPABILITY_ROUTING_V1, Capability.RECEIVE_RUNS),
            decoded.capabilities,
        )
    }

    @Test
    fun capabilityNamesRemainTheJsonRepresentation() {
        val profile = ProfileUpdate(
            ClientId("peer"),
            "Peer",
            "android",
            listOf(Capability.CAPTURE, Capability.DISPLAY),
            1L,
        )

        val json = ProtocolCodec.encodeToJson(profile)
        assertTrue(json.contains("\"clientId\":\"peer\""))
        assertTrue(json.contains("\"capabilities\":[\"CAPTURE\",\"DISPLAY\"]"))
        assertEquals(profile, ProtocolCodec.decodeFromJson<ProfileUpdate>(json))
    }

    @Test
    fun screenCapabilitiesUsePermanentIds12Through18() {
        val screenCapabilities = Capability.entries.drop(12)
        val profile = ProfileUpdate(ClientId("screen"), "Screen", "android", screenCapabilities, 1L)

        val raw = ProtocolCodec.decodeFromCbor<RawCapabilityProfile>(ProtocolCodec.encodeToCbor(profile))

        assertEquals((12..18).toList(), raw.capabilities)
    }

    @Test
    fun preScreenReadersDropNewCapabilitiesWithoutDroppingTheirContainers() {
        val capabilities = listOf(
            Capability.CAPTURE,
            Capability.SCREEN_MIRROR_SOURCE_V1,
            Capability.DISPLAY,
            Capability.SCREEN_MIRROR_ENCODER_H264_HW,
            Capability.DISPLAY,
            Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
        )
        val profile = ProfileUpdate(ClientId("peer"), "Peer", "android", capabilities, 42L)
        val card = ClientCard(
            clientId = profile.clientId,
            identityPublicKey = ByteArray(91),
            displayName = profile.displayName,
            platform = profile.platform,
            capabilities = capabilities,
            createdAt = profile.updatedAt,
        )

        val oldProfile = ProtocolCodec.decodeFromCbor<PreScreenProfile>(ProtocolCodec.encodeToCbor(profile))
        val oldCard = ProtocolCodec.decodeFromCbor<PreScreenClientCard>(ProtocolCodec.encodeToCbor(card))
        val oldNested = ProtocolCodec.decodeFromCbor<PreScreenProfileDataSync>(
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE, profile = profile)),
        )

        val expected = listOf(Capability.CAPTURE, Capability.DISPLAY)
        assertEquals(expected, oldProfile.capabilities)
        assertEquals(expected, oldCard.capabilities)
        assertEquals(expected, oldNested.profile?.capabilities)
        assertEquals(DataSyncKind.PROFILE, oldNested.kind)
    }

    @Test
    fun everyBinaryDtoUsesDenseUniqueNumericLabels() {
        binaryDescriptors().forEach(::assertDenseLabels)
    }

    private fun assertDenseLabels(descriptor: SerialDescriptor) {
        val labels = (0 until descriptor.elementsCount).map { index ->
            descriptor.getElementAnnotations(index).filterIsInstance<CborLabel>().singleOrNull()?.label
        }
        assertEquals(
            "${descriptor.serialName} labels must be permanent 0..n ids",
            (0 until descriptor.elementsCount).map(Int::toLong),
            labels,
        )
    }

    private fun binaryDescriptors(): List<SerialDescriptor> = listOf(
        SignedBlob.serializer().descriptor,
        ClientKeyEpoch.serializer().descriptor,
        ClientCard.serializer().descriptor,
        RouteCapabilities.serializer().descriptor,
        RouteClaim.serializer().descriptor,
        PerRecipientKey.serializer().descriptor,
        Envelope.serializer().descriptor,
        EnvelopeAuth.serializer().descriptor,
        PrivateAssetRef.serializer().descriptor,
        AssetAad.serializer().descriptor,
        NotificationAction.serializer().descriptor,
        ConversationMessage.serializer().descriptor,
        MediaCustomAction.serializer().descriptor,
        NotificationProgress.serializer().descriptor,
        NotificationLiveUpdate.serializer().descriptor,
        CapturedNotification.serializer().descriptor,
        DismissEvent.serializer().descriptor,
        ActionEvent.serializer().descriptor,
        AssetSync.serializer().descriptor,
        AssetSyncItem.serializer().descriptor,
        NotificationFilterRule.serializer().descriptor,
        FilterSync.serializer().descriptor,
        ScreenMirrorConnectionCandidate.serializer().descriptor,
        ScreenMirrorSync.serializer().descriptor,
        DataSync.serializer().descriptor,
        TrustTableEntry.serializer().descriptor,
        TrustTable.serializer().descriptor,
        CardDelivery.serializer().descriptor,
        ProfileUpdate.serializer().descriptor,
        RunTerminalSnapshot.serializer().descriptor,
        RunProgress.serializer().descriptor,
        RunLlmSummary.serializer().descriptor,
        RunState.serializer().descriptor,
        RunControl.serializer().descriptor,
        RunControlResult.serializer().descriptor,
        RunEnvironmentChange.serializer().descriptor,
        RunCommandRequest.serializer().descriptor,
        RunSync.serializer().descriptor,
        SendRequest.serializer().descriptor,
    )

    @Test
    fun compactEncodingDoesNotCarryFieldNameStrings() {
        val encoded = ProtocolCodec.encodeToCbor(simpleNotif())
        listOf("sourceClientId", "sourceKey", "packageName", "appLabel", "postTime", "channelName")
            .forEach { field -> assertFalse("CBOR must not contain field name $field", encoded.containsUtf8(field)) }
    }

    @Test
    fun compactProfileAndCardMeetSizeReductionTargets() {
        val capabilities = Capability.entries
        val profile = ProfileUpdate(ClientId("client-aaa"), "Pixel 10 Pro XL", "android", capabilities, 1_750_000_000_000L)
        val legacyProfile = LegacyProfile(
            profile.clientId,
            profile.displayName,
            profile.platform,
            profile.capabilities,
            profile.updatedAt,
        )
        val card = ClientCard(
            clientId = profile.clientId,
            identityPublicKey = ByteArray(91) { it.toByte() },
            displayName = profile.displayName,
            platform = profile.platform,
            capabilities = capabilities,
            createdAt = profile.updatedAt,
        )
        val legacyCard = LegacyClientCard(
            card.suite,
            card.clientId,
            card.identityPublicKey,
            card.displayName,
            card.platform,
            card.capabilities,
            card.createdAt,
        )

        assertAtLeastPercentSmaller(
            "profile",
            ProtocolCodec.encodeToCbor(profile).size,
            namedKeys.encodeToByteArray(legacyProfile).size,
            40,
        )
        assertAtLeastPercentSmaller(
            "client card",
            ProtocolCodec.encodeToCbor(card).size,
            namedKeys.encodeToByteArray(legacyCard).size,
            40,
        )
    }

    @Test
    fun compactNotificationMeetsSizeReductionTarget() {
        val notification = simpleNotif()
        assertAtLeastPercentSmaller(
            "notification",
            ProtocolCodec.encodeToCbor(notification).size,
            namedKeys.encodeToByteArray(notification).size,
            25,
        )
    }

    private fun assertAtLeastPercentSmaller(name: String, compact: Int, named: Int, percent: Int) {
        assertTrue(
            "$name compact=$compact B, named=$named B must save at least $percent%",
            compact * 100 <= named * (100 - percent),
        )
    }

    @Test
    fun mandatoryDiscriminatorsRemainPresentDespiteDefaultOmission() {
        val envelope = Envelope(
            typ = MessageType.NOTIFICATION,
            signerId = ClientId("a"),
            messageId = "m",
            seq = 0L,
            createdAt = 0L,
            bodyCiphertext = ByteArray(4),
            recipients = emptyList(),
        )
        val header = ProtocolCodec.decodeFromCbor<EnvelopeHeader>(ProtocolCodec.encodeToCbor(envelope))
        assertEquals(1, header.v)
        assertEquals(CipherSuite.CURRENT_ID, header.suite)

        val signed = SignedBlob(SignedType.KEY_EPOCH, signerId = ClientId("a"), payload = byteArrayOf(1), sig = byteArrayOf(2))
        assertEquals(CipherSuite.CURRENT_ID, ProtocolCodec.decodeFromCbor<SuiteAtOne>(ProtocolCodec.encodeToCbor(signed)).suite)

        val card = ClientCard(
            clientId = ClientId("a"),
            identityPublicKey = byteArrayOf(1),
            displayName = "d",
            platform = "android",
            capabilities = emptyList(),
            createdAt = 0L,
        )
        assertEquals(CipherSuite.CURRENT_ID, ProtocolCodec.decodeFromCbor<SuiteAtZero>(ProtocolCodec.encodeToCbor(card)).suite)

        val epoch = ClientKeyEpoch(
            clientId = ClientId("a"),
            identityPublicKey = byteArrayOf(1),
            epoch = 1,
            operationalSigningKey = byteArrayOf(1),
            hpkePublicKey = byteArrayOf(1),
            purposes = emptyList(),
            notBefore = 0L,
            notAfter = 1L,
            minEpoch = 1,
        )
        assertEquals(CipherSuite.CURRENT_ID, ProtocolCodec.decodeFromCbor<SuiteAtZero>(ProtocolCodec.encodeToCbor(epoch)).suite)
        assertEquals(CipherSuite.CURRENT_ID, ProtocolCodec.decodeFromCbor<SuiteAtSeven>(ProtocolCodec.encodeToCbor(asset)).suite)
    }

    @Test
    fun cryptoInputsAreDeterministicUnderCompactEncoding() {
        val auth = EnvelopeAuth(
            v = 1,
            suite = "NS2",
            typ = MessageType.NOTIFICATION,
            signerId = ClientId("a"),
            signerEpoch = 1,
            messageId = "m",
            seq = 1L,
            createdAt = 1L,
            bodyCiphertextSha256 = ByteArray(32) { it.toByte() },
            recipientIds = listOf(ClientId("b")),
            recipientEpochs = listOf(1),
        )
        val aad = AssetAad("NS2", ClientId("a"), "id", "image/png", 99, AssetRole.AVATAR)

        val authBytes = ProtocolCodec.encodeToCbor(auth)
        val aadBytes = ProtocolCodec.encodeToCbor(aad)
        assertArrayEquals(authBytes, ProtocolCodec.encodeToCbor(auth))
        assertArrayEquals(aadBytes, ProtocolCodec.encodeToCbor(aad))
        assertEquals("qwABAWNOUzICbE5PVElGSUNBVElPTgNhYQQBBWFtBgEHAQhYIAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fCYFhYgqBAQ==", authBytes.b64())
        assertEquals("pgBjTlMyAWFhAmJpZANpaW1hZ2UvcG5nBBhjBWZBVkFUQVI=", aadBytes.b64())
    }

    @Test
    fun signedBlobWrapperPreservesOpaquePayloadAndSignatureBytes() {
        val blob = SignedBlob(
            SignedType.CLIENT_CARD,
            signerId = ClientId("a"),
            payload = ByteArray(40) { it.toByte() },
            sig = ByteArray(16) { (it + 1).toByte() },
        )
        val decoded = ProtocolCodec.decodeFromCbor<SignedBlob>(ProtocolCodec.encodeToCbor(blob))
        assertArrayEquals(blob.payload, decoded.payload)
        assertArrayEquals(blob.sig, decoded.sig)
        assertEquals(blob.suite, decoded.suite)
    }

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
}
