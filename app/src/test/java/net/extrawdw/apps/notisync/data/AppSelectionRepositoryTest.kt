package net.extrawdw.apps.notisync.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.operational.AndroidAppEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState
import net.extrawdw.apps.notisync.testsupport.InMemoryOperationalApplicationState
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSelectionRepositoryTest {
    @Test
    fun disablingOneOfOneHundredAppsWritesOnlyThatRowAndPreservesMetadata() = runTest {
        val rows = (0 until 100).map { AndroidAppEntity("app-$it", true, "config-$it", "channels-$it") }
        val state = InMemoryOperationalApplicationState(initialAndroidApps = rows)
        val repository = AppSelectionRepository(backgroundScope, state)

        repository.setEnabled("app-42", false)
        runCurrent()

        assertEquals(1, state.androidRowWrites)
        assertEquals(0, state.androidBulkWrites)
        assertEquals(rows.map { if (it.packageName == "app-42") it.copy(enabled = false) else it }, state.androidApps())
        assertEquals(99, AppSelectionRepository(backgroundScope, state).enabled.value.size)
    }

    @Test
    fun bulkChangesTouchOnlyTheSelectedApps() = runTest {
        val state = InMemoryOperationalApplicationState(
            initialAndroidApps = listOf(AndroidAppEntity("untouched", true, null, null)),
        )
        val repository = AppSelectionRepository(backgroundScope, state)

        repository.setEnabled(listOf("first", "second"), true)
        runCurrent()

        assertEquals(1, state.androidBulkWrites)
        assertEquals(2, state.androidRowWrites)
        assertEquals(setOf("untouched", "first", "second"), AppSelectionRepository(backgroundScope, state).enabled.value)
    }

    @Test
    fun aToggleDuringAnInFlightBulkWritePersistsTheLatestSelection() = runTest {
        val backing = InMemoryOperationalApplicationState()
        val releaseWrite = CompletableDeferred<Unit>()
        val state = object : OperationalApplicationState by backing {
            override suspend fun setAndroidAppsEnabled(enabledByPackage: Map<String, Boolean>) {
                releaseWrite.await()
                backing.setAndroidAppsEnabled(enabledByPackage)
            }
        }
        val repository = AppSelectionRepository(backgroundScope, state)
        repository.setEnabled(listOf("first", "second"), true)
        runCurrent()

        repository.setEnabled("first", false)
        runCurrent()
        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals(setOf("second"), AppSelectionRepository(backgroundScope, backing).enabled.value)
    }
}
