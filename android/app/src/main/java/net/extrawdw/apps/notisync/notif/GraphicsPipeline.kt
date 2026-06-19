package net.extrawdw.apps.notisync.notif

import android.service.notification.StatusBarNotification
import net.extrawdw.apps.notisync.assets.AssetManager
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification

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
        if (plan.largeIcon == GraphicsSlot.PRIVATE) {
            extractor.largeIcon(sbn)?.let { bytes ->
                assets.ensureUploaded(bytes, AssetRole.LARGE_ICON, MIME_WEBP, captured.sourceClientId)
                    ?.let { ref -> result = result.copy(largeIcon = ref) }
            }
        }
        return result
    }

    private companion object {
        const val MIME_WEBP = "image/webp"
    }
}
