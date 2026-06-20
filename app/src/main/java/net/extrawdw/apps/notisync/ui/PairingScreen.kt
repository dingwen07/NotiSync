package net.extrawdw.apps.notisync.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingManager
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
                    onFailure = { result = "Could not pair: ${it.message}" },
                )
            inspecting = false
        }
    }

    fun trust(candidate: PairingCandidate) {
        pendingCandidate = null
        result = null
        inspecting = true
        scope.launch {
            result = withContext(Dispatchers.Default) {
                pairing.accept(candidate.payload).fold(
                    onSuccess = { "Paired with ${it.displayName}" },
                    onFailure = { "Could not pair: ${it.message}" },
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
                onFailure = { result = "Could not open pairing link: ${it.message}" },
            )
        inspecting = false
        onInitialPairingPayloadConsumed()
    }

    val codeState by produceState<PairingCodeState>(PairingCodeState.Loading, pairing) {
        value = withContext(Dispatchers.Default) {
            try {
                PairingCodeState.Ready(QrCodes.encode(pairing.myLink()))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PairingCodeState.Error(t.message ?: "Unknown error")
            }
        }
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
                title = { Text("Pair a device") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                "Show this code to your other device. Its camera can open NotiSync directly, or you can scan from here.",
                style = MaterialTheme.typography.bodyLarge,
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
                        contentDescription = "Pairing QR code",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                }
                is PairingCodeState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Could not prepare pairing code: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text("Safety number: ${graph.identity.clientId.value}", style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = {
                    result = null
                    scanning = true
                    GmsBarcodeScanning.getClient(context, scannerOptions).startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue
                            if (raw == null) {
                                result = "No code detected"
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
                            result = "Scan failed: ${it.message}"
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
                        scanning -> "Scanning..."
                        inspecting -> "Checking device..."
                        else -> "Scan the other device's code"
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
            onTrust = { trust(candidate) },
            onDismiss = { pendingCandidate = null },
        )
    }
}

private sealed interface PairingCodeState {
    data object Loading : PairingCodeState
    data class Ready(val bitmap: Bitmap) : PairingCodeState
    data class Error(val message: String) : PairingCodeState
}

@Composable
private fun TrustDeviceDialog(
    candidate: PairingCandidate,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust this device?") },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "NotiSync verified this signed pairing card. Trust it to exchange notifications with this device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DeviceInfo("Name", candidate.displayName)
                    DeviceInfo("Platform", candidate.platform)
                    DeviceInfo("Safety number", candidate.safetyNumber)
                    DeviceInfo("Identity key", candidate.identityKeyFingerprint)
                    DeviceInfo("Sync key", candidate.hpkeKeyFingerprint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text("Trust")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
