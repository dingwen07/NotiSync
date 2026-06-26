package net.extrawdw.notisync.server

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.playintegrity.v1.PlayIntegrity
import com.google.api.services.playintegrity.v1.PlayIntegrityScopes
import com.google.api.services.playintegrity.v1.model.DecodeIntegrityTokenRequest
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.protocol.crypto.PlayIntegrityBinding
import org.slf4j.LoggerFactory

data class IntegrityPayload(
    val requestPackageName: String?,
    val requestHash: String?,
    val timestampMillis: Long?,
    val appLicensingVerdict: String?,
    val appRecognitionVerdict: String?,
    val appPackageName: String?,
    val deviceRecognitionVerdict: List<String>,
    val deviceActivityLevel: String?,
    val playProtectVerdict: String?,
)

interface PlayIntegrityDecoder {
    suspend fun decode(integrityToken: String): IntegrityPayload
}

class GooglePlayIntegrityDecoder(private val config: ServerConfig) : PlayIntegrityDecoder {
    private val service by lazy {
        val credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(listOf(PlayIntegrityScopes.PLAYINTEGRITY))
        PlayIntegrity.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials),
        )
            .setApplicationName("NotiSyncBroker")
            .build()
    }

    override suspend fun decode(integrityToken: String): IntegrityPayload = withContext(Dispatchers.IO) {
        val response = service.v1().decodeIntegrityToken(
            config.playIntegrityPackageName,
            DecodeIntegrityTokenRequest().setIntegrityToken(integrityToken),
        ).execute()
        val payload = response.tokenPayloadExternal ?: error("missing token payload")
        IntegrityPayload(
            requestPackageName = payload.requestDetails?.requestPackageName,
            requestHash = payload.requestDetails?.requestHash,
            timestampMillis = payload.requestDetails?.timestampMillis,
            appLicensingVerdict = payload.accountDetails?.appLicensingVerdict,
            appRecognitionVerdict = payload.appIntegrity?.appRecognitionVerdict,
            appPackageName = payload.appIntegrity?.packageName,
            deviceRecognitionVerdict = payload.deviceIntegrity?.deviceRecognitionVerdict.orEmpty(),
            deviceActivityLevel = payload.deviceIntegrity?.recentDeviceActivity?.deviceActivityLevel,
            playProtectVerdict = payload.environmentDetails?.playProtectVerdict,
        )
    }
}

class PlayIntegrityVerifier(
    private val config: ServerConfig,
    private val decoder: PlayIntegrityDecoder = GooglePlayIntegrityDecoder(config),
) : AttestationVerifier {
    override val type: String = AttestationType.PLAY_INTEGRITY
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun verify(request: IntegrityVerificationRequest): IntegrityDecision {
        fun reject(reason: String, payload: IntegrityPayload? = null, retryable: Boolean = false): IntegrityDecision.Rejected {
            log.info(
                "Play Integrity rejected client={} reason={} appLicensing={} appRecognition={} device={} activity={} playProtect={} retryable={}",
                request.clientId.shortForm(),
                reason,
                payload?.appLicensingVerdict,
                payload?.appRecognitionVerdict,
                payload?.deviceRecognitionVerdict,
                payload?.deviceActivityLevel,
                payload?.playProtectVerdict,
                retryable,
            )
            return IntegrityDecision.Rejected(reason, retryable)
        }

        val expectedHash = PlayIntegrityBinding.requestHash(request.clientId, request.requestNonce)
        if (request.requestHash != expectedHash) return reject("bad_request_hash")

        if (!config.playIntegrityEnabled) {
            return IntegrityDecision.Accepted(debugBypass = true)
        }

        val payload = runCatching { decoder.decode(request.integrityToken) }.getOrElse {
            val retryable = (it as? GoogleJsonResponseException)?.statusCode == 429
            val reason = if (retryable) "integrity_decode_rate_limited" else "integrity_decode_failed"
            log.warn("Play Integrity decode failed client={} reason={} message={}", request.clientId.shortForm(), reason, it.message)
            return reject(reason, retryable = retryable)
        }

        val now = System.currentTimeMillis()
        if (payload.requestPackageName != config.playIntegrityPackageName) {
            return reject("bad_package", payload)
        }
        if (payload.requestHash != request.requestHash) {
            return reject("integrity_hash_mismatch", payload)
        }
        val tokenAge = payload.timestampMillis?.let { now - it }
        if (tokenAge == null || tokenAge < 0 || tokenAge > config.playIntegrityMaxTokenAgeMillis) {
            // "Stale" is transient ONLY when the verdict aged past the window: Google caches the Standard
            // verdict for a few minutes and mint→verify latency (or a doze gap) can push it over, but a
            // fresh attestation clears it — so signal retryable (→ 429, short client cooldown) for that case.
            // A missing or future-dated timestamp is malformed or clock-skewed, not transient: keep the
            // definitive 403 backoff so a genuinely broken token doesn't re-attest in a tight loop.
            val staleByAge = tokenAge != null && tokenAge > config.playIntegrityMaxTokenAgeMillis
            return reject("stale_integrity_token", payload, retryable = staleByAge)
        }

        val debugBypass = request.debugProof != null &&
            request.debugProof == PlayIntegrityBinding.debugProof(
                config.debugKey,
                request.clientId,
                request.requestNonce,
                request.requestHash,
            )

        if (!debugBypass) {
            requireAllowed("appLicensingVerdict", payload.appLicensingVerdict, config.requiredAppLicensingVerdicts)
                ?.let { return reject(it, payload) }
            requireAllowed("appRecognitionVerdict", payload.appRecognitionVerdict, config.requiredAppRecognitionVerdicts)
                ?.let { return reject(it, payload) }
            if (payload.appPackageName != null && payload.appPackageName != config.playIntegrityPackageName) {
                return reject("bad_app_integrity_package", payload)
            }
        }

        if (!config.requiredDeviceRecognitionVerdicts.acceptsAll(payload.deviceRecognitionVerdict)) {
            return reject("bad_device_integrity", payload)
        }
        // Device activity is an allow-list over the 5 known levels; the default permits all but LEVEL_4
        // (>50 token requests/hour — a strong abuse signal). UNEVALUATED is allowed; null/unknown reject.
        requireAllowed("deviceActivityLevel", payload.deviceActivityLevel, config.allowedDeviceActivityLevels)
            ?.let { return reject(it, payload) }
        if (!(debugBypass && payload.playProtectVerdict == "UNEVALUATED")) {
            requireAllowed("playProtectVerdict", payload.playProtectVerdict, config.requiredPlayProtectVerdicts)
                ?.let { return reject(it, payload) }
        }

        log.info("Play Integrity accepted client={} debugBypass={}", request.clientId.shortForm(), debugBypass)
        return IntegrityDecision.Accepted(debugBypass = debugBypass)
    }

    private fun requireAllowed(name: String, value: String?, allowed: Set<String>): String? {
        if ("*" in allowed) return null
        return if (value != null && value in allowed) null else "bad_$name"
    }

    private fun Set<String>.acceptsAll(actual: List<String>): Boolean =
        "*" in this || all { it in actual }
}

sealed class IntegrityDecision {
    data class Accepted(val debugBypass: Boolean) : IntegrityDecision()
    data class Rejected(val reason: String, val retryable: Boolean = false) : IntegrityDecision()
}
