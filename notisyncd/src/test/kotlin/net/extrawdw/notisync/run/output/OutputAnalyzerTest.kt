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
}
