package net.extrawdw.notisync.run.process

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPresentationTrackerTest {
    @Test
    fun `recognizes DECSET when split at every byte boundary`() {
        val sequence = "\u001B[?1049;1006h"
        for (split in 1 until sequence.length) {
            val tracker = TerminalPresentationTracker()
            tracker.accept(sequence.substring(0, split).encodeToByteArray())
            tracker.accept(sequence.substring(split).encodeToByteArray())
            assertEquals(
                "split=$split",
                "\u001B[?1049l\u001B[?1006l",
                tracker.recovery().toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `tracks fragmented mode changes and emits only outstanding inverses`() {
        val tracker = TerminalPresentationTracker()
        listOf(
            "\u001B[?10",
            "49h\u001B[?25l\u001B[?1000;1006h",
            "\u001B=",
            "\u001B[?1000l",
        ).forEach { tracker.accept(it.encodeToByteArray()) }

        assertEquals(
            "\u001B[?1049l\u001B[?25h\u001B[?1006l\u001B>",
            tracker.recovery().toString(Charsets.UTF_8),
        )
        assertEquals("", tracker.recovery().toString(Charsets.UTF_8))
    }

    @Test
    fun `ignores fake escape sequences inside OSC DCS APC and PM strings`() {
        val tracker = TerminalPresentationTracker()
        val sequences = buildString {
            append("\u001B]title \u001B[?1049h\u0007")
            append("\u001BPpayload \u001B[?1000h\u001B\\")
            append("\u001B_payload \u001B[?1006h\u001B\\")
            append("\u001B^payload \u001B[?2004h\u001B\\")
        }

        tracker.accept(sequences.encodeToByteArray())

        assertEquals("", tracker.recovery().toString(Charsets.UTF_8))
    }

    @Test
    fun `normal application teardown clears every recovery obligation`() {
        val tracker = TerminalPresentationTracker()
        tracker.accept(
            ("\u001B[?1047h\u001B[?1h\u001B[?25l\u001B[?1004h\u001B[?2004h" +
                "\u001B[?2004l\u001B[?1004l\u001B[?25h\u001B[?1l\u001B[?1047l").encodeToByteArray(),
        )

        assertEquals("", tracker.recovery().toString(Charsets.UTF_8))
    }

    @Test
    fun `synchronized output is disabled before all other recovery`() {
        val tracker = TerminalPresentationTracker()
        tracker.accept("\u001B[?1049h\u001B[?2026h\u001B[?7l\u001B[?6h".encodeToByteArray())

        assertEquals(
            "\u001B[?2026l\u001B[?1049l\u001B[?6l\u001B[?7h",
            tracker.recovery().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `tracks curses attributes charset and scroll region without penalizing healthy teardown`() {
        val dirty = TerminalPresentationTracker()
        dirty.accept("\u001B[1;38;5;196m\u001B(0\u001B[2;20r".encodeToByteArray())
        assertEquals(
            "\u001B[r\u001B[0m\u001B(B",
            dirty.recovery().toString(Charsets.UTF_8),
        )

        val clean = TerminalPresentationTracker()
        clean.accept(
            "\u001B[1;31m\u001B(0\u001B[2;20r\u001B[r\u001B(B\u001B[22;39m".encodeToByteArray(),
        )
        assertEquals("", clean.recovery().toString(Charsets.UTF_8))
    }

    @Test
    fun `RIS clears accumulated recovery state`() {
        val tracker = TerminalPresentationTracker()
        tracker.accept("\u001B[?1049h\u001B[?25l\u001B[31m\u001Bc".encodeToByteArray())

        assertEquals("", tracker.recovery().toString(Charsets.UTF_8))
    }

    @Test
    fun `cancels every incomplete terminal control state before returning to the shell`() {
        val cases = listOf(
            "\u001B" to "\u0018",
            "\u001B[?10" to "\u0018",
            "\u001B(" to "\u0018",
            "\u001B]unterminated" to "\u001B\\",
            "\u001BPunterminated" to "\u001B\\",
            "\u001B_unterminated" to "\u001B\\",
            "\u001B^unterminated" to "\u001B\\",
            "\u001BXunterminated" to "\u001B\\",
            "\u001B]almost-st\u001B" to "\\",
        )

        cases.forEach { (partial, expected) ->
            val tracker = TerminalPresentationTracker()
            tracker.accept(partial.encodeToByteArray())
            assertEquals(partial, expected, tracker.recovery().toString(Charsets.UTF_8))
            assertEquals("", tracker.recovery().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `CAN and SUB received from the child cancel incomplete controls without recovery`() {
        listOf('\u0018', '\u001A').forEach { cancel ->
            listOf("\u001B[?104", "\u001B(", "\u001B]osc", "\u001B]osc\u001B").forEach { partial ->
                val tracker = TerminalPresentationTracker()
                tracker.accept((partial + cancel).encodeToByteArray())
                assertEquals("partial=$partial cancel=${cancel.code}", "", tracker.recovery().toString(Charsets.UTF_8))
            }
        }
    }
}
