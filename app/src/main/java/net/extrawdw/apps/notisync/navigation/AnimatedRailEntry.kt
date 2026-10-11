package net.extrawdw.apps.notisync.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
        enter = expandVertically(
            animationSpec = motion.defaultSpatialSpec(),
            expandFrom = Alignment.Top,
        ) + fadeIn(animationSpec = motion.defaultEffectsSpec()),
        exit = shrinkVertically(
            animationSpec = motion.defaultSpatialSpec(),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = motion.defaultEffectsSpec()),
    ) {
        Column {
            content()
            // Keep spacing inside the visibility animation so hidden rows leave no empty gaps.
            Spacer(Modifier.height(spacing))
        }
    }
}
