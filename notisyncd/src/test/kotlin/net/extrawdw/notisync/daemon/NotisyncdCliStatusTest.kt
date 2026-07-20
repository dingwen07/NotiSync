package net.extrawdw.notisync.daemon

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import net.extrawdw.notisync.desktop.DesktopPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotisyncdCliStatusTest {
    @Test
    fun `status reports not running when the daemon socket does not exist`() = withTemporaryRoot { root ->
        val result = status(root)

        assertEquals(1, result.exitCode)
        assertEquals("", result.output)
        assertEquals("notisyncd: not running\n", result.error)
        assertFalse(result.error.contains("No such file or directory"))
    }

    @Test
    fun `status reports not running when a stale daemon socket refuses connections`() =
        withTemporaryRoot { root ->
            val socket = root.resolve("S.notisyncd")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.bind(UnixDomainSocketAddress.of(socket))
            }

            val result = status(root)

            assertEquals(1, result.exitCode)
            assertEquals("", result.output)
            assertEquals("notisyncd: not running\n", result.error)
            assertFalse(result.error.contains("Connection refused"))
        }

    private fun status(root: Path): StatusResult {
        val output = StringBuilder()
        val error = StringBuilder()
        val exitCode = NotisyncdCli(DesktopPaths(root), output, error).run(arrayOf("status"))
        return StatusResult(exitCode, output.toString(), error.toString())
    }

    private fun withTemporaryRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "notisyncd-status-")
        try {
            block(root)
        } finally {
            Files.deleteIfExists(root.resolve("S.notisyncd"))
            Files.deleteIfExists(root)
        }
    }

    private data class StatusResult(
        val exitCode: Int,
        val output: String,
        val error: String,
    )
}
