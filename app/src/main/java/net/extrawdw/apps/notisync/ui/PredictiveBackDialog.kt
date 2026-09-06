package net.extrawdw.apps.notisync.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException

/** Matches PowerKeeper Diagnostics: edge/touch anchored back preview with animated cancellation. */
@Composable
internal fun PredictiveBackDialog(
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(Unit) { visible = true }
    val openness by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        finishedListener = { if (it == 0f) dismiss() },
        label = "dialogOpenness",
    )
    var gestureInProgress by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var rawProgress by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var touchY by remember { mutableFloatStateOf(0f) }
    val gesture by animateFloatAsState(
        targetValue = if (gestureInProgress || committing) rawProgress else 0f,
        label = "predictiveBack",
    )
    val requestClose = { if (visible && dismissEnabled) visible = false }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        PredictiveBackHandler(enabled = visible && dismissEnabled) { events ->
            try {
                events.collect { event ->
                    gestureInProgress = true
                    rawProgress = event.progress
                    swipeEdge = event.swipeEdge
                    touchY = event.touchY
                }
                gestureInProgress = false
                committing = true
                requestClose()
            } catch (_: CancellationException) {
                gestureInProgress = false
                rawProgress = 0f
            }
        }
        Surface(
            Modifier.fillMaxSize().graphicsLayer {
                val scale = lerp(0.92f, 1f, openness) * (1f - 0.10f * gesture)
                scaleX = scale
                scaleY = scale
                alpha = openness * (1f - 0.15f * gesture)
                transformOrigin = if (gesture > 0f) {
                    TransformOrigin(
                        if (swipeEdge == BackEventCompat.EDGE_LEFT) 1f else 0f,
                        if (size.height > 0f) (touchY / size.height).coerceIn(0f, 1f) else 0.5f,
                    )
                } else TransformOrigin.Center
            },
            color = MaterialTheme.colorScheme.background,
        ) { content(requestClose) }
    }
}
