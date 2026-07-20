package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.CborLabel

@Serializable
enum class MirrorImportance { MIN, LOW, DEFAULT, HIGH, NONE } // NONE = blocked; appended to keep CBOR ordinals stable

@Serializable
enum class MirrorCategory {
    MESSAGE, EMAIL, CALL, ALARM, EVENT, REMINDER, SOCIAL, PROGRESS,
    TRANSPORT, SERVICE, STATUS, ERROR, NAVIGATION, NONE,
}

/** The producer declares the rendering style; the consumer reconstructs it, never guesses. Append-only —
 *  keep CBOR ordinals stable. NOTE: an unknown enum value FAILS CBOR decode on an older consumer (kotlinx
 *  throws on an unknown enum name even with ignoreUnknownKeys), so adding a value is a BREAKING change that
 *  requires every client to update. DECORATED_*_CUSTOM_VIEW can't carry the source's custom RemoteViews
 *  cross-device, so the consumer renders decorated-media as MEDIA and a plain decorated view as standard. */
@Serializable
enum class NotificationStyle {
    DEFAULT, BIG_TEXT, BIG_PICTURE, MESSAGING, INBOX, MEDIA, CALL,
    DECORATED_CUSTOM_VIEW, DECORATED_MEDIA_CUSTOM_VIEW,
}

/** The kind of call a [NotificationStyle.CALL] notification (Android CallStyle) represents. Append-only. */
@Serializable
enum class CallType { INCOMING, ONGOING, SCREENING }

/** Android notification group alert behavior. Defaulting to CHILDREN preserves pre-field mirror behavior for
 *  older producers and non-Android captures: receiver-created summaries stay quiet, while child rows decide
 *  alerts. Current Android producers set the source value explicitly. Append-only if expanded. */
@Serializable
enum class GroupAlertBehavior { ALL, SUMMARY, CHILDREN }

/** The graphic's logical slot. Drives placement on the consumer and is bound into the asset AAD.
 *  Append-only — the ordinal is bound into the asset AAD, so existing values must never shift.
 *  APP_ICON delivers the *source app's launcher icon* so a consumer without that app installed can
 *  still show it (Android captures; future iOS). */
@Serializable
enum class AssetRole { LARGE_ICON, BIG_PICTURE, AVATAR, INLINE_IMAGE, APP_ICON }

/** Where a [CapturedNotification] was captured. Drives consumer-side rendering choices (e.g. an iOS
 *  mirror has no rich graphics and shows the bridged iPhone's name). Append-only — keep ordinals stable. */
@Serializable
enum class OriginPlatform { ANDROID_LOCAL, IOS_ANCS }

/**
 * A reference to a *private* notification graphic (contact photo, avatar, big picture). It lives
 * ONLY inside the end-to-end-encrypted body — never in a server-visible envelope field — so the
 * broker can never learn [assetHash] (the plaintext content address) or who the user is messaging.
 *
 * The bytes are uploaded to the broker as an opaque AEAD blob keyed by random ([sourceClientId],
 * [assetId]); the consumer fetches that blob, decrypts it with [assetKey], and verifies the result
 * against [assetHash]. [assetHash] doubles as the receiver's local-cache key (content dedup).
 */
@Serializable
data class PrivateAssetRef(
    @CborLabel(0) val role: AssetRole,
    /** Hex SHA-256 of the *plaintext* bytes: integrity check on open + local cache key. Body-only. */
    @CborLabel(1) val assetHash: String,
    @CborLabel(2) val mimeType: String,
    /** Plaintext byte length, bound into the AEAD AAD. */
    @CborLabel(3) val sizeBytes: Int,
    @CborLabel(4) val sourceClientId: ClientId,
    /** Opaque random server key (Base32 of 24 bytes); never derived from content/package/role. */
    @CborLabel(5) val assetId: String,
    /** Per-asset AES-256-GCM key, delivered only inside this E2E body. */
    @CborLabel(6) @ByteString val assetKey: ByteArray,
    /** The suite that ENCRYPTED the blob; the consumer rebuilds the AAD from this, not the envelope. */
    @CborLabel(7) @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
)

/**
 * The additional authenticated data bound into a private asset's AEAD. CBOR-encoded (length-
 * delimited, unambiguous, byte-identical on Android + JVM) rather than string-joined. Field order
 * is load-bearing — both ends must build the identical struct from the same [PrivateAssetRef].
 */
@Serializable
data class AssetAad(
    @CborLabel(0) val suite: String,
    @CborLabel(1) val sourceClientId: ClientId,
    @CborLabel(2) val assetId: String,
    @CborLabel(3) val mimeType: String,
    @CborLabel(4) val sizeBytes: Int,
    @CborLabel(5) val role: AssetRole,
)

/**
 * One mirrorable action button of a [CapturedNotification]. Carries only what a consumer needs to
 * *render* the button and what the origin needs to *locate* the real action again — never the
 * PendingIntent, icon, or anything executable. The producer filters what it exports (Android skips
 * contextual, auth-gated, and intent-less actions); a consumer renders the list as-is and reports a
 * press back to the origin as an [ActionEvent].
 */
