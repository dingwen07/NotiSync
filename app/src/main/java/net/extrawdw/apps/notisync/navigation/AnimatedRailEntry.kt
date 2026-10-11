package net.extrawdw.apps.notisync.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Gives newly composed secondary destinations the same native expansion as persistent items. */
@Composable
internal fun AnimatedExpandedRailEntries(
    expanded: Boolean,
    content: @Composable (expanded: Boolean) -> Unit,
) {
    AnimatedRailEntry(visible = expanded) {
        // AnimatedVisibility removes the rows after exit. Start each new appearance collapsed
        // before changing the target so Material can animate its icon/label positions.
        var itemsExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(expanded) { itemsExpanded = expanded }
        // New rows follow the revealing section. Root-relative bounds would compensate for
        // its movement and slide rows through the reveal clip before bringing them back.
        LookaheadScope {
            CompositionLocalProvider(LocalRailLookaheadScope provides this) {
                content(itemsExpanded)
            }
        }
    }
}

/** Reveals secondary entries without abruptly changing the centered rail group's height. */
@Composable
internal fun AnimatedRailEntry(
    visible: Boolean,
    spacingAfter: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // The items also change width as their labels move. Vertical-only helpers align at
        // TopCenter, which slides those changing-width children sideways during the reveal.
        enter = expandIn(
            animationSpec = motion.defaultSpatialSpec(),
            expandFrom = Alignment.TopStart,
            initialSize = { IntSize(it.width, 0) },
        ),
        exit = shrinkOut(
            animationSpec = motion.defaultSpatialSpec(),
            shrinkTowards = Alignment.TopStart,
            targetSize = { IntSize(it.width, 0) },
            // Material immediately constrains children to collapsed width. Let its still-
            // morphing indicator fade intact; the rail/scroll viewport provides outer clipping.
            clip = false,
        ),
    ) {
        val opacity = transition.animateFloat(
            transitionSpec = {
                when {
                    EnterExitState.PreEnter isTransitioningTo EnterExitState.Visible ||
                    EnterExitState.Visible isTransitioningTo EnterExitState.PostExit ->
                        motion.defaultEffectsSpec()
                    // Match AnimatedVisibility's fade behavior when an exit is interrupted.
                    else -> spring(stiffness = Spring.StiffnessMediumLow)
                }
            },
            label = "Rail entry opacity",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        // The default fade uses an offscreen buffer bounded by the animated row width.
        // ModulateAlpha preserves overflowing labels/indicators without changing layout.
        Column(Modifier.graphicsLayer {
            alpha = opacity.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }) {
            content()
            // Keep spacing inside the visibility animation so hidden rows leave no empty gaps.
            Spacer(Modifier.height(spacingAfter))
        }
    }
}

/** Animate each item's final bounds, rather than accumulating rounded spacer/row heights. */
@Composable
internal fun Modifier.animateRailItemPlacement(): Modifier {
    val scope = LocalRailLookaheadScope.current ?: return this
    return animateBounds(
        lookaheadScope = scope,
        boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) },
    )
}

/** Our scroll column must supply the minimum row heights normally provided by Material's rail. */
internal fun Modifier.railItemHeight(expanded: Boolean): Modifier =
    heightIn(min = if (expanded) 56.dp else 64.dp)

/** Keep Material's centered internal layout out of the animated width constraints. */
@Composable
internal fun RailItemPlacement(animate: Boolean, content: @Composable () -> Unit) {
    if (animate) {
        Box(Modifier.animateRailItemPlacement(), contentAlignment = Alignment.TopStart) { content() }
    } else {
        content()
    }
}
