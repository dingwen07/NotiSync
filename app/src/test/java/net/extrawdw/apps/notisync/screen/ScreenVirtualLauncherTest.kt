package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenVirtualLauncherTest {
    private val chat = VirtualLauncherApp("com.example.chat", "Chat", "My Messenger")
    private val browser = VirtualLauncherApp("org.browser.mobile", "Browser", "Browser")
    private val apps = listOf(chat, browser)

    @Test fun `search supports labels packages and combined terms without case sensitivity`() {
        assertEquals(listOf(chat), filterVirtualLauncherApps(apps, "MESSENGER"))
        assertEquals(listOf(chat), filterVirtualLauncherApps(apps, "com.example"))
        assertEquals(listOf(chat), filterVirtualLauncherApps(apps, "  messenger   CHAT  "))
        assertEquals(emptyList<VirtualLauncherApp>(), filterVirtualLauncherApps(apps, "missing"))
        assertEquals(apps, filterVirtualLauncherApps(apps, "  "))
    }
}
