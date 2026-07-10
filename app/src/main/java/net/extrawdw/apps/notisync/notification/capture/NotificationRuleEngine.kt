package net.extrawdw.apps.notisync.notification.capture

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.MirrorCategory

/** How the source large icon is handled on the consumer. */
enum class LargeIconHandling {
    /** Default: transfer the original large icon and show it in the large-icon slot. */
    MIRROR,

    /** Treat the original large icon as the conversation/person avatar (e.g. WeChat); the large-icon
     *  slot then falls back to the source app icon, resolved receiver-side. */
    AS_AVATAR,

    OMIT,
}

/** Whether a graphic slot is transferred as a private asset. */
enum class GraphicsSlot { PRIVATE, OMIT }

/** Per-notification plan for how each graphic is sourced. */
data class GraphicsPlan(
    val largeIcon: LargeIconHandling,
    val bigPicture: GraphicsSlot,
    val avatar: GraphicsSlot,
)

/** A package-specific override applied on top of the default plan. */
private interface NotificationRule {
    fun matches(notif: CapturedNotification): Boolean
    fun apply(base: GraphicsPlan): GraphicsPlan
}

/**
 * WeChat conversation/message notifications carry the contact photo as the large icon and aren't
 * MessagingStyle. We mirror them as conversations: the large icon becomes the private person avatar,
 * and the large-icon slot shows the WeChat app icon (resolved receiver-side).
 */
private object WeChatRule : NotificationRule {
    override fun matches(notif: CapturedNotification): Boolean =
        notif.packageName == "com.tencent.mm" &&
                (notif.isConversation || notif.category == MirrorCategory.MESSAGE)

    override fun apply(base: GraphicsPlan): GraphicsPlan =
        base.copy(largeIcon = LargeIconHandling.AS_AVATAR)
}

/**
 * Decides how each graphic is sourced, keeping app-specific overrides out of capture/render code.
 * Default: mirror the original large icon / big picture / per-message avatars as private assets when
 * present. The first matching [NotificationRule] refines the default (single match wins).
 */
class NotificationRuleEngine {
    private val rules = listOf(WeChatRule)

    fun plan(notif: CapturedNotification): GraphicsPlan {
        val base =
            GraphicsPlan(LargeIconHandling.MIRROR, GraphicsSlot.PRIVATE, GraphicsSlot.PRIVATE)
        return rules.firstOrNull { it.matches(notif) }?.apply(base) ?: base
    }
}
