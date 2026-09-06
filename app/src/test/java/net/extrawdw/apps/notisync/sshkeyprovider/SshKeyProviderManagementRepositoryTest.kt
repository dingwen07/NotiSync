package net.extrawdw.apps.notisync.sshkeyprovider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshKeyProviderManagementRepositoryTest {
    @Test
    fun noScreenMeansNoLoadsEvenWhenTheStoreChanges() = runTest {
        val version = MutableStateFlow(0L)
        var loads = 0
        val repository = repository(version) { loads++; snapshot() }
        runCurrent()
        version.value++
        runCurrent()

        assertNull(repository.state.value.snapshot)
        assertEquals(0, loads)
    }

    @Test
    fun observationLoadsOnceSharesRefreshesAndStopsAfterTheLastCollector() = runTest {
        val version = MutableStateFlow(0L)
        var current = snapshot("first.example")
        var loads = 0
        val repository = repository(version) { loads++; current }
        val first = backgroundScope.launch { repository.state.collect() }
        val second = backgroundScope.launch { repository.state.collect() }
        runCurrent()
        assertEquals(1, loads)

        first.cancel()
        current = snapshot("second.example")
        version.value++
        runCurrent()
        assertSame(current, repository.state.value.snapshot)
        assertEquals(2, loads)

        second.cancel()
        runCurrent()
        current = snapshot("third.example")
        version.value++
        runCurrent()
        assertEquals(2, loads)

        backgroundScope.launch { repository.state.collect() }
        runCurrent()
        assertSame(current, repository.state.value.snapshot)
        assertEquals(3, loads)
    }

    @Test
    fun returningToAnUnchangedStoreDoesNotReload() = runTest {
        val version = MutableStateFlow(0L)
        var loads = 0
        val repository = repository(version) { loads++; snapshot() }
        val collector = backgroundScope.launch { repository.state.collect() }
        runCurrent()
        collector.cancel()
        runCurrent()
        backgroundScope.launch { repository.state.collect() }
        runCurrent()
        repository.refresh()
        assertEquals(1, loads)
    }

    @Test
    fun refreshFailurePreservesLastGoodSnapshotAndReturningRetries() = runTest {
        val version = MutableStateFlow(0L)
        val expected = snapshot()
        var failure: Throwable? = null
        val repository = repository(version) {
            failure?.let { throw it }
            expected
        }
        val collector = backgroundScope.launch { repository.state.collect() }
        runCurrent()

        failure = IllegalStateException("database unavailable")
        version.value++
        runCurrent()
        assertSame(expected, repository.state.value.snapshot)
        assertNotNull(repository.state.value.errorMessage)

        collector.cancel()
        runCurrent()
        failure = null
        backgroundScope.launch { repository.state.collect() }
        runCurrent()
        assertNull(repository.state.value.errorMessage)
    }

    @Test
    fun snapshotIncludesItsVersionAfterExpiryCleanupWithoutRepeatingTheLoad() = runTest {
        val version = MutableStateFlow(0L)
        val expected = snapshot()
        var loads = 0
        val repository = repository(version) {
            loads++
            version.value++
            expected
        }
        backgroundScope.launch { repository.state.collect() }
        runCurrent()

        assertSame(expected, repository.state.value.snapshot)
        assertEquals(1, loads)
    }

    private fun TestScope.repository(
        version: MutableStateFlow<Long>,
        loader: () -> SshKeyProviderManagementSnapshot,
    ) = SshKeyProviderManagementRepository(
        changeVersion = version,
        loadSnapshot = {
            val snapshot = loader()
            VersionedSshKeyProviderManagementSnapshot(version.value, snapshot)
        },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun snapshot(hostname: String? = null) = SshKeyProviderManagementSnapshot(
        keys = emptyList(),
        requests = emptyList(),
        knownHosts = hostname?.let {
            listOf(SshKnownHost(ByteArray(32), it, firstApprovedAt = 1L, lastApprovedAt = 1L))
        }.orEmpty(),
        rememberedAuthorizations = emptyList(),
    )
}
