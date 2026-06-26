package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.IntegrityVerificationRequest

/**
 * A pluggable "way" of verifying client integrity. Each method — Play Integrity, Firebase App Check, and
 * (later) native Apple App Attest — implements this and is dispatched by
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
 * Routes an attestation request to the verifier named by its [IntegrityVerificationRequest.attestationType].
 *
 * Honors the master switch uniformly: when attestation is disabled (`playIntegrityEnabled = false`, used by
 * local/TEST instances) every method is accepted — mirroring [PlayIntegrityVerifier]'s own bypass so the
 * TEST broker keeps working unchanged. PoW + the identity request signature still gate the endpoint around
 * this call; this only decides the attestation verdict.
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
            !config.playIntegrityEnabled -> IntegrityDecision.Accepted(debugBypass = true)
            else -> byType[request.attestationType]?.verify(request)
                ?: IntegrityDecision.Rejected("unsupported_attestation_type")
        }
        metrics?.record(request.attestationType, request.clientId, decision)
        return decision
    }
}
