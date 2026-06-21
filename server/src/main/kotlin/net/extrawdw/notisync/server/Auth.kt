package net.extrawdw.notisync.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ErrorResponse
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class ServerAuth(
    private val config: ServerConfig,
    private val jwt: JwtIssuer,
    private val nonces: RequestNonceCache = RequestNonceCache(),
) {
    fun issue(clientId: ClientId): JwtIssuer.IssuedToken = jwt.issue(clientId)

    fun jwksJson(): String = jwt.jwksJson()

    suspend fun requireJwtSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthPrincipal? =
        when (val result = authenticateJwtSigned(call, body, broker)) {
            is AuthResult.Accepted -> result.principal
            is AuthResult.Rejected -> {
                call.respond(result.status, ErrorResponse(result.reason))
                null
            }
        }

    suspend fun authenticateJwtSigned(call: ApplicationCall, body: ByteArray, broker: Broker): AuthResult {
        if (!config.securityEnabled) {
            return AuthResult.Accepted(AuthPrincipal(ClientId("security-disabled"), Long.MAX_VALUE))
        }
        val principal = authenticateBearer(call) ?: return AuthResult.Rejected("auth_required")
        val spki = broker.clientSpki(principal.clientId) ?: return AuthResult.Rejected("unknown_client")
        return verifySignedRequest(call, body, principal.clientId, spki)
            .accepted(principal)
    }

    fun verifySignedRequest(
        call: ApplicationCall,
        body: ByteArray,
        expectedClientId: ClientId,
        signerSpki: ByteArray,
    ): SignatureCheck {
        if (!config.securityEnabled) return SignatureCheck.Accepted
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
        return HttpRequestSigning.Headers(ClientId(clientId), timestamp, nonce, hash, signature)
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

class RequestNonceCache {
    private val values = ConcurrentHashMap<String, Long>()

    fun accept(clientId: ClientId, nonce: String, nowMillis: Long, ttlMillis: Long): Boolean {
        if (values.size > 10_000) purge(nowMillis)
        val key = "${clientId.value}:$nonce"
        val expiresAt = nowMillis + ttlMillis
        return values.putIfAbsent(key, expiresAt) == null
    }

    private fun purge(nowMillis: Long) {
        values.entries.removeIf { it.value <= nowMillis }
    }
}
