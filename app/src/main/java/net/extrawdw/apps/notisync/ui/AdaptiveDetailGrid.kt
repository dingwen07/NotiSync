package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keyed sections animate between columns and pack independently without equal-height row gaps. */
@Composable
internal fun AdaptiveDetailGrid(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    verticalSpacing: Dp,
    content: LazyStaggeredGridScope.(wide: Boolean) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val geometry = LocalDetailPaneGeometry.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val innerWidth = maxWidth - contentPadding.calculateStartPadding(direction) -
            contentPadding.calculateEndPadding(direction)
        // Settings rows fit at phone-like widths; unlike rich request previews they do not
        // need 400 dp per column. Include the gap when deciding whether both columns fit.
        val minimumWideWidth = 320.dp * 2 + 24.dp
        val wide = innerWidth >= minimumWideWidth
        val horizontalPadding = maxWidth - innerWidth
        // Reveal room for the next column without stretching the existing settings rows.
        // This limit applies only during inline pane expansion when two columns can fit.
        val retainedWidth = with(density) {
            if (!wide && geometry != null && geometry.width.toDp() - horizontalPadding >= minimumWideWidth) {
                Modifier.widthIn(max = geometry.splitDetailWidth.toDp())
            } else Modifier
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (wide) 2 else 1),
            modifier = retainedWidth.fillMaxWidth().testTag("adaptive-detail-grid"),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalItemSpacing = verticalSpacing,
        ) { content(wide) }
    }
}

internal fun LazyStaggeredGridScope.detailSection(
    key: String,
    fullLine: Boolean = false,
    content: @Composable () -> Unit,
) {
    item(key = key, span = if (fullLine) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane) {
        Column(
            Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
                .fillMaxWidth().testTag(key),
        ) { content() }
    }
}
