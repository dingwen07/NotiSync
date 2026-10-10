package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.hotspot.controller.SavedHotspot
import net.extrawdw.apps.notisync.hotspot.controller.saveNetworkIntent
import net.extrawdw.apps.notisync.pairing.QrCodes
import net.extrawdw.apps.notisync.ui.icons.material.outlined.qr_code_2 as QrCodeIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.wifi as WifiIcon

@Composable
internal fun HotspotSavedNetworkActions(details: SavedHotspot) {
    val context = LocalContext.current
    var showQr by remember(details) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val results = result.data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
            val message = if (results?.singleOrNull() in setOf(Settings.ADD_WIFI_RESULT_SUCCESS, Settings.ADD_WIFI_RESULT_ALREADY_EXISTS))
                R.string.hotspot_network_saved else R.string.hotspot_network_save_failed
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = {
            runCatching { launcher.launch(details.saveNetworkIntent(context)) }
                .onFailure { Toast.makeText(context, R.string.hotspot_network_save_failed, Toast.LENGTH_SHORT).show() }
        }, enabled = details.usable, modifier = Modifier.weight(1f)) {
            Icon(WifiIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.hotspot_connect))
        }
        OutlinedButton(onClick = { showQr = true }, enabled = details.usable, modifier = Modifier.weight(1f)) {
            Icon(QrCodeIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.hotspot_qr_action))
        }
    }
    if (showQr) {
        val bitmap = remember(details) {
            details.qrPayload()?.let { QrCodes.encode(it, marginModules = 4, characterSet = "UTF-8") }
        }
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(details.ssid) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    bitmap?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.hotspot_show_qr),
                            Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    Text(stringResource(R.string.hotspot_qr_hint))
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.hotspot_qr_close)) } },
        )
    }
}
