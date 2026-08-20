package net.extrawdw.apps.notisync.sshagent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentManagementRepositoryTest {
    @Test
    fun preloadPublishesSnapshotAndUnchangedRefreshDoesNotReload() = runTest {
        val version = MutableStateFlow(0L)
        val expected = snapshot()
        var loads = 0
        val repository = repository(version) {
            loads += 1
            expected
        }

        repository.preload()
        repository.refresh()

        assertSame(expected, repository.state.value.snapshot)
        assertEquals(1, loads)
    }

    @Test
    fun observerRefreshesSnapshotWhenStoreVersionChanges() = runTest {
        val version = MutableStateFlow(0L)
        val first = snapshot("first.example")
        val second = snapshot("second.example")
        var current = first
        val repository = repository(version) { current }
        repository.preload()
        repository.start()
        runCurrent()

        current = second
        version.value = 1L
        runCurrent()

        assertSame(second, repository.state.value.snapshot)
    }

    @Test
    fun refreshFailurePreservesLastGoodSnapshot() = runTest {
        val version = MutableStateFlow(0L)
        val expected = snapshot()
        var failure: Throwable? = null
        val repository = repository(version) {
            failure?.let { throw it }
            expected
        }
        repository.preload()

        failure = IllegalStateException("database unavailable")
        version.value = 1L
        repository.refresh()

        assertSame(expected, repository.state.value.snapshot)
        assertNotNull(repository.state.value.errorMessage)
    }

    @Test
    fun preloadRetriesWhenMutationOverlapsAggregateRead() = runTest {
        val version = MutableStateFlow(0L)
        val stale = snapshot("stale.example")
        val expected = snapshot("current.example")
        var loads = 0
        val repository = repository(version) {
            loads += 1
            if (loads == 1) {
                version.value = 1L
                stale
            } else {
                expected
            }
        }

        repository.preload()

        assertSame(expected, repository.state.value.snapshot)
        assertEquals(2, loads)
    }

    private fun TestScope.repository(
        version: MutableStateFlow<Long>,
        loader: () -> SshAgentManagementSnapshot,
    ) = SshAgentManagementRepository(
        changeVersion = version,
        loadSnapshot = loader,
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun snapshot(hostname: String? = null) = SshAgentManagementSnapshot(
        keys = emptyList(),
        requests = emptyList(),
        knownHosts = hostname?.let {
            listOf(SshKnownHost(ByteArray(32), it, firstApprovedAt = 1L, lastApprovedAt = 1L))
        }.orEmpty(),
        rememberedAuthorizations = emptyList(),
    )
}
