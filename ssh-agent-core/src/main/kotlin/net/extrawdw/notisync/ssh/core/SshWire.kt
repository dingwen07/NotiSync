package net.extrawdw.notisync.ssh.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets

class SshWireException(message: String) : IllegalArgumentException(message)

/** Strict bounded reader for SSH uint32/string/mpint primitives. */
class SshWireReader(
    private val bytes: ByteArray,
    private val maximumStringLength: Int = bytes.size,
) {
    private var offset = 0

    val remaining: Int get() = bytes.size - offset

    fun readByte(): Int {
        requireAvailable(1)
        return bytes[offset++].toInt() and 0xff
    }

    fun readBoolean(): Boolean = when (val value = readByte()) {
        0 -> false
        1 -> true
        else -> throw SshWireException("invalid SSH boolean value $value")
    }

    fun readUInt32(): Long {
        requireAvailable(4)
        val value = ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
        offset += 4
        return value
    }

    fun readString(maximumLength: Int = maximumStringLength): ByteArray {
        val length = readUInt32()
        if (length > maximumLength.toLong() || length > remaining.toLong()) {
            throw SshWireException("SSH string length $length exceeds the available or configured bound")
        }
        val result = bytes.copyOfRange(offset, offset + length.toInt())
        offset += length.toInt()
        return result
    }

    fun readUtf8(maximumLength: Int = maximumStringLength): String {
        val encoded = readString(maximumLength)
        val decoded = encoded.toString(StandardCharsets.UTF_8)
        if (!decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(encoded)) {
            throw SshWireException("SSH string is not canonical UTF-8")
        }
        return decoded
    }

    fun readMpInt(maximumLength: Int = maximumStringLength): BigInteger {
        val encoded = readString(maximumLength)
        if (encoded.isEmpty()) return BigInteger.ZERO
        if (encoded[0].toInt() and 0x80 != 0) throw SshWireException("negative SSH mpint is not allowed")
        if (encoded[0] == 0.toByte()) {
            if (encoded.size == 1 || encoded[1].toInt() and 0x80 == 0) {
                throw SshWireException("non-canonical SSH mpint")
            }
        }
        return BigInteger(1, encoded)
    }

    fun readRemaining(): ByteArray = bytes.copyOfRange(offset, bytes.size).also { offset = bytes.size }

    fun requireEnd() {
        if (remaining != 0) throw SshWireException("unexpected trailing SSH data ($remaining bytes)")
    }

    private fun requireAvailable(count: Int) {
        if (count < 0 || remaining < count) throw SshWireException("truncated SSH data")
    }
}

/** Bounded writer paired with [SshWireReader]. */
class SshWireWriter(private val maximumSize: Int = DEFAULT_MAXIMUM_SIZE) {
    private val output = ByteArrayOutputStream()

    fun writeByte(value: Int): SshWireWriter {
        if (value !in 0..255) throw SshWireException("byte value is outside uint8")
        reserve(1)
        output.write(value)
        return this
    }

    fun writeBoolean(value: Boolean): SshWireWriter = writeByte(if (value) 1 else 0)

    fun writeUInt32(value: Long): SshWireWriter {
        if (value !in 0..UINT32_MAX) throw SshWireException("value is outside uint32")
        reserve(4)
        output.write(((value ushr 24) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write((value and 0xff).toInt())
        return this
    }

    fun writeString(value: ByteArray): SshWireWriter {
        writeUInt32(value.size.toLong())
        writeRaw(value)
        return this
    }

    fun writeUtf8(value: String): SshWireWriter = writeString(value.toByteArray(StandardCharsets.UTF_8))

    fun writeMpInt(value: BigInteger): SshWireWriter {
        if (value.signum() < 0) throw SshWireException("negative mpint is not supported")
        if (value.signum() == 0) return writeString(ByteArray(0))
        return writeString(value.toByteArray())
    }

    fun writeRaw(value: ByteArray): SshWireWriter {
        reserve(value.size)
        output.write(value)
        return this
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun reserve(count: Int) {
        if (count < 0 || output.size().toLong() + count > maximumSize.toLong()) {
            throw SshWireException("SSH output exceeds $maximumSize bytes")
        }
    }

    private companion object {
        const val DEFAULT_MAXIMUM_SIZE = 1024 * 1024
        const val UINT32_MAX = 0xffff_ffffL
    }
}

/** RFC 9987 framing: uint32 packet length followed by exactly that many body bytes. */
object SshAgentFrameCodec {
    const val DEFAULT_MAXIMUM_FRAME_SIZE = 1024 * 1024

    fun read(input: InputStream, maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val header = byteArrayOf(first.toByte(), readRequired(input), readRequired(input), readRequired(input))
        val length = ((header[0].toLong() and 0xff) shl 24) or
            ((header[1].toLong() and 0xff) shl 16) or
            ((header[2].toLong() and 0xff) shl 8) or
            (header[3].toLong() and 0xff)
        if (length <= 0 || length > maximumFrameSize.toLong()) {
            throw SshWireException("SSH agent frame length $length is outside 1..$maximumFrameSize")
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) throw EOFException("truncated SSH agent frame")
            if (read == 0) continue
            offset += read
        }
        return body
    }

    fun write(output: OutputStream, body: ByteArray, maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE) {
        if (body.isEmpty() || body.size > maximumFrameSize) {
            throw SshWireException("SSH agent frame body is outside 1..$maximumFrameSize")
        }
        val header = SshWireWriter(4).writeUInt32(body.size.toLong()).toByteArray()
        output.write(header)
        output.write(body)
        output.flush()
    }

    fun encode(body: ByteArray, maximumFrameSize: Int = DEFAULT_MAXIMUM_FRAME_SIZE): ByteArray {
        val output = ByteArrayOutputStream(body.size + 4)
        write(output, body, maximumFrameSize)
        return output.toByteArray()
    }

    private fun readRequired(input: InputStream): Byte {
        val value = input.read()
        if (value < 0) throw EOFException("truncated SSH agent frame header")
        return value.toByte()
    }
}
