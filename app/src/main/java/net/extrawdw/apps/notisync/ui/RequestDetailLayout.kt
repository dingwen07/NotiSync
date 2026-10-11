package net.extrawdw.apps.notisync.ui

import androidx.compose.animation.animateBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/** Approval summaries stay beside independently scrollable details when the page has room. */
@Composable
internal fun RequestDetailLayout(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    useWideLayout: Boolean,
    header: @Composable (() -> Unit)? = null,
    summary: @Composable ColumnScope.() -> Unit,
    requestInfo: (@Composable ColumnScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val paneGeometry = LocalDetailPaneGeometry.current
    val density = LocalDensity.current
    val expandedPaneWidth = with(density) { paneGeometry?.width?.toDp() ?: Dp.Unspecified }
    val splitPaneWidth = with(density) { paneGeometry?.splitDetailWidth?.toDp() ?: Dp.Unspecified }
    LookaheadScope {
        val summaryContent = rememberDetailContent(this, summary)
        val detailContent = rememberDetailContent(this, details)
        val requestInfoContent = rememberDetailContent(this) { requestInfo?.invoke(this) }
        BoxWithConstraints(modifier.fillMaxSize()) {
            val start = contentPadding.calculateStartPadding(direction)
            val end = contentPadding.calculateEndPadding(direction)
            val wide = useWideLayout && maxWidth - start - end >= 800.dp
            // While revealing a second column, retain the original half-pane card width.
            // Windows too narrow for two columns and standalone approvals still fill normally.
            val cardWidth = if (
                useWideLayout && !wide && expandedPaneWidth.isSpecified &&
                expandedPaneWidth - start - end >= 800.dp
            ) {
                Modifier.widthIn(max = (splitPaneWidth - start - end).coerceAtLeast(0.dp))
            } else Modifier
            val fixedHeader = !wide && header != null
            val columnPadding = PaddingValues(
                top = if (fixedHeader) 16.dp else contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            )
            // Keep both scroll containers attached across reflow. Lazy lists can deactivate
            // movable cards, and animateBounds tracks the scrolling ancestors between frames.
            Column(Modifier.fillMaxSize()) {
                if (fixedHeader) {
                    Box(
                        Modifier.fillMaxWidth().padding(
                            start = start,
                            top = contentPadding.calculateTopPadding(),
                            end = end,
                        ),
                    ) {
                        header()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(start = start, end = end),
                    horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 0.dp),
                ) {
                    // The wrapper keeps each scroll origin local to its column; horizontal
                    // reflow must not be mistaken for a change in the scroll offset.
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(columnPadding),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            summaryContent(cardWidth)
                            if (!wide) detailContent(cardWidth)
                            if (requestInfo != null) requestInfoContent(cardWidth)
                        }
                    }
                    Box(
                        (if (wide) Modifier.weight(1f) else Modifier.width(0.dp)).fillMaxHeight(),
                    ) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(columnPadding),
                        ) {
                            if (wide) detailContent(cardWidth)
                        }
                    }
                }
            }
        }
    }
}

/** One composition and animation identity per group, including while it moves between parents. */
@Composable
private fun rememberDetailContent(
    lookaheadScope: LookaheadScope,
    content: @Composable ColumnScope.() -> Unit,
): @Composable (Modifier) -> Unit {
    val currentContent by rememberUpdatedState(content)
    val motionSpec by rememberUpdatedState(MaterialTheme.motionScheme.defaultSpatialSpec<Rect>())
    return remember(lookaheadScope) {
        movableContentOf<Modifier> { contentModifier ->
            Column(
                Modifier.animateBounds(
                    lookaheadScope,
                    modifier = contentModifier,
                    boundsTransform = { _, _ -> motionSpec },
                )
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                currentContent()
            }
        }
    }
}
