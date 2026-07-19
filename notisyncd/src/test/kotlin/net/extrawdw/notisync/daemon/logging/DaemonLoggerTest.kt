package net.extrawdw.notisync.daemon.logging

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonLoggerTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-20T08:09:10.123Z"), ZoneOffset.UTC)

    @Test
    fun `warning threshold filters info and prefixes every output line`() {
        val output = StringBuilder()
        val logger = DaemonLogger("warning", output, clock) { "peer-worker" }

        logger.info("not visible")
        logger.warn("broker disconnected\nretrying")

        assertEquals(
            """
                2026-07-20T08:09:10.123Z WARN [peer-worker] broker disconnected
                2026-07-20T08:09:10.123Z WARN [peer-worker] retrying

            """.trimIndent(),
            output.toString(),
        )
    }

    @Test
    fun `level updates take effect without restarting`() {
        val output = StringBuilder()
        val logger = DaemonLogger("WARN", output, clock) { "main" }

        logger.info("hidden")
        logger.updateLevel("INFO")
        logger.info("configuration reloaded")

        assertEquals(
            "2026-07-20T08:09:10.123Z INFO [main] configuration reloaded\n",
            output.toString(),
        )
    }

    @Test
    fun `blank thread names use an explicit fallback`() {
        val output = StringBuilder()
        val logger = DaemonLogger("INFO", output, clock) { " \t" }

        logger.info("ready")

        assertEquals(
            "2026-07-20T08:09:10.123Z INFO [unnamed] ready\n",
            output.toString(),
        )
    }

    @Test
    fun `timestamps always contain exactly three UTC fractional digits`() {
        val wholeSecond = StringBuilder()
        DaemonLogger(
            "INFO",
            wholeSecond,
            Clock.fixed(Instant.parse("2026-07-20T08:09:10Z"), ZoneOffset.UTC),
        ) { "main" }.info("whole")
        assertEquals(
            "2026-07-20T08:09:10.000Z INFO [main] whole\n",
            wholeSecond.toString(),
        )

        val subMillisecond = StringBuilder()
        DaemonLogger(
            "INFO",
            subMillisecond,
            Clock.fixed(Instant.parse("2026-07-20T08:09:10.123999999Z"), ZoneOffset.UTC),
        ) { "main" }.info("precise")
        assertEquals(
            "2026-07-20T08:09:10.123Z INFO [main] precise\n",
            subMillisecond.toString(),
        )
    }
}