@Serializable
data class NotificationAction(
    /**
     * Origin-scoped id, echoed back verbatim in [ActionEvent.actionIndex]: the position in the
     * origin's raw `Notification.actions` array (ANDROID_LOCAL) or the ANCS ActionID (IOS_ANCS:
     * 0 = positive, 1 = negative). Only the origin interprets it.
     */
    @CborLabel(0) val index: Int,
    @CborLabel(1) val title: String,
    /** True when the action accepts free-form text (Android RemoteInput) — mirror it as a reply field. */
    @CborLabel(2) val remoteInput: Boolean = false,
    /** Placeholder for the reply field (RemoteInput.getLabel()) when the producer has one. */
    @CborLabel(3) val remoteInputLabel: String? = null,
    /** Android `Notification.Action.SEMANTIC_ACTION_*` constant (0 = none). Rendering hint only. */
    @CborLabel(4) val semanticAction: Int = 0,
    /** Origin hint that performing this opens UI *there* (Android `showsUserInterface`) — the
     *  consumer should surface "check on <origin device>" feedback after sending the event. */
    @CborLabel(5) val showsUserInterface: Boolean = false,
    /** Optional origin-issued generation for replay-safe local integrations such as NotiSync Run. */
    @CborLabel(6) val actionGeneration: Long? = null,
    /** Opaque origin-issued capability, echoed only in the encrypted [ActionEvent]. */
    @CborLabel(7) val actionToken: String? = null,
)

/** One message in a MessagingStyle conversation. A null [sender] denotes the local user ("you"). */
@Serializable
data class ConversationMessage(
    @CborLabel(0) val sender: String? = null,
    @CborLabel(1) val text: String,
    @CborLabel(2) val timestamp: Long,
    @CborLabel(3) val avatar: PrivateAssetRef? = null,
    @CborLabel(4) val dataMimeType: String? = null,
    @CborLabel(5) val data: PrivateAssetRef? = null,
)

/**
 * One app-specific media transport button — a `PlaybackState` custom action (star / like / shuffle / repeat
 * / thumbs) that isn't a standard control. [action] is the opaque id echoed back so the origin can run it on
 * the real session; [name] is its display / accessibility label and drives the consumer's best-effort icon
 * choice — the source app's own icon is a drawable resource in ITS package and can't cross devices, so the
 * consumer heuristic-maps common ones to bundled icons and shows a generic icon otherwise.
 */
@Serializable
data class MediaCustomAction(
    @CborLabel(0) val action: String,
    @CborLabel(1) val name: String = "",
)

/**
 * Progress attached to a promoted ongoing notification. [current] and [total] intentionally use
 * [Long] so producers can report byte/item counts without narrowing them to Android's `Int`-sized
 * progress API. A consumer clamps or scales the pair for its native renderer. When
 * [indeterminate] is true, [current] and [total] are ignored; otherwise a determinate presentation
 * requires both values and a positive [total].
 */
@Serializable
data class NotificationProgress(
    @CborLabel(0) val current: Long? = null,
    @CborLabel(1) val total: Long? = null,
    @CborLabel(2) val indeterminate: Boolean = false,
)

/**
 * Optional presentation metadata for Android 16 promoted ongoing ("Live Update") notifications.
 * It is nested and nullable so older peers ignore one unknown field, while notifications from
 * older producers decode with no change in behavior. Non-Android consumers may ignore this hint.
 */
@Serializable
data class NotificationLiveUpdate(
    @CborLabel(0) val requestPromotedOngoing: Boolean = false,
    @CborLabel(1) val progress: NotificationProgress? = null,
    /** Very short text for Android's promoted-notification chip (Android recommends <= 7 chars). */
    @CborLabel(2) val shortCriticalText: String? = null,
)

/**
 * A normalized, platform-neutral notification — the plaintext body that gets CBOR-encoded and
 * end-to-end encrypted. The producer captures as much useful structure as the platform allows and
 * the user permits; the consumer faithfully reconstructs it.
 */
