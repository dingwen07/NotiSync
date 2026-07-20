package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenViewerToolbarPreferenceStoreTest {
    @Test
    fun `defaults to the top edge with navigation controls visible`() {
        val preferences = ScreenViewerToolbarPreferenceStore(dataStore("toolbar-defaults")).preferences.value

        assertEquals(ScreenViewerToolbarEdge.TOP, preferences.edge)
        assertEquals(
            setOf(
                ScreenViewerControl.BACK,
                ScreenViewerControl.HOME,
                ScreenViewerControl.RECENTS,
            ),
            preferences.pinnedControls,
        )
    }

    @Test
    fun `edge and control visibility persist across store instances`() = runBlocking {
        val dataStore = dataStore("toolbar-persistence")
        val preferences = ScreenViewerToolbarPreferenceStore(dataStore)

        preferences.setEdge(ScreenViewerToolbarEdge.BOTTOM)
        preferences.setControlPinned(ScreenViewerControl.HOME, false)
        preferences.setControlPinned(ScreenViewerControl.KEYBOARD, true)
        preferences.setControlPinned(ScreenViewerControl.POWER, true)

        val reloaded = ScreenViewerToolbarPreferenceStore(dataStore).preferences.value
        assertEquals(ScreenViewerToolbarEdge.BOTTOM, reloaded.edge)
        assertEquals(
            setOf(
                ScreenViewerControl.BACK,
                ScreenViewerControl.RECENTS,
                ScreenViewerControl.KEYBOARD,
                ScreenViewerControl.POWER,
            ),
            reloaded.pinnedControls,
        )
    }

    @Test
    fun `unknown persisted names are dropped without losing known controls`() = runBlocking {
        val dataStore = dataStore("toolbar-forward-compat")
        dataStore.edit {
            it[stringPreferencesKey(EDGE_KEY)] = "floating"
            it[stringSetPreferencesKey(CONTROLS_KEY)] = setOf("home", "power", "future_stylus")
        }

        val preferences = ScreenViewerToolbarPreferenceStore(dataStore).preferences.value

        assertEquals(ScreenViewerToolbarEdge.TOP, preferences.edge)
        assertEquals(setOf(ScreenViewerControl.HOME, ScreenViewerControl.POWER), preferences.pinnedControls)
    }

    @Test
    fun `an explicitly empty control set remains empty and controls can be restored`() = runBlocking {
        val dataStore = dataStore("toolbar-empty")
        val preferences = ScreenViewerToolbarPreferenceStore(dataStore)

        ScreenViewerControl.entries.forEach { preferences.setControlPinned(it, false) }

        assertTrue(preferences.preferences.value.pinnedControls.isEmpty())
        val reloaded = ScreenViewerToolbarPreferenceStore(dataStore)
        assertTrue(reloaded.preferences.value.pinnedControls.isEmpty())

        reloaded.setControlPinned(ScreenViewerControl.KEYBOARD, true)
        assertEquals(setOf(ScreenViewerControl.KEYBOARD), reloaded.preferences.value.pinnedControls)
        assertFalse(reloaded.preferences.value.pinnedControls.contains(ScreenViewerControl.HOME))
    }

    @Test
    fun `restored controls retain canonical toolbar order`() = runBlocking {
        val dataStore = dataStore("toolbar-order")
        dataStore.edit {
            it[stringSetPreferencesKey(CONTROLS_KEY)] = linkedSetOf("power", "back", "keyboard")
        }

        val preferences = ScreenViewerToolbarPreferenceStore(dataStore)

        assertEquals(
            listOf(
                ScreenViewerControl.BACK,
                ScreenViewerControl.KEYBOARD,
                ScreenViewerControl.POWER,
            ),
            preferences.preferences.value.pinnedControls.toList(),
        )
    }

    private fun dataStore(name: String): DataStore<Preferences> {
        val file = File.createTempFile("$name-${System.nanoTime()}", ".preferences_pb")
            .also(File::delete)
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file }
    }

    private companion object {
        const val EDGE_KEY = "screen_viewer_toolbar_edge_v1"
        const val CONTROLS_KEY = "screen_viewer_toolbar_controls_v1"
    }
}
