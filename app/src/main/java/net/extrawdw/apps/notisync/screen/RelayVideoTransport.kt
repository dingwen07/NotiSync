package net.extrawdw.apps.notisync.screen

import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.extrawdw.notisync.peer.transport.BrokerRelayConnection
import net.extrawdw.notisync.protocol.ScreenRelayVideoFragmentHeader
import net.extrawdw.notisync.protocol.ScreenRelayVideoWire
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.SessionDescriptor
import net.extrawdw.notisync.screen.SessionKeyDeriver

internal interface RelayVideoEndpoint : AutoCloseable {
    fun beginVideoRecord(sequence: Long, recordBytes: Int)
    fun abortVideoRecord(sequence: Long)
    fun sendVideoFrame(bytes: ByteArray)
    fun takeVideoFrame(): ByteArray
    fun acknowledgeVideoRecord(sequence: Long, deliveredBytes: Int)
    fun consumeVideoCongestion(): Boolean
}

private class BrokerRelayVideoEndpoint(
    private val connection: BrokerRelayConnection,
) : RelayVideoEndpoint {
    override fun beginVideoRecord(sequence: Long, recordBytes: Int) =
        connection.beginVideoRecord(sequence, recordBytes)

    override fun abortVideoRecord(sequence: Long) = connection.abortVideoRecord(sequence)
    override fun sendVideoFrame(bytes: ByteArray) = connection.sendVideoFrame(bytes)
    override fun takeVideoFrame(): ByteArray = connection.takeVideoFrame()
    override fun acknowledgeVideoRecord(sequence: Long, deliveredBytes: Int) =
        connection.acknowledgeVideoRecord(sequence, deliveredBytes)

    override fun consumeVideoCongestion(): Boolean = connection.consumeVideoCongestion()
    override fun close() = connection.close()
}

/** Source-side record sink for the v1 TCP/WebSocket Relay video protocol. */
internal class RelayVideoRecordSink(
    private val connection: RelayVideoEndpoint,
    descriptor: SessionDescriptor,
    routingToken: ByteArray,
    masterPsk: ByteArray,
) : VideoRecordSink {
    constructor(
        connection: BrokerRelayConnection,
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
    ) : this(BrokerRelayVideoEndpoint(connection), descriptor, routingToken, masterPsk)

    private val aead = RelayVideoAead(descriptor, routingToken, masterPsk)
    private var nextSequence = 0L

    override fun writePreamble(bytes: ByteArray) {
        require(bytes.size == ScrcpyVideoPreamble.SIZE_BYTES)
        sendRecord(ScreenRelayVideoWire.FLAG_PREAMBLE, listOf(bytes))
    }

    override fun writeRecord(record: QueuedVideoRecord) {
        val flags = when {
            record.session -> ScreenRelayVideoWire.FLAG_SESSION
            record.codecConfig -> ScreenRelayVideoWire.FLAG_CODEC_CONFIG
            record.keyFrame -> ScreenRelayVideoWire.FLAG_KEY_FRAME
            else -> ScreenRelayVideoWire.FLAG_DELTA
        }
        sendRecord(flags, listOf(record.header, record.payload))
    }

    override fun consumeCongestion(): Boolean = connection.consumeVideoCongestion()

    override fun flush() = Unit

    override fun close() {
        aead.close()
        connection.close()
    }

    private fun sendRecord(flags: Int, parts: List<ByteArray>) {
        val recordBytes = parts.sumOf(ByteArray::size)
        require(recordBytes in 1..ScreenRelayVideoWire.MAX_RECORD_BYTES)
        val sequence = nextSequence++
        connection.beginVideoRecord(sequence, recordBytes)
        try {
            var offset = 0
            while (offset < recordBytes) {
                val count = minOf(
                    recordBytes - offset,
                    ScreenRelayVideoWire.MAX_FRAGMENT_PLAINTEXT_BYTES,
                )
                val plaintext = copySlice(parts, offset, count)
                val header = ScreenRelayVideoWire.encodeHeader(
                    flags = flags,
                    recordSequence = sequence,
                    recordBytes = recordBytes,
                    fragmentOffset = offset,
                    fragmentBytes = count,
                )
                val ciphertext = aead.seal(header, sequence, offset, plaintext)
                val message = ByteArray(header.size + ciphertext.size)
                header.copyInto(message)
                ciphertext.copyInto(message, header.size)
                connection.sendVideoFrame(message)
                offset += count
            }
        } catch (error: Throwable) {
            connection.abortVideoRecord(sequence)
            throw error
        }
    }

    private fun copySlice(parts: List<ByteArray>, offset: Int, count: Int): ByteArray {
        val result = ByteArray(count)
        var sourceOffset = offset
        var targetOffset = 0
        for (part in parts) {
            if (sourceOffset >= part.size) {
                sourceOffset -= part.size
                continue
            }
            val copied = minOf(count - targetOffset, part.size - sourceOffset)
            part.copyInto(result, targetOffset, sourceOffset, sourceOffset + copied)
            targetOffset += copied
            sourceOffset = 0
            if (targetOffset == count) break
        }
        check(targetOffset == count)
        return result
    }
}

