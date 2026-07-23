package net.extrawdw.apps.notisync.ui

import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.TrustStatus

/**
 * Whether the Devices UI may offer an outbound Android screen session for [this] peer.
 *
 * Capabilities are routing authority, not a liveness signal: once a source advertises the screen
 * protocol, temporary Shizuku/network/encoder failures are reported by the session attempt instead
 * of making this action disappear. The identity checks match the secure-channel recipient rules.
 */
internal fun RosterDevice.supportsScreenMirrorRequest(): Boolean {
    if (!ownDevice || status != TrustStatus.TRUSTED || !keyAvailable || !verified || currentEpoch <= 0) {
        return false
    }
    val advertised = capabilities.toSet()
    return SCREEN_MIRROR_REQUIRED_CAPABILITIES.all(advertised::contains) &&
        SCREEN_MIRROR_ENCODER_CAPABILITIES.any(advertised::contains)
}

private val SCREEN_MIRROR_REQUIRED_CAPABILITIES = setOf(
    Capability.CAPABILITY_ROUTING_V1,
    Capability.SCREEN_MIRROR_SOURCE_V1,
    Capability.SCREEN_MIRROR_CONTROL_V1,
    Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
)

private val SCREEN_MIRROR_ENCODER_CAPABILITIES = setOf(
    Capability.SCREEN_MIRROR_ENCODER_H264_HW,
    Capability.SCREEN_MIRROR_ENCODER_H265_HW,
    Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
)
