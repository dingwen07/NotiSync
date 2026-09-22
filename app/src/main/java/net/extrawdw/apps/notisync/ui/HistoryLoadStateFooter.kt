package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import net.extrawdw.apps.notisync.R

/** Scrolling requests more rows automatically; a button appears only to retry a failure. */
@Composable
internal fun HistoryLoadStateFooter(
    loadState: CombinedLoadStates,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    prepend: Boolean = false,
) {
    val loading = if (prepend) loadState.prepend is LoadState.Loading else {
        loadState.refresh is LoadState.Loading || loadState.append is LoadState.Loading
    }
    val failed = if (prepend) loadState.prepend is LoadState.Error else {
        loadState.refresh is LoadState.Error || loadState.append is LoadState.Error
    }
    if (!loading && !failed) return
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.history_loading), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Text(stringResource(R.string.history_load_failed), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
        }
    }
}
