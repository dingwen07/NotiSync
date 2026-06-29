package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

@Serializable
enum class MirrorImportance { MIN, LOW, DEFAULT, HIGH, NONE } // NONE = blocked; appended to keep CBOR ordinals stable

@Serializable
enum class MirrorCategory {
    MESSAGE, EMAIL, CALL, ALARM, EVENT, REMINDER, SOCIAL, PROGRESS,
    TRANSPORT, SERVICE, STATUS, ERROR, NAVIGATION, NONE,
}

/** The producer declares the rendering style; the consumer reconstructs it, never guesses. */
@Serializable
enum class NotifStyle { DEFAULT, BIG_TEXT, BIG_PICTURE, MESSAGING, INBOX }

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
    val role: AssetRole,
    /** Hex SHA-256 of the *plaintext* bytes: integrity check on open + local cache key. Body-only. */
    val assetHash: String,
    val mimeType: String,
    /** Plaintext byte length, bound into the AEAD AAD. */
    val sizeBytes: Int,
    val sourceClientId: ClientId,
    /** Opaque random server key (Base32 of 24 bytes); never derived from content/package/role. */
    val assetId: String,
    /** Per-asset AES-256-GCM key, delivered only inside this E2E body. */
    @ByteString val assetKey: ByteArray,
    /** The suite that ENCRYPTED the blob; the consumer rebuilds the AAD from this, not the envelope. */
    @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
)

/**
 * The additional authenticated data bound into a private asset's AEAD. CBOR-encoded (length-
 * delimited, unambiguous, byte-identical on Android + JVM) rather than string-joined. Field order
 * is load-bearing — both ends must build the identical struct from the same [PrivateAssetRef].
 */
@Serializable
data class AssetAad(
    val suite: String,
    val sourceClientId: ClientId,
    val assetId: String,
    val mimeType: String,
    val sizeBytes: Int,
    val role: AssetRole,
)

/** One message in a MessagingStyle conversation. A null [sender] denotes the local user ("you"). */
@Serializable
data class ConversationMessage(
    val sender: String? = null,
    val text: String,
    val timestamp: Long,
    val avatar: PrivateAssetRef? = null,
    val dataMimeType: String? = null,
    val data: PrivateAssetRef? = null,
)

/**
 * A normalized, platform-neutral notification — the plaintext body that gets CBOR-encoded and
 * end-to-end encrypted. The producer captures as much useful structure as the platform allows and
 * the user permits; the consumer faithfully reconstructs it.
 */
