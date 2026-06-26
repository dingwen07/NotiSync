package net.extrawdw.notisync.server

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Shared ES256 (ECDSA P-256 + SHA-256) JWT primitives: signing, the DER<->JOSE signature conversions, and
 * PEM/PKCS#8 EC private-key loading. Centralized so the broker's own bearer-token issuer ([JwtIssuer]) and
 * the APNs provider-token signer use one vetted implementation instead of divergent hand-rolled copies.
 */
internal object Es256 {
    /** RFC 7515 base64url, no padding — the JWT/JWS segment encoding. */
    val b64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** Sign [data] with [privateKey] (SHA256withECDSA) and return the fixed 64-byte JOSE (R||S) signature. */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return derToJose(der)
    }

    /** Load a PEM-encoded PKCS#8 EC private key (our JWT key or an Apple `.p8` APNs key). */
    fun loadEcPrivateKeyPem(pem: String): PrivateKey {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val der = Base64.getMimeDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    fun derToJose(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++].toInt() == 0x30)
        val seqLen = derLength(der, offset).also { offset = it.next }.len
        require(seqLen == der.size - offset)
        require(der[offset++].toInt() == 0x02)
        val rLen = derLength(der, offset).also { offset = it.next }.len
        val r = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        require(der[offset++].toInt() == 0x02)
        val sLen = derLength(der, offset).also { offset = it.next }.len
        val s = der.copyOfRange(offset, offset + sLen)
        return r.toJosePart() + s.toJosePart()
    }

    fun joseToDer(jose: ByteArray): ByteArray {
        val r = jose.copyOfRange(0, 32).toDerInteger()
        val s = jose.copyOfRange(32, 64).toDerInteger()
        val body = byteArrayOf(0x02) + derLen(r.size) + r + byteArrayOf(0x02) + derLen(s.size) + s
        return byteArrayOf(0x30) + derLen(body.size) + body
    }

    private data class DerLen(val len: Int, val next: Int)

    private fun derLength(bytes: ByteArray, offset: Int): DerLen {
        val first = bytes[offset].toInt() and 0xff
        if (first < 0x80) return DerLen(first, offset + 1)
        val count = first and 0x7f
        var len = 0
        repeat(count) { len = (len shl 8) or (bytes[offset + 1 + it].toInt() and 0xff) }
        return DerLen(len, offset + 1 + count)
    }

    private fun derLen(len: Int): ByteArray =
        if (len < 0x80) byteArrayOf(len.toByte())
        else {
            val bytes = BigInteger.valueOf(len.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }

    private fun ByteArray.toJosePart(): ByteArray {
        val stripped = dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - stripped.size.coerceAtMost(32)) + stripped.takeLast(32).toByteArray()
    }

    private fun ByteArray.toDerInteger(): ByteArray {
        val nonZero = dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (nonZero.isEmpty()) byteArrayOf(0) else nonZero
        return if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    }
}
