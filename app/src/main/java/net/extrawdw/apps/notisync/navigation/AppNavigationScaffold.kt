package net.extrawdw.apps.notisync.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppNavigationScaffold(
    navigationSuiteType: NavigationSuiteType,
    railState: WideNavigationRailState,
    useModalRail: Boolean,
    railHeader: @Composable () -> Unit,
    navigationItems: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val isRail = navigationSuiteType == NavigationSuiteType.WideNavigationRailCollapsed ||
        navigationSuiteType == NavigationSuiteType.WideNavigationRailExpanded
    if (!isRail) {
        NavigationSuiteScaffold(
            navigationSuiteType = navigationSuiteType,
            navigationItems = navigationItems,
            content = content,
        )
        return
    }

    val railInsets = WideNavigationRailDefaults.windowInsets
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val horizontalRailInsets = with(density) {
        (railInsets.getLeft(density, layoutDirection) +
            railInsets.getRight(density, layoutDirection)).toDp()
    }
    // An explicit minimum replaces Material's 220 dp expanded fallback. Material still
    // measures the widest item with its own padding and animates up to its 360 dp maximum.
    val railModifier = Modifier.widthIn(min = 96.dp + horizontalRailInsets)

    // ModalWideNavigationRail remembers its movable content by lambda identity. Keep that
    // identity stable as the visible destinations change, so item animations are not recreated
    // while the native rail moves content between its collapsed layout and modal window.
    val currentNavigationItems by rememberUpdatedState(navigationItems)
    val currentRailHeader by rememberUpdatedState(railHeader)
    // Reserve bottom safe space inside the scroll content, not around its viewport.
    val bottomInsets = railInsets.only(WindowInsetsSides.Bottom)
    val currentBottomInsets by rememberUpdatedState(bottomInsets)
    val railContent: @Composable () -> Unit = remember {
        {
            NavigationRailContent(
                bottomInsets = currentBottomInsets,
                header = { currentRailHeader() },
                content = { currentNavigationItems() },
            )
        }
    }

    Surface {
        NavigationSuiteScaffoldLayout(
            navigationSuiteType = navigationSuiteType,
            navigationSuite = {
                // Material's Arrangement.Center centers across the header too. Own the header
                // and remaining viewport together so a tall destination list cannot cover it.
                if (useModalRail) {
                    ModalWideNavigationRail(
                        modifier = railModifier,
                        state = railState,
                        windowInsets = railInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start),
                        contentPadding = PaddingValues(top = 8.dp),
                        arrangement = Arrangement.Top,
                        content = railContent,
                    )
                } else {
                    WideNavigationRail(
                        modifier = railModifier,
                        state = railState,
                        windowInsets = railInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Start),
                        contentPadding = PaddingValues(top = 8.dp),
                        arrangement = Arrangement.Top,
                        content = railContent,
                    )
                }
            },
        ) {
            Box(Modifier.consumeWindowInsets(WideNavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start))) {
                content()
            }
        }
    }
}

@Composable
internal fun NonBouncyModalNavigationDrawer(
    enabled: Boolean,
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val appMotionScheme = MaterialTheme.motionScheme
    val drawerMotionScheme = remember(appMotionScheme) {
        NonBouncyDrawerMotionScheme(appMotionScheme)
    }
    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closingDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) backProgress.snapTo(0f)
    }
    MaterialTheme(motionScheme = drawerMotionScheme) {
        val scrimColor = DrawerDefaults.scrimColor
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            scrimColor = scrimColor.copy(alpha = scrimColor.alpha * (1f - backProgress.value)),
            drawerContent = {
                drawerContent(
                    Modifier.graphicsLayer {
                        val offset = drawerState.currentOffset.takeIf { it.isFinite() } ?: 0f
                        // Keep the preview displacement while close() animates its own offset.
                        // Reducing it with the remaining width avoids snapping or sliding twice.
                        val remainingWidth = (size.width + offset).coerceIn(0f, size.width)
                        translationX = closingDirection * remainingWidth * backProgress.value
                    },
                )
            },
        ) {
            MaterialTheme(motionScheme = appMotionScheme, content = content)
        }
        // Register after the destination content so Back closes the drawer before navigating.
        // Use the stateless sheet above to avoid Material's predictive scaling/stretching.
        PredictiveBackHandler(enabled = gesturesEnabled && drawerState.isOpen) { events ->
            try {
                backProgress.stop()
                events.collect { event -> backProgress.snapTo(event.progress.coerceIn(0f, 1f)) }
                drawerState.close()
                backProgress.snapTo(0f)
            } catch (cancelled: CancellationException) {
                scope.launch {
                    backProgress.animateTo(0f, drawerMotionScheme.defaultSpatialSpec())
                }
                throw cancelled
            }
        }
    }
}

/** Drawer motion should be quick and settle once; expressive overshoot is distracting in navigation. */
private class NonBouncyDrawerMotionScheme(base: MotionScheme) : MotionScheme by base {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
}
