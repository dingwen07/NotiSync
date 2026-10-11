package net.extrawdw.apps.notisync

import androidx.annotation.StringRes
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.navigation.label

/**
 * Type-safe routes. Keep this package and the nested names stable: their serializer names identify
 * destinations in saved navigation state, even though the NavHost lives in the navigation package.
 */
internal sealed interface Route {
    @Serializable
    data object Devices : Route

    @Serializable
    data object Apps : Route

    @Serializable
    data object Ios : Route

    @Serializable
    data object Run : Route

    @Serializable
    data object Seal : Route

    @Serializable
    data object SshAgent : Route

    @Serializable
    data object Activity : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object About : Route

    @Serializable
    data object Menu : Route
}

internal enum class AppDestination(val menu: MenuDestination, val route: Route) {
    DEVICES(MenuDestination.DEVICES, Route.Devices),
    APPS(MenuDestination.APPS, Route.Apps),
    IOS(MenuDestination.IPHONE, Route.Ios),
    ACTIVITY(MenuDestination.ACTIVITY, Route.Activity),
    SETTINGS(MenuDestination.SETTINGS, Route.Settings),
    RUN(MenuDestination.RUN, Route.Run),
    SEAL(MenuDestination.SEAL, Route.Seal),
    SSH_AGENT(MenuDestination.SSH_KEYS, Route.SshAgent);

    @get:StringRes val label: Int get() = menu.label

    companion object {
        fun fromMenu(menu: MenuDestination): AppDestination = entries.first { it.menu == menu }
    }
}

/**
 * Switch tabs the Now-in-Android way: save the outgoing tab's nested state, restore the incoming
 * tab's, and keep a single copy on the back stack so System Back from any tab returns to the start
 * destination (and from the start destination, exits).
 */
internal fun NavController.navigateToTopLevel(dest: AppDestination) {
    // Utility pages belong to their caller for Back, but must not become a saved tab stack.
    while (currentDestination?.hasRoute<Route.Menu>() == true ||
        currentDestination?.hasRoute<Route.About>() == true
    ) {
        if (!popBackStack()) break
    }
    if (currentDestination.isOn(dest)) return
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        // Devices already stays on the stack. Restoring its popUpTo alias can reopen
        // the outgoing page instead of returning to the start destination.
        restoreState = dest != AppDestination.DEVICES
    }
}

internal fun NavDestination?.isOn(dest: AppDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(dest.route::class) } == true
