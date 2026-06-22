package net.extrawdw.apps.notisync.channel

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.foundation.TrustPeerDirectory
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic substrate: dedup, sender-auth, signature verify, HPKE-open, and audience scoping. */
class SecureChannelTest {

    private fun channel(
        me: net.extrawdw.notisync.protocol.crypto.IdentitySigner,
        myHpkePrivate: ByteArray,
        trust: FakeTrustState,
        transport: CapturingTransport = CapturingTransport(),
        onBadSignature: (ClientId, Long, DeliveryMode) -> Unit = { _, _, _ -> },
    ) =
        SecureChannel(me, myHpkePrivate, transport, TrustPeerDirectory(trust), log = {}, onBadSignature = onBadSignature)

    @Test
    fun deliversDecryptedBody_withAuthenticatedSenderAndOwnFlag_toRegisteredHandler() {
        val me = newSigner(); val myHpke = newHpke()
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, ownDevice = true)) }
        val channel = channel(me, myHpke.privateKeyset, trust)

        val received = mutableListOf<InboundMessage>()
        channel.onMessage(MessageType.NOTIFICATION) { received.add(it) }
        channel.deliver(seal(sender, MessageType.NOTIFICATION, byteArrayOf(7, 8, 9), me.clientId, myHpke.publicKeyset, "n1"))

        assertEquals(1, received.size)
        assertEquals(sender.clientId, received.single().senderId)
        assertTrue(received.single().senderOwnDevice)
        assertArrayEquals(byteArrayOf(7, 8, 9), received.single().body)
    }

    @Test
    fun surfacesOtherDeviceFlag_butNeverDropsOnIt() {
        val me = newSigner(); val myHpke = newHpke()
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false)) }
        val channel = channel(me, myHpke.privateKeyset, trust)

        val received = mutableListOf<InboundMessage>()
        channel.onMessage(MessageType.NOTIFICATION) { received.add(it) }
        channel.deliver(seal(other, MessageType.NOTIFICATION, byteArrayOf(1), me.clientId, myHpke.publicKeyset, "n1"))

        // The channel itself never applies an ownDevice gate — it forwards with the flag; policy is a caller concern.
        assertEquals(1, received.size)
        assertEquals(false, received.single().senderOwnDevice)
    }

    @Test
    fun dropsDuplicateMessageId_acrossDeliveries() {
        val me = newSigner(); val myHpke = newHpke()
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val channel = channel(me, myHpke.privateKeyset, trust)

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        val env = seal(sender, MessageType.NOTIFICATION, byteArrayOf(1), me.clientId, myHpke.publicKeyset, "dup")
        channel.deliver(env)
        channel.deliver(env) // same messageId — FCM/WebSocket double delivery

        assertEquals("a duplicate messageId must be handled exactly once", 1, count)
    }

    @Test
    fun dropsUnknownSender() {
        val me = newSigner(); val myHpke = newHpke()
        val stranger = newSigner(); val strangerHpke = newHpke()
        val channel = channel(me, myHpke.privateKeyset, FakeTrustState()) // empty roster

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        channel.deliver(seal(stranger, MessageType.NOTIFICATION, byteArrayOf(1), me.clientId, myHpke.publicKeyset, "x"))

        assertEquals(0, count)
    }

    @Test
    fun dropsBadSignature_andNotifies() {
        val me = newSigner(); val myHpke = newHpke()
        val sender = newSigner(); val senderHpke = newHpke()
        val trust = FakeTrustState().apply { peers.value = listOf(peerOf(sender, senderHpke.publicKeyset)) }
        val rejected = mutableListOf<Pair<ClientId, DeliveryMode>>()
        val channel = channel(me, myHpke.privateKeyset, trust, onBadSignature = { id, _, deliveryMode -> rejected.add(id to deliveryMode) })

        var count = 0
        channel.onMessage(MessageType.NOTIFICATION) { count++ }
        val tampered = seal(sender, MessageType.NOTIFICATION, byteArrayOf(1), me.clientId, myHpke.publicKeyset, "t")
            .let { it.copy(sig = ByteArray(it.sig.size)) }
        channel.deliver(tampered, DeliveryMode.FCM_INLINE)

        assertEquals(0, count)
        assertEquals(listOf(sender.clientId to DeliveryMode.FCM_INLINE), rejected)
    }

    @Test
    fun send_sealsToOwnMesh_andReturnsRecipientCount() {
        val me = newSigner(); val myHpke = newHpke()
        val own1 = newSigner(); val own1Hpke = newHpke()
        val own2 = newSigner(); val own2Hpke = newHpke()
        val other = newSigner(); val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(own1, own1Hpke.publicKeyset, ownDevice = true),
                peerOf(own2, own2Hpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false),
            )
        }
        val transport = CapturingTransport()
        val channel = channel(me, myHpke.privateKeyset, trust, transport)

        val n = runBlocking { channel.send(MessageType.NOTIFICATION, byteArrayOf(1), Recipients.OwnMesh, Urgency.HIGH) }

        assertEquals(2, n)
        assertEquals(setOf(own1.clientId, own2.clientId), transport.envelopes.single().recipientIds().toSet())
    }

    @Test
    fun send_toEmptyAudience_returnsZero_andSendsNothing() {
        val me = newSigner(); val myHpke = newHpke()
        val transport = CapturingTransport()
        val channel = channel(me, myHpke.privateKeyset, FakeTrustState(), transport)

        val n = runBlocking { channel.send(MessageType.NOTIFICATION, byteArrayOf(1), Recipients.OwnMesh, Urgency.HIGH) }

        assertEquals(0, n)
        assertTrue(transport.sent.isEmpty())
    }
}
