package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class SecureChannelPolicyTest {
    @Test
    fun highScreenRequestRequiresBodyBoundExactCapableSource() = runBlocking {
        val requester = ClientId("a".repeat(52))
        val source = ClientId("b".repeat(52))
        val request = screenRequest(requester, source)
        val encoded = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = request),
        )
        val validScope = Recipients.OnlyCapable(source, request.requiredSourceCapabilities())

        assertEquals(
            0,
            channel().send(MessageType.DATA_SYNC, encoded, validScope, Urgency.HIGH),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    encoded,
                    validScope.copy(
                        requiredCapabilities = validScope.requiredCapabilities -
                            Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
                    ),
                    Urgency.HIGH,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                channel().send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(
                            DataSyncKind.SCREEN_MIRRORING,
                            screenMirror = request.copy(action = ScreenMirrorAction.STATUS),
                        ),
                    ),
                    validScope,
                    Urgency.HIGH,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                val forged = request.copy(requesterPeerId = ClientId("c".repeat(52)))
                channel().send(
                    MessageType.DATA_SYNC,
                    ProtocolCodec.encodeToCbor(
                        DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = forged),
                    ),
                    Recipients.OnlyCapable(source, forged.requiredSourceCapabilities()),
                    Urgency.HIGH,
                )
            }
        }
        Unit
    }

    @Test
    fun highDataSyncRequiresFilteringDisplayWakeAudience() = runBlocking {
        val channel = channel()

        try {
            channel.send(MessageType.DATA_SYNC, byteArrayOf(1), Recipients.OwnMesh, Urgency.HIGH)
            fail("HIGH DATA_SYNC without the capability-filtered audience must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        try {
            channel.send(
                MessageType.DATA_SYNC,
                byteArrayOf(1),
                Recipients.OwnMeshFiltered(
                    requiredCapabilities = setOf(
                        Capability.DISPLAY,
                        Capability.BACKGROUND_WAKE,
                        Capability.PUSH_FILTERING,
                    ),
                ),
                Urgency.HIGH,
            )
            fail("HIGH DATA_SYNC must not use the legacy capability-routing fallback")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val count = channel.send(
            MessageType.DATA_SYNC,
            byteArrayOf(1),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.BACKGROUND_WAKE,
                    Capability.PUSH_FILTERING,
                ),
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.HIGH,
        )
        assertEquals(0, count)

        try {
            channel.sendAllStrict(
                MessageType.DATA_SYNC,
                listOf(OutboundItem("high-data-sync", byteArrayOf(1))),
                Recipients.OwnMesh,
                Urgency.HIGH,
            ) { }
            fail("strict HIGH DATA_SYNC must enforce the same audience policy")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val emptyStrictBatch = channel.sendAllStrict(
            MessageType.DATA_SYNC,
            emptyList(),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = setOf(
                    Capability.DISPLAY,
                    Capability.BACKGROUND_WAKE,
                    Capability.PUSH_FILTERING,
                ),
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.HIGH,
        ) { }
        assertEquals(0, emptyStrictBatch)
    }

    private fun channel(): SecureChannel {
        val id = ClientId("a".repeat(52))
        val identity = object : IdentitySigner {
            override val publicKeySpki = byteArrayOf(1)
            override val clientId = id
            override fun sign(data: ByteArray) = byteArrayOf(1)
        }
        val operational = object : OperationalSigner {
            override val operationalPublicKeySpki = byteArrayOf(1)
            override val clientId = id
            override val signerEpoch = 1
            override fun sign(data: ByteArray) = byteArrayOf(1)
        }
        val directory = object : PeerDirectory {
            override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
            override fun recipients(scope: Recipients): List<RecipientKey> = emptyList()
        }
        return SecureChannel(
            signer = identity,
            operationalSigner = { operational },
            myHpkePrivate = { null },
            transport = NoopTransport,
            directory = directory,
            log = ChannelLogger { },
        )
    }

    private fun screenRequest(requester: ClientId, source: ClientId) = ScreenMirrorSync(
        action = ScreenMirrorAction.REQUEST,
        sessionId = "session",
        requesterPeerId = requester,
        sourcePeerId = source,
        issuedAt = 1_000,
        expiresAt = 301_000,
        routingToken = ByteArray(16),
        masterPsk = ByteArray(32),
        codec = ScreenMirrorCodec.H264,
        requestControl = true,
        requestClipboard = true,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
        candidates = listOf(
            ScreenMirrorConnectionCandidate(
                ScreenMirrorConnectionCandidate.LAN_TCP,
                host = "192.0.2.10",
                port = 27_171,
            ),
        ),
    )

    private object NoopTransport : Transport {
        override val type = TransportType.WEBSOCKET
        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency) = SendResult(accepted = true)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }
}
