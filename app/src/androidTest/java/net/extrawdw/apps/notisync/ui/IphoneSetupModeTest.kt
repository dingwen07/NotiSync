package net.extrawdw.apps.notisync.ui

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ios.IphoneSetupPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IphoneSetupModeTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "bridge-mode-test-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun settings(listed: Boolean) = IphoneSetupPreferences(prefs, listed)

    @After fun cleanup() { context.deleteSharedPreferences(name) }

    private fun show(preferences: IphoneSetupPreferences) {
        compose.setContent {
            IphoneSetupModeSelector(
                appSetup = preferences.appModeChanges.collectAsState(preferences.usesApp).value,
                onModeChanged = {},
                preferences = preferences,
            )
        }
    }

    private fun tap(id: Int) = compose.onNodeWithText(context.getString(id)).performClick()

    @Test fun unlistedPhoneRemembersFallbackUntilNativeIsConfirmed() {
        val mode = settings(false)
        assertFalse(mode.usesApp)
        assertFalse(mode.warnBeforeNative)
        show(mode)
        compose.runOnIdle { mode.recordRepeatedLoss() }
        assertTrue(settings(false).usesApp)
        assertTrue(settings(false).warnBeforeNative)
        tap(R.string.ios_setup_mode_bluetooth)
        tap(R.string.action_cancel)
        assertTrue(settings(false).usesApp)
        assertTrue(settings(false).warnBeforeNative)
        tap(R.string.ios_setup_mode_bluetooth)
        tap(R.string.action_continue)
        assertFalse(settings(false).usesApp)
        assertFalse(settings(false).warnBeforeNative)
        tap(R.string.ios_setup_mode_app)
        tap(R.string.ios_setup_mode_bluetooth)
        assertFalse(settings(false).usesApp)
    }

    @Test fun listedPhoneDefaultsToAppAndWarnsOnEveryReturnToNative() {
        // An older native choice on an unlisted build never acknowledged the device warning.
        settings(false).selectMode(false)
        val listed = settings(true)
        assertTrue(listed.usesApp)
        show(listed)
        repeat(2) {
            tap(R.string.ios_setup_mode_bluetooth)
            tap(R.string.action_continue)
            assertFalse(settings(true).usesApp)
            assertTrue(settings(true).warnBeforeNative)
            tap(R.string.ios_setup_mode_app)
        }
    }
}
