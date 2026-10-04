package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import net.extrawdw.apps.notisync.ui.icons.material.outlined.menu as MenuIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.crypto.KeyBacking
import net.extrawdw.apps.notisync.ui.theme.SecurityAmberDark
import net.extrawdw.apps.notisync.ui.theme.SecurityAmberLight
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenDark
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenLight
import net.extrawdw.apps.notisync.ui.theme.SecurityRedDark
import net.extrawdw.apps.notisync.ui.theme.SecurityRedLight

data class PermissionState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
)

@Composable
internal fun PermissionCard(
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember { (context.applicationContext as NotiSyncApp).graph }
}

internal val LocalFeatureDrawerOpener = compositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun FeatureDrawerNavigationIcon() {
    LocalFeatureDrawerOpener.current?.let { open ->
        IconButton(onClick = open) {
            Icon(MenuIcon, contentDescription = stringResource(R.string.open_features))
        }
    }
}

/** Shared scaffold with a standard Material 3 top app bar (pinned, does not collapse on scroll). */
@Composable
internal fun NotiScaffold(
    title: String,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { FeatureDrawerNavigationIcon() },
            )
        },
    ) { padding ->
        TabContent { content(padding) }
    }
}

/** Shared content limit for tabs and their detail panes; app bars and backgrounds fill the pane. */
@Composable
internal fun TabContent(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1280.dp).fillMaxSize()) { content() }
    }
}

/** Add spacing inside a scrolling viewport while preserving every scaffold inset. */
@Composable
internal fun PaddingValues.withContentSpacing(
    horizontal: Dp = 0.dp,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + horizontal,
        top = calculateTopPadding() + top,
        end = calculateEndPadding(direction) + horizontal,
        bottom = calculateBottomPadding() + bottom,
    )
}

/** Fixed headers stay below the app bar; their lists keep the bottom inset in content padding. */
@Composable
internal fun PaddingValues.topAndSides(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

@Composable
internal fun keyBackingLabel(backing: KeyBacking): String =
    stringResource(
        when (backing) {
            KeyBacking.UNKNOWN -> R.string.key_backing_unknown
            KeyBacking.UNKNOWN_SECURE -> R.string.key_backing_unknown_secure
            KeyBacking.SOFTWARE -> R.string.key_backing_software
            KeyBacking.TEE -> R.string.key_backing_tee
            KeyBacking.STRONGBOX -> R.string.key_backing_strongbox
        },
    )

/** Color coding the key backing by security strength: software/unknown red, TEE amber, StrongBox green. */
@Composable
internal fun keyBackingColor(backing: KeyBacking): Color {
    val dark = isSystemInDarkTheme()
    return when (backing) {
        KeyBacking.UNKNOWN, KeyBacking.SOFTWARE -> if (dark) SecurityRedDark else SecurityRedLight
        KeyBacking.UNKNOWN_SECURE, KeyBacking.TEE -> if (dark) SecurityAmberDark else SecurityAmberLight
        KeyBacking.STRONGBOX -> if (dark) SecurityGreenDark else SecurityGreenLight
    }
}
