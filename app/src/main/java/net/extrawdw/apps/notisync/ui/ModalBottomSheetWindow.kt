package net.extrawdw.apps.notisync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

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
    val handleVisibility = remember { MutableTransitionState(true) }
    handleVisibility.targetState = !isFullScreen
    val handleSizeAnimation = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val handleFadeAnimation = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = if (isFullScreen) RectangleShape else BottomSheetDefaults.ExpandedShape,
        // Keep Material's handle until its exit finishes, then remove its clickable/semantics
        // wrapper too. Animating height also moves the history header smoothly into place.
        dragHandle = if (handleVisibility.currentState || handleVisibility.targetState) ({
            AnimatedVisibility(
                visibleState = handleVisibility,
                enter = fadeIn(handleFadeAnimation) + expandVertically(
                    animationSpec = handleSizeAnimation,
                    expandFrom = Alignment.Top,
                ),
                exit = fadeOut(handleFadeAnimation) + shrinkVertically(
                    animationSpec = handleSizeAnimation,
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                BottomSheetDefaults.DragHandle()
            }
        }) else null,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        DisableModalBottomSheetNavigationBarContrast()
        content()
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
