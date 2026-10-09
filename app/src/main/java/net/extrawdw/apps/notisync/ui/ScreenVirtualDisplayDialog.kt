package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.screen.ScreenVirtualDisplaySizing
import net.extrawdw.notisync.protocol.ScreenVirtualDisplay

@Composable
internal fun ScreenVirtualDisplayDialog(
    onDismiss: () -> Unit,
    onStart: (ScreenVirtualDisplay, Boolean) -> Unit,
    notificationKey: String? = null,
) {
    val context = LocalContext.current
    val defaults = remember(context) { ScreenVirtualDisplaySizing.forWindow(context) }
    var density by rememberSaveable { mutableStateOf("") }
    val display = ScreenVirtualDisplay(
        defaults.width, defaults.height, if (density.isBlank()) defaults.densityDpi else density.toIntOrNull() ?: 0,
        if (notificationKey != null) ScreenVirtualDisplay.NOTIFICATION else ScreenVirtualDisplay.HOME,
        notificationKey = notificationKey,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_virtual_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.screen_virtual_launch_body))
                OutlinedTextField(density, { density = it.take(3) }, label = { Text(stringResource(R.string.screen_virtual_density)) },
                    placeholder = { Text(stringResource(R.string.screen_virtual_density_auto, defaults.densityDpi)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(display, density.isNotBlank()) }, enabled = display.isValid()) {
            Text(stringResource(R.string.screen_mirror_start))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
