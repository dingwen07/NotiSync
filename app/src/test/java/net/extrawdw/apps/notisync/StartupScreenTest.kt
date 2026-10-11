package net.extrawdw.apps.notisync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupScreenTest {
    @Test
    fun ordinaryCheckingAndInitializationKeepOnlyTheSystemSplash() {
        val checking = AppStartupState(stage = AppStartupStage.CHECKING_STORAGE)
        val initializing = AppStartupState(stage = AppStartupStage.INITIALIZING_APPLICATION)

        assertTrue(shouldKeepSystemSplash(checking))
        assertTrue(shouldKeepSystemSplash(initializing))
    }

    @Test
    fun readyAndFailureReleaseTheSystemSplash() {
        val ready = AppStartupState(stage = AppStartupStage.READY)
        val failed = AppStartupState(stage = AppStartupStage.FAILED)

        assertFalse(shouldKeepSystemSplash(ready))
        assertFalse(shouldKeepSystemSplash(failed))
    }
}