@Serializable
data class CapturedNotification(
    @CborLabel(0) val sourceClientId: ClientId,
    /** Stable per-source identifier derived from the original notification key (for dedup/dismissal). */
    @CborLabel(1) val sourceKey: String,
    @CborLabel(2) val packageName: String,
    @CborLabel(3) val appLabel: String,
    /** Private graphics (body-only). Small icons are never transferred — the consumer renders a
     *  local bundled/generic icon — so there is no small-icon ref here. */
    @CborLabel(4) val largeIcon: PrivateAssetRef? = null,
    @CborLabel(5) val bigPicture: PrivateAssetRef? = null,
    @CborLabel(6) val title: String? = null,
    @CborLabel(7) val text: String? = null,
    @CborLabel(8) val bigText: String? = null,
    @CborLabel(9) val subText: String? = null,
    @CborLabel(10) val style: NotificationStyle = NotificationStyle.DEFAULT,
    @CborLabel(11) val conversationTitle: String? = null,
    @CborLabel(12) val isGroupConversation: Boolean = false,
    @CborLabel(13) val messages: List<ConversationMessage> = emptyList(),
    @CborLabel(14) val category: MirrorCategory = MirrorCategory.NONE,
    @CborLabel(15) val importance: MirrorImportance = MirrorImportance.DEFAULT,
    @CborLabel(16) val postTime: Long,
    /** Source app group key, or a system override group key when the app did not supply one. */
    @CborLabel(17) val groupKey: String? = null,
    /** True when the source notification is the summary row for [groupKey]. */
    @CborLabel(18) val isGroupSummary: Boolean = false,
    /** Source Android `Notification.getGroupAlertBehavior()`. Some apps, notably WhatsApp, keep the child
     *  conversation row `FLAG_ONLY_ALERT_ONCE` but put the audible alert on the group summary
     *  (`SUMMARY`). Consumers need both fields: [onlyAlertOnce] remains the raw source flag, while this
     *  decides whether a receiver-created summary should be the alerting post. */
    @CborLabel(19) val groupAlertBehavior: GroupAlertBehavior = GroupAlertBehavior.CHILDREN,
    @CborLabel(20) val isOngoing: Boolean = false,
    @CborLabel(21) val isClearable: Boolean = true,
    @CborLabel(22) val isForegroundService: Boolean = false,
    /** True if the platform redacted sensitive content (Android 15+); consumer should signal it. */
    @CborLabel(23) val sensitiveRedacted: Boolean = false,

    // --- Channel / channel-group mirroring (all best-effort; null/false on older producers) ---
    /** Source NotificationChannel id (Ranking.getChannel().getId()). */
    @CborLabel(24) val channelId: String? = null,
    /** Source channel user-visible name; may be redacted for a plain listener. */
    @CborLabel(25) val channelName: String? = null,
    /** Source channel's group id (NotificationChannel.getGroup()) — an id, not a name. */
    @CborLabel(26) val channelGroupId: String? = null,
    /** Source channel group display name; only available with a CompanionDeviceManager association. */
    @CborLabel(27) val channelGroupName: String? = null,
    /** Source channel-level importance/mute; drives the mirrored channel's importance. */
    @CborLabel(28) val channelImportance: MirrorImportance? = null,
    /** Whether the mirrored channel should vibrate — mirrored from the source channel's own vibration setting
     *  (Android: NotificationChannel.shouldVibrate(); importance alone never enables vibration). The OS only honors
     *  enableVibration() at channel creation, so this takes effect when the mirror first creates the channel. False
     *  for sources without a channel (iOS/ANCS), which then alert per importance but don't vibrate. */
    @CborLabel(29) val shouldVibrate: Boolean = false,
    /** Whether the source NotificationChannel has None sound (NotificationChannel.getSound() == null) */
    @CborLabel(30) val channelSilent: Boolean? = null,

    // --- Conversation notifications ---
    @CborLabel(31) val isConversation: Boolean = false,
    /** Source conversation shortcut id (Notification.getShortcutId()). */
    @CborLabel(32) val shortcutId: String? = null,
    /** Source conversation channel id (NotificationChannel.getConversationId()). */
    @CborLabel(33) val conversationId: String? = null,
    /** Source conversation channel's parent channel id (NotificationChannel.getParentChannelId()). */
    @CborLabel(34) val parentChannelId: String? = null,

    // --- Cross-platform app icon + capture origin (all appended; default = an Android-local capture) ---
    /** The source app's launcher icon as a private asset, so a consumer that doesn't have the app
     *  installed still renders the real icon. Body-only, content-addressed, deduped like any asset. */
    @CborLabel(35) val appIcon: PrivateAssetRef? = null,
    /** Which platform captured this. ANDROID_LOCAL for a NotificationListener capture; IOS_ANCS for a
     *  notification bridged from a paired iPhone over ANCS. */
    @CborLabel(36) val originPlatform: OriginPlatform = OriginPlatform.ANDROID_LOCAL,
    /** For a bridged capture, the *originating* device's name (e.g. the iPhone's Bluetooth name) — used
     *  for the mirror's group label / "via" line instead of the bridging device's own name. */
    @CborLabel(37) val originDeviceName: String? = null,
    /** For an IOS_ANCS capture, the raw iOS bundle identifier (the ANCS App Identifier), retained for
     *  icon/display resolution alongside the best-match Android [packageName]. */
    @CborLabel(38) val iosBundleId: String? = null,
    /** Stable id of the *originating* device (e.g. a hashed iPhone address) for a bridged capture. Lets the
     *  consumer group/channel notifications per origin: once one client bridges another device, [sourceClientId]
     *  alone no longer identifies the source (its own Android vs the paired iPhone). Null for a local capture. */
    @CborLabel(39) val originDeviceId: String? = null,
    /** The source's raw per-post `FLAG_ONLY_ALERT_ONCE` (Android: `Notification.flags`): an update to this
     *  particular notification key should usually not re-alert while it is still showing. It is not the whole
     *  alert contract by itself: apps may keep a child conversation row `ONLY_ALERT_ONCE` and route the audible
     *  alert through an unsilenced group summary, represented by [groupAlertBehavior] plus [isGroupSummary].
     *  Appended with a default so an older producer (no field) decodes unchanged. */
    @CborLabel(40) val onlyAlertOnce: Boolean = false,

    // --- Action mirroring (appended; empty/false from an older producer decodes unchanged) ---
    /** Mirrorable action buttons, in origin display order. Empty = the source has none the producer
     *  is willing to export (or an old producer). A non-empty list implies the producer handles
     *  [MessageType.ACTION] events for this notification. */
    @CborLabel(41) val actions: List<NotificationAction> = emptyList(),
    /** True when the origin can open this notification's UI on request — it has a content intent
     *  and the producer handles [MessageType.ACTION] — so a consumer may offer tap-to-open-on-origin
     *  (an [ActionKind.TAP] event). False for ANCS bridges (no such ANCS command) and old producers. */
    @CborLabel(42) val hasContentIntent: Boolean = false,

    // --- Rich notification styles (all appended; empty/null/false from an older producer decodes unchanged) ---
    /** InboxStyle lines (`Notification.EXTRA_TEXT_LINES`) — populated for [NotificationStyle.INBOX]. */
    @CborLabel(43) val inboxLines: List<String> = emptyList(),
    /** MediaStyle: the source declared a colorized (accent-tinted) notification (`EXTRA_COLORIZED`). */
    @CborLabel(44) val isColorized: Boolean = false,
    /** MediaStyle compact-view action selection, in the SOURCE's raw action index space (the same space as
     *  [NotificationAction.index]); the consumer maps each to its exported action position. */
    @CborLabel(45) val mediaCompactActionIndices: List<Int> = emptyList(),
    /** CallStyle: the call kind — non-null only for [NotificationStyle.CALL]. */
    @CborLabel(46) val callType: CallType? = null,
    /** CallStyle: the caller's display name (the CallStyle `Person`). The caller photo, when present, rides
     *  the existing [largeIcon] asset. */
    @CborLabel(47) val callerName: String? = null,
    /** CallStyle: optional caller-verification text (e.g. a STIR/SHAKEN attestation line). */
    @CborLabel(48) val callVerificationText: String? = null,
    /** CallStyle answer / decline / hang-up actions, as [NotificationAction.index] values into [actions]: the
     *  consumer builds the CallStyle buttons from the matching mirrored actions (pressing one relays an
     *  [ActionKind.PERFORM] back to the origin, which answers/declines/hangs up the real call). Null = absent. */
    @CborLabel(49) val callAnswerIndex: Int? = null,
    @CborLabel(50) val callDeclineIndex: Int? = null,
    @CborLabel(51) val callHangUpIndex: Int? = null,
    /** The source notification's accent color (`Notification.color`), or null when unset (COLOR_DEFAULT).
     *  Applied on the consumer for MediaStyle colorization and the CallStyle accent. */
    @CborLabel(52) val accentColor: Int? = null,

    // --- MediaStyle playback state (appended; null from an older producer). Captured from the source's
    // MediaSession so the consumer can rebuild a real media session — system-drawn transport controls,
    // play/pause state, and a seekbar — with NO foreground service and NO sound. ---
    /** True if the source MediaSession is PLAYING (else paused/stopped); null = not media / no session read. */
    @CborLabel(53) val mediaIsPlaying: Boolean? = null,
    /** Current playback position (ms) from the source PlaybackState; drives the consumer's seekbar thumb. */
    @CborLabel(54) val mediaPositionMs: Long? = null,
    /** Track duration (ms) from the source MediaMetadata; the seekbar's extent. */
    @CborLabel(55) val mediaDurationMs: Long? = null,
    /** The source PlaybackState action bitmask (`PlaybackState.ACTION_*`, whose values equal AndroidX
     *  `PlaybackStateCompat.ACTION_*`); the consumer rebuilds the transport controls it declares. */
    @CborLabel(56) val mediaActions: Long? = null,
    /** App-specific media buttons (PlaybackState custom actions: star / like / shuffle / …). Relayed
     *  best-effort — the press runs on the origin's session, but the consumer renders a heuristic icon
     *  (the source app's own icon can't cross devices), so uncommon ones show a generic icon. */
    @CborLabel(57) val mediaCustomActions: List<MediaCustomAction> = emptyList(),
    /** Source playback volume at capture time (`MediaController.PlaybackInfo` — the session's audio stream
     *  for local playback, its VolumeProvider when the source is itself casting). Lets the consumer expose
     *  a live volume surface (volume keys / system slider) relaying [MediaCommand.SET_VOLUME] /
     *  [MediaCommand.ADJUST_VOLUME] back. Null = unknown → the consumer renders fixed volume. */
    @CborLabel(58) val mediaVolumeCurrent: Int? = null,
    /** Max volume index of [mediaVolumeCurrent]'s scale. */
    @CborLabel(59) val mediaVolumeMax: Int? = null,
    /** How the source volume can be driven: `VolumeProvider.VOLUME_CONTROL_*` (0 fixed / 1 relative /
     *  2 absolute). Local playback reports absolute (stream volume); AMS now-playing reports relative when
     *  iOS advertises VolumeUp/Down remote commands. */
    @CborLabel(60) val mediaVolumeControl: Int? = null,

    // --- Delivery hint (appended; false from an older producer) ---
    /** True when this [MessageType.NOTIFICATION] is not a fresh post but an in-place UPDATE of an
     *  already-shown ongoing mirror — a media notification's DRAMATIC playback change (track change /
     *  play↔pause). It rides the alerting transport (NOTIFICATION at [Urgency.HIGH], FCM HIGH / APNs
     *  immediate) so a peer's media-controls card flips at once instead of coasting on the NORMAL-priority
     *  quiet channel, but the consumer must render it SILENTLY in place (no second alert) with the SAME
     *  last-writer-wins-on-[postTime] ordering as a [DataSyncKind.NOTIFICATION] quiet update. Minor position
     *  ticks still ride that quiet DATA_SYNC channel; this flag is only set for the discrete, meaningful
     *  changes. False for any first post / plain capture, which alerts normally. */
    @CborLabel(61) val silentUpdate: Boolean = false,

    // --- Promoted ongoing / Live Update presentation (appended; null from an older producer) ---
    /** Android 16 Live Update metadata. Other platforms deliberately ignore this optional hint. */
    @CborLabel(62) val liveUpdate: NotificationLiveUpdate? = null,
)

