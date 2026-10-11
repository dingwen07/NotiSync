package net.extrawdw.apps.notisync.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R

/** A single native rail: expanding it must not move its composition into a Dialog window. */
@Composable
internal fun InWindowModalNavigationRail(
    state: WideNavigationRailState,
    windowInsets: WindowInsets,
    railContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val collapsedWidth = 96.dp + with(density) {
        (windowInsets.getLeft(density, direction) + windowInsets.getRight(density, direction)).toDp()
    }
    val collapsedWidthPx = with(density) { collapsedWidth.roundToPx() }
    var widerThanSlot by remember { mutableStateOf(false) }
    // Keep the background blocked until the native width animation has actually finished.
    val modal = expanded || widerThanSlot
    val scope = rememberCoroutineScope()
    val close = { scope.launch { state.collapse() }; Unit }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val closeLabel = stringResource(R.string.close_features)
    val paneLabel = stringResource(R.string.menu_title)
    val colors = WideNavigationRailDefaults.colors()
    val scrimAlpha = animateFloatAsState(if (expanded) 1f else 0f, tween(220))
    // A timed fade also completes small surface-color differences without a spring threshold jump.
    val containerColor by animateColorAsState(
        if (expanded) colors.modalContainerColor else colors.containerColor, tween(220),
    )
    val contentColor by animateColorAsState(
        if (expanded) colors.modalContentColor else colors.contentColor, tween(220),
    )
    val baseMotion = MaterialTheme.motionScheme
    val railMotion = remember(baseMotion) { RailMotionScheme(baseMotion) }
    LaunchedEffect(expanded) {
        if (expanded) {
            focusManager.clearFocus()
            focusRequester.requestFocus()
        }
    }

    Surface {
        Box(Modifier.fillMaxSize()) {
            NavigationSuiteScaffoldLayout(
                navigationSuiteType = NavigationSuiteType.WideNavigationRailCollapsed,
                navigationSuite = { Spacer(Modifier.width(collapsedWidth).fillMaxHeight()) },
            ) {
                Box(
                    Modifier.consumeWindowInsets(windowInsets.only(WindowInsetsSides.Start))
                        .semantics { if (modal) hideFromAccessibility() }
                        .focusProperties { onEnter = { if (modal) cancelFocusChange() } }
                        .focusGroup(),
                ) { content() }
            }
            if (modal) {
                Canvas(
                    Modifier.fillMaxSize()
                        .clickable(interactionSource = null, indication = null, onClick = close)
                        .semantics { contentDescription = closeLabel },
                ) { drawRect(colors.modalScrimColor, alpha = scrimAlpha.value) }
            }
            MaterialTheme(motionScheme = railMotion) {
                WideNavigationRail(
                    state = state,
                    modifier = Modifier
                        .onSizeChanged { widerThanSlot = it.width > collapsedWidthPx }
                        .focusRequester(focusRequester)
                        .focusGroup()
                        .onPreviewKeyEvent {
                            if (modal && it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                                close()
                                true
                            } else false
                        }
                        .semantics {
                            if (modal) {
                                paneTitle = paneLabel
                                dismiss { close(); true }
                            }
                        },
                    colors = colors.copy(containerColor = containerColor, contentColor = contentColor),
                    shape = if (modal) WideNavigationRailDefaults.modalExpandedShape
                        else WideNavigationRailDefaults.modalCollapsedShape,
                    windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start),
                    contentPadding = PaddingValues(top = 8.dp),
                    arrangement = Arrangement.Top,
                    content = railContent,
                )
            }
            // Register after page content: Back closes the menu before navigating the page.
            BackHandler(enabled = modal, onBack = close)
        }
    }
}

/** Width, item placement, spacing and extra-row reveal use the same non-overshooting spring. */
private class RailMotionScheme(base: MotionScheme) : MotionScheme by base {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 500f)
}
