package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable

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

/**
 * Content-addressed reference to a binary attachment (icon, avatar, big picture). [hash] is the
 * SHA-256 (hex) of the *plaintext* bytes, computed before encryption, so identical assets dedup
 * across messages. Binary attachments are declared in v1 but not yet exercised (text-first slice).
 */
@Serializable
data class AttachmentRef(
    val hash: String,
    val mimeType: String,
    val sizeBytes: Int,
    val role: String,   // "small_icon" | "large_icon" | "big_picture" | "avatar"
)

/** One message in a MessagingStyle conversation. A null [sender] denotes the local user ("you"). */
@Serializable
data class ConversationMessage(
    val sender: String? = null,
    val text: String,
    val timestamp: Long,
    val avatar: AttachmentRef? = null,
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
    val smallIcon: AttachmentRef? = null,
    val largeIcon: AttachmentRef? = null,
    val bigPicture: AttachmentRef? = null,
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
