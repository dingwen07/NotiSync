package net.extrawdw.notisync.run.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
