package net.extrawdw.apps.notisync.notif

import net.extrawdw.notisync.protocol.CapturedNotification

/** How a graphic slot is sourced on the consumer. v1 wires PRIVATE (transfer) and OMIT. */
enum class GraphicsSlot { PRIVATE, OMIT }

/** Per-role plan for one notification's graphics. */
data class GraphicsPlan(
    val largeIcon: GraphicsSlot,
    val bigPicture: GraphicsSlot,
    val avatar: GraphicsSlot,
)

/**
 * Decides how each graphic is sourced, keeping app-specific overrides out of capture/render code.
 * v1 default: mirror the original large icon / big picture / avatar as private assets when present.
 * App-specific rules (e.g. WeChat) and public/bundled small icons are layered in later phases.
 */
class NotificationRuleEngine {
    fun plan(notif: CapturedNotification): GraphicsPlan = GraphicsPlan(
        largeIcon = GraphicsSlot.PRIVATE,
        bigPicture = GraphicsSlot.PRIVATE,
        avatar = GraphicsSlot.PRIVATE,
    )
}
