package net.extrawdw.apps.notisync.data.storage.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.core.OperationalContinuityOrigin
import net.extrawdw.apps.notisync.data.storage.core.ReplayFenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreContinuityReadinessTest {
    @Test
    fun exactGenerationAndIncarnationAreTheOnlyCrossDatabaseIdentity() = runTest {
        assertEquals(OperationalContinuityValidation.VALID, validator().validate())
        assertEquals(
            OperationalContinuityValidation.MARKER_MISSING,
            validator(marker = null).validate(),
        )
        assertEquals(
            OperationalContinuityValidation.GENERATION_MISMATCH,
            validator(marker = marker(generation = 2)).validate(),
        )
        assertEquals(
            OperationalContinuityValidation.INCARNATION_MISMATCH,
            validator(marker = marker(incarnation = "other-incarnation")).validate(),
        )
    }

    @Test
    fun ordinaryPostCutoverWritesDoNotInvalidateContinuity() = runTest {
        assertEquals(
            OperationalContinuityValidation.VALID,
            validator(marker = marker(postCutoverWriteAt = 999)).validate(),
        )
    }

    @Test
    fun replayFenceStateControlsAvailabilityBeforeMarkerRead() = runTest {
        var markerReads = 0
        suspend fun result(state: ReplayFenceState): OperationalContinuityValidation =
            CoreOperationalContinuityValidator(
                transportSource = { transport(state = state) },
                markerSource = OperationalContinuityMarkerSource {
                    markerReads += 1
                    marker()
                },
            ).validate()

        assertEquals(OperationalContinuityValidation.REPLAY_FENCE_REQUIRED, result(ReplayFenceState.FENCE_REQUIRED))
        assertEquals(OperationalContinuityValidation.REPLAY_FENCE_ESTABLISHING, result(ReplayFenceState.ESTABLISHING))
        assertEquals(OperationalContinuityValidation.REPLAY_FENCE_BLOCKED, result(ReplayFenceState.BLOCKED))
        assertEquals(0, markerReads)
        assertEquals(OperationalContinuityValidation.VALID, result(ReplayFenceState.CONTINUITY_INTACT))
        assertEquals(1, markerReads)
    }

    @Test
    fun missingTransportAndCancellationPropagateExactly() = runTest {
        assertEquals(
            OperationalContinuityValidation.CORE_TRANSPORT_MISSING,
            validator(transport = null).validate(),
        )
        val cancelled = CoreOperationalContinuityValidator(
            transportSource = { transport() },
            markerSource = OperationalContinuityMarkerSource { throw CancellationException("cancel") },
        )
        assertTrue(runCatching { cancelled.validate() }.exceptionOrNull() is CancellationException)
    }

    private fun validator(
        transport: CoreTransportSnapshot? = transport(),
        marker: ObservedOperationalContinuityMarker? = marker(),
    ) = CoreOperationalContinuityValidator(
        transportSource = { transport },
        markerSource = OperationalContinuityMarkerSource { marker },
    )

    private fun marker(
        generation: Long = 1,
        incarnation: String = "incarnation-1",
        postCutoverWriteAt: Long? = null,
    ) = ObservedOperationalContinuityMarker(generation, incarnation, postCutoverWriteAt)

    private fun transport(
        state: ReplayFenceState = ReplayFenceState.CONTINUITY_INTACT,
    ) = CoreTransportSnapshot(
        brokerUrl = "https://broker.example.test",
        groupId = null,
        fcmRouteRef = null,
        routeEpoch = 0,
        brokerEndpointRevision = 0,
        selfEpochActivatedAt = null,
        operationalGeneration = 1,
        operationalIncarnationId = "incarnation-1",
        replayFenceState = state,
        continuityOrigin = OperationalContinuityOrigin.VERIFIED_V51_CUTOVER.takeIf {
            state == ReplayFenceState.CONTINUITY_INTACT
        },
        replayFenceId = "fence-1".takeIf { state == ReplayFenceState.ESTABLISHED },
        replayFenceEpoch = 1.takeIf { state == ReplayFenceState.ESTABLISHED },
        updatedAt = 1,
    )
}
