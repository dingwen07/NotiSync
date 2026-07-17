package net.extrawdw.notisync.run.output

import java.io.ByteArrayOutputStream

/**
 * Removes values supplied through a notification RemoteInput action from captured child output.
 *
 * Registration happens before the bytes are written to the child. Output may arrive in arbitrary
 * chunks, so [accept] retains the longest suffix that could still be the beginning of a registered
 * value. The caller remains free to mirror the original bytes to the user's terminal; only the
 * bytes returned here may be logged, analyzed, or sent to a content generator.
 */
internal class RemoteInputRedactor {
    private val patterns = linkedSetOf<ByteSequence>()
    private var pending = ByteArray(0)

    @Synchronized
    fun register(value: String) {
        val normalized = value.trimEnd('\r', '\n')
        addPattern(normalized)
        // PTYs commonly echo an embedded LF as CRLF, so also redact every non-empty input line.
        // This intentionally favors privacy over preserving later coincidental matching output.
        normalized.split("\r\n", "\r", "\n").forEach(::addPattern)
    }

    private fun addPattern(value: String) {
        val bytes = value.encodeToByteArray()
        if (bytes.isNotEmpty()) patterns += ByteSequence(bytes)
    }

    @Synchronized
    fun accept(bytes: ByteArray, length: Int = bytes.size): ByteArray {
        require(length in 0..bytes.size)
        if (length == 0) return ByteArray(0)
        if (patterns.isEmpty()) return bytes.copyOf(length)
        pending += bytes.copyOf(length)
        return drain(finishing = false)
    }

    @Synchronized
    fun finish(): ByteArray = drain(finishing = true)

    private fun drain(finishing: Boolean): ByteArray {
        if (pending.isEmpty()) return ByteArray(0)
        if (patterns.isEmpty()) return pending.also { pending = ByteArray(0) }

        val ordered = patterns.sortedByDescending { it.bytes.size }
        val longest = ordered.first().bytes.size
        val safeEnd = if (finishing) pending.size else (pending.size - longest + 1).coerceAtLeast(0)
        val output = ByteArrayOutputStream(pending.size)
        var index = 0
        while (index < pending.size) {
            val match = ordered.firstOrNull { pattern -> pending.matchesAt(index, pattern.bytes) }
            if (match != null) {
                output.write(REDACTION)
                index += match.bytes.size
                continue
            }
            if (!finishing && index >= safeEnd) break
            output.write(pending[index].toInt())
            index++
        }
        pending = pending.copyOfRange(index, pending.size)
        return output.toByteArray()
    }

    private fun ByteArray.matchesAt(index: Int, pattern: ByteArray): Boolean {
        if (index + pattern.size > size) return false
        for (offset in pattern.indices) if (this[index + offset] != pattern[offset]) return false
        return true
    }

    private class ByteSequence(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteSequence && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private companion object {
        val REDACTION = "[remote input redacted]".encodeToByteArray()
    }
}