/** Requester-side stream that decrypts complete Relay records and acknowledges parser consumption. */
internal class RelayVideoInputStream(
    private val connection: RelayVideoEndpoint,
    descriptor: SessionDescriptor,
    routingToken: ByteArray,
    masterPsk: ByteArray,
) : InputStream() {
    constructor(
        connection: BrokerRelayConnection,
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
    ) : this(BrokerRelayVideoEndpoint(connection), descriptor, routingToken, masterPsk)

    private data class Record(val header: ScreenRelayVideoFragmentHeader, val bytes: ByteArray)

    private val aead = RelayVideoAead(descriptor, routingToken, masterPsk)
    private var pendingFrame: ByteArray? = null
    private var current: Record? = null
    private var currentOffset = 0
    private var lastSequence = -1L
    private var preambleReceived = false
    private var needsKeyFrame = false
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Relay video input is closed")
        require(offset >= 0 && length >= 0 && offset + length <= target.size)
        if (length == 0) return 0
        while (current == null) current = nextDeliverableRecord() ?: return -1
        val record = requireNotNull(current)
        val count = minOf(length, record.bytes.size - currentOffset)
        record.bytes.copyInto(target, offset, currentOffset, currentOffset + count)
        currentOffset += count
        if (currentOffset == record.bytes.size) {
            connection.acknowledgeVideoRecord(record.header.recordSequence, record.header.recordBytes)
            current = null
            currentOffset = 0
        }
        return count
    }

    override fun available(): Int = current?.let { it.bytes.size - currentOffset } ?: 0

    override fun close() {
        if (closed) return
        closed = true
        aead.close()
        connection.close()
    }

    private fun nextDeliverableRecord(): Record? {
        while (!closed) {
            val record = receiveRecord()
            val sequence = record.header.recordSequence
            if (lastSequence >= 0 && sequence != lastSequence + 1) needsKeyFrame = true
            lastSequence = sequence

            when (record.header.flags) {
                ScreenRelayVideoWire.FLAG_PREAMBLE -> {
                    if (preambleReceived || sequence != 0L ||
                        record.bytes.size != ScrcpyVideoPreamble.SIZE_BYTES
                    ) throw IOException("invalid Relay video preamble")
                    preambleReceived = true
                    needsKeyFrame = false
                    return record
                }
                ScreenRelayVideoWire.FLAG_SESSION -> {
                    requirePreamble()
                    needsKeyFrame = true
                    return record
                }
                ScreenRelayVideoWire.FLAG_CODEC_CONFIG -> {
                    requirePreamble()
                    return record
                }
                ScreenRelayVideoWire.FLAG_KEY_FRAME -> {
                    requirePreamble()
                    needsKeyFrame = false
                    return record
                }
                ScreenRelayVideoWire.FLAG_DELTA -> {
                    requirePreamble()
                    if (!needsKeyFrame) return record
                    // This record reached the app but is intentionally obsolete after a broker drop.
                    connection.acknowledgeVideoRecord(sequence, 0)
                }
                else -> throw IOException("unknown Relay video record type")
            }
        }
        return null
    }

    private fun receiveRecord(): Record {
        while (true) {
            val first = pendingFrame ?: connection.takeVideoFrame()
            pendingFrame = null
            val firstHeader = parseHeader(first)
            if (!firstHeader.firstFragment) {
                aead.open(first, firstHeader)
                needsKeyFrame = true
                discardRecord(firstHeader)
                continue
            }
            val record = ByteArray(firstHeader.recordBytes)
            var expectedOffset = 0
            var frame = first
            while (true) {
                val header = parseHeader(frame)
                if (header.recordSequence != firstHeader.recordSequence ||
                    header.flags != firstHeader.flags ||
                    header.recordBytes != firstHeader.recordBytes ||
                    header.fragmentOffset != expectedOffset
                ) {
                    if (header.recordSequence > firstHeader.recordSequence && header.firstFragment) {
                        pendingFrame = frame
                        needsKeyFrame = true
                        connection.acknowledgeVideoRecord(
                            firstHeader.recordSequence,
                            0,
                        )
                        break
                    }
                    throw IOException("invalid Relay video fragment sequence")
                }
                val plaintext = aead.open(frame, header)
                plaintext.copyInto(record, expectedOffset)
                expectedOffset += plaintext.size
                if (expectedOffset == record.size) return Record(firstHeader, record)
                frame = connection.takeVideoFrame()
            }
        }
    }

    private fun discardRecord(initial: ScreenRelayVideoFragmentHeader) {
        var expectedOffset = initial.fragmentOffset + initial.fragmentBytes
        while (expectedOffset < initial.recordBytes) {
            val frame = connection.takeVideoFrame()
            val header = parseHeader(frame)
            if (header.recordSequence != initial.recordSequence) {
                pendingFrame = frame
                break
            }
            if (header.flags != initial.flags || header.recordBytes != initial.recordBytes ||
                header.fragmentOffset != expectedOffset
            ) throw IOException("invalid discarded Relay video fragment sequence")
            expectedOffset += aead.open(frame, header).size
        }
        connection.acknowledgeVideoRecord(initial.recordSequence, 0)
    }

    private fun parseHeader(message: ByteArray): ScreenRelayVideoFragmentHeader =
        ScreenRelayVideoWire.decodeHeader(message)
            ?: throw IOException("invalid Relay video message")

    private fun requirePreamble() {
        if (!preambleReceived) throw IOException("Relay video record arrived before preamble")
    }
}

