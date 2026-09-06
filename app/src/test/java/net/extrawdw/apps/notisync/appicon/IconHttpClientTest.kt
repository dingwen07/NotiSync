package net.extrawdw.apps.notisync.appicon

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IconHttpClientTest {
    @Test
    fun allowsExactLimitAndRejectsEvenOneExtraByte() = runBlocking {
        val source = ByteArray(16) { it.toByte() }
        assertArrayEquals(source, ByteReadChannel(source).readBoundedIconBytes(16))
        assertArrayEquals(byteArrayOf(), ByteReadChannel(byteArrayOf()).readBoundedIconBytes(16))
        try {
            ByteReadChannel(ByteArray(17)).readBoundedIconBytes(16)
            fail("Oversized response must be rejected")
        } catch (_: IconSourceTooLargeException) { }
    }
}
