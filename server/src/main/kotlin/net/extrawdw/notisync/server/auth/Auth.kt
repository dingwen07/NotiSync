package net.extrawdw.notisync.server.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ErrorResponse
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.broker.Broker
import java.util.Base64
import kotlin.math.abs

class ServerAuth(
    private val config: ServerConfig,
    private val jwt: JwtIssuer,
    private val nonces: RequestNonceCache = RequestNonceCache(),
    private val powReplay: RequestNonceCache = RequestNonceCache(),
) {
    fun issue(clientId: ClientId): JwtIssuer.IssuedToken = jwt.issue(clientId)

    fun jwksJson(): String = jwt.jwksJson()

    /** The JWT principal carried by an Authorization: Bearer header, or null if absent/invalid. */
    fun bearerPrincipal(call: ApplicationCall): AuthPrincipal? = authenticateBearer(call)

    /**
     * Validate HTTP Basic credentials for the /v2/metrics diagnostics endpoint. Returns false when the
     * metrics password is unset (endpoint disabled) or the credentials don't match. Constant-time password
     * compare — the creds are low-sensitivity (read-only stats) but a trivial timing oracle is still avoided.
     */
    fun metricsAuthorized(call: ApplicationCall): Boolean {
        val expected = config.metricsPassword
        if (expected.isBlank()) return false
        val header = call.request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Basic ") } ?: return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8) }
            .getOrNull() ?: return false
        val sep = decoded.indexOf(':')
        if (sep < 0) return false
        return decoded.substring(0, sep) == config.metricsUser &&
            java.security.MessageDigest.isEqual(
                decoded.substring(sep + 1).toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
    }

    /**
     * Validates the hashcash proof of work on /v1/integrity/verify. Returns null when valid, else a
     * rejection reason. The proof is bound to the request signature (so it can't be precomputed) and
     * to [ProofOfWork.HEADER_TIMESTAMP] (bounded by the signed-request skew window and replay-cached).
     */
    fun checkProofOfWork(call: ApplicationCall): String? {
        if (config.powDifficulty <= 0) return null
        val signature = call.request.headers[HttpRequestSigning.HEADER_SIGNATURE]?.takeIf { it.isNotBlank() }
            ?: return "pow_required"
        val nonce = call.request.headers[ProofOfWork.HEADER_NONCE]?.takeIf { it.isNotBlank() } ?: return "pow_required"
        val timestamp = call.request.headers[ProofOfWork.HEADER_TIMESTAMP]?.toLongOrNull() ?: return "pow_required"
        val now = System.currentTimeMillis()
        val skew = config.signedRequestMaxSkewMillis
        if (timestamp < now - skew || timestamp > now + skew) return "pow_timestamp_skew"
        val hash = ProofOfWork.hashHex(signature, nonce, timestamp)
        if (!ProofOfWork.satisfies(hash, config.powDifficulty)) return "pow_insufficient"
        if (!powReplay.accept(ClientId("pow"), hash, now, skew)) return "pow_replay"
        return null
    }

    suspend fun requireJwtSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthPrincipal? =
        when (val result = authenticateJwtSigned(call, body, broker)) {
            is AuthResult.Accepted -> result.principal
            is AuthResult.Rejected -> {
                call.respond(result.status, ErrorResponse(result.reason))
                null
            }
        }

    suspend fun requireSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthPrincipal? =
        when (val result = authenticateSigned(call, body, broker)) {
            is AuthResult.Accepted -> result.principal
            is AuthResult.Rejected -> {
                call.respond(result.status, ErrorResponse(result.reason))
                null
            }
        }

    /**
     * Authenticate by request signature ALONE — no JWT bearer required. The clientId is read from the
     * signed headers and bound by the signature (verified against that client's stored identity key).
     * Used by the FCM background-wake fetch, which must work even when the client holds no valid
     * attestation token — it can always sign with its identity key. When attestation is disabled the
     * signature is trusted as-is, mirroring [authenticateJwtSigned].
     */
    suspend fun authenticateSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthResult {
        val clientId = call.request.headers[HttpRequestSigning.HEADER_CLIENT_ID]?.takeIf { it.isNotBlank() }
            ?.let { ClientId(it) }
            ?: return AuthResult.Rejected("signature_required")
        if (!config.playIntegrityEnabled) {
            return AuthResult.Accepted(AuthPrincipal(clientId, Long.MAX_VALUE))
        }
        val (spki, reason) = resolveSignerSpki(call, broker, clientId)
        if (spki == null) return AuthResult.Rejected(reason)
        return verifySignedRequest(call, body, clientId, spki).accepted(AuthPrincipal(clientId, Long.MAX_VALUE))
    }

    suspend fun authenticateJwtSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthResult {
        if (!config.playIntegrityEnabled) {
            return AuthResult.Accepted(AuthPrincipal(ClientId("auth-disabled"), Long.MAX_VALUE))
        }
        val principal = authenticateBearer(call) ?: return AuthResult.Rejected("auth_required")
        val (spki, reason) = resolveSignerSpki(call, broker, principal.clientId)
        if (spki == null) return AuthResult.Rejected(reason)
        return verifySignedRequest(call, body, principal.clientId, spki)
            .accepted(principal)
    }

    /**
     * Resolve the public key the request signature should verify against, by its NS2 signer-epoch header:
     * epoch 0 (or absent) → the identity key (NS1-compatible, always-valid root); ≥1 → the operational
     * key of that key-epoch. Returns (null, reason) when no key is available for the named epoch.
     */
    private suspend fun resolveSignerSpki(call: ApplicationCall, broker: Broker, clientId: ClientId): Pair<ByteArray?, String> {
        val epoch = call.request.headers[HttpRequestSigning.HEADER_SIGNER_EPOCH]?.toIntOrNull() ?: 0
        val spki = if (epoch == 0) broker.clientSpki(clientId) else broker.operationalSpki(clientId, epoch)
        return spki to (if (epoch == 0) "unknown_client" else "unknown_epoch")
    }

    fun verifySignedRequest(
        call: ApplicationCall,
        body: ByteArray,
        expectedClientId: ClientId,
        signerSpki: ByteArray,
    ): SignatureCheck {
        if (!config.playIntegrityEnabled) return SignatureCheck.Accepted
        val headers = signedHeaders(call) ?: return SignatureCheck.Rejected("signature_required")
        if (headers.clientId != expectedClientId) return SignatureCheck.Rejected("signature_client_mismatch")
        val now = System.currentTimeMillis()
        if (abs(now - headers.timestampMillis) > config.signedRequestMaxSkewMillis) {
            return SignatureCheck.Rejected("signature_timestamp_skew")
        }
        val ok = HttpRequestSigning.verify(
            publicKeySpki = signerSpki,
            method = call.request.httpMethod.value,
            pathAndQuery = call.request.uri,
            body = body,
            headers = headers,
        )
        if (!ok) return SignatureCheck.Rejected("bad_signature")
        if (!nonces.accept(headers.clientId, headers.nonce, now, config.signedRequestMaxSkewMillis)) {
            return SignatureCheck.Rejected("signature_replay")
        }
        return SignatureCheck.Accepted
    }

    private fun authenticateBearer(call: ApplicationCall): AuthPrincipal? {
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return jwt.verify(token)
    }

    private fun signedHeaders(call: ApplicationCall): HttpRequestSigning.Headers? {
        val clientId = call.request.headers[HttpRequestSigning.HEADER_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: return null
        val timestamp = call.request.headers[HttpRequestSigning.HEADER_TIMESTAMP]?.toLongOrNull() ?: return null
        val nonce = call.request.headers[HttpRequestSigning.HEADER_NONCE]?.takeIf { it.length in 16..128 } ?: return null
        val hash = call.request.headers[HttpRequestSigning.HEADER_CONTENT_SHA256]?.takeIf { it.isNotBlank() } ?: return null
        val signature = call.request.headers[HttpRequestSigning.HEADER_SIGNATURE]?.takeIf { it.isNotBlank() } ?: return null
        val signerEpoch = call.request.headers[HttpRequestSigning.HEADER_SIGNER_EPOCH]?.toIntOrNull() ?: 0
        return HttpRequestSigning.Headers(ClientId(clientId), timestamp, nonce, hash, signature, signerEpoch)
    }
}

sealed class AuthResult {
    data class Accepted(val principal: AuthPrincipal) : AuthResult()
    data class Rejected(
        val reason: String,
        val status: HttpStatusCode = HttpStatusCode.Unauthorized,
    ) : AuthResult()
}

sealed class SignatureCheck {
    data object Accepted : SignatureCheck()
    data class Rejected(val reason: String) : SignatureCheck()

    fun accepted(principal: AuthPrincipal): AuthResult =
        when (this) {
            Accepted -> AuthResult.Accepted(principal)
            is Rejected -> AuthResult.Rejected(reason)
        }
}

/**
 * Replay guard for signed-request / proof-of-work nonces. Memory is hard-bounded at [maxEntries]: a
 * fixed-capacity insertion-ordered map evicts the oldest entry (which, since every entry shares the
 * same TTL, is also the soonest to expire) once full — so a flood of distinct nonces can never grow
 * it without bound. A still-valid duplicate is rejected as a replay; an expired one is reaccepted.
 */
class RequestNonceCache(private val maxEntries: Int = 100_000) {
    private val values = object : LinkedHashMap<String, Long>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxEntries
    }

    @Synchronized
    fun accept(clientId: ClientId, nonce: String, nowMillis: Long, ttlMillis: Long): Boolean {
        val key = "${clientId.value}:$nonce"
        val existing = values[key]
        if (existing != null && existing > nowMillis) return false // unexpired -> replay
        values[key] = nowMillis + ttlMillis
        return true
    }
}
