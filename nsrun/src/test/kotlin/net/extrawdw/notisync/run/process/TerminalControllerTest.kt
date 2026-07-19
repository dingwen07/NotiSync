package net.extrawdw.notisync.run.process

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalControllerTest {
    @Test
    fun `close restores saved termios and full-screen presentation exactly once`() {
        val tty = Files.createTempFile("nsrun-tty", ".test")
        val calls = mutableListOf<List<String>>()
        val writes = mutableListOf<String>()
        val terminal = SttyTerminalController(
            tty = tty,
            stty = { arguments ->
                calls += arguments
                if (arguments == listOf("-g")) "saved-state\n" else ""
            },
            terminalWriter = { writes += it.toString(Charsets.UTF_8) },
        )

        terminal.enterRawMode()
        terminal.observeOutput("\u001B[?1049h\u001B[?25l\u001B[?1006h\u001B[?2004h".encodeToByteArray())
        terminal.close()
        terminal.close()

        assertEquals(listOf(listOf("-g"), listOf("raw", "-echo"), listOf("saved-state")), calls)
        assertEquals(1, writes.size)
        assertTrue(writes.single().contains("\u001B[?1049l"))
        assertTrue(writes.single().contains("\u001B[?25h"))
        assertTrue(writes.single().contains("\u001B[?1006l"))
        assertTrue(writes.single().contains("\u001B[?2004l"))
    }

    @Test
    fun `close uses sane fallback when exact termios restoration fails`() {
        val tty = Files.createTempFile("nsrun-tty-fallback", ".test")
        val calls = mutableListOf<List<String>>()
        val terminal = SttyTerminalController(
            tty = tty,
            stty = { arguments ->
                calls += arguments
                when (arguments) {
                    listOf("-g") -> "saved-state"
                    listOf("saved-state") -> error("state rejected")
                    else -> ""
                }
            },
            terminalWriter = {},
        )

        terminal.enterRawMode()
        terminal.close()

        assertEquals(listOf("sane"), calls.last())
    }

    @Test
    fun `healthy child teardown produces no presentation recovery bytes`() {
        val tty = Files.createTempFile("nsrun-tty-clean-exit", ".test")
        val writes = mutableListOf<String>()
        val terminal = SttyTerminalController(
            tty = tty,
            stty = { arguments -> if (arguments == listOf("-g")) "saved-state" else "" },
            terminalWriter = { writes += it.toString(Charsets.UTF_8) },
        )

        terminal.enterRawMode()
        terminal.observeOutput("\u001B[?1049h\u001B[?25l\u001B[?1006h".encodeToByteArray())
        terminal.observeOutput("\u001B[?1006l\u001B[?25h\u001B[?1049l".encodeToByteArray())
        terminal.close()

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `raw-mode setup failure without child mode changes emits no presentation bytes`() {
        val tty = Files.createTempFile("nsrun-tty-failed-raw", ".test")
        val writes = mutableListOf<String>()
        val terminal = SttyTerminalController(
            tty = tty,
            stty = { arguments ->
                if (arguments == listOf("-g")) "saved-state" else error("raw mode failed")
            },
            terminalWriter = { writes += it.toString(Charsets.UTF_8) },
        )

        runCatching { terminal.enterRawMode() }
        terminal.close()

        assertTrue(writes.isEmpty())
    }
}
