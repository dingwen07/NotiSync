package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt

/**
 * A full-height history sheet whose list viewport extends behind the navigation bar.
 *
 * The content must use [historySheetContentPadding] so its final item remains outside system UI.
 */
@Suppress("DEPRECATION") // Keep the stable sheet-state factory until its replacement is stable.
@Composable
internal fun EdgeToEdgeHistoryModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val sheetMaxWidth = with(LocalDensity.current) { BottomSheetDefaults.SheetMaxWidth.roundToPx() }
    val fillsWindowWidth = windowWidth <= sheetMaxWidth
    val isFullScreen by remember(sheetState, fillsWindowWidth) {
        derivedStateOf {
            // Expanded can still be a floating sheet on a wide window. Use the actual position
            // so the handle and corners return as soon as a full-width sheet is dragged down.
            fillsWindowWidth && sheetState.hasExpandedState && sheetState.requireOffset() <= 0.5f
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // Keep the half-height anchor available even while asynchronous details are loading.
        modifier = Modifier.fillMaxHeight(),
        sheetState = sheetState,
        shape = if (isFullScreen) RectangleShape else BottomSheetDefaults.ExpandedShape,
        // Remove Material's clickable/semantics wrapper once the handle has collapsed.
        dragHandle = if (!isFullScreen) ({
            HistorySheetDragHandle(sheetState, fillsWindowWidth)
        }) else null,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        DisableModalBottomSheetNavigationBarContrast()
        content()
    }
}

@Composable
private fun HistorySheetDragHandle(sheetState: SheetState, fillsWindowWidth: Boolean) {
    val topInsets = WindowInsets.safeDrawing
    BottomSheetDefaults.DragHandle(
        modifier = Modifier.clipToBounds().layout { measurable, constraints ->
            val handle = measurable.measure(constraints)
            // Material's top padding grows once the sheet enters the status bar. Collapse the
            // handle throughout that region so the header keeps moving instead of waiting there
            // for a second animation. Read offset/insets during layout to follow the same frame.
            val collapseDistance = maxOf(topInsets.getTop(this), handle.height, 1).toFloat()
            val fraction = if (fillsWindowWidth && sheetState.hasExpandedState) {
                (sheetState.requireOffset() / collapseDistance).coerceIn(0f, 1f)
            } else 1f
            val height = (handle.height * fraction).roundToInt()
            layout(handle.width, height) {
                handle.placeRelativeWithLayer(0, (height - handle.height) / 2) {
                    alpha = fraction
                }
            }
        },
    )
}

/** Keeps the title outside the list's overscroll effect while the body still stretches. */
@Composable
internal fun HistorySheetLazyColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    header: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    if (header == null) {
        LazyColumn(
            modifier = modifier,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        return
    }

    val layoutDirection = LocalLayoutDirection.current
    val startPadding = contentPadding.calculateStartPadding(layoutDirection)
    val endPadding = contentPadding.calculateEndPadding(layoutDirection)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().padding(
                start = startPadding,
                top = contentPadding.calculateTopPadding(),
                end = endPadding,
            ),
        ) {
            header()
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = startPadding,
                top = verticalArrangement.spacing,
                end = endPadding,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Composable
internal fun historySheetContentPadding(): PaddingValues {
    val navigationBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    return PaddingValues(
        start = 16.dp,
        end = 16.dp,
        bottom = maxOf(96.dp, navigationBottom + 72.dp),
    )
}

/** Keeps a Material modal sheet's own dialog window transparent behind three-button navigation. */
@Composable
internal fun DisableModalBottomSheetNavigationBarContrast() {
    val view = LocalView.current
    SideEffect {
        val dialogWindow = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.isNavigationBarContrastEnforced = false
    }
}
