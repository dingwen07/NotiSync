package net.extrawdw.notisync.protocol

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

/** The graphic's logical slot. Drives placement on the consumer and is bound into the asset AAD. */
@Serializable
enum class AssetRole { LARGE_ICON, BIG_PICTURE, AVATAR, INLINE_IMAGE }

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
    val suite: String = CipherSuite.CURRENT_ID,
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
    val groupKey: String? = null,
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

    // --- Conversation notifications ---
    val isConversation: Boolean = false,
    /** Source conversation shortcut id (Notification.getShortcutId()). */
    val shortcutId: String? = null,
    /** Source conversation channel id (NotificationChannel.getConversationId()). */
    val conversationId: String? = null,
    /** Source conversation channel's parent channel id (NotificationChannel.getParentChannelId()). */
    val parentChannelId: String? = null,
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
 * The body of a [MessageType.DATA_SYNC] envelope: end-to-end-encrypted, low-urgency (FCM NORMAL
 * priority) repair of missing private graphics. The broker only ever relays it as opaque ciphertext.
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
