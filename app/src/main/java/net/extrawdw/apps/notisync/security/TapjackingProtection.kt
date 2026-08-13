package net.extrawdw.apps.notisync.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Blocks non-system overlays and drops obscured touch events on a sensitive confirmation window. */
internal fun Activity.enableTapjackingProtection() {
    window.setHideOverlayWindows(true)
    window.decorView.filterTouchesWhenObscured = true
}

/** Applies window-level tapjacking protection only while this composable is present. */
@Composable
internal fun TapjackingProtectionEffect() {
    // LocalView.current.rootView is the window's DecorView. Its context can be Android's internal
    // DecorContext, whose wrapper chain does not lead back to the Activity. Resolve from Compose's
    // host context instead, then use the Activity's authoritative window and decor view.
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val decorView = window.decorView
    DisposableEffect(window, decorView) {
        val filteringWasEnabled = decorView.filterTouchesWhenObscured

        window.setHideOverlayWindows(true)
        decorView.filterTouchesWhenObscured = true

        onDispose {
            window.setHideOverlayWindows(false)
            decorView.filterTouchesWhenObscured = filteringWasEnabled
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
