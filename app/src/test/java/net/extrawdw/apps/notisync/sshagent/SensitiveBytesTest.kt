package net.extrawdw.apps.notisync.sshagent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveBytesTest {
    @Test
    fun closeWipesOwnedBufferAndRejectsFurtherAccess() {
        val source = byteArrayOf(1, 2, 3, 4)
        val sensitive = SensitiveBytes.takeOwnership(source)

        sensitive.close()

        assertTrue(source.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { sensitive.bytes }
    }

    @Test
    fun copyHasIndependentOwnership() {
        val source = byteArrayOf(1, 2, 3, 4)
        val original = SensitiveBytes.takeOwnership(source)
        val copy = original.copy()

        original.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), copy.bytes)
        copy.close()
        assertTrue(source.all { it == 0.toByte() })
    }

    @Test
    fun takeTransfersWipeResponsibilityToCaller() {
        val sensitive = SensitiveBytes.takeOwnership(byteArrayOf(1, 2, 3, 4))

        val transferred = sensitive.take()
        sensitive.close()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), transferred)
        transferred.fill(0)
        assertTrue(transferred.all { it == 0.toByte() })
    }
}
