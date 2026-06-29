package net.extrawdw.notisync.server.integrity

import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.server.ServerConfig

/**
 * A pluggable "way" of verifying client integrity. Each method — Firebase App Check today, and (later)
 * native Apple App Attest — implements this and is dispatched by
 * [IntegrityVerificationRequest.attestationType]. Every method returns the common [IntegrityDecision], so
 * `POST /v2/integrity/verify` and everything downstream (the issued broker JWT) stay method-agnostic:
 * adding a new method is one new verifier, with no change to the API surface.
 */
interface AttestationVerifier {
    /** The [net.extrawdw.notisync.protocol.AttestationType] value this verifier handles. */
    val type: String

    suspend fun verify(request: IntegrityVerificationRequest): IntegrityDecision
}

/**
 * Routes an attestation request to the verifier named by its [IntegrityVerificationRequest.attestationType]
 * and returns that verifier's *real* verdict (so metrics stay truthful). Whether a rejection actually blocks
 * issuing a bearer is the caller's policy ([ServerConfig.integrityRequired]) — not decided here.
 *
 * Honors the master switch uniformly: when security is disabled (`securityEnabled = false`, used by
 * local/TEST instances) every method is accepted — the whole auth/attestation stack is bypassed. PoW + the
 * identity request signature still gate the endpoint around this call; this only decides the verdict.
 */
class AttestationService(
    private val config: ServerConfig,
    verifiers: List<AttestationVerifier>,
    private val metrics: AttestationMetrics? = null,
) {
    private val byType: Map<String, AttestationVerifier> = verifiers.associateBy { it.type }

    /** The methods this broker will accept, advertised on /v2/status for client discovery. */
    val acceptedMethods: List<String> = verifiers.map { it.type }

    suspend fun verify(request: IntegrityVerificationRequest): IntegrityDecision {
        val decision = when {
            !config.securityEnabled -> IntegrityDecision.Accepted(debugBypass = true)
            else -> byType[request.attestationType]?.verify(request)
                ?: IntegrityDecision.Rejected("unsupported_attestation_type")
        }
        metrics?.record(request.attestationType, request.clientId, decision)
        return decision
    }
}

sealed class IntegrityDecision {
    /** Optional method-specific data captured for /v2/metrics (e.g. the App Check appId). */
    abstract val detail: VerificationDetail?

    data class Accepted(val debugBypass: Boolean, override val detail: VerificationDetail? = null) : IntegrityDecision()
    data class Rejected(val reason: String, val retryable: Boolean = false, override val detail: VerificationDetail? = null) : IntegrityDecision()
}
