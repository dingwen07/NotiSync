package net.extrawdw.apps.notisync.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        content(itemsExpanded)
    }
}

/** Reveals secondary entries without abruptly changing the centered rail group's height. */
@Composable
internal fun AnimatedRailEntry(
    visible: Boolean,
    spacingAfter: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val spacing by animateDpAsState(
        targetValue = spacingAfter,
        animationSpec = motion.defaultSpatialSpec(),
        label = "Rail entry spacing",
    )
    AnimatedVisibility(
        visible = visible,
        // The items also change width as their labels move. Vertical-only helpers align at
        // TopCenter, which slides those changing-width children sideways during the reveal.
        enter = expandIn(
            animationSpec = motion.defaultSpatialSpec(),
            expandFrom = Alignment.TopStart,
            initialSize = { IntSize(it.width, 0) },
        ) + fadeIn(animationSpec = motion.defaultEffectsSpec()),
        exit = shrinkOut(
            animationSpec = motion.defaultSpatialSpec(),
            shrinkTowards = Alignment.TopStart,
            targetSize = { IntSize(it.width, 0) },
            // Material immediately constrains children to collapsed width. Let its still-
            // morphing indicator fade intact; the rail/scroll viewport provides outer clipping.
            clip = false,
        ) + fadeOut(animationSpec = motion.defaultEffectsSpec()),
    ) {
        Column {
            content()
            // Keep spacing inside the visibility animation so hidden rows leave no empty gaps.
            Spacer(Modifier.height(spacing))
        }
    }
}
