package net.extrawdw.apps.notisync.notification

import android.service.notification.StatusBarNotification
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.NotifStyle

/**
 * Provider-side glue: plans a notification's graphics, extracts the private ones, uploads them as
 * opaque blobs, and returns the [CapturedNotification] enriched with body-only `PrivateAssetRef`s.
 * Runs on a background scope (rasterization + upload) — never on the listener main thread.
 */
class GraphicsPipeline(
    private val ruleEngine: NotificationRuleEngine,
    private val extractor: GraphicsExtractor,
    private val assets: AssetManager,
) {
    suspend fun attach(sbn: StatusBarNotification, captured: CapturedNotification): CapturedNotification {
        val plan = ruleEngine.plan(captured)
        var result = captured

        when (plan.largeIcon) {
            LargeIconHandling.MIRROR -> extractor.largeIcon(sbn)?.let { bytes ->
                upload(bytes, AssetRole.LARGE_ICON, captured)?.let { result = result.copy(largeIcon = it) }
            }
            LargeIconHandling.AS_AVATAR -> extractor.largeIcon(sbn)?.let { bytes ->
                upload(bytes, AssetRole.AVATAR, captured)?.let { result = asConversation(result, it) }
            }
            LargeIconHandling.OMIT -> Unit
        }

        if (result.style == NotifStyle.BIG_PICTURE && plan.bigPicture == GraphicsSlot.PRIVATE) {
            extractor.bigPicture(sbn)?.let { bytes ->
                upload(bytes, AssetRole.BIG_PICTURE, captured)?.let { result = result.copy(bigPicture = it) }
            }
        }

        if (result.style == NotifStyle.MESSAGING && plan.avatar == GraphicsSlot.PRIVATE) {
            result = attachMessageAvatars(sbn, result)
        }
        return result
    }

    private suspend fun upload(bytes: ByteArray, role: AssetRole, notif: CapturedNotification) =
        assets.ensureUploaded(bytes, role, MIME_WEBP, notif.sourceClientId)

    private suspend fun attachMessageAvatars(sbn: StatusBarNotification, notif: CapturedNotification): CapturedNotification {
        val avatarBytes = extractor.messageAvatars(sbn)
        if (avatarBytes.isEmpty()) return notif
        val updated = ArrayList<ConversationMessage>(notif.messages.size)
        for (i in notif.messages.indices) {
            val message = notif.messages[i]
            val bytes = avatarBytes.getOrNull(i)
            if (message.avatar == null && bytes != null) {
                val ref = upload(bytes, AssetRole.AVATAR, notif)
                updated.add(if (ref != null) message.copy(avatar = ref) else message)
            } else {
                updated.add(message)
            }
        }
        return notif.copy(messages = updated)
    }

    /** Render the notification as a conversation carrying [avatarRef] as the contact's avatar. */
    private fun asConversation(notif: CapturedNotification, avatarRef: net.extrawdw.notisync.protocol.PrivateAssetRef): CapturedNotification {
        if (notif.style == NotifStyle.MESSAGING) {
            // Attach the contact avatar to incoming messages that lack their own.
            return notif.copy(messages = notif.messages.map {
                if (it.sender != null && it.avatar == null) it.copy(avatar = avatarRef) else it
            })
        }
        // Synthesize a one-message conversation so the contact photo renders as the person avatar.
        val sender = notif.title?.takeIf { it.isNotBlank() } ?: notif.appLabel
        val message = ConversationMessage(
            sender = sender,
            text = notif.text ?: notif.bigText ?: "",
            timestamp = notif.postTime,
            avatar = avatarRef,
        )
        return notif.copy(style = NotifStyle.MESSAGING, isConversation = true, messages = listOf(message))
    }

    private companion object {
        const val MIME_WEBP = "image/webp"
    }
}
