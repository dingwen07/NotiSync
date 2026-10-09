package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.screen.AndroidScreenMirrorActivity
import net.extrawdw.apps.notisync.screen.ScreenVirtualDisplayIntents
import net.extrawdw.apps.notisync.ui.icons.material.outlined.tv_displays as TvDisplaysIcon
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenVirtualDisplay

@Composable
internal fun ScreenVirtualDisplayButton(
    sourceId: ClientId,
    enabled: Boolean,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    onLaunch: () -> Unit = {},
) {
    val context = LocalContext.current
    var configure by remember(sourceId) { mutableStateOf(false) }
    if (configure && enabled) ScreenVirtualDisplayDialog(
        onDismiss = { configure = false },
        onStart = { display, customDensity ->
            configure = false
            onLaunch()
            context.startActivity(ScreenVirtualDisplayIntents.fitToViewer(
                AndroidScreenMirrorActivity.intent(context, sourceId, display), customDensity,
            ))
        },
    )
    Surface(
        shape = IconButtonDefaults.filledShape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = {
                        onLaunch()
                        context.startActivity(ScreenVirtualDisplayIntents.fitToViewer(
                            AndroidScreenMirrorActivity.intent(context, sourceId, ScreenVirtualDisplay()),
                        ))
                    },
                    onLongClickLabel = stringResource(R.string.screen_virtual_configure),
                    onLongClick = { configure = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TvDisplaysIcon, contentDescription = stringResource(R.string.screen_virtual_title))
        }
    }
}