/** Idempotent dismissal: removing the mirrored notification keyed by ([sourceClientId], [sourceKey]). */
@Serializable
data class DismissEvent(
    @CborLabel(0) val sourceClientId: ClientId,
    @CborLabel(1) val sourceKey: String,
    @CborLabel(2) val dismissedAt: Long,
)

/** What the user did on a mirrored notification. Append-only — keep CBOR ordinals stable. NOTE: an unknown
 *  enum value FAILS CBOR decode on an older origin (kotlinx throws even with ignoreUnknownKeys); the receive
 *  path guards this (SecureChannel.deliver try/catch), so an old origin simply drops a MEDIA event instead of
 *  crashing — the command just no-ops until it updates. */
@Serializable
enum class ActionKind {
    /** Pressed one of the mirrored action buttons ([CapturedNotification.actions]). */
    PERFORM,

    /** Tapped the notification body — the origin fires its content intent ("open it over there"). */
    TAP,

    /** Pressed a transport control on a mirrored MEDIA session — the origin runs [ActionEvent.mediaCommand]
     *  on the real source MediaSession. */
    MEDIA,
}

/** A media transport command relayed for [ActionKind.MEDIA]: the consumer's mirrored media session got a
 *  control press, and the origin replays it on the live source MediaSession. Append-only — an old origin
 *  drops an event carrying an unknown value (same decode guard as [ActionKind]). */
