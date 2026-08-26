package net.extrawdw.apps.notisync

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

internal fun shouldShowCustomStartupScreen(startupState: AppStartupState): Boolean =
    startupState.databaseImportRequired ||
        startupState.stage == AppStartupStage.IMPORTING_DATABASE ||
        startupState.stage == AppStartupStage.FAILED

internal fun shouldKeepSystemSplash(startupState: AppStartupState): Boolean =
    startupState.stage != AppStartupStage.READY && !shouldShowCustomStartupScreen(startupState)

@Composable
internal fun StartupScreen(
    stage: AppStartupStage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Give the logo a stable top-half region so the progress area can appear without moving it.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val logoSize = minOf(maxWidth * 0.42f, maxHeight * 0.72f, 220.dp)
            Image(
                painter = painterResource(R.drawable.ic_notisync_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(percent = 22)),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                stage == AppStartupStage.FAILED -> StartupStatusText(stage.statusTextResource())
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    StartupStatusText(stage.statusTextResource())
                }
            }
        }
    }
}

@Composable
private fun StartupStatusText(@StringRes statusResource: Int) {
    Text(
        text = stringResource(statusResource),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(0.8f),
    )
}

@StringRes
private fun AppStartupStage.statusTextResource(): Int = when (this) {
    AppStartupStage.CHECKING_STORAGE -> R.string.startup_checking_storage
    AppStartupStage.IMPORTING_DATABASE -> R.string.startup_importing_database
    AppStartupStage.INITIALIZING_APPLICATION,
    AppStartupStage.READY,
    -> R.string.startup_initializing_application
    AppStartupStage.FAILED -> R.string.startup_failed
}

@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
@Composable
private fun StartupScreenPreview() {
    NotiSyncTheme {
        StartupScreen(
            stage = AppStartupStage.IMPORTING_DATABASE,
        )
    }
}
