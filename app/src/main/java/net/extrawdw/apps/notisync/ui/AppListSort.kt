package net.extrawdw.apps.notisync.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R

/**
 * How the app pickers (Apps tab, iPhone tab) group and order their rows. Each label doubles as the
 * title of the mode's leading section, so they reuse the section strings.
 */
internal enum class AppListMode(@param:StringRes val labelRes: Int) {
    /** Enabled apps grouped into a "Mirroring" section; everything else below. The default. */
    MIRRORING(R.string.apps_section_mirroring),

    /** The [RECENT_APP_COUNT] most recently active apps in a "Recents" section; everything else below. */
    RECENT(R.string.apps_section_recent),

    /** A single section listing every app strictly by name. */
    NAME(R.string.apps_section_all),
}

/** How many apps [AppListMode.RECENT] surfaces in its "Recents" section. */
internal const val RECENT_APP_COUNT = 8

/** One pinned group of app rows: a stable [key] prefix, a resolved [title], and its [items]. */
internal class AppSection<T>(val key: String, val title: String, val items: List<T>)

/**
 * The search field shared by the Apps and iPhone tabs, with the sort/group control ([AppSortMenu])
 * pinned at its trailing edge — so it stays reachable no matter which section is scrolled into view,
 * and the floating section bars stay slim.
 */
@Composable
internal fun AppListSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    mode: AppListMode,
    onModeChange: (AppListMode) -> Unit,
    allEnabled: Boolean,
    canToggleAll: Boolean,
    onToggleAll: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.apps_clear_search),
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.weight(1f),
        )
        AppSortMenu(mode, onModeChange, allEnabled, canToggleAll, onToggleAll)
    }
}

/**
 * A pinned section bar (title + count).
 *
 * The no-op clickable is load-bearing: a sticky header floats above the list, and with no hit target
 * of its own a tap on it falls through to the row drawn underneath. Consuming clicks here keeps a tap
 * on the bar from toggling whatever row it happens to cover.
 */
@Composable
internal fun SectionHeader(title: String, count: Int) {
    Text(
        stringResource(R.string.section_header, title, count),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // Opaque background matching the screen so list rows scroll *under* the pinned header.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

/**
 * The sort/group control: switches [mode] and offers a single bulk action that turns mirroring on
 * (or off) for every app currently listed.
 *
 * @param allEnabled whether every toggleable app currently shown is already mirrored — sets the bulk
 *   item's direction and label.
 * @param canToggleAll whether there is at least one toggleable app to act on.
 */
@Composable
internal fun AppSortMenu(
    mode: AppListMode,
    onModeChange: (AppListMode) -> Unit,
    allEnabled: Boolean,
    canToggleAll: Boolean,
    onToggleAll: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Outlined.Sort,
                contentDescription = stringResource(R.string.apps_sort_menu),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppListMode.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.labelRes)) },
                    onClick = {
                        onModeChange(entry)
                        expanded = false
                    },
                    leadingIcon = {
                        // A check marks the active mode; a same-size spacer keeps the rest aligned.
                        if (entry == mode) Icon(Icons.Outlined.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (allEnabled) R.string.apps_disable_all else R.string.apps_enable_all
                        )
                    )
                },
                enabled = canToggleAll,
                onClick = {
                    onToggleAll(!allEnabled)
                    expanded = false
                },
            )
        }
    }
}
