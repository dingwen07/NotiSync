package net.extrawdw.notisync.server

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.server.integrity.AppCheckJwks
import net.extrawdw.notisync.server.integrity.AppCheckVerifier
import net.extrawdw.notisync.server.integrity.AttestationService
import net.extrawdw.notisync.server.integrity.AttestationVerifier
import net.extrawdw.notisync.server.integrity.IntegrityDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Local App Check verification (no Firebase Admin Java SDK exists): the broker validates the App Check
 * token as an RS256 JWT against an injected JWKS. These tests mint self-signed App Check JWTs with an
 * in-memory RSA keypair and assert the issuer/audience/sub(app-id)/expiry/signature checks.
 */
class AppCheckVerifierTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val projectNumber = "123456789"
    private val appId = "1:123456789:android:abcdef0123"
    private val issuer = "https://firebaseappcheck.googleapis.com/$projectNumber"
    private val audience = "projects/$projectNumber"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val kid = "test-kid"

    /** A JWKS that knows only our test key, by [kid]. An unknown kid returns null (rotation / forgery). */
    private val jwks = object : AppCheckJwks {
        override fun key(kid: String): RSAPublicKey? =
            if (kid == this@AppCheckVerifierTest.kid) keyPair.public as RSAPublicKey else null
    }

    @After
    fun clearServerProperties() {
        listOf(
            "NOTISYNC_APPCHECK_PROJECT_NUMBER",
            "NOTISYNC_APPCHECK_APP_IDS",
            "NOTISYNC_APPCHECK_MAX_AGE_MS",
            "NOTISYNC_SECURITY_ENABLED",
        ).forEach(System::clearProperty)
    }

    private fun config(): ServerConfig {
        System.setProperty("NOTISYNC_APPCHECK_PROJECT_NUMBER", projectNumber)
        System.setProperty("NOTISYNC_APPCHECK_APP_IDS", appId)
        return ServerConfig.fromEnv()
    }

    private fun verifier() = AppCheckVerifier(config(), jwks)

    private fun now() = System.currentTimeMillis() / 1000

    private fun mint(
        kid: String = this.kid,
        alg: String = "RS256",
        typ: String = "JWT",
        iss: String = issuer,
        aud: List<String> = listOf(audience),
        sub: String = appId,
        exp: Long = now() + 1800,
        iat: Long = now(),
        privateKey: PrivateKey = keyPair.private,
        tamper: Boolean = false,
    ): String {
        val header = """{"alg":"$alg","typ":"$typ","kid":"$kid"}"""
        val audJson = aud.joinToString(",", "[", "]") { "\"$it\"" }
        val claims = """{"iss":"$iss","aud":$audJson,"sub":"$sub","exp":$exp,"iat":$iat}"""
        val h = b64.encodeToString(header.toByteArray())
        val c = b64.encodeToString(claims.toByteArray())
        val signingInput = "$h.$c".toByteArray(Charsets.US_ASCII)
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(signingInput); sign() }
        if (tamper) sig[sig.size - 1] = (sig[sig.size - 1] + 1).toByte()
        return "$h.$c.${b64.encodeToString(sig)}"
    }

    private fun verify(token: String?) = runBlocking {
        verifier().verify(
            IntegrityVerificationRequest(
                clientId = ClientId("appcheck-test"),
                attestationType = AttestationType.FIREBASE_APP_CHECK,
                attestationToken = token,
            )
        )
    }

    private fun rejection(token: String?): IntegrityDecision.Rejected =
        verify(token) as IntegrityDecision.Rejected

    @Test
    fun acceptsAValidToken() {
        assertTrue(verify(mint()) is IntegrityDecision.Accepted)
    }

    @Test
    fun rejectsTokenForAnotherAppId() {
        assertEquals("appcheck_app_not_allowed", rejection(mint(sub = "1:123456789:android:someotherapp")).reason)
    }

    @Test
    fun rejectsExpiredTokenAsRetryable() {
        val d = rejection(mint(exp = now() - 10))
        assertEquals("appcheck_expired", d.reason)
        assertTrue(d.retryable)
    }

    @Test
    fun rejectsTokenFromAnotherProjectByIssuer() {
        assertEquals("appcheck_bad_issuer", rejection(mint(iss = "https://firebaseappcheck.googleapis.com/000")).reason)
    }

    @Test
    fun rejectsTokenWithWrongAudience() {
        assertEquals("appcheck_bad_audience", rejection(mint(aud = listOf("projects/000"))).reason)
    }

    @Test
    fun rejectsTamperedSignature() {
        assertEquals("appcheck_bad_signature", rejection(mint(tamper = true)).reason)
    }

    @Test
    fun rejectsUnknownKidAsRetryable() {
        val d = rejection(mint(kid = "rotated-away-kid"))
        assertEquals("appcheck_unknown_kid", d.reason)
        assertTrue(d.retryable)
    }

    @Test
    fun rejectsNonRs256Alg() {
        // alg-confusion guard: the header alg must be RS256 regardless of how the bytes were actually signed.
        assertEquals("appcheck_bad_alg", rejection(mint(alg = "none")).reason)
    }

    @Test
    fun rejectsMissingToken() {
        assertEquals("missing_appcheck_token", rejection(null).reason)
    }

    // --- AttestationService dispatch ---

    private fun stub(stubType: String, decision: IntegrityDecision) = object : AttestationVerifier {
        override val type = stubType
        override suspend fun verify(request: IntegrityVerificationRequest) = decision
    }

    private fun reqType(type: String) =
        IntegrityVerificationRequest(clientId = ClientId("dispatch-test"), attestationType = type)

    @Test
    fun dispatchRoutesByTypeAndRejectsUnknown() = runBlocking {
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "true")
        val svc = AttestationService(
            ServerConfig.fromEnv(),
            listOf(
                stub(AttestationType.PLAY_INTEGRITY, IntegrityDecision.Accepted(debugBypass = false)),
                stub(AttestationType.FIREBASE_APP_CHECK, IntegrityDecision.Rejected("ac_reason")),
            ),
        )
        assertEquals(listOf(AttestationType.PLAY_INTEGRITY, AttestationType.FIREBASE_APP_CHECK), svc.acceptedMethods)
        assertTrue(svc.verify(reqType(AttestationType.PLAY_INTEGRITY)) is IntegrityDecision.Accepted)
        assertEquals("ac_reason", (svc.verify(reqType(AttestationType.FIREBASE_APP_CHECK)) as IntegrityDecision.Rejected).reason)
        assertEquals("unsupported_attestation_type", (svc.verify(reqType("bogus_method")) as IntegrityDecision.Rejected).reason)
    }

    @Test
    fun dispatchMasterSwitchOffAcceptsEveryMethod() = runBlocking {
        System.setProperty("NOTISYNC_SECURITY_ENABLED", "false")
        val svc = AttestationService(
            ServerConfig.fromEnv(),
            listOf(stub(AttestationType.PLAY_INTEGRITY, IntegrityDecision.Rejected("should_not_run"))),
        )
        assertTrue(svc.verify(reqType("anything-at-all")) is IntegrityDecision.Accepted)
    }
}
