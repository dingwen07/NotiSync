package net.extrawdw.apps.notisync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.navigation.AppMenuShortcuts
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.pairing.PairingDeepLinks
import net.extrawdw.apps.notisync.pairing.PairingNfcInbox
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.ui.OnboardingScreen
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink

class MainActivity : ComponentActivity() {
    private val pendingPairingPayload = MutableStateFlow<String?>(null)
    private val pendingMenuDestination = MutableStateFlow<MenuDestination?>(null)
    private val pendingOpenDevices = MutableStateFlow(false)
    private val pendingDeviceDetails = MutableStateFlow<String?>(null)
    private val pendingOpenRun = MutableStateFlow<RunKey?>(null)
    private val pendingOpenSshHistory = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = applicationContext as NotiSyncApp
        splashScreen.setKeepOnScreenCondition {
            shouldKeepSystemSplash(app.startupState.value)
        }
        consumeLaunchIntent(intent)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val startupState by app.startupState.collectAsStateWithLifecycle()
            val pairingPayload by pendingPairingPayload.collectAsStateWithLifecycle()
            val hcePairingPayload by PairingNfcInbox.pendingPayload.collectAsStateWithLifecycle()
            val menuDestination by pendingMenuDestination.collectAsStateWithLifecycle()
            val openDevices by pendingOpenDevices.collectAsStateWithLifecycle()
            val deviceDetails by pendingDeviceDetails.collectAsStateWithLifecycle()
            val openRun by pendingOpenRun.collectAsStateWithLifecycle()
            val openSshHistory by pendingOpenSshHistory.collectAsStateWithLifecycle()
            NotiSyncTheme {
                when {
                    startupState.stage == AppStartupStage.READY -> {
                        val graph = remember { app.graph }
                        val onboardingCompleted by
                            graph.settings.onboardingCompleted.collectAsStateWithLifecycle()
                        when (onboardingCompleted) {
                            // Persist on the graph scope: the composable (and any rememberCoroutineScope) is
                            // disposed when the flow updates, which would cancel the write mid-flight.
                            false -> OnboardingScreen(
                                onFinish = {
                                    graph.scope.launch { graph.settings.setOnboardingCompleted() }
                                },
                            )
                            // Finishing onboarding lands here with Devices as the NavHost start destination;
                            // a pairing deep link received during onboarding is still pending and opens now.
                            true -> NotiSyncRoot(
                                pendingPairingPayload = pairingPayload,
                                onPendingPairingPayloadConsumed = { pendingPairingPayload.value = null },
                                pendingHcePairingPayload = hcePairingPayload,
                                onPendingHcePairingPayloadConsumed = { payload ->
                                    PairingNfcInbox.consume(applicationContext, payload)
                                },
                                openMenuDestination = menuDestination,
                                onOpenMenuDestinationConsumed = { pendingMenuDestination.value = null },
                                openDevices = openDevices,
                                onOpenDevicesConsumed = { pendingOpenDevices.value = false },
                                openDeviceDetails = deviceDetails,
                                onOpenDeviceDetailsConsumed = { pendingDeviceDetails.value = null },
                                openRun = openRun,
                                onOpenRunConsumed = { pendingOpenRun.value = null },
                                openSshHistoryRequestId = openSshHistory,
                                onOpenSshHistoryConsumed = { pendingOpenSshHistory.value = null },
                            )
                        }
                    }
                    startupState.stage == AppStartupStage.FAILED -> StartupFailureScreen()
                    // Kept behind the system splash during ordinary checking and graph initialization.
                    else -> Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        updatePendingPairingPayload(intent)
        consumeOpenDevices(intent)
        consumeOpenRun(intent)
        consumeOpenSshHistory(intent)
        consumeMenuDestination(intent)
    }

    private fun consumeMenuDestination(intent: Intent?) {
        AppMenuShortcuts.consumeDestination(intent)?.let { pendingMenuDestination.value = it }
    }

    /** A trust notification asked us to open the Devices tab. */
    private fun consumeOpenDevices(intent: Intent?) {
        intent?.getStringExtra(EXTRA_DEVICE_DETAILS_CLIENT_ID)?.takeIf(String::isNotBlank)?.let {
            pendingDeviceDetails.value = it
            intent.removeExtra(EXTRA_DEVICE_DETAILS_CLIENT_ID)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_DEVICES, false) != true) return
        pendingOpenDevices.value = true
        intent.removeExtra(EXTRA_OPEN_DEVICES) // consume so a config change / Recents can't re-trigger it
    }

    /** A locally-rendered Run notification asked us to open its durable detail record. */
    private fun consumeOpenRun(intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION_OPEN_RUN && !intent.hasExtra(EXTRA_RUN_ID)) return
        val host = intent.getStringExtra(EXTRA_RUN_HOST_CLIENT_ID) ?: return
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        pendingOpenRun.value = RunKey(host, runId)
        intent.removeExtra(EXTRA_RUN_HOST_CLIENT_ID)
        intent.removeExtra(EXTRA_RUN_ID)
    }

    /** An auto-approval notification asked us to open one exact durable SSH history record. */
    private fun consumeOpenSshHistory(intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION_OPEN_SSH_HISTORY && !intent.hasExtra(EXTRA_SSH_REQUEST_ID)) return
        val requestId = intent.getStringExtra(EXTRA_SSH_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return
        pendingOpenSshHistory.value = requestId
        intent.removeExtra(EXTRA_SSH_REQUEST_ID)
    }

    companion object {
        const val EXTRA_OPEN_DEVICES = "net.extrawdw.apps.notisync.OPEN_DEVICES"
        const val ACTION_OPEN_DEVICE_DETAILS = "net.extrawdw.apps.notisync.OPEN_DEVICE_DETAILS"
        const val EXTRA_DEVICE_DETAILS_CLIENT_ID = "net.extrawdw.apps.notisync.DEVICE_DETAILS_CLIENT_ID"
        const val ACTION_OPEN_RUN = "net.extrawdw.apps.notisync.OPEN_RUN"
        const val EXTRA_RUN_HOST_CLIENT_ID = "net.extrawdw.apps.notisync.RUN_HOST_CLIENT_ID"
        const val EXTRA_RUN_ID = "net.extrawdw.apps.notisync.RUN_ID"
        const val ACTION_OPEN_SSH_HISTORY = "net.extrawdw.apps.notisync.OPEN_SSH_HISTORY"
        const val EXTRA_SSH_REQUEST_ID = "net.extrawdw.apps.notisync.SSH_REQUEST_ID"
    }

    private fun updatePendingPairingPayload(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_OPEN_DEVICE_DETAILS) return
        // Reopening the app from the Recents list re-delivers the task's base intent — for a
        // QR-launched task that's the original pairing deep link. Ignore it, otherwise every
        // return-from-Recents would surface the trust dialog again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = intent.dataString ?: return
        val payload = BrokerPairingLink.parse(link)?.encode() ?: PairingDeepLinks.payloadFrom(link)
        // Consume the link so the same intent can't re-trigger pairing on a later recreation
        // (including malformed links). Never let external input throw during activity startup.
        intent.data = null
        if (payload == null) {
            Toast.makeText(this, R.string.pair_invalid_link, Toast.LENGTH_LONG).show()
            return
        }
        pendingPairingPayload.value = payload
    }
}