@Serializable
enum class MediaCommand { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, STOP, SEEK, CUSTOM, SET_VOLUME, ADJUST_VOLUME }

/**
 * The body of a [MessageType.ACTION] envelope: a user acted on a *mirrored* notification and the
 * origin should replay it on the real one — fire an action's PendingIntent (with reply text fed
 * into its RemoteInput), perform the ANCS action, or open the content intent on a TAP.
 *
 * Always unicast to [sourceClientId] (the only peer that can perform it); best-effort and NOT
 * idempotent — the origin performs it at most once and simply drops it when the notification is
 * already gone, its action row no longer matches, or the bridged iPhone is unreachable.
 */
@Serializable
data class ActionEvent(
    @CborLabel(0) val sourceClientId: ClientId,
    @CborLabel(1) val sourceKey: String,
    @CborLabel(2) val kind: ActionKind,
    /** The pressed action's [NotificationAction.index] — [ActionKind.PERFORM] only. */
    @CborLabel(3) val actionIndex: Int = 0,
    /** The pressed action's mirrored title, echoed back so the origin can verify the action row
     *  hasn't shifted since capture (a stale press must not fire a different button). */
    @CborLabel(4) val actionTitle: String? = null,
    /** Free-form reply text for a [NotificationAction.remoteInput] action. */
    @CborLabel(5) val remoteInputText: String? = null,
    /** [ActionKind.MEDIA]: the transport command to replay on the origin's live source MediaSession. */
    @CborLabel(6) val mediaCommand: MediaCommand? = null,
    /** Seek target (ms) for [MediaCommand.SEEK]. */
    @CborLabel(7) val mediaSeekMs: Long? = null,
    /** The custom-action id for [MediaCommand.CUSTOM] (a [MediaCustomAction.action]) — run via
     *  `sendCustomAction` on the origin's session. */
    @CborLabel(8) val mediaCustomAction: String? = null,
    /** [MediaCommand.SET_VOLUME]: absolute target index on the source's scale ([CapturedNotification
     *  .mediaVolumeMax]). [MediaCommand.ADJUST_VOLUME]: a ±1 step. The origin applies it through its
     *  MediaController — stream volume for local playback, the VolumeProvider when the source casts. */
    @CborLabel(9) val mediaVolume: Int? = null,
    /** Source-clock time of the user's press. */
    @CborLabel(10) val actedAt: Long,
    /** Echo of [NotificationAction.actionGeneration], when supplied by the origin. */
    @CborLabel(11) val actionGeneration: Long? = null,
    /** Echo of [NotificationAction.actionToken], when supplied by the origin. */
    @CborLabel(12) val actionToken: String? = null,
)

