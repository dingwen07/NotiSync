package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

@Serializable
enum class HotspotAction { QUERY, SET_ENABLED, STATUS, REFRESH }

@Serializable
enum class HotspotState { UNKNOWN, DISABLED, ENABLING, ENABLED, DISABLING, FAILED }

@Serializable
enum class HotspotResult { OK, UNAUTHORIZED, UNSUPPORTED, UNAVAILABLE, FAILED, TIMEOUT, BUSY, EXPIRED }

/** Saved Settings configuration, not a claim that a downstream client has Internet access. E2E only. */
@Serializable
data class HotspotSnapshot(
    @CborLabel(0) val state: HotspotState,
    @CborLabel(1) val ssid: String? = null,
    @CborLabel(2) val psk: String? = null,
    @CborLabel(3) val securityType: Int? = null,
    @CborLabel(4) val wifiTethered: Boolean = false,
    @CborLabel(5) val hiddenSsid: Boolean = false,
) {
    override fun toString(): String = "HotspotSnapshot(state=$state, wifiTethered=$wifiTethered, credentials=redacted)"
}

/**
 * Capability-gated, individually encrypted hotspot control. All operations (including REFRESH and STATUS)
 * require the hotspot device's screen-sharing permission. STATUS without a snapshot has no device details.
 * REFRESH is a best-effort broadcast hint; QUERY always requests a fresh broadcast with a correlated reply.
 * REFRESH still has a requestId for durable request deduplication, but does not require a reply with it.
 * QUERY/REFRESH may omit hotspotDeviceId to address every provider in one signed fanout.
 * SET_ENABLED sets a desired state, never toggles it. No configuration editing is supported.
 */
@Serializable
data class HotspotSync(
    @CborLabel(0) val action: HotspotAction,
    @CborLabel(1) val hotspotDeviceId: ClientId? = null,
    @CborLabel(2) val issuedAt: Long,
    @CborLabel(3) val expiresAt: Long,
    @CborLabel(4) val requestId: String? = null,
    @CborLabel(5) val enabled: Boolean? = null,
    @CborLabel(6) val snapshot: HotspotSnapshot? = null,
    @CborLabel(7) val result: HotspotResult? = null,
    /** Android tethering error number only; never raw platform exception text. */
    @CborLabel(8) val platformError: Int? = null,
) {
    fun isValid(now: Long, envelopeCreatedAt: Long): Boolean {
        if (issuedAt <= 0 || issuedAt > now + CLOCK_SKEW_MS || expiresAt <= now ||
            expiresAt <= issuedAt || expiresAt - issuedAt > LIFETIME_MS ||
            envelopeCreatedAt <= 0 || envelopeCreatedAt < issuedAt - CLOCK_SKEW_MS ||
            envelopeCreatedAt > issuedAt + CLOCK_SKEW_MS
        ) return false
        if (requestId != null && (requestId.isBlank() || requestId.length > 128 || requestId.any(Char::isISOControl))) return false
        if (snapshot?.ssid?.length?.let { it > 128 } == true || snapshot?.psk?.length?.let { it > 128 } == true) return false
        return when (action) {
            HotspotAction.QUERY, HotspotAction.REFRESH ->
                requestId != null && enabled == null && snapshot == null && result == null && platformError == null
            HotspotAction.SET_ENABLED -> hotspotDeviceId != null && requestId != null && enabled != null &&
                snapshot == null && result == null && platformError == null
            HotspotAction.STATUS -> hotspotDeviceId != null && enabled == null && result != null &&
                (snapshot == null || result == HotspotResult.OK)
        }
    }

    companion object {
        const val LIFETIME_MS = 60_000L
        const val CLOCK_SKEW_MS = 30_000L
    }
}
