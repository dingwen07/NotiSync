package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeCryptoTest {

    private class Peer {
        val identity = SoftwareIdentitySigner.generate()
        val hpke = Hpke.generateKeyPair()
        fun recipientKey() = RecipientKey(identity.clientId, hpke.publicKeyset)
    }

    private fun sampleBody(source: ClientId) = ProtocolCodec.encodeToCbor(
        CapturedNotification(
            sourceClientId = source,
            sourceKey = "0|com.example.chat|7|null",
            packageName = "com.example.chat",
            appLabel = "Chat",
            title = "Alice",
            text = "Dinner at 7?",
            style = NotifStyle.MESSAGING,
            category = MirrorCategory.MESSAGE,
            importance = MirrorImportance.HIGH,
            postTime = 1_750_000_000_000L,
        )
    )

    @Test
    fun clientIdDerivationIsDeterministic() {
        val s = SoftwareIdentitySigner.generate()
        assertEquals(ClientIds.derive(s.publicKeySpki), ClientIds.derive(s.publicKeySpki))
        assertEquals(s.clientId, ClientIds.derive(s.publicKeySpki))
        // 20 bytes -> 32 base32 chars.
        assertEquals(32, s.clientId.value.length)
    }

    @Test
    fun sealThenOpenRoundTripsForEveryRecipient() {
        val sender = SoftwareIdentitySigner.generate()
        val a = Peer()
        val b = Peer()
        val body = sampleBody(sender.clientId)

        val env = EnvelopeCrypto.seal(
            signer = sender,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = body,
            recipients = listOf(a.recipientKey(), b.recipientKey()),
            messageId = "01J0MSG0001",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
        )

        // The broker sees opaque ciphertext only.
        assertNotEquals("body must be encrypted", body.toList(), env.bodyCiphertext.toList())

        val openedByA = EnvelopeCrypto.open(env, a.identity.clientId, a.hpke.privateKeyset)
        val openedByB = EnvelopeCrypto.open(env, b.identity.clientId, b.hpke.privateKeyset)
        assertArrayEquals(body, openedByA)
        assertArrayEquals(body, openedByB)

        val decoded = ProtocolCodec.decodeFromCbor<CapturedNotification>(openedByA)
        assertEquals("Dinner at 7?", decoded.text)
        assertEquals(NotifStyle.MESSAGING, decoded.style)
    }

    @Test
    fun signatureVerifiesForSenderAndFailsForOthers() {
        val sender = SoftwareIdentitySigner.generate()
        val a = Peer()
        val env = EnvelopeCrypto.seal(
            sender, MessageType.NOTIFICATION, sampleBody(sender.clientId),
            listOf(a.recipientKey()), "01J0MSG0002", 2L, 1_750_000_000_000L,
        )
        assertTrue(EnvelopeCrypto.verify(env, sender.publicKeySpki))
        assertFalse("must reject a different signer's key", EnvelopeCrypto.verify(env, a.identity.publicKeySpki))
    }

    @Test
    fun tamperedCiphertextFailsSignatureAndDecryption() {
        val sender = SoftwareIdentitySigner.generate()
        val a = Peer()
        val env = EnvelopeCrypto.seal(
            sender, MessageType.NOTIFICATION, sampleBody(sender.clientId),
            listOf(a.recipientKey()), "01J0MSG0003", 3L, 1_750_000_000_000L,
        )
        val tamperedBody = env.bodyCiphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val tampered = env.copy(bodyCiphertext = tamperedBody)

        // authBytes hashes the ciphertext, so the source signature no longer matches.
        assertFalse(EnvelopeCrypto.verify(tampered, sender.publicKeySpki))
        // And GCM authentication fails on open.
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(tampered, a.identity.clientId, a.hpke.privateKeyset)
        }
    }

    @Test
    fun wrongPrivateKeyCannotOpen() {
        val sender = SoftwareIdentitySigner.generate()
        val a = Peer()
        val b = Peer()
        val env = EnvelopeCrypto.seal(
            sender, MessageType.NOTIFICATION, sampleBody(sender.clientId),
            listOf(a.recipientKey()), "01J0MSG0004", 4L, 1_750_000_000_000L,
        )
        // b is not a recipient at all.
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCrypto.open(env, b.identity.clientId, b.hpke.privateKeyset)
        }
        // Even claiming to be a, b's HPKE private key cannot unseal the DEK.
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(env, a.identity.clientId, b.hpke.privateKeyset)
        }
    }
}
