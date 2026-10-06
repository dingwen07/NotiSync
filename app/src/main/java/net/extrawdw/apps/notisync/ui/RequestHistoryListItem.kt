package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.ui.icons.material.outlined.chevron_right as ChevronRightIcon

@Composable
internal fun RequestHistoryListItem(
    modifier: Modifier = Modifier,
    leadingContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val defaultPadding = ListItemDefaults.ContentPadding
    val layoutDirection = LocalLayoutDirection.current
    ListItem(
        // Let wrapped content grow without the default multiline minimum adding blank space.
        modifier = modifier.heightIn(min = 64.dp),
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = { Icon(ChevronRightIcon, contentDescription = null) },
        contentPadding = PaddingValues(
            start = defaultPadding.calculateStartPadding(layoutDirection),
            top = defaultPadding.calculateTopPadding(),
            end = defaultPadding.calculateEndPadding(layoutDirection),
            bottom = defaultPadding.calculateBottomPadding() + 4.dp,
        ),
        content = content,
    )
}
