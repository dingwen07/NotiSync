package net.extrawdw.apps.notisync.ui

import kotlin.math.abs
import kotlin.math.sqrt

/** Content swipes move one step; dragging the divider retains Material's unrestricted behavior. */
internal class PaneSwipeGesture(
    private val positions: List<Float>,
    private val startOffset: Float,
) {
    private val origin = positions.indices.minBy { abs(positions[it] - startOffset) }
    private val first = (origin - 1).coerceAtLeast(0)
    private val last = (origin + 1).coerceAtMost(positions.lastIndex)
    val minimum: Float get() = positions[first]
    val maximum: Float get() = positions[last]

    fun targetIndex(offset: Float, velocity: Float, density: Float): Int {
        val position = offset.coerceIn(minimum, maximum)
        // Require some intentional travel as well as velocity, so a tiny flick is not a command.
        if (abs(velocity) >= 400f * density && abs(position - startOffset) >= 24f * density) {
            return if (velocity > 0) {
                (first..last).firstOrNull { positions[it] > position } ?: last
            } else {
                (last downTo first).firstOrNull { positions[it] < position } ?: first
            }
        }
        val next = if (position >= positions[origin]) last else first
        val distance = abs(positions[next] - positions[origin])
        return if (abs(position - positions[origin]) >= distance * .35f) next else origin
    }
}

internal const val PaneAnchoringStiffness = 1000f

/** Preserve release momentum toward the target without overshooting a critically damped spring. */
internal fun paneSwipeSettlingVelocity(velocity: Float, distance: Float): Float {
    val limit = abs(distance) * sqrt(PaneAnchoringStiffness)
    return if (distance >= 0) velocity.coerceIn(0f, limit) else velocity.coerceIn(-limit, 0f)
}
