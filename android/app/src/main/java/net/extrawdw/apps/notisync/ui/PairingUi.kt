package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val pairing = remember { PairingManager(graph) }
    var result by remember { mutableStateOf<String?>(null) }

    // Google code scanner: scanning happens in a Google Play services UI, so the app needs no
    // CAMERA permission and no camera plumbing of its own.
    val scanner = remember {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
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

            val payload = remember { pairing.myPayload() }
            val bitmap = remember(payload) { QrCodes.encode(payload) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Pairing QR code",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text("Safety number: ${graph.identity.clientId.value}", style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = {
                    result = null
                    scanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue
                            result = if (raw == null) {
                                "No code detected"
                            } else {
                                pairing.accept(raw).fold(
                                    onSuccess = { "Paired with ${it.displayName}" },
                                    onFailure = { "Could not pair: ${it.message}" },
                                )
                            }
                        }
                        .addOnCanceledListener { result = null }
                        .addOnFailureListener { result = "Scan failed: ${it.message}" }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Text("  Scan the other device's code")
            }

            result?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
