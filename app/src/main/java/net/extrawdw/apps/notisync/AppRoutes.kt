package net.extrawdw.apps.notisync

import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import java.util.UUID
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.navigation.label

/**
 * Type-safe routes. Keep this package and the nested names stable: their serializer names identify
 * destinations in saved navigation state, even though the host lives in the navigation package.
 */
internal sealed interface Route : NavKey {
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
    data class About(val id: String = UUID.randomUUID().toString()) : Route

    @Serializable
    data class Menu(val id: String = UUID.randomUUID().toString()) : Route
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

internal fun Route.isOn(dest: AppDestination): Boolean = this == dest.route
