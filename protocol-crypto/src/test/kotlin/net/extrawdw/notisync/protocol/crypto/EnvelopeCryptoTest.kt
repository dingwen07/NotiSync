package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.CipherSuite
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
    fun sealsToARawRecipientKeyAndOpensWithTheTinkPrivateKeyset() {
        val sender = SoftwareIdentitySigner.generate()
        val a = Peer()
        // After §B.2 the device publishes the raw 32-byte key in ClientKeyEpoch.hpkePublicKeyset; a sender
        // builds the RecipientKey from those bytes, Hpke.seal dispatches to the raw path, and the recipient
        // still opens with its unchanged Tink private keyset.
        val rawRecipient = RecipientKey(a.identity.clientId, Hpke.rawPublicKey(a.hpke.publicKeyset))
        val body = sampleBody(sender.clientId)
        val env = EnvelopeCrypto.seal(
            sender, MessageType.NOTIFICATION, body, listOf(rawRecipient), "01J0RAW001", 1L, 1_750_000_000_000L,
        )
        assertArrayEquals(body, EnvelopeCrypto.open(env, a.identity.clientId, a.hpke.privateKeyset))
    }

    @Test
    fun sealSkipsAnUnsealableRecipientAndDeliversToTheRest() {
        val sender = SoftwareIdentitySigner.generate()
        val good = Peer()
        // 3 bytes: not a 32-byte raw key and not a Tink keyset, so Hpke.seal throws for this recipient. A single
        // unsealable peer (an old/corrupt/future-format key) must not abort the fan-out to the healthy peers.
        val bad = RecipientKey(ClientId("badpeerbadpeerbadpeerbadpeerbadp"), byteArrayOf(1, 2, 3))
        val body = sampleBody(sender.clientId)

        val env = EnvelopeCrypto.seal(
            sender, MessageType.NOTIFICATION, body,
            listOf(bad, good.recipientKey()), "01J0SKIP001", 1L, 1_750_000_000_000L,
        )

        // The bad recipient was dropped; the good one survives, and the signature covers only the survivors.
        assertEquals(1, env.recipients.size)
        assertEquals(good.identity.clientId, env.recipients.single().recipientId)
        assertTrue(EnvelopeCrypto.verify(env, sender.publicKeySpki))
        assertArrayEquals(body, EnvelopeCrypto.open(env, good.identity.clientId, good.hpke.privateKeyset))
    }

    @Test
    fun sealThrowsOnlyWhenEveryRecipientIsUnsealable() {
        val sender = SoftwareIdentitySigner.generate()
        val bad1 = RecipientKey(ClientId("bad1bad1bad1bad1bad1bad1bad1bad1"), byteArrayOf(1, 2, 3))
        val bad2 = RecipientKey(ClientId("bad2bad2bad2bad2bad2bad2bad2bad2"), byteArrayOf(4, 5, 6, 7))
        assertThrows(IllegalArgumentException::class.java) {
            EnvelopeCrypto.seal(
                sender, MessageType.NOTIFICATION, sampleBody(sender.clientId),
                listOf(bad1, bad2), "01J0SKIP002", 2L, 1_750_000_000_000L,
            )
        }
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
    fun operationalSignedEnvelopeVerifiesAgainstOperationalKeyNotIdentity() {
        val identity = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = 1)
        val a = Peer()
        val env = EnvelopeCrypto.seal(
            signer = op,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = sampleBody(identity.clientId),
            recipients = listOf(RecipientKey(a.identity.clientId, a.hpke.publicKeyset, recipientEpoch = 1)),
            messageId = "01J0OP0001",
            seq = 1L,
            createdAt = 1_750_000_000_000L,
            suite = CipherSuite.NS2.id,
        )
        assertEquals(1, env.signerEpoch)
        // The clientId stays the identity fingerprint even though the operational key signed.
        assertEquals(identity.clientId, env.signerId)
        // Verifies against the operational key the key-epoch published...
        assertTrue(EnvelopeCrypto.verify(env, op.operationalPublicKeySpki))
        // ...and NOT against the identity key (which did not sign this envelope).
        assertFalse(EnvelopeCrypto.verify(env, identity.publicKeySpki))
        // Round-trips for the recipient's epoch-1 HPKE keyset.
        assertArrayEquals(sampleBody(identity.clientId), EnvelopeCrypto.open(env, a.identity.clientId, a.hpke.privateKeyset))
    }

    @Test
    fun crossEpochSealedDekCannotBeOpenedUnderAnotherEpoch() {
        val identity = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = 1)
        val a = Peer()
        val env = EnvelopeCrypto.seal(
            signer = op,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = sampleBody(identity.clientId),
            recipients = listOf(RecipientKey(a.identity.clientId, a.hpke.publicKeyset, recipientEpoch = 1)),
            messageId = "01J0OP0002",
            seq = 2L,
            createdAt = 1_750_000_000_000L,
            suite = CipherSuite.NS2.id,
        )
        // A copy claiming the DEK was sealed under epoch 2: the HPKE context no longer matches, so the
        // AEAD open fails — a (claimed, sealed) epoch mismatch cannot be passed off.
        val forged = env.copy(recipients = env.recipients.map { it.copy(recipientEpoch = 2) })
        assertThrows(Exception::class.java) {
            EnvelopeCrypto.open(forged, a.identity.clientId, a.hpke.privateKeyset)
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