/** Direction of an [AssetSync] item in the private-asset repair flow. */
@Serializable
enum class AssetSyncKind {
    /** Consumer → provider: "I couldn't fetch/decrypt this asset; please re-provide it." */
    ASSET_MISSING,

    /** Provider → consumer: "I re-uploaded it; here's a fresh ref — fetch again." */
    ASSET_READY,
}

/**
 * Repair of missing private graphics: end-to-end-encrypted, low-urgency, relayed by the broker as
 * opaque ciphertext. Carried inside a [DataSync] (see [DataSyncKind.ASSET]).
 */
@Serializable
data class AssetSync(
    @CborLabel(0) val kind: AssetSyncKind,
    @CborLabel(1) val items: List<AssetSyncItem>,
)

/** One asset in an [AssetSync] batch, keyed by its plaintext [assetHash] (safe inside the E2E body). */
@Serializable
data class AssetSyncItem(
    @CborLabel(0) val assetHash: String,
    /** The (now-unfetchable) id the consumer tried — ASSET_MISSING only. */
    @CborLabel(1) val assetId: String? = null,
    /** A fresh ref the consumer can re-fetch from — ASSET_READY only. */
    @CborLabel(2) val ref: PrivateAssetRef? = null,
)

/** Selects which sub-body a [DataSync] carries. Append-only — keep CBOR ordinals stable (the wire encodes
 *  the serial NAME; an unknown value throws on decode and is dropped by the peer's guarded DataSync decode). */
@Serializable
enum class DataSyncKind { ASSET, PROFILE, TRUST, CARD, FILTER, NOTIFICATION, RUN, SCREEN_MIRRORING }

/** Lifecycle operation carried by [ScreenMirrorSync]. */
@Serializable
enum class ScreenMirrorAction { REQUEST, STATUS, CANCEL, END }

/** Video codec selected by the requester before a session is offered to its source. */
@Serializable
enum class ScreenMirrorCodec {
    H264,
    H265,
    AV1;

    /** The source capability which proves this codec can be encoded in hardware. */
    fun requiredEncoderCapability(): Capability = when (this) {
        H264 -> Capability.SCREEN_MIRROR_ENCODER_H264_HW
        H265 -> Capability.SCREEN_MIRROR_ENCODER_H265_HW
        AV1 -> Capability.SCREEN_MIRROR_ENCODER_AV1_HW
    }
}

/** Canonical screen protocol v1 quality bounds shared by requesters and sources. */
object ScreenMirrorQualityLimits {
    const val MIN_DIMENSION = 240
    const val MAX_DIMENSION = 8_192
    const val MIN_FPS = 1
    const val MAX_FPS = 240
    const val MIN_BITRATE_BPS = 128_000
    const val MAX_BITRATE_BPS = 100_000_000
}

/** Status details for [ScreenMirrorAction.STATUS] and the terminal [ScreenMirrorAction.END]. */
@Serializable
enum class ScreenMirrorStatus {
    CONNECTING,
    READY,
    UNAUTHORIZED,
    EXPIRED,
    BUSY,
    SHIZUKU_UNAVAILABLE,
    CODEC_UNAVAILABLE,
    CODEC_START_FAILED,
    TRANSPORT_FAILED,
    ENDED,
}

/**
 * One requester-listener location. [kind] deliberately remains an open string registry so transports
 * such as Wi-Fi Aware can be added without making an older DATA_SYNC decoder reject the whole request.
 * `LAN_TCP` uses [host] + [port]; `DNS_SD` uses [serviceName] and may also carry [port].
 */
@Serializable
data class ScreenMirrorConnectionCandidate(
    @CborLabel(0) val kind: String,
    @CborLabel(1) val host: String? = null,
    @CborLabel(2) val port: Int? = null,
    @CborLabel(3) val serviceName: String? = null,
    /** Optional interface/scope hint (for example an IPv6 zone id); never an authentication input. */
    @CborLabel(4) val interfaceName: String? = null,
) {
    companion object {
        const val LAN_TCP = "LAN_TCP"
        const val DNS_SD = "DNS_SD"
    }
}

/**
 * Flat screen-session rendezvous body. REQUEST-only secrets and choices are nullable/defaulted so STATUS,
 * CANCEL, and END do not repeat them. All fields are inside the signed, E2E-encrypted DATA_SYNC envelope.
 * Implementations must validate the action-specific required fields before using them.
 */
