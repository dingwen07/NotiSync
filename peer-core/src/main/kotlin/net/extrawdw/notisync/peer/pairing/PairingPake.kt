package net.extrawdw.notisync.peer.pairing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.extrawdw.notisync.protocol.BrokerPairing
import net.extrawdw.notisync.protocol.PairingEncryptedCard
import net.extrawdw.notisync.protocol.PairingPakeFrame
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

enum class PairingRole { HOST, CLIENT;
    val peer: PairingRole get() = if (this == HOST) CLIENT else HOST
}

/**
 * Single-use RFC 8236 J-PAKE using BC's NIST-3072 group and explicit round-3 key confirmation.
 * The QR secret is never sent to the broker, hashed into a public verifier, or used directly as an
 * encryption key. Version, broker, session, host identity and fixed roles enter the ZK proofs via participant IDs.
 * HKDF-SHA256 additionally binds directional AES-256-GCM keys to every byte of the PAKE transcript.
 */
class PairingPake(link: BrokerPairingLink, private val role: PairingRole) : AutoCloseable {
    private val random = SecureRandom()
    private val context = "notisync-pair-v1|${link.brokerUrl}|${link.sessionId}|${link.hostId}"
    private fun id(forRole: PairingRole) = "$context|${forRole.name.lowercase(Locale.ROOT)}"
    private var participant: JPAKEParticipant? = link.secret.toCharArray().let { password ->
        try {
            JPAKEParticipant(id(role), password, JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), random)
        } finally { password.fill('\u0000') }
    }
    private var phase = 0
    private var material: BigInteger? = null
    private val transcript = ByteArrayOutputStream()
    private var previous = byteArrayOf()
    private var transcriptHash = byteArrayOf()
    private var sendKey = byteArrayOf()
    private var receiveKey = byteArrayOf()
    private var sentCard = false
    private var receivedCard = false

    fun round1(): ByteArray = step(0) {
        val p = participant!!.createRound1PayloadToSend()
        encode(1, listOf(p.gx1, p.gx2) + p.knowledgeProofForX1 + p.knowledgeProofForX2)
            .also { previous = it }
    }

    fun round2(received: ByteArray): ByteArray = step(1) {
        val v = decode(received, 1, 6)
        participant!!.validateRound1PayloadReceived(JPAKERound1Payload(id(role.peer), v[0], v[1],
            arrayOf(v[2], v[3]), arrayOf(v[4], v[5])))
        record(previous, received)
        val p = participant!!.createRound2PayloadToSend()
        encode(2, listOf(p.a) + p.knowledgeProofForX2s).also { previous = it }
    }

    fun round3(received: ByteArray): ByteArray = step(2) {
        val v = decode(received, 2, 3)
        participant!!.validateRound2PayloadReceived(JPAKERound2Payload(id(role.peer), v[0], arrayOf(v[1], v[2])))
        record(previous, received)
        material = participant!!.calculateKeyingMaterial()
        val p = participant!!.createRound3PayloadToSend(material!!)
        encode(3, listOf(p.macTag)).also { previous = it }
    }

    fun confirm(received: ByteArray): Unit = step(3) {
        val v = decode(received, 3, 1)
        participant!!.validateRound3PayloadReceived(JPAKERound3Payload(id(role.peer), v[0]), material!!)
        record(previous, received)
        transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray())
        val ikm = material!!.toByteArray()
        try {
            sendKey = derive(ikm, "${id(role)}|CARD")
            receiveKey = derive(ikm, "${id(role.peer)}|CARD")
        } finally {
            ikm.fill(0)
            material = null
            participant = null
            previous = byteArrayOf()
            transcript.reset()
        }
    }

    fun encryptCard(payload: String): ByteArray {
        check(phase == 4 && !sentCard) { "Pairing key is not confirmed or CARD already sent" }
        sentCard = true
        val plain = payload.toByteArray(Charsets.UTF_8)
        require(plain.size in 1..BrokerPairing.MAX_CARD_BYTES)
        val nonce = ByteArray(12).also(random::nextBytes)
        return ProtocolCodec.encodeToCbor(PairingEncryptedCard(nonce, cipher(Cipher.ENCRYPT_MODE, sendKey, nonce, role).doFinal(plain)))
    }

    fun decryptCard(bytes: ByteArray): String {
        check(phase == 4 && !receivedCard) { "Pairing key is not confirmed or CARD already received" }
        receivedCard = true
        require(bytes.size <= BrokerPairing.MAX_FRAME_BYTES)
        val card = ProtocolCodec.decodeFromCbor<PairingEncryptedCard>(bytes)
        require(card.nonce.size == 12 && card.ciphertext.size in 17..(BrokerPairing.MAX_CARD_BYTES + 16))
        return cipher(Cipher.DECRYPT_MODE, receiveKey, card.nonce, role.peer).doFinal(card.ciphertext).toString(Charsets.UTF_8)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, sender: PairingRole): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(id(sender).toByteArray(Charsets.UTF_8) + transcriptHash)
        }

    private fun derive(ikm: ByteArray, info: String): ByteArray = ByteArray(32).also { key ->
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, transcriptHash, info.toByteArray(Charsets.UTF_8)))
            generateBytes(key, 0, key.size)
        }
    }

    private fun encode(round: Int, values: List<BigInteger>) =
        ProtocolCodec.encodeToCbor(PairingPakeFrame(round, id(role), values.map { it.toByteArray() }))

    private fun decode(bytes: ByteArray, round: Int, count: Int): List<BigInteger> {
        require(bytes.size <= BrokerPairing.MAX_FRAME_BYTES)
        val frame = ProtocolCodec.decodeFromCbor<PairingPakeFrame>(bytes)
        require(frame.round == round && frame.participantId == id(role.peer) && frame.values.size == count) {
            "Invalid pairing transcript"
        }
        return frame.values.map {
            require(it.size in 1..385) // 3072 bits plus sign; bounds modular-exponentiation input.
            val value = BigInteger(it)
            require(value.toByteArray().contentEquals(it))
            if (round != 3) require(value.signum() >= 0 && value < JPAKEPrimeOrderGroups.NIST_3072.p)
            value
        }
    }

    private fun record(local: ByteArray, remote: ByteArray) {
        val ordered = if (role == PairingRole.HOST) listOf(local, remote) else listOf(remote, local)
        val stream = DataOutputStream(transcript)
        ordered.forEach { stream.writeInt(it.size); stream.write(it) }
    }

    private inline fun <T> step(expected: Int, block: () -> T): T {
        check(phase == expected) { "Pairing session already used" }
        phase = -1 // Any validation failure permanently burns this attempt.
        return try { block().also { phase = expected + 1 } } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        phase = -1
        participant = null
        material = null
        sendKey.fill(0)
        receiveKey.fill(0)
        transcript.reset()
    }
}
