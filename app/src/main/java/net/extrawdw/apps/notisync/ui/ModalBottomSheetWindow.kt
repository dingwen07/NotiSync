package net.extrawdw.apps.notisync.ui

import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A full-height history sheet whose list viewport extends behind the navigation bar.
 *
 * The content must use [historySheetContentPadding] so its final item remains outside system UI.
 */
@Composable
internal fun EdgeToEdgeHistoryModalBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val view = LocalView.current
    SideEffect {
        val dialogWindow = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.isNavigationBarContrastEnforced = false
    }
}
