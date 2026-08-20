package net.extrawdw.apps.notisync.data.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayModelsTest {
    @Test
    fun authenticatedTokenOwnsExactSha256BytesWithoutLeakingThem() {
        val source = ByteArray(RelayLimits.AUTHENTICATED_TOKEN_BYTES) { it.toByte() }
        val token = AuthenticatedRelayToken.of(source)
        source[0] = 99

        assertEquals(token, AuthenticatedRelayToken.of(ByteArray(32) { it.toByte() }))
        val exposed = token.copyBytes()
        exposed[1] = 99
        assertArrayEquals(ByteArray(32) { it.toByte() }, token.copyBytes())
        assertFalse(token.toString().contains(source.joinToString()))
        assertThrows(IllegalArgumentException::class.java) {
            AuthenticatedRelayToken.of(ByteArray(RelayLimits.AUTHENTICATED_TOKEN_BYTES - 1))
        }
    }

    @Test
    fun continuityAndStableCodesRejectUnboundedOrNonPortableValues() {
        assertEquals(1, RelayOperationalContinuity(1, "incarnation-1").generation)
        listOf("", "has:separator", "x".repeat(129)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                RelayOperationalContinuity(1, value)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RelayOperationalContinuity(0, "incarnation-1")
        }
        assertEquals("storage_retry", RelayStableCode.of("storage_retry").token)
        listOf("", "UPPER", "contains space", "x".repeat(RelayLimits.MAX_CODE_CHARS + 1)).forEach { code ->
            assertThrows(IllegalArgumentException::class.java) { RelayStableCode.of(code) }
        }
    }

}
