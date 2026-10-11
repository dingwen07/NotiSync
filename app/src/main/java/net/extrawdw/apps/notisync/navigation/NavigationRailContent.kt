package net.extrawdw.apps.notisync.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keeps the menu button outside the destinations' centered, scrollable viewport. */
@Composable
internal fun NavigationRailContent(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.Center) { header() }
        Box(
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Material items supply their own leading inset, so keep their icons anchored.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) { content() }
        }
    }
}
