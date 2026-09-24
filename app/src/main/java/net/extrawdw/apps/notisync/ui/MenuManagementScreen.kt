package net.extrawdw.apps.notisync.ui

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.navigation.AppMenuShortcuts
import net.extrawdw.apps.notisync.navigation.MenuConfiguration
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.navigation.icon
import net.extrawdw.apps.notisync.navigation.label
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import net.extrawdw.apps.notisync.ui.icons.material.outlined.arrow_back as ArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.drag_handle as DragHandleIcon

@Composable
internal fun MenuManagementScreen(navigationLimit: Int, onBack: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val saved by graph.settings.menuConfiguration.collectAsStateWithLifecycle()
    var configuration by remember { mutableStateOf(saved) }
    val list = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(list) { from, to ->
        // Use stable keys rather than lazy-list indexes: the description is a non-draggable header.
        val order = configuration.order.toMutableList()
        val fromIndex = order.indexOf(from.key)
        val toIndex = order.indexOf(to.key)
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            order.add(toIndex, order.removeAt(fromIndex))
            configuration = configuration.copy(order = order)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    val shortcutLimit = remember { AppMenuShortcuts.limit(context) }
    LaunchedEffect(saved) { if (!reorderState.isAnyItemDragging) configuration = saved }

    fun save(value: MenuConfiguration) {
        configuration = value.normalized()
        val snapshot = configuration
        graph.scope.launch(Dispatchers.Main.immediate) {
            runCatching { graph.settings.setMenuConfiguration(snapshot) }.onFailure {
                configuration = graph.settings.menuConfiguration.value
                Toast.makeText(context, R.string.menu_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun move(id: String, delta: Int) {
        val order = configuration.order.toMutableList()
        val index = order.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in order.indices) return
        order.removeAt(index)
        order.add(target, id)
        save(configuration.copy(order = order))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_manage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ArrowBackIcon, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = !reorderState.isAnyItemDragging,
                        onClick = { save(MenuConfiguration()) },
                    ) {
                        Text(stringResource(R.string.menu_reset))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "menu-description") {
                    Text(stringResource(R.string.menu_description), Modifier.padding(bottom = 8.dp))
                }
                items(configuration.destinations(), key = { it.id }) { destination ->
                    val label = stringResource(destination.label)
                    val moveUp = stringResource(R.string.menu_move_up)
                    val moveDown = stringResource(R.string.menu_move_down)
                    val selectedNavigation = destination.id in configuration.navigation
                    val selectedShortcut = destination.id in configuration.shortcuts
                    val navigationLabel = stringResource(R.string.menu_navigation) + ": " + label
                    val shortcutLabel = stringResource(R.string.menu_shortcut) + ": " + label
                    val index = configuration.order.indexOf(destination.id)
                    ReorderableItem(reorderState, key = destination.id) { isDragging ->
                        val elevation by animateDpAsState(
                            targetValue = if (isDragging) 6.dp else 0.dp,
                            label = "Menu item elevation",
                        )
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                            modifier = Modifier.fillMaxWidth()
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(moveUp) { move(destination.id, -1); true })
                                        if (index < configuration.order.lastIndex) add(CustomAccessibilityAction(moveDown) { move(destination.id, 1); true })
                                    }
                                },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(destination.icon, null, Modifier.size(24.dp))
                                    Text(label, Modifier.weight(1f).padding(horizontal = 12.dp),
                                        style = MaterialTheme.typography.titleMedium)
                                    Icon(DragHandleIcon, stringResource(R.string.menu_reorder, label),
                                        Modifier.size(48.dp).draggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                // Keep persistence and launcher updates out of drag-frame callbacks.
                                                save(configuration)
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            },
                                        ).padding(12.dp))
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ToggleButton(
                                        checked = selectedNavigation,
                                        modifier = Modifier.semantics { contentDescription = navigationLabel },
                                        enabled = !reorderState.isAnyItemDragging &&
                                            (selectedNavigation || configuration.navigation.size < navigationLimit) &&
                                            (!selectedNavigation || (destination != MenuDestination.DEVICES &&
                                                configuration.navigation.size > 1)),
                                        onCheckedChange = { checked ->
                                            save(configuration.copy(navigation = if (checked)
                                                configuration.navigation + destination.id else configuration.navigation - destination.id))
                                        },
                                    ) {
                                        Text(stringResource(R.string.menu_navigation))
                                    }
                                    ToggleButton(
                                        checked = selectedShortcut,
                                        modifier = Modifier.semantics { contentDescription = shortcutLabel },
                                        enabled = !reorderState.isAnyItemDragging &&
                                            (selectedShortcut || configuration.shortcuts.size < shortcutLimit),
                                        onCheckedChange = { checked ->
                                            save(configuration.copy(shortcuts = if (checked)
                                                configuration.shortcuts + destination.id else configuration.shortcuts - destination.id))
                                        },
                                    ) {
                                        Text(stringResource(R.string.menu_shortcut))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
