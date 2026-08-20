package net.extrawdw.apps.notisync.data.storage.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadException
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadFailureCode
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalPayloadKeyAccessTest {
    @Test
    fun featureLocalEnsureRepeatsIdempotentCreateAndSelfTestWithoutJournal() = runTest {
        val vault = FakeVault()
        val ensurer = ensurer(vault = vault)

        assertEquals(OperationalPayloadKeyAvailability.Ready(GENERATION), ensurer.ensureCurrent())
        assertEquals(OperationalPayloadKeyAvailability.Ready(GENERATION), ensurer.ensureCurrent())
        assertEquals(
            listOf("create:$GENERATION", "self_test:$GENERATION", "create:$GENERATION", "self_test:$GENERATION"),
            vault.events,
        )
    }

    @Test
    fun cancellationAfterIdempotentCreateNeedsNoPersistedRecoveryState() = runTest {
        val vault = FakeVault(cancelFirstCreate = true)
        val ensurer = ensurer(vault = vault)

        assertTrue(runCatching { ensurer.ensureCurrent() }.exceptionOrNull() is CancellationException)
        assertEquals(OperationalPayloadKeyAvailability.Ready(GENERATION), ensurer.ensureCurrent())
        assertEquals(2, vault.events.count { it == "create:$GENERATION" })
        assertEquals(1, vault.events.count { it == "self_test:$GENERATION" })
    }

    @Test
    fun generationChangeDuringProviderWorkFailsRetryable() = runTest {
        val source = SequencedGenerationSource(listOf(GENERATION, GENERATION + 1))
        val result = ensurer(generationSource = source).ensureCurrent()

        assertTrue(result is OperationalPayloadKeyAvailability.Unavailable)
        result as OperationalPayloadKeyAvailability.Unavailable
        assertEquals(GENERATION + 1, result.generation)
        assertEquals(OperationalPayloadKeyFailureKind.RETRYABLE, result.failure.kind)
        assertEquals("operational_generation_changed", result.failure.code)
    }

    @Test
    fun providerAndMissingKeyFailuresUseTypedPrivacySafePolicy() = runTest {
        val provider = ensurer(
            vault = FakeVault(
                failure = protectedFailure(ProtectedPayloadFailureCode.PROVIDER_FAILURE),
            ),
        ).ensureCurrent() as OperationalPayloadKeyAvailability.Unavailable
        assertEquals(OperationalPayloadKeyFailureKind.RETRYABLE, provider.failure.kind)
        assertEquals("protected_payload_provider_failure", provider.failure.code)

        val missing = ensurer(
            vault = FakeVault(
                failure = protectedFailure(ProtectedPayloadFailureCode.KEY_MISSING),
            ),
        ).ensureCurrent() as OperationalPayloadKeyAvailability.Unavailable
        assertEquals(OperationalPayloadKeyFailureKind.SECURITY_BLOCKING, missing.failure.kind)
        assertEquals("protected_payload_key_missing", missing.failure.code)
    }

    @Test
    fun maintenanceGateSerializesProtectedWriterAndReset() = runTest {
        val gate = OperationalStorageMaintenanceGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resetEntered = CompletableDeferred<Unit>()

        val writer = async {
            gate.withProtectedGeneration(GENERATION, OperationalGenerationSource { GENERATION }) {
                entered.complete(Unit)
                release.await()
                "written"
            }
        }
        entered.await()
        val reset = async {
            gate.withExclusiveAccess {
                resetEntered.complete(Unit)
            }
        }
        assertFalse(resetEntered.isCompleted)
        release.complete(Unit)
        assertEquals(ProtectedGenerationResult.Executed("written"), writer.await())
        reset.await()
        assertTrue(resetEntered.isCompleted)
    }

    private fun ensurer(
        generationSource: OperationalGenerationSource = OperationalGenerationSource { GENERATION },
        vault: FakeVault = FakeVault(),
    ): OperationalPayloadKeyEnsurer = OperationalPayloadKeyEnsurer(
        generationSource = generationSource,
        vault = vault,
        maintenanceGate = OperationalStorageMaintenanceGate(),
    )

    private fun protectedFailure(code: ProtectedPayloadFailureCode): ProtectedPayloadException =
        ProtectedPayloadException(code, ProtectedPayloadOperation.SELF_TEST_KEY)

    private class FakeVault(
        private val cancelFirstCreate: Boolean = false,
        private val failure: ProtectedPayloadException? = null,
    ) : OperationalPayloadKeyVault {
        val events = mutableListOf<String>()
        private var cancelled = false

        override suspend fun create(generation: Long) {
            events += "create:$generation"
            if (cancelFirstCreate && !cancelled) {
                cancelled = true
                throw CancellationException("simulated process stop")
            }
            failure?.let { throw it }
        }

        override suspend fun selfTest(generation: Long) {
            events += "self_test:$generation"
            failure?.let { throw it }
        }
    }

    private class SequencedGenerationSource(generations: List<Long?>) : OperationalGenerationSource {
        private val values = ArrayDeque(generations)
        override suspend fun currentGeneration(): Long? = values.removeFirst()
    }

    private companion object {
        const val GENERATION = 7L
    }
}