@Serializable
data class ScreenMirrorSync(
    @CborLabel(0) val action: ScreenMirrorAction,
    @CborLabel(1) @EncodeDefault(ALWAYS) val protocolVersion: Int = 1,
    @CborLabel(2) val sessionId: String,
    @CborLabel(3) val requesterPeerId: ClientId,
    @CborLabel(4) val sourcePeerId: ClientId,
    @CborLabel(5) val issuedAt: Long,
    @CborLabel(6) val expiresAt: Long? = null,
    @CborLabel(7) @ByteString val routingToken: ByteArray? = null,
    @CborLabel(8) @ByteString val masterPsk: ByteArray? = null,
    @CborLabel(9) val codec: ScreenMirrorCodec? = null,
    @CborLabel(10) val requestControl: Boolean = false,
    @CborLabel(11) val requestClipboard: Boolean = false,
    @CborLabel(12) val maxDimension: Int? = null,
    @CborLabel(13) val maxFps: Int? = null,
    @CborLabel(14) val videoBitrateBps: Int? = null,
    @CborLabel(15) val candidates: List<ScreenMirrorConnectionCandidate> = emptyList(),
    @CborLabel(16) val status: ScreenMirrorStatus? = null,
    /** Bounded human-readable diagnostic; never use it for protocol control flow. */
    @CborLabel(17) val detail: String? = null,
) {
    /** Exact declarations required from the source for this request. */
    fun requiredSourceCapabilities(): Set<Capability> {
        if (action != ScreenMirrorAction.REQUEST) return emptySet()
        val selectedCodec = codec ?: return emptySet()
        return buildSet {
            add(Capability.CAPABILITY_ROUTING_V1)
            add(Capability.SCREEN_MIRROR_SOURCE_V1)
            // Screen protocol v1 is routed only to the complete MVP source implementation.
            // Per-session feature flags may disable use, but never weaken routing authority.
            add(Capability.SCREEN_MIRROR_CONTROL_V1)
            add(Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1)
            add(selectedCodec.requiredEncoderCapability())
        }
    }
}

/**
 * One suppression rule a client asks a peer (the notification *source*) to apply to deliveries bound
 * for the requester. A notification the source captures matches the rule when its capture origin, app,
 * and (Android only) channel match — the same fields a source-side filter keys on.
 */
@Serializable
data class NotificationFilterRule(
    /** Which capture origin this rule targets. ANDROID_LOCAL = the source's own NotificationListener
     *  captures; IOS_ANCS = notifications the source bridges from a paired iPhone over ANCS. */
    @CborLabel(0) val originPlatform: OriginPlatform,
    /** App scope: an Android package name (ANDROID_LOCAL) or an iOS bundle id (IOS_ANCS). null = every
     *  app of this origin — a device-level master switch for that origin on the target. */
    @CborLabel(1) val appId: String? = null,
    /** Channel scope within [appId] (ANDROID_LOCAL only; iOS has no channels). null = all channels of
     *  [appId]. Ignored when [appId] is null. */
    @CborLabel(2) val channelId: String? = null,
)

/**
 * A client's request that a peer (the notification *source*) stop delivering matching notifications to
 * the requester. Carried over [DataSyncKind.FILTER]. It is a FULL snapshot: the receiver REPLACES the
 * requester's prior filter (last-writer-wins on [updatedAt]); an empty [rules] clears it. The requester
 * is the envelope signer, so the receiver keys the stored filter by that signer id and, when a captured
 * notification matches, drops the requester from the recipient list — the notification never reaches it.
 *
 * Mainly used by the iOS client (its Notification Service Extension cannot suppress an APNs push
 * locally); an Android peer honors it when forwarding its own captures. Per design, IOS_ANCS rules are
 * NOT keyed by the bridged iPhone's `originDeviceId` — each ANCS-bridged app is a master switch per
 * trusted (bridging) device.
 */
@Serializable
data class FilterSync(
    @CborLabel(0) val rules: List<NotificationFilterRule>,
    /** Source-clock time the requester built this snapshot; the receiver resolves races last-writer-wins. */
    @CborLabel(1) val updatedAt: Long,
)

/**
 * The body of a [MessageType.DATA_SYNC] envelope: the group's end-to-end-encrypted data/control
 * channel, which the broker only ever relays as opaque ciphertext. Most kinds use low urgency (FCM
 * NORMAL); selected [DataSyncKind.RUN] lifecycle edges may use HIGH only for explicitly capable
 * recipients. [kind] selects the populated sub-body — a flat discriminated struct (like [WsMessage])
 * rather than polymorphic serialization, so it encodes byte-identically on Android + JVM and a new
 * variant never has to be told apart by a fragile try-decode.
 */
@Serializable
data class DataSync(
    @CborLabel(0) val kind: DataSyncKind,
    /** Private-graphic repair — populated iff [kind] == [DataSyncKind.ASSET]. */
    @CborLabel(1) val asset: AssetSync? = null,
    /** A peer's mutable profile changed — populated iff [kind] == [DataSyncKind.PROFILE]. */
    @CborLabel(2) val profile: ProfileUpdate? = null,
    /** A peer's trust roster — populated iff [kind] == [DataSyncKind.TRUST]. */
    @CborLabel(3) val trust: TrustTable? = null,
    /** A self-authenticating delivery (a client card and/or a key-epoch) — iff [kind] == [DataSyncKind.CARD]. */
    @CborLabel(4) val card: CardDelivery? = null,
    /** A peer's request to suppress notifications bound for it — populated iff [kind] == [DataSyncKind.FILTER]. */
    @CborLabel(5) val filter: FilterSync? = null,
    /**
     * A full notification delivered over the quiet (FCM NORMAL / APNs background) channel — populated iff
     * [kind] == [DataSyncKind.NOTIFICATION]. Deliberately GENERIC: any notification may ride the low-urgency
     * channel (the first user is throttled updates to an ongoing notification), so a burst never wakes the
     * device the way an alert push would. The consumer renders it SILENTLY and applies last-writer-wins per
     * ([CapturedNotification.sourceClientId], [CapturedNotification.sourceKey]) on [CapturedNotification.postTime]:
     * the broker relays these store-and-forward with no content coalescing and may deliver a stale backlog
     * after the recipient was offline, so an out-of-order older post must never clobber a newer one.
     */
    @CborLabel(6) val notification: CapturedNotification? = null,
    /** NotiSync Run state and control traffic — populated iff [kind] == [DataSyncKind.RUN]. */
    @CborLabel(7) val run: RunSync? = null,
    /** Android screen-session rendezvous/status traffic — iff [kind] == [DataSyncKind.SCREEN_MIRRORING]. */
    @CborLabel(8) val screenMirror: ScreenMirrorSync? = null,
)

