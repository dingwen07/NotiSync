package net.extrawdw.apps.notisync.screen

import android.content.Context
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import net.extrawdw.notisync.protocol.ScreenVirtualDisplay

internal object ScreenVirtualDisplaySizing {
    /** The viewer is edge-to-edge, so system bars do not reduce its video viewport. */
    fun forWindow(context: Context): ScreenVirtualDisplay {
        val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return forViewport(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
    }

    /** Preserve the viewer's aspect and dp sizing when large windows exceed the protocol limits. */
    fun forViewport(width: Int, height: Int, densityDpi: Int, fixedDensityDpi: Int? = null): ScreenVirtualDisplay {
        require(width > 0 && height > 0 && densityDpi > 0)
        require(fixedDensityDpi == null || fixedDensityDpi in 120..640)
        val scale = min(
            max(1.0, 240.0 / min(width, height)),
            min(4096.0 / max(width, height), sqrt(8_388_608.0 / (width.toDouble() * height))),
        )
        return ScreenVirtualDisplay(
            width = (width * scale).toInt().coerceIn(240, 4096),
            height = (height * scale).toInt().coerceIn(240, 4096),
            densityDpi = fixedDensityDpi ?: (densityDpi * scale).roundToInt().coerceIn(120, 640),
        )
    }
}
