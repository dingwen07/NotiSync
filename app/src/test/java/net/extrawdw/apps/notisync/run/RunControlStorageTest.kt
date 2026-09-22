package net.extrawdw.apps.notisync.run

import net.extrawdw.apps.notisync.data.storage.operational.RunControlStorage
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RunControlStorageTest {
    @Test
    fun everyControlKindRoundTripsWithoutNormalizingInputOrSignal() {
        val refresh = refresh()
        val controls = listOf(
            refresh,
            refresh.copy(kind = RunControlKind.WRITE_INPUT, interactionGeneration = 0, inputText = ""),
            refresh.copy(
                kind = RunControlKind.WRITE_INPUT,
                interactionGeneration = 4_294_967_296L,
                inputText = "  λ 中文 😀\r\nnext line\n",
            ),
            refresh.copy(kind = RunControlKind.SIGNAL, signal = "RTMIN+1"),
            refresh.copy(kind = RunControlKind.SIGNAL, signal = "09"),
        )

        for (control in controls) {
            val values = RunControlStorage.values(control)
            assertEquals(RunControlStorage.columns, values.keys.toList())
            assertEquals(control, RunControlStorage.reconstruct(values))
        }
        val refreshValues = RunControlStorage.values(refresh)
        assertNull(refreshValues["interaction_generation"])
        assertNull(refreshValues["input_text"])
        assertNull(refreshValues["signal"])
    }

    @Test
    fun reconstructionEnforcesProtocolControlShape() {
        val original = RunControlStorage.values(refresh())
        for (invalid in listOf(
            original + ("kind" to "UNKNOWN"),
            original + ("kind" to "WRITE_INPUT"),
            original + ("signal" to "TERM"),
            original + ("requested_at" to -1L),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                RunControlStorage.reconstruct(invalid)
            }
        }
    }

    private fun refresh() = RunControl(
        requestId = "00000000-0000-4000-8000-000000000010",
        hostClientId = ClientId("host"),
        runId = "run-1",
        kind = RunControlKind.REFRESH,
        requestedAt = 1_000,
    )
}
