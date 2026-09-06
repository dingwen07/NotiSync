package net.extrawdw.apps.notisync.sshkeyprovider

import org.junit.Assert.*
import org.junit.Test

class DesktopApplicationIconSourceTest {
    @Test
    fun parsesNumericIdsAndBundleIds() {
        assertEquals(DesktopApplicationIconSource.AppStore("497799835"), DesktopApplicationIconSource.parse(" 497799835 "))
        assertEquals(DesktopApplicationIconSource.AppStore("497799835"), DesktopApplicationIconSource.parse("id497799835"))
        assertEquals(DesktopApplicationIconSource.BundleId("com.apple.dt.Xcode"), DesktopApplicationIconSource.parse("com.apple.dt.Xcode"))
    }

    @Test
    fun extractsStorefrontAndIdentityFromModernAndLegacyLinks() {
        assertEquals(
            DesktopApplicationIconSource.AppStore("497799835", "gb"),
            DesktopApplicationIconSource.parse("https://apps.apple.com/gb/app/xcode/id497799835?mt=12"),
        )
        assertEquals(
            DesktopApplicationIconSource.AppStore("497799835"),
            DesktopApplicationIconSource.parse("https://apps.apple.com/app/id497799835?l=en"),
        )
        assertEquals(
            DesktopApplicationIconSource.AppStore("497799835", "cn"),
            DesktopApplicationIconSource.parse("http://itunes.apple.com/cn/app/xcode/id497799835"),
        )
        assertEquals(
            DesktopApplicationIconSource.AppStore("497799835"),
            DesktopApplicationIconSource.parse("itms-apps://itunes.apple.com/WebObjects/MZStore.woa/wa/viewSoftware?id=497799835&mt=8"),
        )
    }

    @Test
    fun keepsImageUrlsAndQueryStringsWithoutGuessingTheirFormat() {
        listOf(
            "https://example.com/icon.png?signature=a%2Fb&size=512",
            "https://example.com/image?id=7",
            "https://example.com/icon.svg",
            "https://example.com/icon.webp",
        ).forEach { url ->
            assertEquals(DesktopApplicationIconSource.ImageUrl(url), DesktopApplicationIconSource.parse(url))
        }
    }

    @Test
    fun rejectsInvalidSourcesWithoutTreatingArbitraryApplePagesAsApplications() {
        listOf("", "0", "-12", "999999999999999999999999", "https://", "file:///icon.png",
            "content://documents/1", "javascript:alert(1)", "https://user:pass@example.com/image",
            "http://example.com/image", "https://apps.apple.com/us/app/no-app-id", "a b",
        ).forEach { assertNull(it, DesktopApplicationIconSource.parse(it)) }
    }
}
