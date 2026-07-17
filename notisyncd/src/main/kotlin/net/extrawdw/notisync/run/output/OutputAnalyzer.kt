package net.extrawdw.notisync.run.output

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

data class DetectedProgress(val current: Long, val total: Long) {
    val percent: Int get() = ((current.coerceIn(0, total) * 100) / total.coerceAtLeast(1)).toInt()
}

enum class PromptKind { YES_NO, TEXT }

data class OutputSnapshot(
    val tail: String,
    val progress: DetectedProgress?,
    val prompt: PromptKind?,
)

/**
 * A bounded terminal-like text projection. It consumes ANSI CSI/OSC sequences and honors CR rewrites;
 * the raw bytes remain in RunLog and are never reconstructed from this lossy display view.
 */
class OutputAnalyzer(
    private val rows: Int = 8,
    private val maxLineChars: Int = 4096,
) {
    private enum class EscapeState { NORMAL, ESC, CSI, OSC, OSC_ESC }

    private val completed = ArrayDeque<String>()
    private val current = StringBuilder()
    private var escapeState = EscapeState.NORMAL

    @Synchronized
    fun accept(bytes: ByteArray, length: Int = bytes.size) {
        val text = String(bytes, 0, length, StandardCharsets.UTF_8)
        for (character in text) accept(character)
    }

    @Synchronized
    fun snapshot(): OutputSnapshot {
        val lines = buildList {
            addAll(completed)
            if (current.isNotEmpty()) add(current.toString())
        }.takeLast(rows)
        val tail = lines.joinToString("\n").trimEnd()
        return OutputSnapshot(
            tail = tail,
            progress = ProgressDetector.detect(lines.asReversed()),
            prompt = PromptDetector.detect(lines.lastOrNull().orEmpty()),
        )
    }

    private fun accept(character: Char) {
        when (escapeState) {
            EscapeState.ESC -> {
                escapeState = when (character) {
                    '[' -> EscapeState.CSI
                    ']' -> EscapeState.OSC
                    else -> EscapeState.NORMAL
                }
                return
            }
            EscapeState.CSI -> {
                if (character.code in 0x40..0x7e) escapeState = EscapeState.NORMAL
                return
            }
            EscapeState.OSC -> {
                if (character == '\u0007') escapeState = EscapeState.NORMAL
                else if (character == '\u001b') escapeState = EscapeState.OSC_ESC
                return
            }
            EscapeState.OSC_ESC -> {
                escapeState = if (character == '\\') EscapeState.NORMAL else EscapeState.OSC
                return
            }
            EscapeState.NORMAL -> Unit
        }
        when (character) {
            '\u001b' -> escapeState = EscapeState.ESC
            '\r' -> current.setLength(0)
            '\n' -> commitLine()
            '\b' -> if (current.isNotEmpty()) current.setLength(current.length - 1)
            '\t' -> repeat(8 - current.length % 8) { appendPrintable(' ') }
            else -> if (character >= ' ' && character != '\u007f') appendPrintable(character)
        }
    }

    private fun appendPrintable(character: Char) {
        if (current.length < maxLineChars) current.append(character)
    }

    private fun commitLine() {
        completed.addLast(current.toString())
        current.setLength(0)
        while (completed.size > rows * 3) completed.removeFirst()
    }
}

object ProgressDetector {
    private val percentage = Regex("(?<![\\d.])(100|\\d{1,2})(?:\\.\\d+)?\\s*%")
    private val fraction = Regex("(?<!\\d)(\\d{1,12})\\s*/\\s*(\\d{1,12})(?!\\d)")
    private val bar = Regex("[\\[|](={2,}|#{2,}|█{2,}|━{2,})[>\\s.-]*[]|]")

    fun detect(linesNewestFirst: List<String>): DetectedProgress? {
        for (line in linesNewestFirst) {
            percentage.findAll(line).lastOrNull()?.let { match ->
                return DetectedProgress(match.groupValues[1].toLong(), 100)
            }
            fraction.findAll(line).lastOrNull()?.let { match ->
                val current = match.groupValues[1].toLongOrNull() ?: return@let
                val total = match.groupValues[2].toLongOrNull() ?: return@let
                if (total > 0 && current <= total * 2) return DetectedProgress(current.coerceAtMost(total), total)
            }
            val match = bar.find(line)
            if (match != null) {
                val body = match.value.drop(1).dropLast(1)
                val filled = body.count { it == '=' || it == '#' || it == '█' || it == '━' }
                val total = body.count { !it.isWhitespace() || it == ' ' }
                if (total > 0) return DetectedProgress(filled.toLong(), total.toLong())
            }
        }
        return null
    }
}

object PromptDetector {
    private val yesNo = Regex("(?:\\[|\\()\\s*(?:y(?:es)?\\s*/\\s*n(?:o)?|n(?:o)?\\s*/\\s*y(?:es)?)\\s*(?:]|\\))?\\s*[:?]?\\s*$", RegexOption.IGNORE_CASE)
    private val generic = Regex("(?:password|passphrase|enter|input|continue|press (?:return|enter))[^\\n]{0,80}[:?]\\s*$", RegexOption.IGNORE_CASE)

    fun detect(lastLine: String): PromptKind? = when {
        yesNo.containsMatchIn(lastLine) -> PromptKind.YES_NO
        generic.containsMatchIn(lastLine) -> PromptKind.TEXT
        else -> null
    }
}
