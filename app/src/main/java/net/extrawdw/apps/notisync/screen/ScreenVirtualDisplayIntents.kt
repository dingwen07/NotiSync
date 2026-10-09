package net.extrawdw.apps.notisync.screen

import android.content.Intent
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenVirtualDisplay

/** Only explicit, non-exported viewer/service intents carry this local configuration. */
internal object ScreenVirtualDisplayIntents {
    private const val EXTRA = "net.extrawdw.apps.notisync.screen.VIRTUAL_DISPLAY"
    private const val FIT_VIEWER = "net.extrawdw.apps.notisync.screen.FIT_VIRTUAL_DISPLAY_TO_VIEWER"
    private const val CUSTOM_DENSITY = "net.extrawdw.apps.notisync.screen.VIRTUAL_DISPLAY_CUSTOM_DENSITY"

    fun fitToViewer(intent: Intent, customDensity: Boolean = false): Intent =
        setCustomDensity(intent.putExtra(FIT_VIEWER, true), customDensity)

    fun setCustomDensity(intent: Intent, custom: Boolean): Intent = intent.putExtra(CUSTOM_DENSITY, custom)
    fun hasCustomDensity(intent: Intent): Boolean = intent.getBooleanExtra(CUSTOM_DENSITY, false)

    /** Resolve once in the actual viewer window, not the notification renderer's application context. */
    fun resolveViewerSize(context: android.content.Context, intent: Intent) {
        if (!intent.getBooleanExtra(FIT_VIEWER, false)) return
        val display = requireNotNull(read(intent))
        val size = ScreenVirtualDisplaySizing.forWindow(context)
        put(intent, display.copy(width = size.width, height = size.height,
            densityDpi = if (hasCustomDensity(intent)) display.densityDpi else size.densityDpi))
        intent.removeExtra(FIT_VIEWER)
    }

    fun put(intent: Intent, display: ScreenVirtualDisplay?): Intent = intent.apply {
        if (display != null) {
            require(display.isValid())
            putExtra(EXTRA, ProtocolCodec.encodeToJson(display))
        }
    }

    fun read(intent: Intent): ScreenVirtualDisplay? {
        if (!intent.hasExtra(EXTRA)) return null
        val encoded = requireNotNull(intent.getStringExtra(EXTRA))
        require(encoded.length <= 8192)
        return ProtocolCodec.decodeFromJson<ScreenVirtualDisplay>(encoded).also { require(it.isValid()) }
    }
}
