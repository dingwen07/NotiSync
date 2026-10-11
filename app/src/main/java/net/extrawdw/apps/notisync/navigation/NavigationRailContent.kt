package net.extrawdw.apps.notisync.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/** Centers destinations in the rail while keeping their scroll viewport below the menu button. */
@Composable
internal fun NavigationRailContent(
    bottomInsets: WindowInsets,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxHeight(),
        content = {
            Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) { header() }
            // Material items supply their own leading inset, so keep their icons anchored.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) {
                content()
                // Keep the viewport edge-to-edge, with enough scrollable space to bring the
                // final item above the system navigation bar when scrolled to the end.
                Spacer(Modifier.windowInsetsBottomHeight(bottomInsets))
            }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val headerPlaceable = measurables[0].measure(looseConstraints)
        val height = constraints.maxHeight
        val contentTop = (headerPlaceable.height + 8.dp.roundToPx()).coerceAtMost(height)
        val destinations = measurables[1].measure(
            looseConstraints.copy(maxHeight = height - contentTop),
        )
        val width = constraints.constrainWidth(maxOf(headerPlaceable.width, destinations.width))
        layout(width, height) {
            headerPlaceable.placeRelative(0, 0)
            // Center against the full rail, not just the space left below the header.
            // The trailing inset balances the bottom safe area. Tall lists start below
            // the header instead of overlapping it, and retain a bounded scroll viewport.
            val destinationTop = ((height - destinations.height) / 2).coerceAtLeast(contentTop)
            destinations.placeRelative(0, destinationTop)
        }
    }
}
