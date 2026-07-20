package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ScreenViewerToolbarEdge {
    TOP,
    BOTTOM,
}

/** Canonical toolbar priority. Pinned controls that do not fit remain in overflow. */
internal enum class ScreenViewerControl {
    BACK,
    HOME,
    RECENTS,
    KEYBOARD,
    POWER,
}

internal data class ScreenViewerToolbarPreferences(
    val edge: ScreenViewerToolbarEdge = ScreenViewerToolbarEdge.TOP,
    val pinnedControls: Set<ScreenViewerControl> = defaultPinnedControls(),
)

/** Durable viewer chrome preferences shared by every Android-to-Android screen session. */
internal class ScreenViewerToolbarPreferenceStore(
    private val store: DataStore<Preferences>,
) {
    private val mutex = Mutex()
    private val _preferences = MutableStateFlow(load())
    val preferences: StateFlow<ScreenViewerToolbarPreferences> = _preferences.asStateFlow()

    suspend fun setEdge(edge: ScreenViewerToolbarEdge) = mutex.withLock {
        update { current -> current.copy(edge = edge) }
    }

    suspend fun setControlPinned(control: ScreenViewerControl, pinned: Boolean) = mutex.withLock {
        update { current ->
            val selected = current.pinnedControls.toMutableSet().apply {
                if (pinned) add(control) else remove(control)
            }
            current.copy(pinnedControls = canonicalControls(selected))
        }
    }

    private suspend fun update(
        transform: (ScreenViewerToolbarPreferences) -> ScreenViewerToolbarPreferences,
    ) {
        var next = ScreenViewerToolbarPreferences()
        store.edit { persisted ->
            next = transform(decode(persisted))
            persisted[EDGE_KEY] = next.edge.name.lowercase()
            persisted[CONTROLS_KEY] = next.pinnedControls.mapTo(linkedSetOf()) {
                it.name.lowercase()
            }
        }
        _preferences.value = next
    }

    private fun load(): ScreenViewerToolbarPreferences = runCatching {
        runBlocking { decode(store.data.first()) }
    }.getOrDefault(ScreenViewerToolbarPreferences())

    private fun decode(persisted: Preferences): ScreenViewerToolbarPreferences {
        val edge = persisted[EDGE_KEY]
            ?.let { name ->
                ScreenViewerToolbarEdge.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            }
            ?: ScreenViewerToolbarEdge.TOP
        val controls = persisted[CONTROLS_KEY]
            ?.mapNotNull { name ->
                ScreenViewerControl.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            }
            ?.let(::canonicalControls)
            ?: defaultPinnedControls()
        return ScreenViewerToolbarPreferences(edge = edge, pinnedControls = controls)
    }

    private fun canonicalControls(controls: Collection<ScreenViewerControl>): Set<ScreenViewerControl> =
        ScreenViewerControl.entries.filterTo(linkedSetOf(), controls::contains)

    private companion object {
        val EDGE_KEY = stringPreferencesKey("screen_viewer_toolbar_edge_v1")
        val CONTROLS_KEY = stringSetPreferencesKey("screen_viewer_toolbar_controls_v1")
    }
}

private fun defaultPinnedControls(): Set<ScreenViewerControl> = linkedSetOf(
    ScreenViewerControl.BACK,
    ScreenViewerControl.HOME,
    ScreenViewerControl.RECENTS,
)
