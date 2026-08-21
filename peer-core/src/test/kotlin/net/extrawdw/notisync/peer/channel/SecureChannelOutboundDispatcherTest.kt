package net.extrawdw.notisync.peer.channel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelOutboundDispatcherTest {
    @Test
    fun outboundSigningAndTransportNeverInheritTheCallerThread() = runBlocking {
        val callerThread = Thread.currentThread().name
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, WORKER_THREAD_NAME)
        }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val identity = SoftwareIdentitySigner.generate()
            val signingThreads = CopyOnWriteArrayList<String>()
            val operational = RecordingOperationalSigner(
                SoftwareOperationalSigner.generate(identity.clientId, 1),
                signingThreads,
            )
            val transport = RecordingTransport()
            val recipient = RecipientKey(ClientId("recipient"), Hpke.generateKeyPair().publicKeyset)
            val channel = SecureChannel(
                signer = identity,
                operationalSigner = { operational },
                myHpkePrivate = { null },
                transport = transport,
                directory = FixedDirectory(recipient),
                log = ChannelLogger { },
                now = { 1_750_000_000_000L },
                outboundDispatcher = dispatcher,
            )

            channel.send(
                MessageType.NOTIFICATION,
                byteArrayOf(1),
                Recipients.OwnMesh,
                Urgency.HIGH,
            )
            channel.sendAllStrict(
                MessageType.NOTIFICATION,
                listOf(OutboundItem("stable-id", byteArrayOf(2))),
                Recipients.OwnMesh,
                Urgency.HIGH,
            ) { }

            assertEquals(2, signingThreads.size)
            assertTrue(signingThreads.all { it.startsWith(WORKER_THREAD_NAME) })
            assertEquals(2, transport.sendThreads.size)
            assertTrue(transport.sendThreads.all { it.startsWith(WORKER_THREAD_NAME) })
            assertFalse(callerThread.startsWith(WORKER_THREAD_NAME))
        }
    }

    private class RecordingOperationalSigner(
        private val delegate: OperationalSigner,
        private val threads: MutableList<String>,
    ) : OperationalSigner {
        override val operationalPublicKeySpki = delegate.operationalPublicKeySpki
        override val clientId = delegate.clientId
        override val signerEpoch = delegate.signerEpoch

        override fun sign(data: ByteArray): ByteArray {
            threads += Thread.currentThread().name
            return delegate.sign(data)
        }
    }

    private class FixedDirectory(private val recipient: RecipientKey) : PeerDirectory {
        override fun resolveSender(id: ClientId, signerEpoch: Int): SenderKey? = null
        override fun recipients(scope: Recipients): List<RecipientKey> = listOf(recipient)
    }

    private class RecordingTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val sendThreads = CopyOnWriteArrayList<String>()

        override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
            sendThreads += Thread.currentThread().name
            return SendResult(accepted = true)
        }
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) = Unit
        override suspend fun uploadPrivateAsset(
            sourceClientId: ClientId,
            assetId: String,
            ciphertext: ByteArray,
        ) = false
        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? = null
    }

    private companion object {
        const val WORKER_THREAD_NAME = "keystore-outbound-worker"
    }
}
