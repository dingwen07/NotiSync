package net.extrawdw.apps.notisync.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

// Tuning for the expand/collapse + predictive-back container transform. Mirrors the values used by
// the AskMyTimeline "Chat history" overlay this is modelled on.
private const val BACK_DRAG_SCALE_DELTA =
    0.16f          // max peek shrink while dragging back (× the cap below)
private const val BACK_DRAG_PROGRESS_MULTIPLIER =
    0.68f  // cap the live drag short of a full collapse
private const val BACK_DRAG_TRANSLATION_RATIO =
    0.64f    // how far the page slides toward the swipe edge
private const val BACK_DRAG_VERTICAL_MULTIPLIER =
    0.45f  // how much vertical finger travel nudges the page
private const val BACK_DIM_ALPHA = 0.24f                 // scrim opacity behind the expanded page
private const val BACK_COLLAPSE_MILLIS = 240             // expand + settle-to-button duration
private const val BACK_CANCEL_MILLIS =
    180               // spring-back when a back gesture is abandoned
private val pairDragMaxOffsetY = 96.dp
private val pairCollapsedCornerRadius = 28.dp
private val predictiveBackEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * Full-screen pairing flow rendered as a state-driven overlay that expands out of the "Pair a device"
 * stripe and collapses back into it, with the system predictive-back gesture driving the collapse.
 *
 * Adapted from AskMyTimeline's square "History" button in two ways the brief called out:
 *  - the origin is a full-width **stripe**, so the settle target uses a *non-uniform* scale (wide and
 *    short) instead of a uniform square shrink — the page furls straight back into the bar;
 *  - that origin lives in a scrollable list rather than a fixed bar, so [pairButtonBounds] is sampled
 *    live from the button's `onGloballyPositioned` and may move (or be stale) as the list scrolls.
 *
 * @param pairButtonBounds the button's bounds in root coordinates, or null before it has been laid out.
 * @param onClose called once the collapse animation has fully played out, to drop the overlay.
 */
