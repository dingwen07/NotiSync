package net.extrawdw.apps.notisync.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/** Blocks non-system overlays and drops obscured touch events on a sensitive confirmation window. */
internal fun Activity.enableTapjackingProtection() {
    window.setHideOverlayWindows(true)
    window.decorView.filterTouchesWhenObscured = true
}

/** Applies window-level tapjacking protection only while this composable is present. */
@Composable
internal fun TapjackingProtectionEffect() {
    val rootView = LocalView.current.rootView
    val activity = rootView.context.findActivity() ?: return
    DisposableEffect(activity, rootView) {
        val filteringWasEnabled = rootView.filterTouchesWhenObscured

        activity.window.setHideOverlayWindows(true)
        rootView.filterTouchesWhenObscured = true

        onDispose {
            activity.window.setHideOverlayWindows(false)
            rootView.filterTouchesWhenObscured = filteringWasEnabled
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
