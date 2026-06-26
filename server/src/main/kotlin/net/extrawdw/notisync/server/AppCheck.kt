package net.extrawdw.notisync.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Supplies Firebase App Check JWKS verification keys by `kid`. Injectable so tests don't hit the network. */
interface AppCheckJwks {
    /** The RSA public key for [kid], fetching/refreshing as needed; null if still unknown after a refresh. */
    fun key(kid: String): RSAPublicKey?
}

/** A single JWKS fetch result. Separated from the HTTP client so tests can drive responses. */
internal data class JwksHttpResponse(val statusCode: Int, val body: String)

/** Fetches the raw JWKS document. Injectable so tests don't hit the network. */
internal fun interface JwksFetcher {
    fun get(): JwksHttpResponse
}

/**
 * Fetches and caches the Firebase App Check JWKS (RS256 RSA keys). Refetches when a `kid` is unseen (key
 * rotation), but at most once per [minRefetchIntervalMillis] — throttled by last *attempt*, so neither a
 * bogus `kid` nor an upstream outage drives a fetch on every request.
 *
 * Only a 2xx response with at least one usable key replaces the cache: a non-2xx (5xx / HTML error page) or
 * an unparseable/empty body is NOT cached, so a transient JWKS blip can never evict good keys and reject
 * every token until the cooldown elapses.
 */
class HttpAppCheckJwks internal constructor(
    private val minRefetchIntervalMillis: Long,
    private val fetcher: JwksFetcher,
) : AppCheckJwks {
    constructor(url: String, minRefetchIntervalMillis: Long = 5L * 60 * 1000) :
        this(minRefetchIntervalMillis, httpFetcher(url))

    private val b64 = Base64.getUrlDecoder()
    private val keys = AtomicReference<Map<String, RSAPublicKey>>(emptyMap())

    // Epoch (not MIN_VALUE, whose subtraction from `now` overflows) so the very first call always fetches.
    @Volatile
    private var lastFetchAttempt: Long = 0L

    override fun key(kid: String): RSAPublicKey? {
        keys.get()[kid]?.let { return it }
        val now = System.currentTimeMillis()
        // Throttle by last attempt (success OR failure) so an unknown kid / outage can't fetch every call.
        if (now - lastFetchAttempt < minRefetchIntervalMillis) return null
        synchronized(this) {
            keys.get()[kid]?.let { return it } // a peer thread may have just refreshed
            if (now - lastFetchAttempt < minRefetchIntervalMillis) return null
            lastFetchAttempt = now
            val fetched = runCatching { fetchKeys() }.getOrNull() ?: return null
            keys.set(fetched) // only a good response replaces the cache; a bad one leaves prior keys intact
            return fetched[kid]
        }
    }

    /** Fetch + parse the JWKS, throwing on a non-2xx or no-usable-keys response so [key] never overwrites a
     *  previously-good cache (caching an empty map would reject every token until the cooldown elapses). */
    private fun fetchKeys(): Map<String, RSAPublicKey> {
        val response = fetcher.get()
        check(response.statusCode in 200..299) { "App Check JWKS fetch HTTP ${response.statusCode}" }
        val parsed = parse(response.body)
        check(parsed.isNotEmpty()) { "App Check JWKS contained no usable RSA keys" }
        return parsed
    }

    private fun parse(body: String): Map<String, RSAPublicKey> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val keysArr = root["keys"] as? JsonArray ?: return emptyMap()
        val factory = KeyFactory.getInstance("RSA")
        val out = HashMap<String, RSAPublicKey>()
        for (el in keysArr) {
            val o = el as? JsonObject ?: continue
            val kid = (o["kid"] as? JsonPrimitive)?.contentOrNull ?: continue
            if ((o["kty"] as? JsonPrimitive)?.contentOrNull != "RSA") continue
            val n = (o["n"] as? JsonPrimitive)?.contentOrNull ?: continue
            val e = (o["e"] as? JsonPrimitive)?.contentOrNull ?: continue
            val modulus = BigInteger(1, b64.decode(n))
            val exponent = BigInteger(1, b64.decode(e))
            out[kid] = factory.generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        }
        return out
    }

    private companion object {
        private fun httpFetcher(url: String): JwksFetcher {
            val http = HttpClient.newHttpClient()
            return JwksFetcher {
                val response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                JwksHttpResponse(response.statusCode(), response.body())
            }
        }
    }
}

