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
    private enum class EscapeState {
        NORMAL,
        ESC,
        ESC_INTERMEDIATE,
        CSI,
        OSC,
        OSC_ESC,
        CONTROL_STRING,
        CONTROL_STRING_ESC,
    }

    private val completed = ArrayDeque<String>()
    private val current = StringBuilder()
    /** UTF-16 index kept on a code-point boundary. CR and backspace move this cursor; they do not erase. */
    private var cursor = 0
    private var escapeState = EscapeState.NORMAL
    private var rawBytesSeen = 0L
    private var terminalTruncated = false
    private var pendingUtf8 = ByteArray(0)

    /** Convenience for callers whose displayed bytes are also the unmodified child bytes. */
    @Synchronized
    fun accept(bytes: ByteArray, length: Int = bytes.size) {
        observeRawBytes(length)
        acceptDisplay(bytes, length)
    }

    /** Count bytes at the child-output boundary, before redaction or any other display transformation. */
    @Synchronized
    fun observeRawBytes(length: Int) {
        require(length >= 0)
        rawBytesSeen = if (Long.MAX_VALUE - rawBytesSeen < length) Long.MAX_VALUE else rawBytesSeen + length
    }

    /** Consume bytes that are safe to analyze after remote-input redaction. */
    @Synchronized
    fun acceptDisplay(bytes: ByteArray, length: Int = bytes.size) {
        require(length in 0..bytes.size)
        val combined = pendingUtf8 + bytes.copyOf(length)
        val completeLength = combined.completeUtf8PrefixLength()
        pendingUtf8 = combined.copyOfRange(completeLength, combined.size)
        val text = String(combined, 0, completeLength, StandardCharsets.UTF_8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            acceptCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
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

    private fun acceptCodePoint(codePoint: Int) {
        when (escapeState) {
            EscapeState.ESC -> {
                escapeState = when (codePoint) {
                    '['.code -> EscapeState.CSI
                    ']'.code -> EscapeState.OSC
                    'P'.code, 'X'.code, '^'.code, '_'.code -> EscapeState.CONTROL_STRING
                    in 0x20..0x2f -> EscapeState.ESC_INTERMEDIATE
                    else -> EscapeState.NORMAL
                }
                return
            }
            EscapeState.ESC_INTERMEDIATE -> {
                escapeState = when {
                    codePoint == ESC -> EscapeState.ESC
                    codePoint in 0x30..0x7e -> EscapeState.NORMAL
                    else -> EscapeState.ESC_INTERMEDIATE
                }
                return
            }
            EscapeState.CSI -> {
                escapeState = when {
                    codePoint == ESC -> EscapeState.ESC
                    codePoint in 0x40..0x7e -> EscapeState.NORMAL
                    else -> EscapeState.CSI
                }
                return
            }
            EscapeState.OSC -> {
                if (codePoint == BEL || codePoint == C1_ST) escapeState = EscapeState.NORMAL
                else if (codePoint == ESC) escapeState = EscapeState.OSC_ESC
                return
            }
            EscapeState.OSC_ESC -> {
                escapeState = when (codePoint) {
                    '\\'.code, C1_ST -> EscapeState.NORMAL
                    ESC -> EscapeState.OSC_ESC
                    else -> EscapeState.OSC
                }
                return
            }
            EscapeState.CONTROL_STRING -> {
                if (codePoint == C1_ST) escapeState = EscapeState.NORMAL
                else if (codePoint == ESC) escapeState = EscapeState.CONTROL_STRING_ESC
                return
            }
            EscapeState.CONTROL_STRING_ESC -> {
                escapeState = when (codePoint) {
                    '\\'.code, C1_ST -> EscapeState.NORMAL
                    ESC -> EscapeState.CONTROL_STRING_ESC
                    else -> EscapeState.CONTROL_STRING
                }
                return
            }
            EscapeState.NORMAL -> Unit
        }

        when (codePoint) {
            ESC -> escapeState = EscapeState.ESC
            C1_CSI -> escapeState = EscapeState.CSI
            C1_OSC -> escapeState = EscapeState.OSC
            C1_DCS, C1_SOS, C1_PM, C1_APC -> escapeState = EscapeState.CONTROL_STRING
            '\r'.code -> cursor = 0
            '\n'.code -> commitLine()
            '\b'.code -> if (cursor > 0) cursor = Character.offsetByCodePoints(current, cursor, -1)
            '\t'.code -> advanceToNextTabStop()
            else -> if (codePoint.isSafeTerminalCodePoint()) writePrintable(codePoint)
        }
    }

    /** Overwrite at the cursor like a terminal cell while retaining any untouched suffix. */
    private fun writePrintable(codePoint: Int) {
        val replacement = String(Character.toChars(codePoint))
        if (cursor < current.length) {
            val replacedEnd = Character.offsetByCodePoints(current, cursor, 1)
            val newLength = current.length - (replacedEnd - cursor) + replacement.length
            if (newLength > maxLineChars) {
                terminalTruncated = true
                return
            }
            current.replace(cursor, replacedEnd, replacement)
        } else {
            if (current.length + replacement.length > maxLineChars) {
                terminalTruncated = true
                return
            }
            current.append(replacement)
        }
        cursor += replacement.length
    }

    /** HT moves the cursor; it only pads cells when the target lies beyond the existing line. */
    private fun advanceToNextTabStop() {
        val column = current.codePointCount(0, cursor)
        val targetColumn = column + (TAB_WIDTH - column % TAB_WIDTH)
        val lineColumns = current.codePointCount(0, current.length)
        if (targetColumn <= lineColumns) {
            cursor = Character.offsetByCodePoints(current, 0, targetColumn)
            return
        }
        cursor = current.length
        repeat(targetColumn - lineColumns) { writePrintable(' '.code) }
    }

    private fun commitLine() {
        completed.addLast(current.toString())
        current.setLength(0)
        cursor = 0
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
        const val TAB_WIDTH = 8
        const val BEL = 0x07
        const val ESC = 0x1b
        const val C1_DCS = 0x90
        const val C1_SOS = 0x98
        const val C1_CSI = 0x9b
        const val C1_ST = 0x9c
        const val C1_OSC = 0x9d
        const val C1_PM = 0x9e
        const val C1_APC = 0x9f

        fun Int.isSafeTerminalCodePoint(): Boolean =
            this >= 0x20 && this != 0x7f && this !in 0x80..0x9f && !isBidiControl()

        fun Int.isBidiControl(): Boolean =
            this == 0x061c || this in 0x200e..0x200f || this in 0x202a..0x202e ||
                this in 0x2066..0x2069 || this in 0x206a..0x206f
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
