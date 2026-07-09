package net.extrawdw.apps.notisync.notification

import android.service.notification.StatusBarNotification
import net.extrawdw.apps.notisync.analytics.PerfSpan
import net.extrawdw.apps.notisync.analytics.perfTrace
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.NotificationStyle

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
    suspend fun attach(
        sbn: StatusBarNotification,
        captured: CapturedNotification
    ): CapturedNotification = perfTrace("graphics_attach") { span ->
        val plan = ruleEngine.plan(captured)
        var result = captured
        // `*_ms` time the rasterize/encode (the CPU cost); each graphic's seal+upload is timed separately in
        // `asset_upload`. `omitted_count` = graphics the rule dropped OR that overran their WEBP size budget
        // (the extractor returns null) — i.e. graphics a peer will not receive. Counts are derived from the
        // enriched result below, so the message helpers stay untouched.
        var omitted = 0

        when (plan.largeIcon) {
            LargeIconHandling.MIRROR -> {
                val t0 = System.nanoTime()
                val bytes = extractor.largeIcon(sbn)
                span.metric("large_icon_ms", (System.nanoTime() - t0) / 1_000_000)
                if (bytes == null) omitted++
                else upload(bytes, AssetRole.LARGE_ICON, captured)?.let { result = result.copy(largeIcon = it) }
            }

            LargeIconHandling.AS_AVATAR -> {
                val t0 = System.nanoTime()
                val bytes = extractor.largeIcon(sbn)
                span.metric("large_icon_ms", (System.nanoTime() - t0) / 1_000_000)
                if (bytes == null) omitted++
                else upload(bytes, AssetRole.AVATAR, captured)?.let { result = asConversation(result, it) }
            }

            LargeIconHandling.OMIT -> omitted++
        }

        if (result.style == NotificationStyle.BIG_PICTURE && plan.bigPicture == GraphicsSlot.PRIVATE) {
            val t0 = System.nanoTime()
            val bytes = extractor.bigPicture(sbn)
            span.metric("big_picture_ms", (System.nanoTime() - t0) / 1_000_000)
            if (bytes == null) omitted++
            else upload(bytes, AssetRole.BIG_PICTURE, captured)?.let { result = result.copy(bigPicture = it) }
        } else if (result.style == NotificationStyle.BIG_PICTURE && plan.bigPicture == GraphicsSlot.OMIT) {
            omitted++
        }

        if (result.style == NotificationStyle.MESSAGING && plan.avatar == GraphicsSlot.PRIVATE) {
            result = attachMessageAvatars(sbn, result)
        }

        if (result.style == NotificationStyle.MESSAGING) {
            result = attachMessageData(sbn, result)
        }

        result = attachAppIcon(result, span)

        val avatarCount = result.messages.count { it.avatar != null }
        val inlineCount = result.messages.count { it.data != null }
        val singles = listOfNotNull(result.largeIcon, result.bigPicture, result.appIcon).size
        span.metric("avatar_count", avatarCount.toLong())
        span.metric("inline_image_count", inlineCount.toLong())
        span.metric("attached_count", (singles + avatarCount + inlineCount).toLong())
        span.metric("omitted_count", omitted.toLong())
        result
    }

    /**
     * App icon (any style): deliver the source app's launcher icon so a consumer that doesn't have the
     * app installed still renders the real icon (the small/status-bar icon is never transferred). The
     * asset layer content-addresses it, so this uploads once per app and is reference-only thereafter.
     */
    suspend fun attachAppIcon(
        captured: CapturedNotification,
        span: PerfSpan? = null,
    ): CapturedNotification {
        val t0 = System.nanoTime()
        val bytes = extractor.appIcon(captured.packageName)
        span?.metric("app_icon_ms", (System.nanoTime() - t0) / 1_000_000)
        bytes?.let {
            upload(it, AssetRole.APP_ICON, captured)?.let { ref -> return captured.copy(appIcon = ref) }
        }
        return captured
    }

    private suspend fun upload(
        bytes: ByteArray,
        role: AssetRole,
        notif: CapturedNotification,
        mimeType: String = MIME_WEBP,
    ) = assets.ensureUploaded(bytes, role, mimeType, notif.sourceClientId)

    private suspend fun attachMessageAvatars(
        sbn: StatusBarNotification,
        notif: CapturedNotification
    ): CapturedNotification {
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

    private suspend fun attachMessageData(
        sbn: StatusBarNotification,
        notif: CapturedNotification
    ): CapturedNotification {
        val messageData = extractor.messageData(sbn)
        if (messageData.isEmpty()) return notif
        val updated = ArrayList<ConversationMessage>(notif.messages.size)
        for (i in notif.messages.indices) {
            val message = notif.messages[i]
            val data = messageData.getOrNull(i)
            if (message.data == null && data != null) {
                val ref = upload(data.bytes, AssetRole.INLINE_IMAGE, notif, data.mimeType)
                updated.add(
                    if (ref != null) {
                        message.copy(dataMimeType = data.mimeType, data = ref)
                    } else {
                        message
                    }
                )
            } else {
                updated.add(message)
            }
        }
        return notif.copy(messages = updated)
    }

    /** Render the notification as a conversation carrying [avatarRef] as the contact's avatar. */
    private fun asConversation(
        notif: CapturedNotification,
        avatarRef: net.extrawdw.notisync.protocol.PrivateAssetRef
    ): CapturedNotification {
        if (notif.style == NotificationStyle.MESSAGING) {
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
        return notif.copy(
            style = NotificationStyle.MESSAGING,
            isConversation = true,
            messages = listOf(message)
        )
    }

    private companion object {
        const val MIME_WEBP = "image/webp"
    }
}
