package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Approval summaries stay beside independently scrollable details when the page has room. */
@Composable
internal fun RequestDetailLayout(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    useWideLayout: Boolean,
    header: @Composable (() -> Unit)? = null,
    summary: @Composable ColumnScope.() -> Unit,
    requestInfo: (@Composable ColumnScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit,
) {
    // Keep expanded process paths and open dialogs alive while moving between columns and a list.
    val currentSummary by rememberUpdatedState(summary)
    val currentDetails by rememberUpdatedState(details)
    val currentRequestInfo by rememberUpdatedState(requestInfo)
    val summaryContent = remember {
        movableContentOf {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                currentSummary()
            }
        }
    }
    val detailContent = remember {
        movableContentOf {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                currentDetails()
            }
        }
    }
    val requestInfoContent = remember {
        movableContentOf {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                currentRequestInfo?.invoke(this)
            }
        }
    }
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val start = contentPadding.calculateStartPadding(direction)
        val end = contentPadding.calculateEndPadding(direction)
        if (useWideLayout && maxWidth - start - end >= 800.dp) {
            val columnPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            )
            Row(
                Modifier.fillMaxSize().padding(start = start, end = end),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = columnPadding,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { summaryContent() }
                    if (requestInfo != null) item { requestInfoContent() }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = columnPadding) {
                    item { detailContent() }
                }
            }
        } else {
            HistorySheetLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                header = header,
            ) {
                item { summaryContent() }
                item { detailContent() }
                if (requestInfo != null) item { requestInfoContent() }
            }
        }
    }
}
