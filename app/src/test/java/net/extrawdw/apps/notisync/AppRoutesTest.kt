package net.extrawdw.apps.notisync

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutesTest {
    @Test
    fun routeNamesRemainCompatibleWithSavedNavigationState() {
        // Navigation derives destination IDs from these descriptors. Moving files must not
        // rename routes and invalidate a back stack saved before the refactor.
        val descriptors = listOf(
            Route.Devices.serializer().descriptor,
            Route.Apps.serializer().descriptor,
            Route.Ios.serializer().descriptor,
            Route.Run.serializer().descriptor,
            Route.Seal.serializer().descriptor,
            Route.SshAgent.serializer().descriptor,
            Route.Activity.serializer().descriptor,
            Route.Settings.serializer().descriptor,
            Route.About.serializer().descriptor,
            Route.Menu.serializer().descriptor,
        )
        assertEquals(
            listOf("Devices", "Apps", "Ios", "Run", "Seal", "SshAgent", "Activity", "Settings", "About", "Menu")
                .map { "net.extrawdw.apps.notisync.Route.$it" },
            descriptors.map { it.serialName },
        )
    }
}
