package net.extrawdw.apps.notisync.ui

import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.nfc.TagLostException
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import net.extrawdw.apps.notisync.ui.icons.material.filled.arrow_back as FilledArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.qr_code_scanner as QrCodeScannerIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.share as ShareIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.qr_code_2 as QrCodeIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.pairing.KeyEpochStatus
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import net.extrawdw.apps.notisync.pairing.PairingNfcReaderSession
import net.extrawdw.apps.notisync.pairing.QrCodes
import net.extrawdw.apps.notisync.pairing.formatPairingSystemTime
import net.extrawdw.apps.notisync.security.TapjackingProtectionEffect
import net.extrawdw.notisync.peer.trust.RosterDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun PairingScreen(
    onBack: () -> Unit,
    onPairingCandidate: (PairingCandidate) -> Unit,
    onBrokerPairing: (BrokerPairingLink) -> Unit,
    onBrokerPairingCandidate: (PairingCandidate) -> Unit,
) {
    val context = LocalContext.current
    // Resolve strings via LocalResources so they re-read on configuration changes (locale, etc.);
    // context is retained for non-resource use (scanner client, NFC controller, startActivity).
    val resources = LocalResources.current
    val graph = rememberGraph()
    val pairing = remember { PairingManager(graph) }
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf(false) }
    var codeGeneration by remember { mutableIntStateOf(0) }
    var hasResumed by remember { mutableStateOf(false) }

    var showLegacyQr by remember { mutableStateOf(false) }
    var hostScreenActive by remember { mutableStateOf(true) }
    var hostLink by remember { mutableStateOf<BrokerPairingLink?>(null) }
    var hostUnavailable by remember { mutableStateOf(false) }
    val brokerUrl by graph.settings.brokerUrl.collectAsStateWithLifecycle()
    val acceptResult = remember { AtomicBoolean(true) }
    val deliverBrokerCandidate by rememberUpdatedState(onBrokerPairingCandidate)
    DisposableEffect(Unit) { onDispose { acceptResult.set(false) } }

    fun finishPairing(deliver: () -> Unit) {
        if (acceptResult.compareAndSet(true, false)) {
            hostScreenActive = false
            deliver()
        }
    }

    LaunchedEffect(pairing, brokerUrl, scanning, inspecting, hostScreenActive) {
        hostLink = null
        hostUnavailable = false
        if (!hostScreenActive || scanning || inspecting) return@LaunchedEffect
        val candidate = pairing.hostExchange(
            brokerUrl = brokerUrl,
            onLink = { link ->
                hostLink = link
                if (link != null) hostUnavailable = false
            },
            onUnavailable = { hostUnavailable = true },
        )
        finishPairing { deliverBrokerCandidate(candidate) }
    }
    val brokerBitmap by produceState<Bitmap?>(null, hostLink) {
        value = null
        val link = hostLink
        value = if (link == null) null else withContext(Dispatchers.Default) {
            QrCodes.encode(link.encode(), marginModules = 4)
        }
    }

    fun showScanFailure(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun openDateAndTimeSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        }.recoverCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // Recreate the signed card after every return to this screen. This covers our Date & Time action as well
    // as a manual Settings visit, and ensures the warning and QR carry the same corrected wall-clock value.
    LifecycleResumeEffect(Unit) {
        if (hasResumed) codeGeneration += 1 else hasResumed = true
        onPauseOrDispose { }
    }

    fun inspect(content: String) {
        if (!acceptResult.get()) return
        BrokerPairingLink.parse(content)?.let {
            finishPairing { onBrokerPairing(it) }
            return
        }
        inspecting = true
        scope.launch {
            withContext(Dispatchers.Default) { pairing.inspect(content) }
                .fold(
                    onSuccess = { candidate -> finishPairing { onPairingCandidate(candidate) } },
                    onFailure = {
                        showScanFailure(resources.getString(R.string.pair_could_not_pair, it.message))
                    },
                )
            inspecting = false
        }
    }

    val codeState by produceState<PairingCodeState>(
        PairingCodeState.Loading,
        pairing,
        codeGeneration,
    ) {
        value = PairingCodeState.Loading
        value = withContext(Dispatchers.Default) {
            try {
                val pairingLink = pairing.myLink()
                PairingCodeState.Ready(
                    payload = pairingLink.payload,
                    url = pairingLink.url,
                    bitmap = QrCodes.encode(pairingLink.url),
                    automaticTimeEnabled = pairingLink.automaticTimeEnabled,
                    createdAt = pairingLink.createdAt,
                    timeZoneId = pairingLink.timeZoneId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PairingCodeState.Error(t.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }
    val legacyPairingUrl = (codeState as? PairingCodeState.Ready)?.url
    val pairingPayload = (codeState as? PairingCodeState.Ready)?.payload

    fun sharePairingUrl(url: String?, brokerAssisted: Boolean = false) {
        url ?: return
        val title = resources.getString(
            if (brokerAssisted) R.string.pair_broker_share_link_title else R.string.pair_share_link_title,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    }

    // Reader mode disables this device's card-emulation mode by design. A verified result is handed to the
    // root immediately, which removes this page before showing the Devices-page approval sheet and restores
    // HCE for the next interaction.
    LifecycleResumeEffect(pairingPayload) {
        val readerSession = pairingPayload?.let { ownPayload ->
            PairingNfcReaderSession.start(
                context = context,
                ownPayload = ownPayload,
                onPayload = ::inspect,
                onFailure = {
                    showScanFailure(
                        resources.getString(
                            R.string.pair_could_not_pair,
                            resources.nfcPairingFailureDetail(it),
                        )
                    )
                },
            )
        }
        onPauseOrDispose { readerSession?.close() }
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
                    IconButton(onClick = {
                        acceptResult.set(false)
                        hostScreenActive = false
                        onBack()
                    }) {
                        Icon(
                            FilledArrowBackIcon,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLegacyQr = true }, enabled = legacyPairingUrl != null) {
                        Icon(QrCodeIcon, contentDescription = stringResource(R.string.pair_legacy_qr_title))
                    }
                    IconButton(onClick = { sharePairingUrl(legacyPairingUrl) }, enabled = legacyPairingUrl != null) {
                        Icon(
                            ShareIcon,
                            contentDescription = stringResource(R.string.pair_share_title)
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.pair_broker_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            val ready = codeState as? PairingCodeState.Ready
            if (ready?.automaticTimeEnabled == false) {
                AutomaticTimeWarning(
                    onOpenSettings = ::openDateAndTimeSettings,
                )
            }
            (codeState as? PairingCodeState.Error)?.let { error ->
                Text(
                    stringResource(R.string.pair_could_not_prepare, error.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.pair_broker_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val bitmap = brokerBitmap
                if (hostLink != null && bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.pair_broker_qr),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(
                                if (hostUnavailable) R.string.pair_broker_reconnecting else R.string.pair_broker_connecting,
                            ))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.pair_verification_number, graph.identity.clientId.value),
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = { sharePairingUrl(hostLink?.encode(), brokerAssisted = true) },
                modifier = Modifier.fillMaxWidth(),
                enabled = hostLink != null && !scanning && !inspecting,
            ) {
                Icon(ShareIcon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.pair_broker_share_title))
            }

            Button(
                onClick = {
                    scanning = true
                    GmsBarcodeScanning.getClient(context, scannerOptions).startScan()
                        .addOnSuccessListener { barcode ->
                            if (!acceptResult.get()) return@addOnSuccessListener
                            val raw = barcode.rawValue
                            if (raw == null) {
                                showScanFailure(resources.getString(R.string.pair_no_code))
                                scanning = false
                            } else {
                                scanning = false
                                inspect(raw)
                            }
                        }
                        .addOnCanceledListener {
                            if (!acceptResult.get()) return@addOnCanceledListener
                            scanning = false
                        }
                        .addOnFailureListener {
                            if (!acceptResult.get()) return@addOnFailureListener
                            showScanFailure(resources.getString(R.string.pair_scan_failed, it.message))
                            scanning = false
                        }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning && !inspecting,
            ) {
                if (scanning || inspecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        QrCodeScannerIcon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
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
            Text(
                stringResource(R.string.pair_intro_nfc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showLegacyQr) {
        (codeState as? PairingCodeState.Ready)?.let { ready ->
            LegacyPairingQrSheet(
                ready,
                onDismiss = { showLegacyQr = false },
                onShare = { sharePairingUrl(ready.url) },
            )
        }
    }

}

private sealed interface PairingCodeState {
    data object Loading : PairingCodeState
    data class Ready(
        val payload: String,
        val url: String,
        val bitmap: Bitmap,
        val automaticTimeEnabled: Boolean?,
        val createdAt: Long,
        val timeZoneId: String,
    ) : PairingCodeState
    data class Error(val message: String) : PairingCodeState
}

@Composable
private fun LegacyPairingQrSheet(state: PairingCodeState.Ready, onDismiss: () -> Unit, onShare: () -> Unit) {
    val resources = LocalResources.current
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.pair_legacy_qr_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pair_legacy_qr_intro), style = MaterialTheme.typography.bodyMedium)
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pair_qr_code_desc),
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Text(
                stringResource(
                    R.string.pair_signed_card_time,
                    formatPairingSystemTime(state.createdAt, state.timeZoneId, resources.configuration.locales[0]),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(ShareIcon, contentDescription = null)
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.pair_share_title))
            }
        }
    }
}

@Composable
private fun AutomaticTimeWarning(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.pair_auto_time_warning_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.pair_auto_time_warning_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Text(stringResource(R.string.pair_auto_time_open_settings))
            }
        }
    }
}

private fun Resources.nfcPairingFailureDetail(failure: Throwable): String {
    val causes = generateSequence(failure) { current ->
        current.cause?.takeUnless { it === current }
    }.take(16).toList()
    return getString(
        when {
            causes.any { it is TagLostException } -> R.string.pair_nfc_connection_lost
            causes.any { it is IOException } -> R.string.pair_nfc_communication_failed
            else -> R.string.pair_nfc_exchange_failed
        }
    )
}

@Composable
internal fun PairingApprovalSheet(
    candidate: PairingCandidate,
    brokerAuthenticated: Boolean = false,
    existingTrustedDevice: RosterDevice?,
    approvingOwnDevice: Boolean?,
    error: String?,
    onTrustOwn: () -> Unit,
    onTrustOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val approving = approvingOwnDevice != null
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val existingDeviceName = existingTrustedDevice?.displayName ?: candidate.displayName

    // This confirmation used to inherit protection from PairingOverlay. It now lives above Devices, so keep
    // the same obscured-touch/tapjacking protection for the full sheet lifetime.
    TapjackingProtectionEffect()
    ModalBottomSheet(
        onDismissRequest = { if (!approving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.pair_trust_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(if (brokerAuthenticated) R.string.pair_broker_trust_body else R.string.pair_trust_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.pair_verification_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    DeviceInfo(stringResource(R.string.pair_field_name), candidate.displayName)
                    DeviceInfo(stringResource(R.string.pair_field_platform), candidate.platform)
                    DeviceInfo(
                        stringResource(R.string.pair_field_verification_number),
                        candidate.safetyNumber
                    )
                    DeviceInfo(
                        stringResource(R.string.pair_field_identity_key),
                        candidate.identityKeyFingerprint,
                        monospace = true,
                    )
                    // The immutable identity key (above) delegates a rotatable OPERATIONAL key per epoch. Show
                    // that epoch + its signing key + HPKE keyset ONLY when the key-epoch's signature verified
                    // against the identity; a tampered (INVALID) or absent epoch shows a note instead of forged
                    // key fingerprints, so the user never confirms key material the identity didn't authorize.
                    when (candidate.keyEpochStatus) {
                        KeyEpochStatus.VERIFIED -> OperationalKeyChip(candidate)
                        KeyEpochStatus.ABSENT -> Text(
                            stringResource(R.string.pair_keys_sync_later),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        KeyEpochStatus.INVALID -> Text(
                            stringResource(R.string.pair_keys_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onTrustOwn,
                enabled = !approving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (approvingOwnDevice == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(
                    when (existingTrustedDevice?.ownDevice) {
                        true -> stringResource(R.string.pair_update_device, existingDeviceName)
                        false -> stringResource(R.string.pair_update_as_own)
                        null -> stringResource(R.string.pair_trust_own)
                    }
                )
            }
            OutlinedButton(
                onClick = onTrustOther,
                enabled = !approving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (approvingOwnDevice == false) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(
                    when (existingTrustedDevice?.ownDevice) {
                        true -> stringResource(R.string.pair_update_as_other)
                        false -> stringResource(R.string.pair_update_device, existingDeviceName)
                        null -> stringResource(R.string.pair_trust_other)
                    }
                )
            }
        }
    }
}

/**
 * The peer's NS2 operational layer in one chip: the current [PairingCandidate.epoch] and the two keys that
 * epoch authorizes — the operational signing key and the HPKE keyset. Grouped so the user reads it as a
 * single delegated unit under the identity key, and so it is visibly distinct from the immutable identity row.
 */
@Composable
private fun OperationalKeyChip(candidate: PairingCandidate) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pair_operational_chip_title, candidate.epoch),
                style = MaterialTheme.typography.labelLarge,
            )
            DeviceInfo(
                stringResource(R.string.pair_field_signing_key),
                candidate.operationalKeyFingerprint,
                monospace = true,
            )
            DeviceInfo(
                stringResource(R.string.pair_field_encryption_key),
                candidate.hpkeKeyFingerprint,
                monospace = true,
            )
        }
    }
}

@Composable
private fun DeviceInfo(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}