/** AES-256-GCM with a session-derived key and a unique (record sequence, fragment offset) nonce. */
internal class RelayVideoAead(
    descriptor: SessionDescriptor,
    routingToken: ByteArray,
    masterPsk: ByteArray,
) : AutoCloseable {
    private val key: ByteArray = deriveKey(descriptor, routingToken, masterPsk)

    fun seal(
        header: ByteArray,
        recordSequence: Long,
        fragmentOffset: Int,
        plaintext: ByteArray,
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, header, recordSequence, fragmentOffset).doFinal(plaintext)

    fun open(message: ByteArray, header: ScreenRelayVideoFragmentHeader): ByteArray = try {
        cipher(
            Cipher.DECRYPT_MODE,
            ScreenRelayVideoWire.headerBytes(message),
            header.recordSequence,
            header.fragmentOffset,
        ).doFinal(message, ScreenRelayVideoWire.HEADER_BYTES, message.size - ScreenRelayVideoWire.HEADER_BYTES)
    } catch (error: Exception) {
        throw IOException("Relay video authentication failed", error)
    }

    override fun close() = key.fill(0)

    private fun cipher(
        mode: Int,
        header: ByteArray,
        recordSequence: Long,
        fragmentOffset: Int,
    ): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(
            mode,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce(recordSequence, fragmentOffset)),
        )
        updateAAD(header)
    }

    private fun nonce(recordSequence: Long, fragmentOffset: Int): ByteArray = ByteArray(12).also { nonce ->
        repeat(8) { index -> nonce[index] = (recordSequence ushr (56 - index * 8)).toByte() }
        repeat(4) { index -> nonce[8 + index] = (fragmentOffset ushr (24 - index * 8)).toByte() }
    }

    private companion object {
        val INFO = "notisync-screen-relay-video-v1".encodeToByteArray()

        fun deriveKey(
            descriptor: SessionDescriptor,
            routingToken: ByteArray,
            masterPsk: ByteArray,
        ): ByteArray {
            val channelKey = SessionKeyDeriver.derive(
                masterPsk,
                routingToken,
                descriptor,
                ScreenChannel.VIDEO,
            )
            return try {
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(channelKey, "HmacSHA256"))
                    doFinal(INFO)
                }
            } finally {
                channelKey.fill(0)
            }
        }
    }
}