/**
 * Verifies a Firebase App Check token locally as an RS256 JWT against the App Check JWKS. The Firebase Admin
 * *Java* SDK has no App Check API, so we validate the token directly per Firebase's "verify without the Admin
 * SDK" guidance: signature (RS256/JWKS) + iss + aud (project number) + sub (app-id allow-list) + expiry.
 *
 * App Check uses Play Integrity internally and surfaces only the app id — no device verdicts — so client
 * genuineness for this method is the App Check token plus the surrounding identity request signature, nonce
 * replay guard, and PoW. A cached/reused token is fine: it only re-proves genuineness for a fresh, signed,
 * replay-checked request.
 */
class AppCheckVerifier(
    private val config: ServerConfig,
    private val jwks: AppCheckJwks = HttpAppCheckJwks(config.appCheckJwksUrl),
) : AttestationVerifier {
    override val type: String = AttestationType.FIREBASE_APP_CHECK
    private val log = LoggerFactory.getLogger(javaClass)
    private val b64 = Base64.getUrlDecoder()
    private val expectedIssuer = "https://firebaseappcheck.googleapis.com/${config.appCheckProjectNumber}"
    private val expectedAudience = "projects/${config.appCheckProjectNumber}"

    override suspend fun verify(request: IntegrityVerificationRequest): IntegrityDecision = withContext(Dispatchers.IO) {
        fun reject(reason: String, retryable: Boolean = false): IntegrityDecision.Rejected {
            log.info("App Check rejected client={} reason={} retryable={}", request.clientId.shortForm(), reason, retryable)
            return IntegrityDecision.Rejected(reason, retryable)
        }

        val token = request.attestationToken?.takeIf { it.isNotBlank() }
            ?: return@withContext reject("missing_appcheck_token")
        if (config.appCheckProjectNumber.isBlank() || config.appCheckAppIds.isEmpty()) {
            return@withContext reject("appcheck_not_configured")
        }
        val parts = token.split('.')
        if (parts.size != 3) return@withContext reject("appcheck_malformed")

        val header = runCatching { Json.parseToJsonElement(String(b64.decode(parts[0]))).jsonObject }.getOrNull()
            ?: return@withContext reject("appcheck_bad_header")
        if ((header["typ"] as? JsonPrimitive)?.contentOrNull != "JWT") return@withContext reject("appcheck_bad_typ")
        if ((header["alg"] as? JsonPrimitive)?.contentOrNull != "RS256") return@withContext reject("appcheck_bad_alg")
        val kid = (header["kid"] as? JsonPrimitive)?.contentOrNull ?: return@withContext reject("appcheck_no_kid")

        // An unknown kid is transient (key rotation): signal retryable so the client re-attests shortly.
        val key = jwks.key(kid) ?: return@withContext reject("appcheck_unknown_kid", retryable = true)
        val signature = runCatching { b64.decode(parts[2]) }.getOrNull()
            ?: return@withContext reject("appcheck_bad_signature")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val sigOk = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(signingInput)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!sigOk) return@withContext reject("appcheck_bad_signature")

        val claims = runCatching { Json.parseToJsonElement(String(b64.decode(parts[1]))).jsonObject }.getOrNull()
            ?: return@withContext reject("appcheck_bad_claims")
        if ((claims["iss"] as? JsonPrimitive)?.contentOrNull != expectedIssuer) return@withContext reject("appcheck_bad_issuer")
        val aud = when (val el = claims["aud"]) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(el.contentOrNull)
            else -> emptyList()
        }
        if (expectedAudience !in aud) return@withContext reject("appcheck_bad_audience")
        val sub = (claims["sub"] as? JsonPrimitive)?.contentOrNull ?: return@withContext reject("appcheck_no_sub")
        if (sub !in config.appCheckAppIds) return@withContext reject("appcheck_app_not_allowed")

        val nowSeconds = System.currentTimeMillis() / 1000
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: return@withContext reject("appcheck_no_exp")
        if (exp <= nowSeconds) return@withContext reject("appcheck_expired", retryable = true)
        val iat = (claims["iat"] as? JsonPrimitive)?.longOrNull
        if (iat != null) {
            if (iat > nowSeconds + 60) return@withContext reject("appcheck_future_iat")
            if (config.appCheckMaxTokenAgeMillis > 0 && (nowSeconds - iat) * 1000 > config.appCheckMaxTokenAgeMillis) {
                return@withContext reject("appcheck_stale_token", retryable = true)
            }
        }

        log.info("App Check accepted client={} app={}", request.clientId.shortForm(), sub)
        IntegrityDecision.Accepted(debugBypass = false, detail = VerificationDetail(appId = sub))
    }
}
