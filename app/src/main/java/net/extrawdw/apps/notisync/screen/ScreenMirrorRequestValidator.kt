package net.extrawdw.apps.notisync.screen

import kotlin.math.abs
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.protocol.ScreenMirrorQualityLimits
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync

data class ScreenMirrorValidationFailure(
    val status: ScreenMirrorStatus,
    val detail: String,
)

object ScreenMirrorRequestValidator {
    fun validate(
        request: ScreenMirrorSync,
        authenticatedSender: ClientId,
        ownClientId: ClientId,
        envelopeCreatedAt: Long,
        now: Long,
        authorized: Boolean,
        codecAvailable: Boolean,
    ): ScreenMirrorValidationFailure? {
        if (request.action != ScreenMirrorAction.REQUEST) return invalid("not a request")
        if (request.protocolVersion != SCREEN_PROTOCOL_VERSION) return invalid("unsupported protocol version")
        if (request.requesterPeerId != authenticatedSender || request.sourcePeerId != ownClientId) {
            return ScreenMirrorValidationFailure(ScreenMirrorStatus.UNAUTHORIZED, "peer identity mismatch")
        }
        if (!authorized) return ScreenMirrorValidationFailure(ScreenMirrorStatus.UNAUTHORIZED, "peer not authorized")
        if (!validSessionId(request.sessionId)) return invalid("invalid session id")

        val expiresAt = request.expiresAt ?: return invalid("missing expiry")
        if (
            request.issuedAt <= 0 || envelopeCreatedAt <= 0 || expiresAt <= request.issuedAt ||
            expiresAt - request.issuedAt > MAX_REQUEST_LIFETIME_MS ||
            abs(envelopeCreatedAt - request.issuedAt) > MAX_ENVELOPE_TIMESTAMP_DELTA_MS ||
            abs(envelopeCreatedAt - now) > MAX_CLOCK_SKEW_MS ||
            request.issuedAt > now + MAX_CLOCK_SKEW_MS || now >= expiresAt
        ) {
            return ScreenMirrorValidationFailure(ScreenMirrorStatus.EXPIRED, "expired or invalid timestamp")
        }
        if (request.routingToken?.size != ROUTING_TOKEN_BYTES || request.masterPsk?.size != MASTER_PSK_BYTES) {
            return invalid("invalid rendezvous secret lengths")
        }
        if (request.codec == null) return invalid("missing codec")
        if (!codecAvailable) {
            return ScreenMirrorValidationFailure(ScreenMirrorStatus.CODEC_UNAVAILABLE, "hardware codec unavailable")
        }
        if (!validQuality(request)) return invalid("invalid quality limits")
        if (request.candidates.isEmpty() || request.candidates.size > MAX_CANDIDATES ||
            request.candidates.none(::validCandidate)
        ) {
            return invalid("no valid connection candidate")
        }
        return null
    }

    internal fun validCandidate(candidate: ScreenMirrorConnectionCandidate): Boolean = when (candidate.kind) {
        ScreenMirrorConnectionCandidate.LAN_TCP ->
            candidate.host?.let(::validHost) == true && candidate.port?.let { it in 1..65535 } == true
        ScreenMirrorConnectionCandidate.DNS_SD ->
            candidate.serviceName?.let(::validServiceName) == true &&
                (candidate.port == null || candidate.port.let { it in 1..65535 })
        ScreenMirrorConnectionCandidate.WIFI_AWARE ->
            candidate.serviceName?.let(AndroidWifiAwareServiceNames::isValid) == true &&
                candidate.host == null &&
                candidate.interfaceName == null && candidate.port?.let { it in 1..65535 } == true
        else -> false // Open registry on the wire; unknown transports never grant routing authority.
    }

    private fun validQuality(request: ScreenMirrorSync): Boolean =
        (request.maxDimension == null || request.maxDimension in
            ScreenMirrorQualityLimits.MIN_DIMENSION..ScreenMirrorQualityLimits.MAX_DIMENSION) &&
            (request.maxFps == null || request.maxFps in
                ScreenMirrorQualityLimits.MIN_FPS..ScreenMirrorQualityLimits.MAX_FPS) &&
            (request.videoBitrateBps == null || request.videoBitrateBps in
                ScreenMirrorQualityLimits.MIN_BITRATE_BPS..ScreenMirrorQualityLimits.MAX_BITRATE_BPS)

    private fun validSessionId(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)

    private fun validHost(value: String): Boolean =
        value.isNotBlank() && value.length <= 255 && value.none { it.isWhitespace() || it in "/\\?#@%" }

    private fun validServiceName(value: String): Boolean =
        value.isNotBlank() && value.length <= 255 && value.none(Char::isISOControl)

    private fun invalid(detail: String) =
        ScreenMirrorValidationFailure(ScreenMirrorStatus.TRANSPORT_FAILED, detail)

    const val SCREEN_PROTOCOL_VERSION = 1
    const val ROUTING_TOKEN_BYTES = 16
    const val MASTER_PSK_BYTES = 32
    const val MAX_REQUEST_LIFETIME_MS = 5L * 60 * 1000
    const val MAX_CLOCK_SKEW_MS = 2L * 60 * 1000
    const val MAX_ENVELOPE_TIMESTAMP_DELTA_MS = 2L * 60 * 1000
    const val MAX_CANDIDATES = 8
}
