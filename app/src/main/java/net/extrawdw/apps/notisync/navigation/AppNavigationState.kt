package net.extrawdw.apps.notisync.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import net.extrawdw.apps.notisync.AppDestination
import net.extrawdw.apps.notisync.Route

@Composable
internal fun rememberAppNavigationState(): AppNavigationState {
    val topLevelRoute = rememberSerializable(
        serializer = MutableStateSerializer(NavKeySerializer()),
    ) { mutableStateOf<NavKey>(Route.Devices) }
    // Keep every stack in composition, including tabs hidden by menu customization.
    val backStacks: Map<NavKey, NavBackStack<NavKey>> = AppDestination.entries.associate { destination ->
        destination.route to key(destination) { rememberNavBackStack(destination.route) }
    }
    return remember { AppNavigationState(topLevelRoute, backStacks) }
}

/** Owns tab history; utility pages are discarded when selecting any top-level destination. */
internal class AppNavigationState(
    topLevelRoute: MutableState<NavKey>,
    private val backStacks: Map<NavKey, NavBackStack<NavKey>>,
) {
    private var topLevelRoute by topLevelRoute
    private val currentStack get() = backStacks.getValue(topLevelRoute)
    val currentRoute: Route get() = currentStack.last() as Route

    fun navigateToTopLevel(destination: AppDestination) {
        while (currentStack.size > 1) currentStack.removeLastOrNull()
        topLevelRoute = destination.route
    }

    fun navigate(route: Route) {
        require(route is Route.Menu || route is Route.About)
        // Match launchSingleTop while keeping separate identities for Menu -> About -> Menu.
        if (currentRoute::class != route::class) currentStack.add(route)
    }

    fun goBack() {
        if (currentStack.size > 1) {
            currentStack.removeLastOrNull()
        } else {
            topLevelRoute = Route.Devices
        }
    }

    @Composable
    fun toEntries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        // Decorate each stack independently so inactive tabs retain saveable state and ViewModels.
        val entries = backStacks.mapValues { (route, stack) ->
            key(route) {
                rememberDecoratedNavEntries(
                    backStack = stack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider,
                )
            }
        }
        val home = entries.getValue(Route.Devices)
        return if (topLevelRoute == Route.Devices) home else home + entries.getValue(topLevelRoute)
    }
}
