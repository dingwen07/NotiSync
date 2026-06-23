package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.CipherSuite
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyEpochTest {

    private fun keyEpochBlob(
        identity: SoftwareIdentitySigner,
        epoch: Int,
        opSpki: ByteArray,
        hpke: ByteArray,
        signWith: SoftwareIdentitySigner = identity,
        stripIdentity: Boolean = false,
    ): SignedBlob {
        val ke = ClientKeyEpoch(
            suite = CipherSuite.NS2.id,
            clientId = identity.clientId,
            identityPublicKey = if (stripIdentity) ByteArray(0) else identity.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = opSpki,
            hpkePublicKeyset = hpke,
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = Long.MAX_VALUE,
            minEpoch = 1,
        )
        val payload = ProtocolCodec.encodeToCbor(ke)
        return SignedBlob(SignedType.KEY_EPOCH, signerId = identity.clientId, payload = payload, sig = signWith.sign(payload))
    }

    @Test
    fun selfContainedKeyEpochVerifiesAndBindsClientId() {
        val identity = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = 1)
        val hpke = Hpke.generateKeyPair()
        val blob = keyEpochBlob(identity, 1, op.operationalPublicKeySpki, hpke.publicKeyset)

        val ke = KeyEpochs.verify(blob)
        assertNotNull(ke)
        assertEquals(identity.clientId, ke!!.clientId)
        assertArrayEquals(op.operationalPublicKeySpki, ke.operationalSigningKey)

        // A matching pin is accepted; a different pinned identity is rejected (no key swap on a known peer).
        assertNotNull(KeyEpochs.verify(blob, identity.publicKeySpki))
        assertNull(KeyEpochs.verify(blob, SoftwareIdentitySigner.generate().publicKeySpki))
    }

    @Test
    fun keyEpochRejectedWhenTamperedOrWrongTypeOrZeroEpoch() {
        val identity = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = 1)
        val hpke = Hpke.generateKeyPair()
        val blob = keyEpochBlob(identity, 1, op.operationalPublicKeySpki, hpke.publicKeyset)

        // Tampered payload no longer matches the identity signature.
        val badPayload = blob.payload.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(KeyEpochs.verify(blob.copy(payload = badPayload)))

        // Wrong discriminator.
        assertNull(KeyEpochs.verify(blob.copy(typ = SignedType.CLIENT_CARD)))

        // Epoch 0 is reserved for the identity key and is never a real key-epoch.
        assertNull(KeyEpochs.verify(keyEpochBlob(identity, 0, op.operationalPublicKeySpki, hpke.publicKeyset)))

        // Signed by a different identity than the one it carries → not self-consistent.
        val imposter = SoftwareIdentitySigner.generate()
        assertNull(KeyEpochs.verify(keyEpochBlob(identity, 1, op.operationalPublicKeySpki, hpke.publicKeyset, signWith = imposter)))
    }

    @Test
    fun strippedKeyEpoch_verifiesOnlyAgainstThePinnedIdentity() {
        // A pairing-QR key-epoch omits its identity anchor (the accompanying card supplies it).
        val identity = SoftwareIdentitySigner.generate()
        val op = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = 1)
        val hpke = Hpke.generateKeyPair()
        val stripped = keyEpochBlob(identity, 1, op.operationalPublicKeySpki, hpke.publicKeyset, stripIdentity = true)

        // No identity carried AND none pinned → nothing to verify against → reject (not self-contained).
        assertNull(KeyEpochs.verify(stripped))
        // Pinned to the correct identity (from the card) → verifies, and binds the carried clientId to it.
        val ke = KeyEpochs.verify(stripped, identity.publicKeySpki)
        assertNotNull(ke)
        assertEquals(identity.clientId, ke!!.clientId)
        assertArrayEquals(op.operationalPublicKeySpki, ke.operationalSigningKey)
        // Pinned to the WRONG identity → reject (the signature won't verify under it).
        assertNull(KeyEpochs.verify(stripped, SoftwareIdentitySigner.generate().publicKeySpki))
    }
}
