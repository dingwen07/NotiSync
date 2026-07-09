package net.extrawdw.apps.notisync.data

import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

/**
 * A trusted peer device in the group. For v1 the group is simply "me + my peers"; peers are added
 * through QR pairing. Public key material is held base64-encoded for easy persistence/use.
 *
 * [identityPublicKeyB64] is the immutable identity anchor pinned at pairing (first-verified-wins). In
 * NS2 the OPERATIONAL key material rotates per epoch: [hpkePublicKeyB64] is the *current* epoch's HPKE
 * public key and [currentEpoch] names it (≥1 once a key-epoch is held; 0 = the NS1 identity-era single key).
 * A peer is sealable only once a current key-epoch is held — the directory binds [currentEpoch] into each
 * outbound [net.extrawdw.notisync.protocol.crypto.RecipientKey]. The remaining profile fields
 * ([displayName], [platform], [capabilities]) are mutable and converge via DATA_SYNC profile updates;
 * [profileUpdatedAt] is the source-clock stamp of the last applied one (last-writer-wins). New fields
 * default so previously-persisted peer JSON still decodes.
 */
@Serializable
data class Peer(
    val clientId: ClientId,
    val displayName: String,
    val platform: String,
    val identityPublicKeyB64: String,
    val hpkePublicKeyB64: String,
    val addedAt: Long,
    val lastSeenAt: Long = 0L,
    val capabilities: List<Capability> = emptyList(),
    val profileUpdatedAt: Long = 0L,
    /** One of the user's own devices (full mirroring) vs an "other" device in the synced private contact list. */
    val ownDevice: Boolean = true,
    /** NS2: the epoch of [hpkePublicKeyB64] — the recipient HPKE epoch a sender seals to. 0 = NS1-era. */
    val currentEpoch: Int = 0,
)

/** A user-facing record for the Activity screen. Never contains plaintext notification content. */
@Serializable
data class ActivityEvent(
    val kind: Kind,
    val title: String,
    val detail: String,
    val timestamp: Long,
    val deliveryMode: DeliveryMode? = null,
) {
    @Serializable
    enum class Kind { CAPTURED, SENT, RECEIVED, DISMISSED, PAIRED, ROUTE_REPAIR, ERROR }
}

/**
 * Per-app mirroring configuration beyond the on/off allowlist ([AppSelectionRepository]). Persisted as
 * JSON (pkg -> config) in the shared Preferences DataStore by [AppConfigRepository]. Every field defaults
 * to the pre-feature behaviour, so an app with no stored config mirrors exactly as before: no ongoing
 * notifications, no channel suppression. Appended fields MUST keep defaults so previously-persisted JSON
 * still decodes.
 */
@Serializable
data class PerAppConfig(
    /** Mirror this app's ongoing (media / transport / foreground-service) notifications. Default off. */
    val mirrorOngoing: Boolean = false,
    /** Minimum seconds between mirrored UPDATES of one of this app's ongoing notifications. 0 = mirror only
     *  the initial post (no updates). Updates ride the quiet channel (DATA_SYNC / FCM NORMAL), never alerts. */
    val updateIntervalSec: Int = 0,
    /** Also mirror this app's non-media ongoing notifications to iOS devices. Default OFF: an iPhone lacking
     *  com.apple.developer.usernotifications.filtering can't render an ongoing update silently, so every progress
     *  change would re-alert instead of refreshing in place. Media playback has its own iOS opt-in below because
     *  Android media notifications are not always platform-ongoing. */
    val mirrorOngoingToIos: Boolean = false,
    /** Also mirror this app's MediaStyle playback notifications to iOS devices. Default OFF: media rows can update
     *  often, and iOS would show those updates as fresh notifications instead of refreshing silently. */
    val mirrorMediaPlaybackToIos: Boolean = false,
    /** Source channel ids the user disabled for mirroring (default: all channels ON). */
    val disabledChannelIds: Set<String> = emptySet(),
    /** Source channel-group ids the user disabled — suppresses every channel in the group. */
    val disabledGroupIds: Set<String> = emptySet(),
    /** RECEIVER-side: play the ringtone + vibration ([CallRinger]) for this app's mirrored incoming calls.
     *  Default ON. When off, the call still mirrors and pops up — it just doesn't ring on this device. */
    val ringForCalls: Boolean = true,
)

/**
 * A notification channel observed from an app's captures, remembered so the per-app config sheet can list
 * real channels to toggle. Recorded on every capture (a cheap no-op once known); "removing" one only
 * forgets it from the list — it reappears the next time a capture arrives on that channel.
 */
@Serializable
data class SeenChannel(
    val channelId: String,
    val channelName: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
)

/** Snapshot of permission / connectivity state for the diagnostics surface. */
data class DiagnosticsState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val transportConnected: Boolean = false,
    val fcmTokenRegistered: Boolean = false,
    val keyBacking: String = "unknown",
    val clientId: String = "",
)
