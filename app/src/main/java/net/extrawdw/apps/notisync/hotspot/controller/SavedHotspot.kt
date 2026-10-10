package net.extrawdw.apps.notisync.hotspot.controller

import net.extrawdw.notisync.protocol.HotspotSnapshot

/** Last successfully received configuration. It remains useful while the hotspot device is offline. */
internal data class SavedHotspot(
    val ssid: String,
    val psk: String?,
    val securityType: Int,
    val hiddenSsid: Boolean,
    val updatedAt: Long,
) {
    // SoftApConfiguration's public security-type registry, not WifiConfiguration's different ids.
    val usable: Boolean get() = ssid.isNotEmpty() && ssid != "<unknown ssid>" &&
        ssid.toByteArray(Charsets.UTF_8).size <= 32 && '\u0000' !in ssid && when (securityType) {
            0, 4, 5 -> psk == null
            1, 2 -> psk != null && psk.length in 8..63 && psk.all { it.code in 32..126 }
            3 -> psk != null && psk.length in 1..63 && psk.all { it.code in 32..126 }
            else -> false
        }

    /** Android Settings uses WPA for PSK/SAE transition, SAE for WPA3-only, and nopass for open/OWE. */
    fun qrPayload(): String? {
        if (!usable) return null
        val security = when (securityType) { 1, 2 -> "WPA"; 3 -> "SAE"; else -> "nopass" }
        return buildString {
            append("WIFI:T:").append(security).append(";S:").append(escapeWifiQr(ssid)).append(';')
            psk?.let { append("P:").append(escapeWifiQr(it)).append(';') }
            append("H:").append(hiddenSsid).append(";;")
        }
    }

    override fun toString(): String = "SavedHotspot(securityType=$securityType, updatedAt=$updatedAt, credentials=redacted)"

    companion object {
        fun from(snapshot: HotspotSnapshot, updatedAt: Long): SavedHotspot? {
            return SavedHotspot(
                snapshot.ssid ?: return null, snapshot.psk, snapshot.securityType ?: return null,
                snapshot.hiddenSsid, updatedAt,
            ).takeIf { it.usable }
        }
    }
}

internal fun escapeWifiQr(value: String): String = buildString {
    value.forEach { character ->
        if (character in "\\;,:\"") append('\\')
        append(character)
    }
}
