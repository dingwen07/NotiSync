package net.extrawdw.apps.notisync.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import net.extrawdw.apps.notisync.ui.icons.material.outlined.sort as SortIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.check as CheckIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.close as CloseIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.search as SearchIcon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R

/**
 * How the app pickers (Apps tab, iPhone tab) group and order their rows. Each label doubles as the
 * title of the mode's leading section, so they reuse the section strings.
 */
internal enum class AppListMode(@param:StringRes val labelRes: Int) {
    /** Enabled apps grouped into a "Mirroring" section; the full app list below. The default. */
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

/** Search shares the title row when the current pane is wide enough, including phone landscape. */
@Composable
internal fun AppListScaffold(
    title: String,
    searchBar: @Composable (Modifier, Boolean) -> Unit,
    content: @Composable (PaddingValues, @Composable () -> Unit) -> Unit,
) {
    val currentSearchBar by rememberUpdatedState(searchBar)
    // Keep the field's cursor/focus and the open menu when resizing moves the controls.
    val controls = remember {
        movableContentOf<Modifier, Boolean> { modifier, compact -> currentSearchBar(modifier, compact) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Use this pane's constraints, not the activity width: Apps can share a window with details.
        val inlineSearch = maxWidth >= 600.dp
        NotiScaffold(
            title = title,
            titleTrailingContent = if (inlineSearch) {
                { controls(Modifier.weight(1f).padding(start = 24.dp, end = 4.dp), true) }
            } else null,
        ) { padding ->
            content(padding) {
                if (!inlineSearch) {
                    controls(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 8.dp),
                        false,
                    )
                }
            }
        }
    }
}

/**
 * The search field shared by the Apps and iPhone tabs, with the sort/group control ([AppSortMenu])
 * pinned at its trailing edge — so it stays reachable no matter which section is scrolled into view,
 * and the floating section bars stay slim.
 */
@Composable
internal fun AppListSearchBar(
    state: TextFieldState,
    placeholder: String,
    mode: AppListMode,
    onModeChange: (AppListMode) -> Unit,
    allEnabled: Boolean,
    canToggleAll: Boolean,
    onToggleAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val textStyle = MaterialTheme.typography.bodyLarge
    // A slimmer inline pill, with enough room for larger accessibility text sizes.
    val inlineHeight = with(LocalDensity.current) { textStyle.lineHeight.toDp() + 8.dp }
        .coerceAtLeast(40.dp)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
    ) {
        OutlinedTextField(
            state = state,
            textStyle = textStyle,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(SearchIcon, contentDescription = null) },
            trailingIcon = {
                if (state.text.isNotEmpty()) {
                    IconButton(onClick = { state.clearText() }) {
                        Icon(
                            CloseIcon,
                            contentDescription = stringResource(R.string.apps_clear_search),
                        )
                    }
                }
            },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = CircleShape,
            contentPadding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                else OutlinedTextFieldDefaults.contentPaddingWithoutLabel(),
            modifier = Modifier.weight(1f, fill = !compact).then(
                if (compact) Modifier.widthIn(max = 480.dp).height(inlineHeight) else Modifier,
            ),
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
                SortIcon,
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
                        if (entry == mode) Icon(CheckIcon, contentDescription = null)
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
