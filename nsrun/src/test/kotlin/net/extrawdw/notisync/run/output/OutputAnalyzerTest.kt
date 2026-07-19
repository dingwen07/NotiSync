package net.extrawdw.notisync.run.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAnalyzerTest {
    @Test
    fun `strips ANSI and honors carriage-return progress rewrites`() {
        val analyzer = OutputAnalyzer()
        analyzer.accept("\u001b[32mDownloading\u001b[0m 10%\rDownloading 62%".encodeToByteArray())
        val snapshot = analyzer.snapshot()
        assertEquals("Downloading 62%", snapshot.tail)
        assertEquals(62, snapshot.progress?.percent)
        assertFalse(snapshot.tail.contains('\u001b'))
    }

    @Test
    fun `detects fraction progress and yes-no prompts`() {
        val analyzer = OutputAnalyzer()
        analyzer.accept("Processed 23/100\nContinue? [Y/n] ".encodeToByteArray())
        val snapshot = analyzer.snapshot()
        assertEquals(23, snapshot.progress?.percent)
        assertEquals(PromptKind.YES_NO, snapshot.prompt)
    }

    @Test
    fun `terminal snapshot is a sanitized UTF-8 bounded tail`() {
        val analyzer = OutputAnalyzer()
        val output = buildString {
            repeat(9_000) { index -> append("line-").append(index).append(" \u001b[31mred\u001b[0m\n") }
            append("final-€")
        }.encodeToByteArray()
        analyzer.accept(output)

        val snapshot = analyzer.snapshot()

        assertTrue(snapshot.tail.encodeToByteArray().size <= 64 * 1024)
        assertTrue(snapshot.truncated)
        assertEquals(output.size.toLong(), snapshot.rawBytesSeen)
        assertTrue(snapshot.tail.endsWith("final-€"))
        assertFalse(snapshot.tail.contains('\u001b'))
    }

    @Test
    fun `preserves UTF-8 characters split across output reads`() {
        val analyzer = OutputAnalyzer()
        val bytes = "cost €5".encodeToByteArray()
        val euro = bytes.indexOf(0xE2.toByte())
        analyzer.accept(bytes.copyOfRange(0, euro + 1))
        analyzer.accept(bytes.copyOfRange(euro + 1, bytes.size))

        assertEquals("cost €5", analyzer.snapshot().tail)
    }

    @Test
    fun `carriage return and backspace move the cursor without erasing the suffix`() {
        val analyzer = OutputAnalyzer()

        analyzer.accept("abcdef\rxy\nabcdef\b\bX".encodeToByteArray())

        assertEquals("xycdef\nabcdXf", analyzer.snapshot().tail)
    }

    @Test
    fun `tabs advance to the next eight-column stop`() {
        val analyzer = OutputAnalyzer()

        analyzer.accept("ab\tX\nabcdefgh\r\tX".encodeToByteArray())

        assertEquals("ab      X\nabcdefghX", analyzer.snapshot().tail)
    }

    @Test
    fun `strips seven-bit and C1 control strings plus bidi controls`() {
        val analyzer = OutputAnalyzer()
        val unsafe = buildString {
            append("safe")
            append("\u001bPignored\u001b\\")
            append("\u009dtitle\u009c")
            append("\u009b31m")
            append('\u202e')
            append("text")
        }

        analyzer.accept(unsafe.encodeToByteArray())

        assertEquals("safetext", analyzer.snapshot().tail)
    }

    @Test
    fun `raw byte count can be observed before redacted display bytes`() {
        val analyzer = OutputAnalyzer()

        analyzer.observeRawBytes(123)
        analyzer.acceptDisplay("[remote input redacted]".encodeToByteArray())

        assertEquals(123, analyzer.snapshot().rawBytesSeen)
        assertEquals("[remote input redacted]", analyzer.snapshot().tail)
    }
}
