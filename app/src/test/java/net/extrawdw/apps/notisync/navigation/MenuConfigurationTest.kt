package net.extrawdw.apps.notisync.navigation

import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuConfigurationTest {
    @Test
    fun resizingMovesOverflowIntoMenuWithoutDuplicatesOrLostSelections() {
        val order = listOf("run", "seal", "ssh_keys", "apps", "iphone", "activity", "settings", "devices")
        val configuration = MenuConfiguration(order = order, navigation = order.toSet())

        assertEquals(order.take(7), configuration.navigationDestinations(7).map { it.id })
        assertEquals(order.drop(7), configuration.overflowDestinations(7).map { it.id })
        assertEquals(order.take(5), configuration.navigationDestinations(5).map { it.id })
        assertEquals(order.drop(5), configuration.overflowDestinations(5).map { it.id })
        assertEquals(order.take(7), configuration.navigationDestinations(7).map { it.id })
        assertEquals(order, configuration.destinations().map { it.id })
        assertEquals(order.toSet(), configuration.navigation)
    }

    @Test
    fun savedCustomizationRoundTripsWithoutLosingUnselectedPages() {
        val configuration = MenuConfiguration(
            order = listOf("seal", "iphone", "apps", "run", "ssh_keys", "devices", "activity", "settings"),
            navigation = setOf("seal", "iphone"),
            shortcuts = setOf("iphone", "ssh_keys"),
        )
        val restored = ProtocolCodec.decodeFromJson<MenuConfiguration>(
            ProtocolCodec.encodeToJson(configuration),
        ).normalized()

        assertEquals(configuration, restored)
        assertEquals(listOf("seal", "iphone"), restored.navigationDestinations(5).map { it.id })
        assertEquals(configuration.order.drop(2), restored.overflowDestinations(5).map { it.id })
        assertEquals(MenuDestination.entries.toSet(), restored.destinations().toSet())
    }

    @Test
    fun staleConfigurationRestoresMissingPagesAndOneUsableNavigationEntry() {
        val restored = MenuConfiguration(
            order = listOf("removed_page", "seal", "seal"),
            navigation = setOf("removed_page"),
            shortcuts = setOf("removed_page", "iphone"),
        ).normalized()

        assertEquals(MenuDestination.entries.toSet(), restored.destinations().toSet())
        assertEquals(8, restored.order.size)
        assertEquals(listOf(MenuDestination.SEAL), restored.navigationDestinations(5))
        assertEquals(setOf("iphone"), restored.shortcuts)
        assertTrue("iphone" in MenuConfiguration().shortcuts)
    }
}