@Serializable
data class CapturedNotification(
    val sourceClientId: ClientId,
    /** Stable per-source identifier derived from the original notification key (for dedup/dismissal). */
    val sourceKey: String,
    val packageName: String,
    val appLabel: String,
    /** Private graphics (body-only). Small icons are never transferred — the consumer renders a
     *  local bundled/generic icon — so there is no small-icon ref here. */
    val largeIcon: PrivateAssetRef? = null,
    val bigPicture: PrivateAssetRef? = null,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val style: NotifStyle = NotifStyle.DEFAULT,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val messages: List<ConversationMessage> = emptyList(),
    val category: MirrorCategory = MirrorCategory.NONE,
    val importance: MirrorImportance = MirrorImportance.DEFAULT,
    val postTime: Long,
    /** Source app group key, or a system override group key when the app did not supply one. */
    val groupKey: String? = null,
    /** True when the source notification is the summary row for [groupKey]. */
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    /** True if the platform redacted sensitive content (Android 15+); consumer should signal it. */
    val sensitiveRedacted: Boolean = false,

    // --- Channel / channel-group mirroring (all best-effort; null/false on older producers) ---
    /** Source NotificationChannel id (Ranking.getChannel().getId()). */
    val channelId: String? = null,
    /** Source channel user-visible name; may be redacted for a plain listener. */
    val channelName: String? = null,
    /** Source channel's group id (NotificationChannel.getGroup()) — an id, not a name. */
    val channelGroupId: String? = null,
    /** Source channel group display name; only available with a CompanionDeviceManager association. */
    val channelGroupName: String? = null,
    /** Source channel-level importance/mute; drives the mirrored channel's importance. */
    val channelImportance: MirrorImportance? = null,
    /** Whether the mirrored channel should vibrate — mirrored from the source channel's own vibration setting
     *  (Android: NotificationChannel.shouldVibrate(); importance alone never enables vibration). The OS only honors
     *  enableVibration() at channel creation, so this takes effect when the mirror first creates the channel. False
     *  for sources without a channel (iOS/ANCS), which then alert per importance but don't vibrate. */
    val shouldVibrate: Boolean = false,

    // --- Conversation notifications ---
    val isConversation: Boolean = false,
    /** Source conversation shortcut id (Notification.getShortcutId()). */
    val shortcutId: String? = null,
    /** Source conversation channel id (NotificationChannel.getConversationId()). */
    val conversationId: String? = null,
    /** Source conversation channel's parent channel id (NotificationChannel.getParentChannelId()). */
    val parentChannelId: String? = null,

    // --- Cross-platform app icon + capture origin (all appended; default = an Android-local capture) ---
    /** The source app's launcher icon as a private asset, so a consumer that doesn't have the app
     *  installed still renders the real icon. Body-only, content-addressed, deduped like any asset. */
    val appIcon: PrivateAssetRef? = null,
    /** Which platform captured this. ANDROID_LOCAL for a NotificationListener capture; IOS_ANCS for a
     *  notification bridged from a paired iPhone over ANCS. */
    val originPlatform: OriginPlatform = OriginPlatform.ANDROID_LOCAL,
    /** For a bridged capture, the *originating* device's name (e.g. the iPhone's Bluetooth name) — used
     *  for the mirror's group label / "via" line instead of the bridging device's own name. */
    val originDeviceName: String? = null,
    /** For an IOS_ANCS capture, the raw iOS bundle identifier (the ANCS App Identifier), retained for
     *  icon/display resolution alongside the best-match Android [packageName]. */
    val iosBundleId: String? = null,
    /** Stable id of the *originating* device (e.g. a hashed iPhone address) for a bridged capture. Lets the
     *  consumer group/channel notifications per origin: once one client bridges another device, [sourceClientId]
     *  alone no longer identifies the source (its own Android vs the paired iPhone). Null for a local capture. */
    val originDeviceId: String? = null,
    /** The source's per-post `FLAG_ONLY_ALERT_ONCE` (Android: `Notification.flags`): the app's own intent
     *  that an update to this key must NOT re-alert (sound / heads-up) while the notification is still
     *  showing — set on enrichment updates like a messaging app attaching an inline image to a message it
     *  already alerted, or a download refreshing its progress. The consumer mirrors it verbatim into
     *  `setOnlyAlertOnce`, so the mirror reproduces the source's exact alerting cadence rather than
     *  re-buzzing on every update; a genuinely new message is a separate post the app leaves alerting, so it
     *  still heads-up. Appended with a default so an older producer (no field) decodes unchanged. */
    val onlyAlertOnce: Boolean = false,
)

