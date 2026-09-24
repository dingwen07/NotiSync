package net.extrawdw.apps.notisync.navigation

import kotlinx.serialization.Serializable

enum class MenuDestination(val id: String) {
    DEVICES("devices"), APPS("apps"), IPHONE("iphone"), ACTIVITY("activity"), SETTINGS("settings"),
    RUN("run"), SEAL("seal"), SSH_KEYS("ssh_keys");

    companion object {
        fun fromId(id: String?): MenuDestination? = entries.firstOrNull { it.id == id }
    }
}

/** Stable IDs tolerate new destinations and older saved configurations without losing menu access. */
@Serializable
data class MenuConfiguration(
    val order: List<String> = MenuDestination.entries.map { it.id },
    val navigation: Set<String> = MenuDestination.entries.take(5).map { it.id }.toSet(),
    val shortcuts: Set<String> = setOf(MenuDestination.APPS.id, MenuDestination.IPHONE.id),
) {
    fun normalized(): MenuConfiguration {
        val known = MenuDestination.entries.map { it.id }
        val completeOrder = (order.filter { it in known } + known).distinct()
        return copy(
            order = completeOrder,
            navigation = navigation.intersect(known.toSet()).ifEmpty { setOf(completeOrder.first()) },
            shortcuts = completeOrder.filter { it in shortcuts }.take(4).toSet(),
        )
    }

    fun destinations(): List<MenuDestination> = normalized().order.mapNotNull(MenuDestination::fromId)

    fun navigationDestinations(limit: Int): List<MenuDestination> =
        destinations().filter { it.id in normalized().navigation }.take(limit)

    fun overflowDestinations(limit: Int): List<MenuDestination> {
        val visible = navigationDestinations(limit).toSet()
        return destinations().filterNot { it in visible }
    }
}
