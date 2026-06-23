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
    const val VERSION = "notisync-http-sign-v2"
    const val HEADER_CLIENT_ID = "X-NotiSync-Client-Id"
    const val HEADER_TIMESTAMP = "X-NotiSync-Timestamp"
    const val HEADER_NONCE = "X-NotiSync-Nonce"
    const val HEADER_CONTENT_SHA256 = "X-NotiSync-Content-SHA256"
    const val HEADER_SIGNATURE = "X-NotiSync-Signature"
    /** NS2: which key signed — "0"/absent = identity (NS1-compatible), ≥1 = operational epoch. */
    const val HEADER_SIGNER_EPOCH = "X-NotiSync-Signer-Epoch"

    private val b64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()

    data class Headers(
        val clientId: ClientId,
        val timestampMillis: Long,
        val nonce: String,
        val contentSha256: String,
        val signature: String,
        /** Signing-key selector: 0 = identity (root) key, ≥1 = operational key of that epoch. */
        val signerEpoch: Int = 0,
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
        signerEpoch: Int = 0,
    ): ByteArray =
        buildString {
            append(VERSION).append('\n')
            append(method.uppercase()).append('\n')
            append(pathAndQuery.ifBlank { "/" }).append('\n')
            append(clientId.value).append('\n')
            // The signer-epoch is always bound (0 = identity, ≥1 = operational), so it can't be stripped or
            // replayed as another epoch, and the v2 VERSION tag means an NS1 signature never verifies here.
            append(signerEpoch).append('\n')
            append(timestampMillis).append('\n')
            append(nonce).append('\n')
            append(contentSha256)
        }.toByteArray(Charsets.UTF_8)

    /** Sign with the device IDENTITY key (signerEpoch 0) — attestation, card/epoch publish, recovery. */
    fun sign(
        signer: IdentitySigner,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): Headers = signInternal(signer.clientId, 0, signer::sign, method, pathAndQuery, body, nowMillis, nonce)

    /** Sign with a delegated OPERATIONAL key (signerEpoch ≥ 1) — the routine authenticated hot path. */
    fun sign(
        signer: OperationalSigner,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): Headers = signInternal(signer.clientId, signer.signerEpoch, signer::sign, method, pathAndQuery, body, nowMillis, nonce)

    private fun signInternal(
        clientId: ClientId,
        signerEpoch: Int,
        sign: (ByteArray) -> ByteArray,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
        nowMillis: Long,
        nonce: String,
    ): Headers {
        val hash = bodyHash(body)
        val canonical = canonical(method, pathAndQuery, clientId, nowMillis, nonce, hash, signerEpoch)
        return Headers(
            clientId = clientId,
            timestampMillis = nowMillis,
            nonce = nonce,
            contentSha256 = hash,
            signature = b64Url.encodeToString(sign(canonical)),
            signerEpoch = signerEpoch,
        )
    }

    /**
     * Verify a request signature against [publicKeySpki] — the IDENTITY key when [Headers.signerEpoch]
     * is 0, else the OPERATIONAL key of that epoch (the caller resolves it from a verified key-epoch).
     */
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
            signerEpoch = headers.signerEpoch,
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

/**
 * Lightweight hashcash-style proof of work, used to make the unauthenticated /v1/integrity/verify
 * endpoint costly to flood (each accepted request gates a billed Play Integrity decode). The proof is
 * bound to the request's signature, so it can't be precomputed without first signing the request, and
 * to a timestamp so the server can bound and replay-protect it. Verifying is one SHA-256; solving is
 * ~16^difficulty hashes (difficulty 3 ≈ 4096, sub-millisecond on a phone).
 */
object ProofOfWork {
    const val HEADER_NONCE = "X-PoW-Nonce"
    const val HEADER_TIMESTAMP = "X-PoW-Timestamp"
    const val DEFAULT_DIFFICULTY = 4

    private val HEX = "0123456789abcdef".toCharArray()

    /** A solved proof: the winning [nonce] and how many hashes were tried to find it. */
    data class Solution(val nonce: String, val hashes: Long)

    /** Lowercase-hex SHA-256 over "signature\nnonce\ntimestamp". */
    fun hashHex(signature: String, nonce: String, timestampMillis: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$signature\n$nonce\n$timestampMillis".toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** True when [hashHex] begins with [difficulty] hex zeros. */
    fun satisfies(hashHex: String, difficulty: Int): Boolean {
        if (difficulty <= 0) return true
        if (hashHex.length < difficulty) return false
        for (i in 0 until difficulty) if (hashHex[i] != '0') return false
        return true
    }

    /** Grind a counter nonce until the hash meets [difficulty], returning the winning nonce and the
     *  number of hashes tried (winning counter + 1). */
    fun solveCounted(signature: String, timestampMillis: Long, difficulty: Int = DEFAULT_DIFFICULTY): Solution {
        var n = 0L
        while (true) {
            val nonce = n.toString()
            if (satisfies(hashHex(signature, nonce, timestampMillis), difficulty)) return Solution(nonce, n + 1)
            n++
        }
    }

    /** Convenience wrapper returning just the winning nonce. */
    fun solve(signature: String, timestampMillis: Long, difficulty: Int = DEFAULT_DIFFICULTY): String =
        solveCounted(signature, timestampMillis, difficulty).nonce
}
