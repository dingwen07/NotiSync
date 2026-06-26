package net.extrawdw.notisync.server

import net.extrawdw.notisync.server.integrity.HttpAppCheckJwks
import net.extrawdw.notisync.server.integrity.JwksHttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Guards the App Check JWKS cache against poisoning: a transient non-2xx (or unparseable) response from the
 * JWKS endpoint must never evict good keys or get cached as an empty map — that would reject every App Check
 * token until the cooldown elapses.
 */
class HttpAppCheckJwksTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val pub = (KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey)
    private val kid = "kid-1"

    private fun BigInteger.unsigned(): ByteArray = toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun jwksJson(): String =
        """{"keys":[{"kty":"RSA","kid":"$kid","n":"${b64.encodeToString(pub.modulus.unsigned())}","e":"${b64.encodeToString(pub.publicExponent.unsigned())}"}]}"""

    @Test
    fun transientNon200DoesNotEvictGoodKeys() {
        val responses = ArrayDeque<JwksHttpResponse>()
        val jwks = HttpAppCheckJwks(
            minRefetchIntervalMillis = 0, // disable the throttle so each cache miss attempts a fetch
            fetcher = { responses.removeFirst() },
        )

        responses.add(JwksHttpResponse(200, jwksJson()))
        assertNotNull("first fetch caches the key", jwks.key(kid))

        // A refresh triggered by an unknown kid returns 503; it must NOT evict the cached good key.
        responses.add(JwksHttpResponse(503, "upstream down"))
        assertNull("unknown kid + bad response resolves to null", jwks.key("unknown-kid"))
        assertNotNull("the originally-cached key still resolves after the failed refresh", jwks.key(kid))
    }

    @Test
    fun unparseableBodyIsNotCached() {
        var calls = 0
        val jwks = HttpAppCheckJwks(
            minRefetchIntervalMillis = 0,
            fetcher = { calls++; JwksHttpResponse(200, "<html>not json</html>") },
        )
        assertNull(jwks.key(kid))
        assertEquals(1, calls)
    }

    @Test
    fun unknownKidIsThrottledWithinCooldown() {
        var calls = 0
        val jwks = HttpAppCheckJwks(
            minRefetchIntervalMillis = 5L * 60 * 1000,
            fetcher = { calls++; JwksHttpResponse(503, "down") },
        )
        assertNull(jwks.key("unknown"))
        assertNull(jwks.key("unknown")) // within cooldown: no second fetch
        assertEquals("a bogus kid / outage can't drive a fetch on every request", 1, calls)
    }
}
