package net.extrawdw.notisync.desktop

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPathsTest {
    private val home = Path.of("/Users/tester")

    @Test
    fun `macOS defaults daemon logs to Library Logs`() {
        val paths = DesktopPaths.defaults(home, "Mac OS X")

        assertEquals(home.resolve(".notisync"), paths.dataDirectory)
        assertEquals(home.resolve("Library/Logs/NotiSync"), paths.logDirectory)
    }

    @Test
    fun `Linux defaults daemon logs to XDG state log directory`() {
        val xdgState = "/var/tmp/test-state"
        val paths = DesktopPaths.defaults(home, "Linux", xdgStateHome = xdgState)

        assertEquals(Path.of(xdgState, "notisync/log"), paths.logDirectory)
    }

    @Test
    fun `Linux ignores relative XDG state home`() {
        val paths = DesktopPaths.defaults(home, "Linux", xdgStateHome = "relative-state")

        assertEquals(home.resolve(".local/state/notisync/log"), paths.logDirectory)
    }

    @Test
    fun `explicit data roots remain self contained unless log root is also set`() {
        val isolated = DesktopPaths.defaults(home, "Linux", configuredDataDirectory = "/tmp/notisync-data")
        val split = DesktopPaths.defaults(
            home,
            "Linux",
            configuredDataDirectory = "/tmp/notisync-data",
            configuredLogDirectory = "/tmp/notisync-logs",
        )

        assertEquals(Path.of("/tmp/notisync-data/logs"), isolated.logDirectory)
        assertEquals(Path.of("/tmp/notisync-logs"), split.logDirectory)
    }
}
