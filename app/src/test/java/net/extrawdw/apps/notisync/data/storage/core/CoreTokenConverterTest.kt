package net.extrawdw.apps.notisync.data.storage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreTokenConverterTest {
    @Test
    fun stateTokensRoundTripWithoutUsingOrdinals() {
        assertEquals(ReplayFenceState.CONTINUITY_INTACT, ReplayFenceState.fromToken("CONTINUITY_INTACT"))
        assertEquals(ReplayFenceState.FENCE_REQUIRED, ReplayFenceState.fromToken("FENCE_REQUIRED"))
        assertEquals(
            OperationalContinuityOrigin.VERIFIED_V51_CUTOVER,
            OperationalContinuityOriginConverter.decode("VERIFIED_V51_CUTOVER"),
        )
        assertEquals(
            CryptoEpochState.RETIRED,
            CryptoEpochState.fromToken(CryptoEpochState.RETIRED.token),
        )
        assertEquals("TERMINAL_REJECTED", CoreCommandOutcome.TERMINAL_REJECTED.token)
        assertEquals(
            KeystoreOperationTarget.WRAPPING_KEY,
            KeystoreOperationTargetConverter.decode("WRAPPING_KEY"),
        )
    }

    @Test
    fun unknownStateTokensFailClosed() {
        assertThrows(IllegalStateException::class.java) { ReplayFenceState.fromToken("ACTIVE") }
        assertThrows(IllegalStateException::class.java) { OperationalContinuityOrigin.fromToken("RESET") }
        assertThrows(IllegalStateException::class.java) { IdentityLifecycleState.fromToken("") }
        assertThrows(IllegalStateException::class.java) {
            KeystoreOperationTarget.fromToken("OPERATIONAL_GENERATION")
        }
    }
}
