package net.extrawdw.apps.notisync.ui

import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val DetailPaneSpacer = 16.dp

/** Pixel geometry shared by opening, native anchors, body dragging, and request-card sizing. */
internal class DetailPaneGeometry(val width: Int, requestedSpacer: Int) {
    // Material splits the spacer into two integer halves when dragging. Use that same effective
    // gap for the scaffold's initial layout, including densities that round 16 dp to an odd pixel.
    val spacer = requestedSpacer / 2 * 2
    private val halfSpacer = spacer / 2
    val splitDetailWidth: Int get() = paneWidthAt(.5f, main = false)

    fun firstPaneWidthAt(divider: Float): Int =
        (divider.roundToInt() - halfSpacer).coerceIn(0, (width - spacer).coerceAtLeast(0))

    fun paneWidthAt(proportion: Float, main: Boolean): Int = when (proportion) {
        0f -> if (main) 0 else width
        1f -> if (main) width else 0
        else -> {
            val first = firstPaneWidthAt(width * proportion)
            if (main) first else (width - first - spacer).coerceAtLeast(0)
        }
    }

    fun firstPaneWidthDuringMotion(detailWidth: Int, progress: Float): Int =
        (width - (detailWidth + spacer).coerceAtMost(width) * progress).roundToInt()

    fun dragOffsetFromHandle(measuredCenter: Float): Float = when {
        // Material insets the visible handle at the two edge anchors.
        measuredCenter <= halfSpacer + 1 -> 0f
        measuredCenter >= width - halfSpacer - 1 -> width.toFloat()
        else -> measuredCenter
    }
}