@Composable
internal fun PairingOverlay(
    pairButtonBounds: Rect?,
    onClose: () -> Unit,
    initialPairingPayload: String? = null,
    onInitialPairingPayloadConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val enterProgress = remember { Animatable(0f) }     // 0 → 1 expand from the stripe
    val dragProgress = remember { Animatable(0f) }      // 0 → 0.72, follows the live back gesture
    val collapseProgress = remember { Animatable(0f) }  // 0 → 1 final settle into the stripe
    var overlayBounds by remember { mutableStateOf<Rect?>(null) }
    var firstTouchY by remember { mutableFloatStateOf(Float.NaN) }
    var currentTouchY by remember { mutableFloatStateOf(Float.NaN) }
    var collapseStartProgress by remember { mutableFloatStateOf(0f) }
    var collapseStartTouchDeltaY by remember { mutableFloatStateOf(0f) }
    var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var collapsing by remember { mutableStateOf(false) }

    suspend fun collapseToPairButton(afterCollapse: () -> Unit = {}) {
        if (collapsing) return
        collapsing = true
        // Freeze the live-drag state so the settle continues smoothly from wherever the finger left off.
        collapseStartProgress = dragProgress.value
        collapseStartTouchDeltaY = touchDeltaY(firstTouchY, currentTouchY)
        collapseProgress.snapTo(0f)
        collapseProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = BACK_COLLAPSE_MILLIS,
                easing = FastOutSlowInEasing
            ),
        )
        afterCollapse()
        onClose()
    }

    LaunchedEffect(Unit) {
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = BACK_COLLAPSE_MILLIS,
                easing = FastOutSlowInEasing
            ),
        )
    }

    // System Back during the brief expand (before the predictive handler arms at full expansion):
    // collapse instead of letting Back fall through to the navigator and exit the app.
    BackHandler(enabled = !collapsing && enterProgress.value < 1f) {
        scope.launch { collapseToPairButton() }
    }

    PredictiveBackHandler(enabled = !collapsing && enterProgress.value >= 1f) { backEvents ->
        collapseProgress.snapTo(0f)
        try {
            backEvents.collect { event ->
                if (firstTouchY.isNaN()) firstTouchY = event.touchY
                currentTouchY = event.touchY
                swipeEdge = event.swipeEdge
                dragProgress.snapTo(
                    transformPredictiveBackProgress(event.progress) * BACK_DRAG_PROGRESS_MULTIPLIER,
                )
            }
            collapseToPairButton()
        } catch (cancellation: CancellationException) {
            collapseProgress.snapTo(0f)
            dragProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = BACK_CANCEL_MILLIS,
                    easing = FastOutSlowInEasing
                ),
            )
            firstTouchY = Float.NaN
            currentTouchY = Float.NaN
            throw cancellation
        }
    }

    val currentOverlayBounds = overlayBounds
    val enter = enterProgress.value
    val drag = if (collapsing) collapseStartProgress else dragProgress.value
    val collapse = collapseProgress.value
    val edgeDirection = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f

    val width = currentOverlayBounds?.width?.takeIf { it > 0f } ?: 1f
    val height = currentOverlayBounds?.height?.takeIf { it > 0f } ?: 1f
    val dragMaxOffsetY = with(LocalDensity.current) { pairDragMaxOffsetY.toPx() }

    // Live-drag "peek": a subtle uniform shrink + slide while the finger is down — shape-agnostic, so
    // it reads the same regardless of the button it will eventually fold into.
    val activeTouchDeltaY =
        if (collapsing) collapseStartTouchDeltaY else touchDeltaY(firstTouchY, currentTouchY)
    val dragYDirection = when {
        activeTouchDeltaY > 0f -> 1f
        activeTouchDeltaY < 0f -> -1f
        else -> 0f
    }
    val dragYProgress = (abs(activeTouchDeltaY) / height * 2f).coerceIn(0f, 1f)
    val dragScale = 1f - BACK_DRAG_SCALE_DELTA * drag
    val dragX = edgeDirection * width * (1f - dragScale) / 2f * BACK_DRAG_TRANSLATION_RATIO
    val dragY =
        dragYDirection * dragMaxOffsetY * dragYProgress * drag * BACK_DRAG_VERTICAL_MULTIPLIER

    // Settle target: the page's centre slides onto the button's centre…
    val targetBounds = pairButtonBounds
    val targetX = if (currentOverlayBounds != null && targetBounds != null) {
        targetBounds.center.x - currentOverlayBounds.center.x
    } else {
        edgeDirection * width
    }
    val targetY = if (currentOverlayBounds != null && targetBounds != null) {
        targetBounds.center.y - currentOverlayBounds.center.y
    } else {
        -height * 0.32f
    }
    // …and shrinks to the button's *actual* footprint. Non-uniform (wide, short) is the whole point of
    // the stripe adaptation: matching width and height independently lands the page exactly on the bar.
    val targetScaleX =
        if (targetBounds != null) (targetBounds.width / width).coerceIn(0.05f, 1f) else 0.92f
    val targetScaleY =
        if (targetBounds != null) (targetBounds.height / height).coerceIn(0.02f, 1f) else 0.08f

    val enterStartX = if (targetBounds != null) targetX else 0f
    val enterStartY = if (targetBounds != null) targetY else 0f
    val enterStartScaleX = if (targetBounds != null) targetScaleX else 1f
    val enterStartScaleY = if (targetBounds != null) targetScaleY else 1f

    val backTranslationX = lerp(dragX, targetX, collapse)
    val backTranslationY = lerp(dragY, targetY, collapse)
    val backScaleX = lerp(dragScale, targetScaleX, collapse)
    val backScaleY = lerp(dragScale, targetScaleY, collapse)

    val translationX = lerp(enterStartX, backTranslationX, enter)
    val translationY = lerp(enterStartY, backTranslationY, enter)
    val scaleX = lerp(enterStartScaleX, backScaleX, enter)
    val scaleY = lerp(enterStartScaleY, backScaleY, enter)
    val alpha = lerp(0f, lerp(1f, 0f, collapse), enter)

    // Corners are square at full expansion and round up as the page folds into the (pill-shaped) bar.
    val cornerProgress = maxOf(drag, collapse, 1f - enter)
    val shape = RoundedCornerShape(pairCollapsedCornerRadius * cornerProgress)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = BACK_DIM_ALPHA * enter * (1f - collapse) }
                .background(Color.Black),
        )
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { overlayBounds = it.boundsInRoot() }
                .graphicsLayer {
                    this.translationX = translationX
                    this.translationY = translationY
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                    this.alpha = alpha
                    this.shadowElevation = 24.dp.toPx() * cornerProgress
                    this.shape = shape
                    this.clip = cornerProgress > 0f
                },
            color = MaterialTheme.colorScheme.background,
            shape = shape,
        ) {
            PairingScreen(
                onBack = { scope.launch { collapseToPairButton() } },
                initialPairingPayload = initialPairingPayload,
                onInitialPairingPayloadConsumed = onInitialPairingPayloadConsumed,
            )
        }
    }
}

private fun transformPredictiveBackProgress(progress: Float): Float =
    predictiveBackEasing.transform(progress.coerceIn(0f, 1f))

private fun touchDeltaY(firstTouchY: Float, currentTouchY: Float): Float =
    if (firstTouchY.isNaN() || currentTouchY.isNaN()) 0f else currentTouchY - firstTouchY
