package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.crypto.KeyBacking

data class PermissionState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
)

@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember { (context.applicationContext as NotiSyncApp).graph }
}

/** Shared scaffold with a standard Material 3 top app bar (pinned, does not collapse on scroll). */
@Composable
internal fun NotiScaffold(title: String, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(title) }) },
    ) { padding ->
        content(Modifier.padding(padding))
    }
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
