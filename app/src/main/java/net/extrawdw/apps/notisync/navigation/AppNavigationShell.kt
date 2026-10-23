package net.extrawdw.apps.notisync.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.AppDestination
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.Route
import net.extrawdw.apps.notisync.isOn
import net.extrawdw.apps.notisync.ui.LocalFeatureDrawerOpener
import net.extrawdw.apps.notisync.ui.icons.material.outlined.edit as EditIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.info as InfoIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.menu as MenuIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.menu_open as MenuOpenIcon

/** Owns adaptive navigation presentation and menu expansion; routing remains with the caller. */
@Composable
internal fun AppNavigationShell(
    menuConfiguration: MenuConfiguration,
    currentDestination: Route,
    openMenuDestination: MenuDestination?,
    onNavigationRequestStarted: () -> Unit,
    onOpenMenuDestinationConsumed: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
    openMenu: () -> Unit,
    openAbout: () -> Unit,
    content: @Composable (isRail: Boolean, navigationLimit: Int) -> Unit,
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val layoutType = NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    val suiteIsDrawer = layoutType == NavigationSuiteType.NavigationDrawer
    val suiteIsRail = layoutType == NavigationSuiteType.WideNavigationRailCollapsed ||
        layoutType == NavigationSuiteType.WideNavigationRailExpanded
    // Reserve enough space for two preferred panes even at Material's widest expanded rail.
    // Otherwise rail expansion alone changes an open inline detail into a modal sheet.
    val paneDirective = calculatePaneScaffoldDirective(adaptiveInfo)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    val safeWindowWidth = with(density) {
        (LocalWindowInfo.current.containerSize.width - safeInsets.getLeft(density, layoutDirection) -
            safeInsets.getRight(density, layoutDirection)).toDp()
    }
    val expandedRailSpace = 360.dp // Material 3 expanded rail maximum, including long labels.
    val twoPaneSpace = paneDirective.defaultPanePreferredWidth * 2 + paneDirective.horizontalPartitionSpacerSize
    val useModalRail = !adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    ) || safeWindowWidth - expandedRailSpace < twoPaneSpace
    val railState = rememberWideNavigationRailState()
    val railExpanded = suiteIsRail && railState.targetValue == WideNavigationRailValue.Expanded
    val itemLayoutType = if (railExpanded) NavigationSuiteType.WideNavigationRailExpanded else layoutType
    val railItemSpacing = if (adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(
            WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND,
        )) 12.dp else 8.dp
    val navigationLimit = if (suiteIsDrawer) 8 else if (suiteIsRail) 7 else 5
    val collapsedDestinations = menuConfiguration.navigationDestinations(navigationLimit)
        .map(AppDestination::fromMenu)
    // Keep the collapsed destinations first, with all expanded-only entries appended together.
    val navigationDestinations = if (suiteIsRail) {
        menuConfiguration.expandedRailDestinations(navigationLimit)
    } else {
        menuConfiguration.navigationDestinations(navigationLimit, showAll = suiteIsDrawer)
    }.map(AppDestination::fromMenu)
    val overflowDestinations = menuConfiguration.overflowDestinations(navigationLimit).map(AppDestination::fromMenu)
    val featureDrawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var pendingFeatureDestination by remember { mutableStateOf<AppDestination?>(null) }

    LaunchedEffect(suiteIsRail) {
        if (suiteIsRail) featureDrawerState.close()
        else railState.snapTo(WideNavigationRailValue.Collapsed)
    }

    fun navigateFromNavigation(action: () -> Unit) {
        if (railExpanded && useModalRail) {
            drawerScope.launch {
                railState.collapse()
                action()
            }
        } else action()
    }

    LaunchedEffect(openMenuDestination) {
        openMenuDestination?.let {
            onNavigationRequestStarted()
            featureDrawerState.close()
            if (useModalRail) railState.collapse()
            onNavigate(AppDestination.fromMenu(it))
            onOpenMenuDestinationConsumed()
        }
    }

    val navigationItems: @Composable () -> Unit = {
        val destinationItem: @Composable (AppDestination, NavigationSuiteType) -> Unit = { dest, type ->
            val selected = currentDestination.isOn(dest)
            RailItemPlacement(animate = suiteIsRail && dest !in collapsedDestinations) {
                NavigationSuiteItem(
                    navigationSuiteType = type,
                    modifier = if (suiteIsRail) Modifier.railItemHeight(
                        expanded = type == NavigationSuiteType.WideNavigationRailExpanded,
                    ) else Modifier,
                    selected = selected,
                    onClick = { navigateFromNavigation { onNavigate(dest) } },
                    icon = { TopLevelNavIcon(dest, selected) },
                    label = { TopLevelNavLabel(dest) },
                )
            }
        }
        val primaryDestinations = if (suiteIsRail) collapsedDestinations else navigationDestinations
        primaryDestinations.forEach { dest ->
            key(dest) {
                if (suiteIsRail) {
                    AnimatedRailEntry(
                        visible = true,
                        modifier = Modifier.animateRailItemPlacement(),
                        spacingAfter = if (!railExpanded && dest != collapsedDestinations.lastOrNull())
                            railItemSpacing else 0.dp,
                    ) { destinationItem(dest, itemLayoutType) }
                } else destinationItem(dest, itemLayoutType)
            }
        }
        val utilities: @Composable (NavigationSuiteType) -> Unit = { type ->
            if (suiteIsDrawer) HorizontalDivider() else Spacer(Modifier.height(12.dp))
            RailItemPlacement(animate = suiteIsRail) {
                NavigationSuiteItem(
                    navigationSuiteType = type,
                    modifier = if (suiteIsRail) Modifier.railItemHeight(
                        expanded = type == NavigationSuiteType.WideNavigationRailExpanded,
                    ) else Modifier,
                    selected = currentDestination is Route.Menu,
                    onClick = { navigateFromNavigation(openMenu) },
                    icon = {
                        NavigationSelectionIcon(
                            EditIcon, R.drawable.avd_nav_edit,
                            currentDestination is Route.Menu, null,
                        )
                    },
                    label = { Text(stringResource(R.string.menu_title)) },
                )
            }
            RailItemPlacement(animate = suiteIsRail) {
                NavigationSuiteItem(
                    navigationSuiteType = type,
                    modifier = if (suiteIsRail) Modifier.railItemHeight(
                        expanded = type == NavigationSuiteType.WideNavigationRailExpanded,
                    ) else Modifier,
                    selected = currentDestination is Route.About,
                    onClick = { navigateFromNavigation(openAbout) },
                    icon = {
                        NavigationSelectionIcon(
                            InfoIcon, R.drawable.avd_nav_info,
                            currentDestination is Route.About, null,
                        )
                    },
                    label = { Text(stringResource(R.string.about_title)) },
                )
            }
        }
        if (suiteIsRail) {
            // Reveal the whole appended section together. Independent row-height animations
            // let Menu appear below a partially revealed row, then push it out of the viewport.
            AnimatedExpandedRailEntries(expanded = railExpanded) { appendedExpanded ->
                val appendedLayoutType = if (appendedExpanded) {
                    NavigationSuiteType.WideNavigationRailExpanded
                } else NavigationSuiteType.WideNavigationRailCollapsed
                navigationDestinations.filterNot { it in collapsedDestinations }.forEach { dest ->
                    key(dest) { destinationItem(dest, appendedLayoutType) }
                }
                utilities(appendedLayoutType)
            }
        } else if (suiteIsDrawer) {
            utilities(itemLayoutType)
        }
    }

    NonBouncyModalNavigationDrawer(
        enabled = !suiteIsRail,
        drawerState = featureDrawerState,
        gesturesEnabled = !suiteIsDrawer,
        drawerContent = { sheetModifier ->
            ModalDrawerSheet(modifier = sheetModifier.width(296.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.menu_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            drawerScope.launch { featureDrawerState.close(); openMenu() }
                        }) {
                            Icon(EditIcon, stringResource(R.string.menu_manage))
                        }
                    }
                    HorizontalDivider()
                    // Hide only pages actually visible in navigation. Selections beyond its adaptive
                    // limit remain reachable here when the window becomes smaller.
                    overflowDestinations.forEach { dest ->
                        val selected = pendingFeatureDestination?.let { it == dest }
                            ?: currentDestination.isOn(dest)
                        NavigationDrawerItem(
                            selected = selected,
                            onClick = {
                                if (pendingFeatureDestination == null) {
                                    pendingFeatureDestination = dest
                                    drawerScope.launch {
                                        try {
                                            featureDrawerState.close()
                                            onNavigate(dest)
                                        } finally {
                                            pendingFeatureDestination = null
                                        }
                                    }
                                }
                            },
                            icon = { TopLevelNavIcon(dest, selected) },
                            label = { TopLevelNavLabel(dest) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    selected = currentDestination is Route.About,
                    onClick = { drawerScope.launch { featureDrawerState.close(); openAbout() } },
                    icon = {
                        NavigationSelectionIcon(
                            InfoIcon, R.drawable.avd_nav_info,
                            currentDestination is Route.About, null,
                        )
                    },
                    label = { Text(stringResource(R.string.about_title)) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
    ) {
        CompositionLocalProvider(
            // On tablets the hamburger belongs to the rail header, not the page's top app bar.
            LocalFeatureDrawerOpener provides if (suiteIsDrawer || suiteIsRail) null else ({
                drawerScope.launch { featureDrawerState.open() }
            })
        ) {
            AppNavigationScaffold(
                navigationSuiteType = layoutType,
                railState = railState,
                useModalRail = useModalRail,
                railHeader = {
                    if (suiteIsRail) {
                        IconButton(onClick = { drawerScope.launch { railState.toggle() } }) {
                            Icon(
                                if (railExpanded) MenuOpenIcon else MenuIcon,
                                stringResource(if (railExpanded) R.string.close_features else R.string.open_features),
                            )
                        }
                    }
                },
                navigationItems = {
                    if (suiteIsDrawer) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { navigationItems() }
                    } else navigationItems()
                },
            ) {
                content(suiteIsRail, navigationLimit)
            }
        }
    }
}

// Every tab glyph is centered in a 24dp box, but mobile_3 fills 22/24 of its viewBox (vs 16–20
// for the others), so its taller silhouette reads as raised. Render just the iOS glyph slightly
// smaller, inside the same 24dp box, so its visual height matches the rest of the set.
private val TopLevelNavIconSize = 24.dp
private val TopLevelNavIosIconSize = 20.dp

@Composable
private fun TopLevelNavIcon(dest: AppDestination, selected: Boolean) {
    val glyphSize =
        if (dest == AppDestination.IOS) TopLevelNavIosIconSize else TopLevelNavIconSize
    Box(Modifier.size(TopLevelNavIconSize), contentAlignment = Alignment.Center) {
        NavigationSelectionIcon(
            icon = dest.menu.icon,
            animation = dest.menu.selectionAnimation,
            selected = selected,
            contentDescription = stringResource(dest.label),
            modifier = Modifier.size(glyphSize),
            rotationWhenSelected = when (dest) {
                AppDestination.SETTINGS -> 60f
                else -> 0f
            },
        )
    }
}

@Composable
private fun TopLevelNavLabel(dest: AppDestination) {
    Text(stringResource(dest.label), maxLines = 1)
}
