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

/** Default toolbar priority. Pinned controls that do not fit remain in overflow. */
internal enum class ScreenViewerControl {
    BACK,
    HOME,
    RECENTS,
    KEYBOARD,
    POWER,
    NOTIFICATION_PANEL,
    LAUNCHER,
}

internal fun screenViewerAvailableControls(virtualDisplay: Boolean = false): List<ScreenViewerControl> =
    if (virtualDisplay) listOf(ScreenViewerControl.BACK, ScreenViewerControl.HOME, ScreenViewerControl.LAUNCHER,
        ScreenViewerControl.RECENTS, ScreenViewerControl.KEYBOARD)
    else ScreenViewerControl.entries.filter { it != ScreenViewerControl.LAUNCHER }

internal data class ScreenViewerToolbarPreferences(
    val edge: ScreenViewerToolbarEdge = ScreenViewerToolbarEdge.TOP,
    val pinnedControls: Set<ScreenViewerControl> = defaultPinnedControls(),
    val controlOrder: List<ScreenViewerControl> = screenViewerAvailableControls(),
)

/** Durable viewer chrome preferences shared by every Android-to-Android screen session. */
internal class ScreenViewerToolbarPreferenceStore(
    private val store: DataStore<Preferences>,
    private val virtualDisplay: Boolean = false,
) {
    private val availableControls = screenViewerAvailableControls(virtualDisplay)
    private val prefix = if (virtualDisplay) "screen_virtual_viewer_toolbar" else "screen_viewer_toolbar"
    private val edgeKey = stringPreferencesKey("${prefix}_edge_v1")
    private val controlsKey = stringSetPreferencesKey("${prefix}_controls_v1")
    private val orderKey = stringPreferencesKey("${prefix}_order_v1")
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

    suspend fun setControlOrder(controls: List<ScreenViewerControl>) = mutex.withLock {
        update { current -> current.copy(controlOrder = canonicalOrder(controls)) }
    }

    private suspend fun update(
        transform: (ScreenViewerToolbarPreferences) -> ScreenViewerToolbarPreferences,
    ) {
        var next = defaults()
        store.edit { persisted ->
            next = transform(decode(persisted))
            persisted[edgeKey] = next.edge.name.lowercase()
            persisted[controlsKey] = next.pinnedControls.mapTo(linkedSetOf()) {
                it.name.lowercase()
            }
            persisted[orderKey] = next.controlOrder.joinToString(",") { it.name.lowercase() }
        }
        _preferences.value = next
    }

    private fun load(): ScreenViewerToolbarPreferences = runCatching {
        runBlocking { decode(store.data.first()) }
    }.getOrDefault(defaults())

    private fun defaults() = ScreenViewerToolbarPreferences(
        pinnedControls = if (virtualDisplay) linkedSetOf(ScreenViewerControl.BACK, ScreenViewerControl.HOME,
            ScreenViewerControl.LAUNCHER, ScreenViewerControl.RECENTS) else defaultPinnedControls(),
        controlOrder = availableControls,
    )

    private fun decode(persisted: Preferences): ScreenViewerToolbarPreferences {
        val edge = persisted[edgeKey]
            ?.let { name ->
                ScreenViewerToolbarEdge.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            }
            ?: ScreenViewerToolbarEdge.TOP
        val controls = persisted[controlsKey]
            ?.mapNotNull { name ->
                ScreenViewerControl.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            }
            ?.let(::canonicalControls)
            ?: defaults().pinnedControls
        val controlOrder = persisted[orderKey]
            ?.split(',')
            ?.mapNotNull { name ->
                ScreenViewerControl.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                }
            }
            ?.let(::canonicalOrder)
            ?: availableControls
        return ScreenViewerToolbarPreferences(
            edge = edge,
            pinnedControls = controls,
            controlOrder = controlOrder,
        )
    }

    private fun canonicalControls(controls: Collection<ScreenViewerControl>): Set<ScreenViewerControl> =
        availableControls.filterTo(linkedSetOf(), controls::contains)

    private fun canonicalOrder(controls: Collection<ScreenViewerControl>): List<ScreenViewerControl> =
        buildList {
            controls.forEach { control -> if (control in availableControls && control !in this) add(control) }
            availableControls.forEach { control -> if (control !in this) add(control) }
        }
}

private fun defaultPinnedControls(): Set<ScreenViewerControl> = linkedSetOf(
    ScreenViewerControl.BACK,
    ScreenViewerControl.HOME,
    ScreenViewerControl.RECENTS,
)
