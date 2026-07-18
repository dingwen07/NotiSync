package net.extrawdw.notisync.peer.channel

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SecureChannelPolicyTest {
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
