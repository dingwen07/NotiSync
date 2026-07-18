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
    val rawBytesSeen: Long = tail.encodeToByteArray().size.toLong(),
    val truncated: Boolean = false,
)

/**
 * A bounded terminal-like text projection. It consumes ANSI CSI/OSC sequences and honors CR rewrites;
 * the raw bytes remain in RunLog and are never reconstructed from this lossy display view.
 */
class OutputAnalyzer(
    private val rows: Int = 8,
    private val maxLineChars: Int = MAX_TERMINAL_BYTES,
    private val maxTerminalBytes: Int = MAX_TERMINAL_BYTES,
) {
    private enum class EscapeState { NORMAL, ESC, CSI, OSC, OSC_ESC }

    private val completed = ArrayDeque<String>()
    private val current = StringBuilder()
    private var escapeState = EscapeState.NORMAL
    private var rawBytesSeen = 0L
    private var terminalTruncated = false
    private var pendingUtf8 = ByteArray(0)

    @Synchronized
    fun accept(bytes: ByteArray, length: Int = bytes.size) {
        require(length in 0..bytes.size)
        rawBytesSeen += length
        val combined = pendingUtf8 + bytes.copyOf(length)
        val completeLength = combined.completeUtf8PrefixLength()
        pendingUtf8 = combined.copyOfRange(completeLength, combined.size)
        val text = String(combined, 0, completeLength, StandardCharsets.UTF_8)
        for (character in text) accept(character)
    }

    @Synchronized
    fun snapshot(): OutputSnapshot {
        val allLines = buildList {
            addAll(completed)
            if (current.isNotEmpty()) add(current.toString())
        }
        val detectionLines = allLines.takeLast(rows)
        val complete = allLines.joinToString("\n").trimEnd()
        val tail = complete.takeLastUtf8Bytes(maxTerminalBytes)
        return OutputSnapshot(
            tail = tail,
            progress = ProgressDetector.detect(detectionLines.asReversed()),
            prompt = PromptDetector.detect(detectionLines.lastOrNull().orEmpty()),
            rawBytesSeen = rawBytesSeen,
            truncated = terminalTruncated || tail != complete,
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
        if (current.length < maxLineChars) current.append(character) else terminalTruncated = true
    }

    private fun commitLine() {
        completed.addLast(current.toString())
        current.setLength(0)
        var retainedBytes = completed.sumOf { it.encodeToByteArray().size + 1 }
        while (retainedBytes > maxTerminalBytes * 2 && completed.size > rows) {
            retainedBytes -= completed.removeFirst().encodeToByteArray().size + 1
            terminalTruncated = true
        }
    }

    private fun String.takeLastUtf8Bytes(limit: Int): String {
        if (encodeToByteArray().size <= limit) return this
        var bytes = 0
        var index = length
        while (index > 0) {
            val previous = offsetByCodePoints(index, -1)
            val count = substring(previous, index).encodeToByteArray().size
            if (bytes + count > limit) break
            bytes += count
            index = previous
        }
        return substring(index)
    }

    private fun ByteArray.completeUtf8PrefixLength(): Int {
        if (isEmpty()) return 0
        var leadIndex = lastIndex
        while (leadIndex >= 0 && (this[leadIndex].toInt() and 0xC0) == 0x80) leadIndex--
        if (leadIndex < 0) return size
        val lead = this[leadIndex].toInt() and 0xFF
        val expected = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (size - leadIndex < expected) leadIndex else size
    }

    private companion object {
        const val MAX_TERMINAL_BYTES = 64 * 1024
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
