package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.NotificationStyle
import java.security.MessageDigest
import java.util.Base64

/** Conversation identity is independent of its notification channel and its current notification key. */
internal fun mirroredConversationShortcutId(notif: CapturedNotification): String? {
    if (!notif.isConversation || notif.style != NotificationStyle.MESSAGING || notif.isGroupSummary) return null
    val conversation = notif.shortcutId?.takeIf(String::isNotBlank)
        ?: notif.conversationId?.takeIf(String::isNotBlank)
        ?: notif.sourceKey
    val identity = listOf(
        notif.sourceClientId.value,
        notif.originDeviceId?.takeIf(String::isNotBlank).orEmpty(),
        notif.packageName,
        conversation,
    ).joinToString("") { "${it.length}:$it" }
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    // A new namespace prevents Android from restoring deleted legacy conversation channels when
    // it resolves (parent channel, shortcut ID). Existing category channels can keep their settings.
    return "conversation:${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
}
