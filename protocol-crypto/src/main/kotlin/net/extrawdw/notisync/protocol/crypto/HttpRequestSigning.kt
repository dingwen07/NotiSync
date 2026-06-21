package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.ClientId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Canonical HTTP request signing shared by the Android client and broker. The signature covers the
 * method, raw path/query, timestamp, nonce, and SHA-256 of the exact transmitted body bytes.
 */
object HttpRequestSigning {
    const val VERSION = "notisync-http-sign-v1"
    const val HEADER_CLIENT_ID = "X-NotiSync-Client-Id"
    const val HEADER_TIMESTAMP = "X-NotiSync-Timestamp"
    const val HEADER_NONCE = "X-NotiSync-Nonce"
    const val HEADER_CONTENT_SHA256 = "X-NotiSync-Content-SHA256"
    const val HEADER_SIGNATURE = "X-NotiSync-Signature"

    private val b64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()

    data class Headers(
        val clientId: ClientId,
        val timestampMillis: Long,
        val nonce: String,
        val contentSha256: String,
        val signature: String,
    )

    fun bodyHash(body: ByteArray): String =
        b64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(body))

    fun newNonce(byteCount: Int = 18): String =
        b64Url.encodeToString(ByteArray(byteCount).also { random.nextBytes(it) })

    fun canonical(
        method: String,
        pathAndQuery: String,
        clientId: ClientId,
        timestampMillis: Long,
        nonce: String,
        contentSha256: String,
    ): ByteArray =
        buildString {
            append(VERSION).append('\n')
            append(method.uppercase()).append('\n')
            append(pathAndQuery.ifBlank { "/" }).append('\n')
            append(clientId.value).append('\n')
            append(timestampMillis).append('\n')
            append(nonce).append('\n')
            append(contentSha256)
        }.toByteArray(Charsets.UTF_8)

    fun sign(
        signer: IdentitySigner,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): Headers {
        val hash = bodyHash(body)
        val canonical = canonical(method, pathAndQuery, signer.clientId, nowMillis, nonce, hash)
        return Headers(
            clientId = signer.clientId,
            timestampMillis = nowMillis,
            nonce = nonce,
            contentSha256 = hash,
            signature = b64Url.encodeToString(signer.sign(canonical)),
        )
    }

    fun verify(
        publicKeySpki: ByteArray,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        headers: Headers,
    ): Boolean {
        if (headers.contentSha256 != bodyHash(body)) return false
        val signature = runCatching { b64UrlDecoder.decode(headers.signature) }.getOrNull() ?: return false
        val canonical = canonical(
            method = method,
            pathAndQuery = pathAndQuery,
            clientId = headers.clientId,
            timestampMillis = headers.timestampMillis,
            nonce = headers.nonce,
            contentSha256 = headers.contentSha256,
        )
        return IdentityVerifier.verify(publicKeySpki, canonical, signature)
    }
}

object PlayIntegrityBinding {
    const val VERSION = "notisync-play-integrity-v1"

    fun requestHash(clientId: ClientId, requestNonce: String): String {
        val bytes = "$VERSION\n${clientId.value}\n$requestNonce".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    fun debugProof(debugKey: String, clientId: ClientId, requestNonce: String, requestHash: String): String? {
        val keyBytes = if (debugKey.isBlank()) return null else runCatching {
            Base64.getDecoder().decode(debugKey)
        }.getOrNull() ?: return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"))
            update(VERSION.toByteArray(Charsets.UTF_8))
            update('\n'.code.toByte())
            update(clientId.value.toByteArray(Charsets.UTF_8))
            update('\n'.code.toByte())
            update(requestNonce.toByteArray(Charsets.UTF_8))
            update('\n'.code.toByte())
            update(requestHash.toByteArray(Charsets.UTF_8))
        }.doFinal()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
    }
}
