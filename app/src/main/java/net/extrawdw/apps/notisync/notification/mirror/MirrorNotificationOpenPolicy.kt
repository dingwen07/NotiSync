package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.peer.trust.RosterDevice
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.TrustStatus

/** Where a mirrored notification's UI-opening tap should leave the user. */
internal enum class MirrorNotificationOpenRoute {
    /** The notification originated in this process (for example, a locally bridged iPhone). */
    LOCAL_ORIGIN,

    /** Open the authenticated screen viewer while the action is relayed to the Android origin. */
    SCREEN_MIRROR,

    /** Retain the existing remote-action plus "check on device" feedback. */
    REMOTE_ONLY,
}

/** UI actions re-evaluate routing when clicked; reply/non-UI actions remain broadcast-only. */
internal fun mirrorNotificationActionUsesUiTrampoline(
    showsUserInterface: Boolean,
    remoteInput: Boolean,
): Boolean = showsUserInterface && !remoteInput

/**
 * Chooses the local UI fallback without weakening the screen requester's own trust checks.
 *
 * A peer must advertise the complete screen-source v1 surface and a hardware encoder, because the
 * Android requester intentionally refuses partial implementations and software encoding. The
 * viewer revalidates these properties when opening; this decision only avoids launching it for a
 * notification origin which is already known to be ineligible.
 */
internal fun mirrorNotificationOpenRoute(
    sourceClientId: ClientId,
    ownClientId: ClientId?,
    sourceDevice: RosterDevice?,
    originPlatform: OriginPlatform? = OriginPlatform.ANDROID_LOCAL,
    trustQuarantined: Boolean = false,
): MirrorNotificationOpenRoute {
    if (sourceClientId == ownClientId) return MirrorNotificationOpenRoute.LOCAL_ORIGIN
    // A remote Android peer may bridge an iPhone notification, but mirroring that peer's Android
    // display would not reveal the UI which the ANCS action opened on the iPhone.
    if (originPlatform != OriginPlatform.ANDROID_LOCAL) {
        return MirrorNotificationOpenRoute.REMOTE_ONLY
    }
    if (trustQuarantined) return MirrorNotificationOpenRoute.REMOTE_ONLY
    if (sourceDevice?.clientId != sourceClientId) return MirrorNotificationOpenRoute.REMOTE_ONLY
    if (
        !sourceDevice.ownDevice ||
        sourceDevice.status != TrustStatus.TRUSTED ||
        !sourceDevice.keyAvailable ||
        !sourceDevice.verified ||
        sourceDevice.currentEpoch <= 0
    ) {
        return MirrorNotificationOpenRoute.REMOTE_ONLY
    }

    val advertised = sourceDevice.capabilities.toSet()
    return if (
        MIRROR_NOTIFICATION_SCREEN_REQUIRED_CAPABILITIES.all(advertised::contains) &&
        MIRROR_NOTIFICATION_SCREEN_ENCODER_CAPABILITIES.any(advertised::contains)
    ) {
        MirrorNotificationOpenRoute.SCREEN_MIRROR
    } else {
        MirrorNotificationOpenRoute.REMOTE_ONLY
    }
}

private val MIRROR_NOTIFICATION_SCREEN_REQUIRED_CAPABILITIES = setOf(
    Capability.CAPABILITY_ROUTING_V1,
    Capability.SCREEN_MIRROR_SOURCE_V1,
    Capability.SCREEN_MIRROR_CONTROL_V1,
    Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
)

private val MIRROR_NOTIFICATION_SCREEN_ENCODER_CAPABILITIES = setOf(
    Capability.SCREEN_MIRROR_ENCODER_H264_HW,
    Capability.SCREEN_MIRROR_ENCODER_H265_HW,
    Capability.SCREEN_MIRROR_ENCODER_AV1_HW,
)
