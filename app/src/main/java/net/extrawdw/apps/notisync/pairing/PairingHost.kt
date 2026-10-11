package net.extrawdw.apps.notisync.pairing

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.BrokerPairingSheet
import net.extrawdw.apps.notisync.ui.PairingApprovalSheet
import net.extrawdw.apps.notisync.ui.PairingOverlay
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import net.extrawdw.notisync.protocol.TrustStatus

/** UI actions shared by the Devices destination and the app-level pairing overlays. */
internal class PairingHostState(
    private val visibility: MutableState<Boolean>,
    private val quarantine: State<Boolean>,
    private val buttonBounds: MutableState<Rect?>,
    val onPairingCandidate: (PairingCandidate) -> Unit,
    val onBrokerPairingCandidate: (PairingCandidate) -> Unit,
    val onBrokerPairing: (BrokerPairingLink) -> Unit,
) {
    var isOpen: Boolean by visibility
    val isQuarantined: Boolean get() = quarantine.value
    var pairButtonBounds: Rect? by buttonBounds

    fun open() {
        if (!isQuarantined) isOpen = true
    }

    fun close() {
        isOpen = false
    }
}

/** Keeps NFC lifecycle effects and pairing sheets above the navigation suite. */
@Composable
internal fun PairingHost(
    graph: AppGraph,
    pendingPairingPayload: String?,
    onPendingPairingPayloadConsumed: () -> Unit,
    pendingHcePairingPayload: String?,
    onPendingHcePairingPayloadConsumed: (String) -> Unit,
    foregroundResumeGeneration: Int,
    usePairingPane: Boolean,
    onOpenDevices: () -> Unit,
    content: @Composable (PairingHostState) -> Unit,
) {
    val context = LocalContext.current
    // Pairing callbacks can finish after a configuration change without restarting the operation.
    val resources by rememberUpdatedState(LocalResources.current)
    val openDevices by rememberUpdatedState(onOpenDevices)
    val pairing = remember { PairingManager(graph) }
    // Called outside the NavHost, so pending trust decisions retain the Activity's ViewModel scope.
    val pairingUi = androidx.lifecycle.viewmodel.compose.viewModel { PairingViewModel() }
    // Pairing is frozen during a trust-tamper quarantine — the stripe is disabled in DevicesScreen, and
    // this also blocks the deep-link path so a pairing link can't bypass the freeze.
    val quarantine = graph.trust.quarantined.collectAsStateWithLifecycle()
    val quarantined by quarantine

    // Pairing is a state-driven overlay rather than a nav destination, so it can expand out of — and
    // collapse back into — the "Device Pairing" stripe with a predictive-back-driven container transform
    // (see PairingOverlay). The stripe reports its live position here; the overlay renders above the
    // whole navigation suite so the Devices tab (and bar) stay visible as the page folds away.
    val pairingVisibility = rememberSaveable { mutableStateOf(false) }
    var showPairing by pairingVisibility
    val buttonBounds = remember { mutableStateOf<Rect?>(null) }
    val pairButtonBounds by buttonBounds
    var pairingReview by pairingUi.review
    var brokerPairingLink by pairingUi.brokerLink
    val pairingApprovalOwnDevice by pairingUi.approvingOwnDevice
    var pairingApprovalError by pairingUi.error
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    var foregroundPairingUrl by remember {
        mutableStateOf(PairingCardStore.current()?.let(PairingDeepLinks::create))
    }

    // Refresh the signed public card on every foreground entry (and rename). The previously persisted card
    // remains immediately usable while StrongBox signing runs off-main.
    LaunchedEffect(showPairing, deviceName, foregroundResumeGeneration) {
        if (!showPairing) {
            withContext(Dispatchers.IO) { runCatching { pairing.myLink() } }
                .onSuccess { foregroundPairingUrl = it.url }
        }
    }

    // Compatibility path for Android NDEF readers and iPhone: add the Type 4 AID only while this Activity is
    // resumed outside pairing UI. While approval is pending, withhold NDEF to prevent another system tag
    // dispatch but keep the proprietary AID preferred; the sheet must not switch this device to reader mode.
    LifecycleResumeEffect(showPairing, pairingReview != null || brokerPairingLink != null, foregroundPairingUrl) {
        when {
            showPairing -> Unit
            pairingReview != null || brokerPairingLink != null -> PairingNfcController.enableForegroundCustomAidOnly(context)
            else -> foregroundPairingUrl?.let {
                PairingNfcController.enableForegroundNdef(context, it)
            }
        }
        onPauseOrDispose { PairingNfcController.disableForegroundNdef(context) }
    }

    fun openPairingCandidate(
        candidate: PairingCandidate,
        source: PairingReviewSource = PairingReviewSource.INTERACTIVE,
    ) {
        // Reader mode suppresses this device's HCE mode. Remove the pairing page first, then show approval
        // above Devices so the reciprocal peer can continue to address our always-on custom AID.
        showPairing = false
        openDevices()
        pairingApprovalError = null
        val existingTrustedDevice = graph.trust.roster.value.firstOrNull {
            it.clientId == candidate.clientId && it.status == TrustStatus.TRUSTED
        }
        pairingReview = PairingReview(candidate, source, existingTrustedDevice)
    }

    fun approvePairing(review: PairingReview, ownDevice: Boolean) {
        pairingUi.approve(graph, pairing, context.applicationContext, review, ownDevice)
    }

    val completedPairing by pairingUi.completed
    LaunchedEffect(completedPairing) {
        completedPairing?.let { (review, name) ->
            if (review.source == PairingReviewSource.HCE) {
                onPendingHcePairingPayloadConsumed(review.candidate.payload)
            }
            Toast.makeText(context, resources.getString(R.string.pair_paired_with, name), Toast.LENGTH_LONG).show()
            pairingUi.review.value = null
            pairingUi.completed.value = null
        }
    }

    LaunchedEffect(pendingPairingPayload, pendingHcePairingPayload, quarantined, pairingReview != null, brokerPairingLink) {
        if (quarantined) {
            showPairing = false
            brokerPairingLink = null
            return@LaunchedEffect
        }
        // Queue another scan while a secure exchange or CARD review is active, including a failed approval
        // that the user may retry. Never replace the visible identity during a trust decision.
        if (pairingReview != null || brokerPairingLink != null) return@LaunchedEffect
        val fromDeepLink = pendingPairingPayload != null
        val payload = pendingPairingPayload ?: pendingHcePairingPayload ?: return@LaunchedEffect
        openDevices()
        BrokerPairingLink.parse(payload)?.let { link ->
            showPairing = false
            pairingReview = null
            brokerPairingLink = link
            if (fromDeepLink) onPendingPairingPayloadConsumed() else onPendingHcePairingPayloadConsumed(payload)
            return@LaunchedEffect
        }
        val inspection = withContext(Dispatchers.Default) { pairing.inspect(payload) }
        inspection.fold(
            onSuccess = { candidate ->
                val source = if (fromDeepLink) {
                    PairingReviewSource.DEEP_LINK
                } else {
                    PairingReviewSource.HCE
                }
                if (source == PairingReviewSource.HCE) {
                    PairingNfcInbox.dismissNotification(context)
                }
                openPairingCandidate(candidate, source)
            },
            onFailure = {
                val message = if (fromDeepLink) {
                    resources.getString(R.string.pair_could_not_open_link, it.message)
                } else {
                    resources.getString(R.string.pair_could_not_pair, it.message)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
        )
        if (fromDeepLink) {
            onPendingPairingPayloadConsumed()
        } else if (inspection.isFailure) {
            // Keep a verified HCE card durable until the user approves or dismisses the review. Invalid
            // input is terminal and should not reopen on every future app launch.
            onPendingHcePairingPayloadConsumed(payload)
        }
    }

    val state = PairingHostState(
        visibility = pairingVisibility,
        quarantine = quarantine,
        buttonBounds = buttonBounds,
        onPairingCandidate = { openPairingCandidate(it) },
        onBrokerPairingCandidate = { openPairingCandidate(it, PairingReviewSource.BROKER) },
        onBrokerPairing = {
            showPairing = false
            brokerPairingLink = it
        },
    )
    Box(modifier = Modifier.fillMaxSize()) {
        content(state)

        if (showPairing && !quarantined && !usePairingPane) {
            PairingOverlay(
                pairButtonBounds = pairButtonBounds,
                onClose = { showPairing = false },
                onPairingCandidate = { openPairingCandidate(it) },
                onBrokerPairingCandidate = { openPairingCandidate(it, PairingReviewSource.BROKER) },
                onBrokerPairing = {
                    showPairing = false
                    brokerPairingLink = it
                },
            )
        }

        if (!quarantined) brokerPairingLink?.let { link ->
            BrokerPairingSheet(
                link = link,
                pairing = pairing,
                onCandidate = {
                    brokerPairingLink = null
                    openPairingCandidate(it, PairingReviewSource.BROKER)
                },
                onDismiss = { brokerPairingLink = null },
            )
        }

        pairingReview?.let { review ->
            PairingApprovalSheet(
                candidate = review.candidate,
                brokerAuthenticated = review.source == PairingReviewSource.BROKER,
                existingTrustedDevice = review.existingTrustedDevice,
                approvingOwnDevice = pairingApprovalOwnDevice,
                error = pairingApprovalError,
                onTrustOwn = { approvePairing(review, ownDevice = true) },
                onTrustOther = { approvePairing(review, ownDevice = false) },
                onDismiss = {
                    if (review.source == PairingReviewSource.HCE) {
                        onPendingHcePairingPayloadConsumed(review.candidate.payload)
                    }
                    pairingApprovalError = null
                    pairingReview = null
                },
            )
        }
    }
}
