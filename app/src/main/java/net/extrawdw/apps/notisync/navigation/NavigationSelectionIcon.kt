package net.extrawdw.apps.notisync.navigation

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.integerResource
import net.extrawdw.apps.notisync.R

/** Morphs the symbol's FILL axis without changing the navigation item's size or position. */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
internal fun NavigationSelectionIcon(
    icon: ImageVector,
    @DrawableRes animation: Int?,
    selected: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    rotationWhenSelected: Float = 0f,
) {
    val rotation = animateFloatAsState(
        targetValue = if (selected) rotationWhenSelected else 0f,
        animationSpec = tween(
            durationMillis = integerResource(R.integer.navigation_icon_selection_duration_ms),
            easing = FastOutSlowInEasing,
        ),
        label = "Navigation icon rotation",
    )
    val iconModifier = modifier.graphicsLayer { rotationZ = rotation.value }
    if (animation == null) {
        Icon(icon, contentDescription, iconModifier)
    } else {
        val vector = AnimatedImageVector.animatedVectorResource(animation)
        Icon(
            painter = rememberAnimatedVectorPainter(vector, atEnd = selected),
            contentDescription = contentDescription,
            modifier = iconModifier,
        )
    }
}
