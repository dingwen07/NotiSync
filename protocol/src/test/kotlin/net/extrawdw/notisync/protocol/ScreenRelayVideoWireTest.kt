package net.extrawdw.notisync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenRelayVideoWireTest {
    @Test
    fun canonicalHeaderRoundTripsWithCiphertextSize() {
        val headerBytes = ScreenRelayVideoWire.encodeHeader(
            flags = ScreenRelayVideoWire.FLAG_KEY_FRAME,
            recordSequence = 42,
            recordBytes = 50_000,
            fragmentOffset = ScreenRelayVideoWire.MAX_FRAGMENT_PLAINTEXT_BYTES,
            fragmentBytes = 100,
        )
        val message = headerBytes + ByteArray(100 + ScreenRelayVideoWire.AEAD_TAG_BYTES)

        val header = requireNotNull(ScreenRelayVideoWire.decodeHeader(message))

        assertEquals(ScreenRelayVideoWire.FLAG_KEY_FRAME, header.flags)
        assertEquals(42L, header.recordSequence)
        assertEquals(50_000, header.recordBytes)
        assertEquals(ScreenRelayVideoWire.MAX_FRAGMENT_PLAINTEXT_BYTES, header.fragmentOffset)
        assertEquals(100, header.fragmentBytes)
    }

    @Test
    fun rejectsNonCanonicalFlagsAndCiphertextLength() {
        val header = ScreenRelayVideoWire.encodeHeader(
            flags = ScreenRelayVideoWire.FLAG_DELTA,
            recordSequence = 0,
            recordBytes = 10,
            fragmentOffset = 0,
            fragmentBytes = 10,
        )
        val wrongFlags = header.copyOf().also {
            it[6] = (ScreenRelayVideoWire.FLAG_DELTA or ScreenRelayVideoWire.FLAG_KEY_FRAME).toByte()
        } + ByteArray(10 + ScreenRelayVideoWire.AEAD_TAG_BYTES)

        assertNull(ScreenRelayVideoWire.decodeHeader(wrongFlags))
        assertNull(ScreenRelayVideoWire.decodeHeader(header + ByteArray(9 + ScreenRelayVideoWire.AEAD_TAG_BYTES)))
    }
}
