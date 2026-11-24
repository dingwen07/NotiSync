package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ios.IosCompanion
import net.extrawdw.apps.notisync.ios.IosPeerSelection

/** Ask CDM for a specific iPhone while the bridge setup screen is in the foreground. */
@Composable
internal fun IosCompanionAccess(enabled: Boolean, appSetup: Boolean, selected: IosPeerSelection?, onSelected: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val peers by graph.iosDeviceRepo.connectedLePeers.collectAsStateWithLifecycle()
    val setupPeer by graph.iosDeviceRepo.setupPeer.collectAsStateWithLifecycle()
    val notifySelected by rememberUpdatedState(onSelected)
    val enabledNow by rememberUpdatedState(enabled)
    val helperMode by rememberUpdatedState(appSetup)
    val setupSession = rememberSaveable(enabled, appSetup) { SystemClock.elapsedRealtimeNanos() }
    var resumed by remember { mutableStateOf(false) }
    val attemptedLinks = rememberSaveable(setupSession) { Bundle() }
    var offeredHelper by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    // A null timestamp identifies an encrypted helper request, which can already be bonded.
    var pendingLink by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<IntentSender?>(null) }
    var ambiguous by remember { mutableStateOf(emptyList<IosCompanion.Peer>()) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    LifecycleResumeEffect(setupSession) {
        resumed = enabled
        val candidates = graph.iosDeviceRepo.pairingCandidates
        if (enabled && !appSetup) candidates.foregroundSession = setupSession
        onPauseOrDispose {
            resumed = false
            if (candidates.foregroundSession == setupSession) candidates.foregroundSession = null
        }
    }

    fun failed() {
        confirmation = null
        pending = null
        if (active && enabledNow) Toast.makeText(context, R.string.ios_pairing_try_live, Toast.LENGTH_LONG).show()
    }
    fun approved(info: AssociationInfo) {
        val address = pending ?: return
        if (!active) return
        val nativeLink = pendingLink
        if (!enabledNow || helperMode != (nativeLink == null) || !address.equals(info.deviceMacAddress?.toString(), true)) {
            failed()
            return
        }
        val manager = graph.iosBridgeManager
        if (nativeLink == null) {
            val current = IosCompanion.selectedPeer(context)
            if (current?.address != address && manager?.confirmSetupPeer(address) != true) {
                failed()
                return
            }
        } else {
            val offer = manager?.currentPairingOffer(address)
            if (offer?.session != setupSession || offer.eligibleAt != nativeLink) {
                failed()
                return
            }
        }
        pending = null
        IosCompanion.selectAssociation(context, info)
        IosCompanion.observePresence(context)
        manager?.refreshSelection()
        if (nativeLink != null && manager?.chooseConnectedPeer(address) != true) failed()
        notifySelected()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val info = result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            if (info != null) approved(info) else if (pending != null) failed()
        } else pending = null
    }
    // Android's pairing dialog can pause setup while CDM is preparing its own confirmation.
    LaunchedEffect(confirmation, resumed, enabled, appSetup, pending) {
        val sender = confirmation ?: return@LaunchedEffect
        if (pending == null || !enabled || appSetup != (pendingLink == null)) {
            confirmation = null
            pending = null
        } else if (resumed) {
            confirmation = null
            runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { failed() }
        }
    }
    fun request(address: String, link: Long?) {
        if (pending != null || !resumed) return
        val cdm = IosCompanion.manager(context) ?: return failed()
        pendingLink = link
        pending = address
        Log.i("IosCompanionAccess", "Requesting single-device CDM access (helper=${link == null})")
        runCatching {
            cdm.associate(IosCompanion.request(address), context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (active && pending == address && pendingLink == link) confirmation = intentSender
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) { if (pending == address) approved(associationInfo) }
                override fun onFailure(message: CharSequence?) { if (active && pending == address) failed() }
            })
        }.onFailure { failed() }
    }
    fun requestNative(peer: IosCompanion.Peer) {
        val offer = peer.nativeOffer ?: return
        if (offer.session == setupSession) request(peer.address, offer.eligibleAt)
    }
    LaunchedEffect(enabled, resumed, appSetup, peers, pending, setupPeer, selected, setupSession) {
        if (!resumed || !enabled || pending != null) return@LaunchedEffect
        if (appSetup) {
            val address = setupPeer?.address ?: selected?.address
            if (address == null) { offeredHelper = null; return@LaunchedEffect }
            if (address == offeredHelper || IosCompanion.hasAssociation(context, address, selected)) return@LaunchedEffect
            // CDM can authorize a saved, bonded helper even while the iPhone is out of range.
            if (setupPeer == null && IosCompanion.needsLeEnrollment(context, selected)) return@LaunchedEffect
            offeredHelper = address
            request(address, null)
        } else {
            val arrivals = peers.filter { peer ->
                val offer = peer.nativeOffer ?: return@filter false
                offer.session == setupSession && attemptedLinks.getLong(peer.address, -1L) != offer.eligibleAt
            }
            if (arrivals.isEmpty()) return@LaunchedEffect
            delay(250) // Allow simultaneous notifications from the Bluetooth stack to arrive together.
            arrivals.forEach { attemptedLinks.putLong(it.address, it.nativeOffer!!.eligibleAt) }
            if (arrivals.size == 1 && ambiguous.isEmpty()) requestNative(arrivals.single())
            else ambiguous = (ambiguous + arrivals).distinct()
        }
    }
    if (enabled && !appSetup && ambiguous.isNotEmpty()) {
        val live = ambiguous.filter { candidate -> candidate.nativeOffer?.session == setupSession &&
            peers.any { it.address == candidate.address && it.nativeOffer == candidate.nativeOffer } }
        if (live.isEmpty()) LaunchedEffect(Unit) { ambiguous = emptyList() }
        else IosIncomingPeerPicker(live, onDismiss = { ambiguous = emptyList() }) { peer ->
            ambiguous = emptyList()
            requestNative(peer)
        }
    }
}
