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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.apps.notisync.pairing.QrCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val pairing = remember { PairingManager(graph) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val codeState by produceState<PairingCodeState>(PairingCodeState.Loading, pairing) {
        value = withContext(Dispatchers.Default) {
            try {
                val payload = pairing.myPayload()
                PairingCodeState.Ready(QrCodes.encode(payload))
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
                "Show this code to your other device, then scan its code. Both devices add each other as trusted peers.",
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
                                scope.launch {
                                    result = withContext(Dispatchers.Default) {
                                        pairing.accept(raw).fold(
                                            onSuccess = { "Paired with ${it.displayName}" },
                                            onFailure = { "Could not pair: ${it.message}" },
                                        )
                                    }
                                    scanning = false
                                }
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
                enabled = !scanning,
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(if (scanning) "Scanning..." else "Scan the other device's code")
            }

            result?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

private sealed interface PairingCodeState {
    data object Loading : PairingCodeState
    data class Ready(val bitmap: Bitmap) : PairingCodeState
    data class Error(val message: String) : PairingCodeState
}
