package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class MirrorImportance { MIN, LOW, DEFAULT, HIGH }

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
)

/** Idempotent dismissal: removing the mirrored notification keyed by ([sourceClientId], [sourceKey]). */
@Serializable
data class DismissEvent(
    val sourceClientId: ClientId,
    val sourceKey: String,
    val dismissedAt: Long,
)
