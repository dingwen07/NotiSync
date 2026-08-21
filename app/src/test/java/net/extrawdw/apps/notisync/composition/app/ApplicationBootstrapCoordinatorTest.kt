package net.extrawdw.apps.notisync.composition.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationBootstrapCoordinatorTest {
    @Test
    fun readyServiceIsPublishedExactlyOnce() = runTest {
        var calls = 0
        val service = Any()
        val coordinator = ApplicationBootstrapCoordinator(backgroundScope) {
            calls += 1
            ApplicationBootstrapOutcome.Ready(service)
        }

        val first = coordinator.start()
        val second = coordinator.start()
        first.join()

        assertSame(first, second)
        assertEquals(1, calls)
        val state = coordinator.state.value
        assertTrue(state is ApplicationBootstrapState.Ready<*>)
        assertSame(service, (state as ApplicationBootstrapState.Ready<*>).services)
    }

    @Test
    fun typedUnavailableOutcomeNeverPublishesReady() = runTest {
        val failure = ApplicationBootstrapFailure(
            ApplicationBootstrapFailureKind.SECURITY_BLOCKING,
            "verified_state_invalid",
        )
        val coordinator = ApplicationBootstrapCoordinator<Any>(backgroundScope) {
            ApplicationBootstrapOutcome.Unavailable(failure)
        }

        coordinator.start().join()

        assertEquals(ApplicationBootstrapState.Unavailable(failure), coordinator.state.value)
    }

    @Test
    fun unexpectedFailureIsRedactedAndSecurityBlocking() = runTest {
        val coordinator = ApplicationBootstrapCoordinator<Any>(backgroundScope) {
            error("private variable detail")
        }

        coordinator.start().join()

        assertEquals(
            ApplicationBootstrapState.Unavailable(
                ApplicationBootstrapFailure(
                    ApplicationBootstrapFailureKind.SECURITY_BLOCKING,
                    "application_bootstrap_unexpected_failure",
                ),
            ),
            coordinator.state.value,
        )
    }

    @Test
    fun backgroundConsumerWaitsForRuntimeStartup() = runTest {
        val releaseStartup = CompletableDeferred<Unit>()
        val runtime = Any()
        val coordinator = ApplicationBootstrapCoordinator<Any>(backgroundScope) {
            releaseStartup.await()
            ApplicationBootstrapOutcome.Ready(runtime)
        }
        coordinator.start()

        val observed = async { coordinator.awaitStartup() }
        assertFalse(observed.isCompleted)

        releaseStartup.complete(Unit)
        assertSame(runtime, (observed.await() as ApplicationBootstrapState.Ready<*>).services)
    }
}