/** Idempotent dismissal: removing the mirrored notification keyed by ([sourceClientId], [sourceKey]). */
@Serializable
data class DismissEvent(
    val sourceClientId: ClientId,
    val sourceKey: String,
    val dismissedAt: Long,
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
data class AssetSync(val kind: AssetSyncKind, val items: List<AssetSyncItem>)

/** One asset in an [AssetSync] batch, keyed by its plaintext [assetHash] (safe inside the E2E body). */
@Serializable
data class AssetSyncItem(
    val assetHash: String,
    /** The (now-unfetchable) id the consumer tried — ASSET_MISSING only. */
    val assetId: String? = null,
    /** A fresh ref the consumer can re-fetch from — ASSET_READY only. */
    val ref: PrivateAssetRef? = null,
)

/** Selects which sub-body a [DataSync] carries. Append-only — keep CBOR ordinals stable. */
@Serializable
enum class DataSyncKind { ASSET, PROFILE, TRUST, CARD, FILTER }

/**
 * One suppression rule a client asks a peer (the notification *source*) to apply to deliveries bound
 * for the requester. A notification the source captures matches the rule when its capture origin, app,
 * and (Android only) channel match — the same fields a source-side filter keys on.
 */
@Serializable
data class NotificationFilterRule(
    /** Which capture origin this rule targets. ANDROID_LOCAL = the source's own NotificationListener
     *  captures; IOS_ANCS = notifications the source bridges from a paired iPhone over ANCS. */
    val originPlatform: OriginPlatform,
    /** App scope: an Android package name (ANDROID_LOCAL) or an iOS bundle id (IOS_ANCS). null = every
     *  app of this origin — a device-level master switch for that origin on the target. */
    val appId: String? = null,
    /** Channel scope within [appId] (ANDROID_LOCAL only; iOS has no channels). null = all channels of
     *  [appId]. Ignored when [appId] is null. */
    val channelId: String? = null,
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
    val rules: List<NotificationFilterRule>,
    /** Source-clock time the requester built this snapshot; the receiver resolves races last-writer-wins. */
    val updatedAt: Long,
)

/**
 * The body of a [MessageType.DATA_SYNC] envelope: the group's low-urgency (FCM NORMAL), end-to-end-
 * encrypted control channel, which the broker only ever relays as opaque ciphertext. [kind] selects
 * the populated sub-body — a flat discriminated struct (like [WsMessage]) rather than polymorphic
 * serialization, so it encodes byte-identically on Android + JVM and a new variant never has to be
 * told apart by a fragile try-decode.
 */
@Serializable
data class DataSync(
    val kind: DataSyncKind,
    /** Private-graphic repair — populated iff [kind] == [DataSyncKind.ASSET]. */
    val asset: AssetSync? = null,
    /** A peer's mutable profile changed — populated iff [kind] == [DataSyncKind.PROFILE]. */
    val profile: ProfileUpdate? = null,
    /** A peer's trust roster — populated iff [kind] == [DataSyncKind.TRUST]. */
    val trust: TrustTable? = null,
    /** A self-authenticating delivery (a client card and/or a key-epoch) — iff [kind] == [DataSyncKind.CARD]. */
    val card: CardDelivery? = null,
    /** A peer's request to suppress notifications bound for it — populated iff [kind] == [DataSyncKind.FILTER]. */
    val filter: FilterSync? = null,
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
    val clientId: ClientId,
    /** TRUSTED/REVOKED for any device; an own-mesh device may also carry PENDING_* informationally. */
    val status: TrustStatus,
    val updatedAt: Long,
    val keyAvailable: Boolean,
    /**
     * True for one of the broadcaster's own-mesh devices (consensus trust, may carry PENDING_*); false
     * for an "other" device — a separately-paired entry in the broadcaster's private contact list, which
     * a receiver applies immediately (no pending, last-writer-wins) and never re-shares outside its own
     * mesh. Append-only: defaults true so a roster from an older peer (own devices only) decodes unchanged.
     */
    val ownDevice: Boolean = true,
    /**
     * NS2: the highest [ClientKeyEpoch.epoch] the broadcaster holds for this device (0 = unknown / not
     * advertised, e.g. an NS1-shaped roster). Drives epoch convergence: a receiver that sees an advertised
     * epoch higher than the one it holds for this id refetches via `GET /v2/keyepoch/{id}`. Additive with a
     * default so an older roster (no epoch column) decodes unchanged.
     */
    val epoch: Int = 0,
)

/** A device's broadcast trust roster (its TRUSTED + REVOKED decisions). Carried over [DataSyncKind.TRUST]. */
@Serializable
data class TrustTable(val entries: List<TrustTableEntry>)

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
    val clientId: ClientId,
    val card: SignedBlob? = null,
    val epochBlob: SignedBlob? = null,
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
    val clientId: ClientId,
    val displayName: String,
    val platform: String,
    val capabilities: List<Capability>,
    /** Source-clock timestamp of the change. */
    val updatedAt: Long,
)
