package net.extrawdw.apps.notisync.data.activity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned, bounded codec for the Activity render-argument column.
 *
 * The schema is intentionally fixed-width and canonical.  A newer version is not guessed at, and a
 * malformed v1 payload becomes a safe semantic state instead of terminating a Room Flow collector.
 */
object ActivityRenderArgsCodec {
    const val CURRENT_VERSION = 1

    private val MAGIC = byteArrayOf(0x4E, 0x53, 0x41, 0x52) // "NSAR"
    private const val COUNT_FLAG = 1
    private const val REVISION_FLAG = 1 shl 1
    private const val DURATION_FLAG = 1 shl 2
    private const val KNOWN_FLAGS = COUNT_FLAG or REVISION_FLAG or DURATION_FLAG
    private const val ENCODED_SIZE = 4 + 1 + 1 + Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES

    fun encode(args: ActivityRenderArgs.V1): ByteArray {
        val encoded = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.BIG_ENDIAN)
        encoded.put(MAGIC)
        encoded.put(CURRENT_VERSION.toByte())
        var flags = 0
        if (args.count != null) flags = flags or COUNT_FLAG
        if (args.revision != null) flags = flags or REVISION_FLAG
        if (args.durationMillis != null) flags = flags or DURATION_FLAG
        encoded.put(flags.toByte())
        encoded.putInt(args.count ?: 0)
        encoded.putLong(args.revision ?: 0L)
        encoded.putLong(args.durationMillis ?: 0L)
        return encoded.array()
    }

    /**
     * Decodes without exposing persisted bytes.  Only a bounded copy of a known-version payload is made;
     * unknown or oversized bytes are represented by metadata-only safe states.
     */
    fun decode(version: Int, bytes: ByteArray): ActivityRenderArgs = when {
        version != CURRENT_VERSION -> ActivityRenderArgs.Unsupported(version)
        bytes.size > ActivityLimits.MAX_RENDER_ARGS_BYTES ->
            ActivityRenderArgs.Corrupt(version, ActivityRenderArgs.CorruptReason.OVERSIZE)
        bytes.size != ENCODED_SIZE ->
            ActivityRenderArgs.Corrupt(version, ActivityRenderArgs.CorruptReason.MALFORMED)
        else -> decodeV1(bytes)
    }

    private fun decodeV1(bytes: ByteArray): ActivityRenderArgs {
        val candidate = bytes.copyOf()
        if (!candidate.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return ActivityRenderArgs.Corrupt(
                CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.MALFORMED,
            )
        }
        val encoded = ByteBuffer.wrap(candidate).order(ByteOrder.BIG_ENDIAN)
        encoded.position(MAGIC.size)
        if (encoded.get().toInt() != CURRENT_VERSION) {
            return ActivityRenderArgs.Corrupt(
                CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.MALFORMED,
            )
        }
        val flags = encoded.get().toInt() and 0xFF
        if (flags and KNOWN_FLAGS.inv() != 0) {
            return ActivityRenderArgs.Corrupt(
                CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.NON_CANONICAL,
            )
        }
        val countValue = encoded.int
        val revisionValue = encoded.long
        val durationValue = encoded.long
        val args = try {
            ActivityRenderArgs.V1(
                count = countValue.takeIf { flags and COUNT_FLAG != 0 },
                revision = revisionValue.takeIf { flags and REVISION_FLAG != 0 },
                durationMillis = durationValue.takeIf { flags and DURATION_FLAG != 0 },
            )
        } catch (_: IllegalArgumentException) {
            return ActivityRenderArgs.Corrupt(
                CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.MALFORMED,
            )
        }
        if (!ActivityRenderArgsCodec.encode(args).contentEquals(candidate)) {
            return ActivityRenderArgs.Corrupt(
                CURRENT_VERSION,
                ActivityRenderArgs.CorruptReason.NON_CANONICAL,
            )
        }
        return args
    }
}
