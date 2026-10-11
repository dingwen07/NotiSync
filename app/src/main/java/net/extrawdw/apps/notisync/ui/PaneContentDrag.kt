package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.PaneExpansionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Bridges body drags to the public pane API; the divider keeps Material's own drag handler. */
internal class PaneContentDrag(private val state: PaneExpansionState) {
    // Measurements are read only when a gesture begins, without retaining LayoutCoordinates.
    var regionLeft = 0f
    var dividerCenter = 0f
    var offset = 0f
    var settling: Job? = null
    var isSettling by mutableStateOf(false)
    var gesture: PaneSwipeGesture? = null

    fun moveBy(delta: Float, geometry: DetailPaneGeometry): Float {
        val previous = offset
        offset = (offset + delta).coerceIn(gesture?.minimum ?: 0f, gesture?.maximum ?: geometry.width.toFloat())
        state.setFirstPaneWidth(geometry.firstPaneWidthAt(offset))
        return offset - previous
    }
}

@Composable
internal fun rememberPaneContentDrag(state: PaneExpansionState): PaneContentDrag =
    remember(state) { PaneContentDrag(state) }

@Composable
internal fun Modifier.paneContentDraggable(
    drag: PaneContentDrag,
    state: PaneExpansionState,
    anchors: List<PaneExpansionAnchor.Proportion>,
    geometry: DetailPaneGeometry,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val draggableState = remember(drag, geometry) {
        DraggableState { drag.moveBy(it, geometry) }
    }
    LaunchedEffect(enabled, geometry) {
        drag.settling?.cancelAndJoin()
    }
    return onGloballyPositioned { drag.regionLeft = it.positionInRoot().x }
        .draggable(
            state = draggableState,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            interactionSource = interactionSource,
            onDragStarted = {
                val measured = drag.dividerCenter - drag.regionLeft
                val start = geometry.dragOffsetFromHandle(measured)
                drag.settling?.cancelAndJoin()
                drag.offset = start
                drag.gesture = PaneSwipeGesture(anchors.map { it.proportion * geometry.width }, start)
                drag.moveBy(0f, geometry)
            },
            onDragStopped = { velocity ->
                if (enabled) drag.settling = scope.launch {
                    val gesture = drag.gesture ?: return@launch
                    val target = anchors[gesture.targetIndex(drag.offset, velocity, density)]
                    drag.isSettling = true
                    try {
                        // One native anchored animation, with no free-fling/second-snap handoff.
                        state.animateTo(target, paneSwipeSettlingVelocity(
                            velocity, target.proportion * geometry.width - drag.offset,
                        ))
                    } finally {
                        drag.isSettling = false
                    }
                }
            },
        )
}
