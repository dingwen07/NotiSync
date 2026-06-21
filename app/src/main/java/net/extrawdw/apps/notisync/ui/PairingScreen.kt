package net.extrawdw.apps.notisync.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.apps.notisync.pairing.PairingNfcController
import net.extrawdw.apps.notisync.pairing.QrCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun PairingScreen(
    onBack: () -> Unit,
    initialPairingPayload: String? = null,
    onInitialPairingPayloadConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val pairing = remember { PairingManager(graph) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var pendingCandidate by remember { mutableStateOf<PairingCandidate?>(null) }

    fun inspect(content: String) {
        result = null
        inspecting = true
        scope.launch {
            withContext(Dispatchers.Default) { pairing.inspect(content) }
                .fold(
                    onSuccess = { pendingCandidate = it },
                    onFailure = { result = context.getString(R.string.pair_could_not_pair, it.message) },
                )
            inspecting = false
        }
    }

    fun trust(candidate: PairingCandidate, ownDevice: Boolean) {
        pendingCandidate = null
        result = null
        inspecting = true
        scope.launch {
            result = withContext(Dispatchers.Default) {
                pairing.accept(candidate.payload, ownDevice).fold(
                    onSuccess = { context.getString(R.string.pair_paired_with, it.displayName) },
                    onFailure = { context.getString(R.string.pair_could_not_pair, it.message) },
                )
            }
            inspecting = false
        }
    }

    LaunchedEffect(initialPairingPayload) {
        val payload = initialPairingPayload ?: return@LaunchedEffect
        result = null
        inspecting = true
        withContext(Dispatchers.Default) { pairing.inspect(payload) }
            .fold(
                onSuccess = { pendingCandidate = it },
                onFailure = { result = context.getString(R.string.pair_could_not_open_link, it.message) },
            )
        inspecting = false
        onInitialPairingPayloadConsumed()
    }

    val codeState by produceState<PairingCodeState>(PairingCodeState.Loading, pairing) {
        value = withContext(Dispatchers.Default) {
            try {
                val pairingUrl = pairing.myLink()
                PairingCodeState.Ready(pairingUrl, QrCodes.encode(pairingUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PairingCodeState.Error(t.message ?: context.getString(R.string.error_unknown))
            }
        }
    }
    val pairingUrl = (codeState as? PairingCodeState.Ready)?.url

    fun sharePairingUrl() {
        val url = pairingUrl ?: return
        val title = context.getString(R.string.pair_share_title)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    }

    LifecycleResumeEffect(pairingUrl) {
        if (pairingUrl != null) PairingNfcController.enable(context, pairingUrl)
        onPauseOrDispose { PairingNfcController.disable(context) }
    }

    val scannerOptions = remember {
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pair_a_device)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = ::sharePairingUrl, enabled = pairingUrl != null) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.pair_share_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.pair_intro_nfc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val state = codeState) {
                PairingCodeState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is PairingCodeState.Ready -> {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.pair_qr_code_desc),
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
                is PairingCodeState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.pair_could_not_prepare, state.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(stringResource(R.string.pair_verification_number, graph.identity.clientId.value), style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = {
                    result = null
                    scanning = true
                    GmsBarcodeScanning.getClient(context, scannerOptions).startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue
                            if (raw == null) {
                                result = context.getString(R.string.pair_no_code)
                                scanning = false
                            } else {
                                scanning = false
                                inspect(raw)
                            }
                        }
                        .addOnCanceledListener {
                            result = null
                            scanning = false
                        }
                        .addOnFailureListener {
                            result = context.getString(R.string.pair_scan_failed, it.message)
                            scanning = false
                        }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning && !inspecting,
            ) {
                if (scanning || inspecting) {
                    CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    when {
                        scanning -> stringResource(R.string.pair_scanning)
                        inspecting -> stringResource(R.string.pair_checking)
                        else -> stringResource(R.string.pair_scan_button)
                    }
                )
            }

            result?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    pendingCandidate?.let { candidate ->
        TrustDeviceDialog(
            candidate = candidate,
            onTrustOwn = { trust(candidate, ownDevice = true) },
            onTrustOther = { trust(candidate, ownDevice = false) },
            onDismiss = { pendingCandidate = null },
        )
    }
}

private sealed interface PairingCodeState {
    data object Loading : PairingCodeState
    data class Ready(val url: String, val bitmap: Bitmap) : PairingCodeState
    data class Error(val message: String) : PairingCodeState
}

@Composable
private fun TrustDeviceDialog(
    candidate: PairingCandidate,
    onTrustOwn: () -> Unit,
    onTrustOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_trust_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.pair_trust_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DeviceInfo(stringResource(R.string.pair_field_name), candidate.displayName)
                    DeviceInfo(stringResource(R.string.pair_field_platform), candidate.platform)
                    DeviceInfo(stringResource(R.string.pair_field_verification_number), candidate.safetyNumber)
                    DeviceInfo(stringResource(R.string.pair_field_identity_key), candidate.identityKeyFingerprint)
                    DeviceInfo(stringResource(R.string.pair_field_sync_key), candidate.hpkeKeyFingerprint)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onTrustOther) { Text(stringResource(R.string.pair_trust_other)) }
                TextButton(onClick = onTrustOwn) { Text(stringResource(R.string.pair_trust_own)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DeviceInfo(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
