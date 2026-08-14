package net.extrawdw.apps.notisync.sshagent

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshAesWrappedKeyEnvelopeCodecTest {
    @Test
    fun roundTripsBoundedEnvelope() {
        val wrapNonce = ByteArray(12) { it.toByte() }
        val wrappedDek = ByteArray(48) { (it + 12).toByte() }
        val dataNonce = ByteArray(12) { (it + 60).toByte() }
        val ciphertext = ByteArray(96) { (it + 72).toByte() }

        val decoded = SshAesWrappedKeyEnvelopeCodec.decode(
            SshAesWrappedKeyEnvelopeCodec.encode(wrapNonce, wrappedDek, dataNonce, ciphertext),
        )

        assertArrayEquals(wrapNonce, decoded.wrapNonce)
        assertArrayEquals(wrappedDek, decoded.wrappedDek)
        assertArrayEquals(dataNonce, decoded.dataNonce)
        assertArrayEquals(ciphertext, decoded.dataCiphertext)
    }

    @Test
    fun rejectsTruncatedEnvelope() {
        val encoded = SshAesWrappedKeyEnvelopeCodec.encode(
            ByteArray(12),
            ByteArray(48),
            ByteArray(12),
            ByteArray(32),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SshAesWrappedKeyEnvelopeCodec.decode(encoded.copyOf(encoded.size - 1))
        }
    }

    @Test
    fun rejectsOversizedDeclaredFieldBeforeAllocation() {
        val encoded = ByteBuffer.allocate(9)
            .put(byteArrayOf(0x4e, 0x53, 0x41, 0x57))
            .put(1)
            .putInt(Int.MAX_VALUE)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            SshAesWrappedKeyEnvelopeCodec.decode(encoded)
        }
    }
}
