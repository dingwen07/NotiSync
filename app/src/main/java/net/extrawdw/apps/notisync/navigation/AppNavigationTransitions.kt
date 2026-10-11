package net.extrawdw.apps.notisync.navigation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.Path

/** Material shared-axis Z motion, with the enter/exit directions used by Drive's folders. */
internal object AppNavigationTransitions {
    private const val DurationMillis = 450
    private const val FadeThroughThreshold = 0.35f
    private const val RecededScale = 0.8f

    // Material's emphasized easing has two cubic segments.
    private val emphasized = PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )
    private val scaleSpec = tween<Float>(DurationMillis, easing = emphasized)

    // FadeThroughProvider splits the eased progress, rather than the elapsed time.
    // Keeping both fades on the full timeline also lets predictive back seek and reverse them.
    private val enterFadeSpec = tween<Float>(
        DurationMillis,
        easing = Easing { fraction ->
            ((emphasized.transform(fraction) - FadeThroughThreshold) /
                (1f - FadeThroughThreshold)).coerceIn(0f, 1f)
        },
    )
    private val exitFadeSpec = tween<Float>(
        DurationMillis,
        easing = Easing { fraction ->
            (emphasized.transform(fraction) / FadeThroughThreshold).coerceIn(0f, 1f)
        },
    )

    val enter = fadeIn(enterFadeSpec) + scaleIn(scaleSpec, initialScale = RecededScale)
    val exit = fadeOut(exitFadeSpec) + scaleOut(scaleSpec, targetScale = RecededScale)
}
