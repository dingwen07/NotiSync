package net.extrawdw.apps.notisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupScreenTest {
    @Test
    fun normalProgressDelayIsMeasuredFromApplicationStartup() {
        val startup = 5_000L

        assertEquals(1L, remainingStartupProgressDelayMillis(startup, nowElapsedRealtime = 5_999L))
        assertEquals(0L, remainingStartupProgressDelayMillis(startup, nowElapsedRealtime = 6_000L))
        assertEquals(0L, remainingStartupProgressDelayMillis(startup, nowElapsedRealtime = 7_000L))
    }

    @Test
    fun normalInitializationWaitsForDelayBeforeShowingProgress() {
        val state = AppStartupState(stage = AppStartupStage.INITIALIZING_APPLICATION)

        assertFalse(shouldShowStartupProgress(state, normalStartupDelayElapsed = false))
        assertTrue(shouldShowStartupProgress(state, normalStartupDelayElapsed = true))
    }

    @Test
    fun databaseImportShowsProgressImmediatelyAndKeepsItThroughInitialization() {
        val importing = AppStartupState(
            stage = AppStartupStage.IMPORTING_DATABASE,
            databaseImportRequired = true,
        )
        val initializing = importing.copy(stage = AppStartupStage.INITIALIZING_APPLICATION)

        assertTrue(shouldShowStartupProgress(importing, normalStartupDelayElapsed = false))
        assertTrue(shouldShowStartupProgress(initializing, normalStartupDelayElapsed = false))
    }

    @Test
    fun failedStartupStopsIndeterminateProgress() {
        val state = AppStartupState(
            stage = AppStartupStage.FAILED,
            databaseImportRequired = true,
        )

        assertFalse(shouldShowStartupProgress(state, normalStartupDelayElapsed = true))
    }
}