/**
 * The trust state of one device, in a device's local roster and on the wire. PENDING_* are local-only
 * UI states (a received change awaiting the user's approval) and are NEVER broadcast; only TRUSTED and
 * REVOKED travel in a [TrustTable]. Append-only — keep CBOR ordinals stable.
 */
@Serializable
enum class TrustStatus { PENDING_TRUST, TRUSTED, PENDING_REVOKE, REVOKED }

/**
 * One row of a broadcast trust roster. [updatedAt] is the source-clock time the asserting device last
 * (un)trusted this id; receivers resolve concurrent assertions last-writer-wins on it, biased toward
 * the protective interpretation. [keyAvailable] tells peers whether the broadcaster holds this id's
 * signed card, so a peer that has it can repair a keyless peer over [DataSyncKind.CARD].
 */
@Serializable
data class TrustTableEntry(
    @CborLabel(0) val clientId: ClientId,
    /** TRUSTED/REVOKED for any device; an own-mesh device may also carry PENDING_* informationally. */
    @CborLabel(1) val status: TrustStatus,
    @CborLabel(2) val updatedAt: Long,
    @CborLabel(3) val keyAvailable: Boolean,
    /**
     * True for one of the broadcaster's own-mesh devices (consensus trust, may carry PENDING_*); false
     * for an "other" device — a separately-paired entry in the broadcaster's private contact list, which
     * a receiver applies immediately (no pending, last-writer-wins) and never re-shares outside its own
     * mesh. Append-only: defaults true so a roster from an older peer (own devices only) decodes unchanged.
     */
    @CborLabel(4) val ownDevice: Boolean = true,
    /**
     * NS2: the highest [ClientKeyEpoch.epoch] the broadcaster holds for this device (0 = unknown / not
     * advertised, e.g. an NS1-shaped roster). Drives epoch convergence: a receiver that sees an advertised
     * epoch higher than the one it holds for this id refetches via `GET /v2/keyepoch/{id}`. Additive with a
     * default so an older roster (no epoch column) decodes unchanged.
     */
    @CborLabel(5) val epoch: Int = 0,
)

/** A device's broadcast trust roster (its TRUSTED + REVOKED decisions). Carried over [DataSyncKind.TRUST]. */
@Serializable
data class TrustTable(@CborLabel(0) val entries: List<TrustTableEntry>)

/**
 * Delivery of self-authenticating material about [clientId] over [DataSyncKind.CARD] — used to repair a
 * peer that trusts [clientId] but lacks its keys, and for a device to (re)announce its own. It can carry a
 * [card] (NS1 client card) and/or an [epochBlob] (NS2 [ClientKeyEpoch] rotation push); both are
 * self-authenticating (clientId == fingerprint(identityKey) + identity signature, carrying the identity
 * key), so the receiver verifies each independently — the enclosing envelope only attests the relay and
 * [clientId] need not be the sender. NS1 populates [card]; NS2 populates [epochBlob] (an epoch-only push
 * carries no card, since the profile travels via [ProfileUpdate]). `GET /v2/keyepoch/{clientId}` is the
 * pull fallback for a peer that missed an [epochBlob] push.
 */
@Serializable
data class CardDelivery(
    @CborLabel(0) val clientId: ClientId,
    @CborLabel(1) val card: SignedBlob? = null,
    @CborLabel(2) val epochBlob: SignedBlob? = null,
)

/**
 * A device announcing a change to the *mutable* part of its own [ClientCard] so the rest of the
 * group converges (today: a rename; the same channel carries any future mutable field).
 *
 * It deliberately carries NO key material: identity and HPKE keys are immutable trust anchors fixed
 * at pairing, so a profile update can never become a key-rotation vector. Authenticity comes from the
 * enclosing signed [Envelope]; a receiver must require [clientId] == the envelope's signer before
 * applying, and resolve ties with [updatedAt] (last-writer-wins).
 */
@Serializable
data class ProfileUpdate(
    @CborLabel(0) val clientId: ClientId,
    @CborLabel(1) val displayName: String,
    @CborLabel(2) val platform: String,
    @CborLabel(3) @Serializable(with = CapabilityListSerializer::class) val capabilities: List<Capability>,
    /** Source-clock timestamp of the change. */
    @CborLabel(4) val updatedAt: Long,
)
