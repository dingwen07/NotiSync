package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.IntentSender
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
internal fun IphoneCompanionAccess(enabled: Boolean, appSetup: Boolean, selected: IosPeerSelection?, onSelected: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val peers by graph.iosDeviceRepo.connectedLePeers.collectAsStateWithLifecycle()
    val setupPeer by graph.iosDeviceRepo.setupPeer.collectAsStateWithLifecycle()
    val notifySelected by rememberUpdatedState(onSelected)
    val enabledNow by rememberUpdatedState(enabled)
    val helperMode by rememberUpdatedState(appSetup)
    var resumedSince by remember { mutableStateOf<Long?>(null) }
    var attemptedAt by rememberSaveable { mutableLongStateOf(-1L) }
    var offeredHelper by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    // A null timestamp identifies an encrypted helper request, which can already be bonded.
    var pendingLink by rememberSaveable { mutableStateOf<Long?>(null) }
    var ambiguous by remember { mutableStateOf(emptyList<IosCompanion.Peer>()) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    LifecycleResumeEffect(enabled) {
        if (enabled) resumedSince = SystemClock.elapsedRealtime()
        onPauseOrDispose { resumedSince = null }
    }

    fun failed() {
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
        } else if (graph.iosDeviceRepo.connectedLePeers.value.none { it.address == address && it.newServerLinkAt == nativeLink }) {
            failed()
            return
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
    fun request(address: String, link: Long?) {
        if (pending != null || resumedSince == null) return
        val cdm = IosCompanion.manager(context) ?: return failed()
        pendingLink = link
        pending = address
        Log.i("IphoneCompanionAccess", "Requesting single-device CDM access (helper=${link == null})")
        runCatching {
            cdm.associate(IosCompanion.request(address), context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    if (!active || pending != address) return
                    if (!enabledNow || resumedSince == null) { pending = null; return }
                    runCatching { launcher.launch(IntentSenderRequest.Builder(intentSender).build()) }.onFailure { failed() }
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) { if (pending == address) approved(associationInfo) }
                override fun onFailure(message: CharSequence?) { if (active && pending == address) failed() }
            })
        }.onFailure { failed() }
    }
    LaunchedEffect(enabled, resumedSince, appSetup, peers, pending, setupPeer, selected) {
        val start = resumedSince ?: return@LaunchedEffect
        if (!enabled || pending != null) return@LaunchedEffect
        if (appSetup) {
            val address = setupPeer?.address ?: selected?.address
            if (address == null) { offeredHelper = null; return@LaunchedEffect }
            if (address == offeredHelper || IosCompanion.hasAssociation(context, address, selected)) return@LaunchedEffect
            // CDM can authorize a saved, bonded helper even while the iPhone is out of range.
            if (setupPeer == null && IosCompanion.needsLeEnrollment(context, selected)) return@LaunchedEffect
            offeredHelper = address
            request(address, null)
        } else {
            val arrivals = peers.filter { it.newServerLinkAt?.let { time -> time >= start && time > attemptedAt } == true }
            if (arrivals.isEmpty()) return@LaunchedEffect
            delay(250) // Allow simultaneous notifications from the Bluetooth stack to arrive together.
            attemptedAt = arrivals.maxOf { it.newServerLinkAt!! }
            if (arrivals.size == 1 && ambiguous.isEmpty()) request(arrivals.single().address, arrivals.single().newServerLinkAt)
            else ambiguous = (ambiguous + arrivals).distinct()
        }
    }
    if (enabled && !appSetup && ambiguous.isNotEmpty()) {
        val live = ambiguous.filter { candidate -> peers.any { it.address == candidate.address && it.newServerLinkAt == candidate.newServerLinkAt } }
        if (live.isEmpty()) LaunchedEffect(Unit) { ambiguous = emptyList() }
        else IncomingIphonePicker(live, onDismiss = { ambiguous = emptyList() }) { peer ->
            ambiguous = emptyList()
            request(peer.address, peer.newServerLinkAt)
        }
    }
}
