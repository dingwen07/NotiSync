package net.extrawdw.notisync.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object ChannelBindingCodec {
    private const val MAGIC = 0x4e535331 // NSS1
    private const val MAX_FRAME_BYTES = 4 * 1024

    fun write(output: OutputStream, binding: ChannelBinding) {
        val body = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeInt(MAGIC)
                data.writeShort(ScreenSessionProtocol.VERSION)
                data.writeByte(binding.channel.ordinal)
                data.writeByte(binding.role.ordinal)
                val descriptor = binding.descriptor
                data.writeUtf8(descriptor.sessionId)
                data.writeUtf8(descriptor.sourcePeerId)
                data.writeUtf8(descriptor.requesterPeerId)
                data.writeLong(descriptor.issuedAtEpochMillis)
                data.writeLong(descriptor.expiresAtEpochMillis)
                data.writeUtf8(descriptor.codec)
                data.writeBoolean(descriptor.controlEnabled)
                data.writeBoolean(descriptor.clipboardEnabled)
                data.writeInt(descriptor.maxDimension)
                data.writeInt(descriptor.maxFps)
                data.writeInt(descriptor.videoBitrateBps)
            }
            bytes.toByteArray()
        }
        require(body.size <= MAX_FRAME_BYTES) { "channel binding frame is too large" }
        DataOutputStream(output).run {
            writeInt(body.size)
            write(body)
            flush()
        }
    }

    fun read(input: InputStream): ChannelBinding {
        val framed = DataInputStream(input)
        val size = try {
            framed.readInt()
        } catch (error: EOFException) {
            throw IOException("channel closed before binding", error)
        }
        if (size !in 1..MAX_FRAME_BYTES) throw IOException("invalid channel binding size: $size")
        val body = framed.readNBytes(size)
        if (body.size != size) throw EOFException("truncated channel binding")
        val data = DataInputStream(ByteArrayInputStream(body))
        if (data.readInt() != MAGIC) throw IOException("invalid channel binding magic")
        if (data.readUnsignedShort() != ScreenSessionProtocol.VERSION) {
            throw IOException("unsupported channel binding version")
        }
        val channel = ScreenChannel.entries.getOrNull(data.readUnsignedByte())
            ?: throw IOException("invalid screen channel")
        val role = SessionRole.entries.getOrNull(data.readUnsignedByte())
            ?: throw IOException("invalid session role")
        val descriptor = try {
            SessionDescriptor(
                sessionId = data.readUtf8(128),
                sourcePeerId = data.readUtf8(256),
                requesterPeerId = data.readUtf8(256),
                issuedAtEpochMillis = data.readLong(),
                expiresAtEpochMillis = data.readLong(),
                codec = data.readUtf8(32),
                controlEnabled = data.readBoolean(),
                clipboardEnabled = data.readBoolean(),
                maxDimension = data.readInt(),
                maxFps = data.readInt(),
                videoBitrateBps = data.readInt(),
            )
        } catch (error: IllegalArgumentException) {
            throw IOException("invalid channel binding fields", error)
        }
        if (data.available() != 0) throw IOException("trailing channel binding bytes")
        return ChannelBinding(descriptor, channel, role)
    }

    private fun DataInputStream.readUtf8(maxBytes: Int): String {
        val size = readUnsignedShort()
        if (size > maxBytes) throw IOException("channel binding string is too large")
        val bytes = readNBytes(size)
        if (bytes.size != size) throw EOFException("truncated channel binding string")
        val value = bytes.decodeToString(throwOnInvalidSequence = true)
        if (value.encodeToByteArray().size != size) throw IOException("non-canonical UTF-8")
        return value
    }
}
