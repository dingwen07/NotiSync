package net.extrawdw.apps.notisync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import net.extrawdw.apps.notisync.ui.icons.material.outlined.arrow_back as ArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.view_cozy as AppsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.devices as DevicesIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.notifications as NotificationsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.wifi_notification as WifiNotificationIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.phone_iphone as PhoneIphoneIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.qr_code_2 as QrCode2Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.extrawdw.apps.notisync.ios.IosBridgeService
import net.extrawdw.apps.notisync.ios.IosBridgeStatus
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ios.IosBluetoothPermissions
import net.extrawdw.apps.notisync.ios.IosCompanion

/** Ordered wizard steps. APPS (the choose-apps-to-mirror reminder) only shows once listener access exists. */
private enum class OnboardingStep { WELCOME, NOTIFICATIONS, LISTENER, APPS, IPHONE, PAIR }

private fun previousStep(from: OnboardingStep, listenerEnabled: Boolean): OnboardingStep =
    when (from) {
        OnboardingStep.WELCOME, OnboardingStep.NOTIFICATIONS -> OnboardingStep.WELCOME
        OnboardingStep.LISTENER -> OnboardingStep.NOTIFICATIONS
        OnboardingStep.APPS -> OnboardingStep.LISTENER
        OnboardingStep.IPHONE ->
            if (listenerEnabled) OnboardingStep.APPS else OnboardingStep.LISTENER
        OnboardingStep.PAIR -> OnboardingStep.IPHONE
    }

/**
 * First-launch setup wizard: welcome → post-notifications permission → notification-listener access
 * (grant or skip; granting continues into the choose-apps-to-mirror reminder) → iPhone bridge pairing
 * (skippable) → pair-your-other-devices guidance. Finishing lands on the Devices tab, which is the
 * navigation start destination that composes right after [onFinish].
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val resources = LocalResources.current

    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }

    var selectedPeer by remember { mutableStateOf(IosCompanion.selectedPeer(context)) }
    val deviceName by graph.iosDeviceRepo.deviceName.collectAsStateWithLifecycle()
    val pairedName = remember(selectedPeer, deviceName) {
        IosCompanion.selectedDeviceName(context, selectedPeer) ?: deviceName ?: selectedPeer?.address
    }
    val bridgeEnabled by graph.settings.iosBridgeEnabled.collectAsStateWithLifecycle()
    val status by graph.iosDeviceRepo.status.collectAsStateWithLifecycle()
    val setupPeer by graph.iosDeviceRepo.setupPeer.collectAsStateWithLifecycle()
    val setupPreferences = remember(context) { IosCompanion.setupPreferences(context) }
    val appSetup by setupPreferences.appModeChanges.collectAsStateWithLifecycle(setupPreferences.usesApp)

    // Re-read both permissions whenever we return to the foreground (back from the system settings
    // pages the grant buttons open) — the same idiom as DevicesDestination.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        selectedPeer = IosCompanion.selectedPeer(context)
        onPauseOrDispose { }
    }
    val postGranted = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
    val listenerEnabled = remember(refresh) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    // After a denial the system silently auto-denies re-requests, so the grant button would become a
    // no-op — flip it to an open-settings action instead. Reset if the permission shows up granted
    // (e.g. the user granted it from settings and came back).
    var postDeniedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(postGranted) { if (postGranted) postDeniedOnce = false }
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        if (granted) step = OnboardingStep.LISTENER else postDeniedOnce = true
    }

    // Coming back from the listener-settings page with access freshly granted continues straight into
    // the apps reminder, so "now turn on the apps you want mirrored" lands the moment it applies.
    var hadListener by remember { mutableStateOf(listenerEnabled) }
    LaunchedEffect(listenerEnabled) {
        if (listenerEnabled && !hadListener && step == OnboardingStep.LISTENER) {
            step = OnboardingStep.APPS
        }
        hadListener = listenerEnabled
    }

    val needsEnrollment = remember(selectedPeer, status) { IosCompanion.needsLeEnrollment(context, selectedPeer) }
    IphoneCompanionAccess(
        enabled = step == OnboardingStep.IPHONE && bridgeEnabled && (appSetup || needsEnrollment && status == IosBridgeStatus.ADVERTISING),
        appSetup = appSetup, selected = selectedPeer,
    ) {
        selectedPeer = IosCompanion.selectedPeer(context)
    }
    LaunchedEffect(status, deviceName, setupPeer) {
        selectedPeer = IosCompanion.selectedPeer(context)
    }
    LaunchedEffect(step, bridgeEnabled, status) {
        if (step == OnboardingStep.IPHONE && bridgeEnabled &&
            (status == IosBridgeStatus.OFF || status == IosBridgeStatus.ERROR) &&
            IosBluetoothPermissions.hasRequired(context) &&
            graph.settings.iosBridgeEnabledNow()
        ) {
            IosBridgeService.start(context)
        }
    }
    // Same denied-once handling as POST_NOTIFICATIONS: a denied Bluetooth set auto-denies re-requests,
    // so "Pair iPhone" would no-op — route to the app's settings page (Permissions → Nearby devices).
    val btPermissionsGranted = remember(refresh) {
        IosBluetoothPermissions.hasRequired(context)
    }
    var btDeniedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(btPermissionsGranted) { if (btPermissionsGranted) btDeniedOnce = false }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh++
        if (IosBluetoothPermissions.hasRequired(context)) graph.setIosBridgeEnabled(true) else btDeniedOnce = true
    }

    fun startIphonePairing() {
        if (IosBluetoothPermissions.hasRequired(context)) {
            graph.setIosBridgeEnabled(true)
        } else {
            btPermissionLauncher.launch(IosBluetoothPermissions.requestPermissions())
        }
    }

    // Dot indicator positions. APPS stays listed while shown even if access is revoked mid-flow.
    val steps = remember(listenerEnabled, step) {
        buildList {
            add(OnboardingStep.WELCOME)
            add(OnboardingStep.NOTIFICATIONS)
            add(OnboardingStep.LISTENER)
            if (listenerEnabled || step == OnboardingStep.APPS) add(OnboardingStep.APPS)
            add(OnboardingStep.IPHONE)
            add(OnboardingStep.PAIR)
        }
    }

    BackHandler(enabled = step != OnboardingStep.WELCOME) {
        step = previousStep(step, listenerEnabled)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step != OnboardingStep.WELCOME) {
                    IconButton(onClick = { step = previousStep(step, listenerEnabled) }) {
                        Icon(
                            ArrowBackIcon,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    (slideInHorizontally { if (forward) it / 5 else -it / 5 } + fadeIn())
                        .togetherWith(slideOutHorizontally { if (forward) -it / 5 else it / 5 } + fadeOut())
                },
                label = "onboardingStep",
            ) { current ->
                when (current) {
                    OnboardingStep.WELCOME -> StepPage(
                        icon = DevicesIcon,
                        title = stringResource(R.string.onboarding_welcome_title),
                        body = stringResource(R.string.onboarding_welcome_body),
                        primaryLabel = stringResource(R.string.onboarding_welcome_start),
                        onPrimary = { step = OnboardingStep.NOTIFICATIONS },
                    )

                    OnboardingStep.NOTIFICATIONS -> StepPage(
                        icon = NotificationsIcon,
                        title = stringResource(R.string.onboarding_notifications_title),
                        body = stringResource(R.string.onboarding_notifications_body),
                        status = if (postGranted) stringResource(R.string.onboarding_notifications_granted) else null,
                        primaryLabel = stringResource(
                            when {
                                postGranted -> R.string.onboarding_continue
                                postDeniedOnce -> R.string.devices_open_settings
                                else -> R.string.onboarding_notifications_grant
                            }
                        ),
                        onPrimary = {
                            when {
                                postGranted -> step = OnboardingStep.LISTENER
                                postDeniedOnce -> context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                                else ->
                                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        secondaryLabel = if (postGranted) null else stringResource(R.string.onboarding_skip),
                        onSecondary = { step = OnboardingStep.LISTENER },
                    )

                    OnboardingStep.LISTENER -> StepPage(
                        icon = WifiNotificationIcon,
                        title = stringResource(R.string.onboarding_listener_title),
                        body = stringResource(R.string.onboarding_listener_body),
                        status = if (listenerEnabled) stringResource(R.string.onboarding_listener_granted) else null,
                        primaryLabel = stringResource(
                            if (listenerEnabled) R.string.onboarding_continue
                            else R.string.devices_open_settings
                        ),
                        onPrimary = {
                            if (listenerEnabled) {
                                step = OnboardingStep.APPS
                            } else {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        secondaryLabel = if (listenerEnabled) null else stringResource(R.string.onboarding_skip),
                        onSecondary = { step = OnboardingStep.IPHONE }, // no access → nothing to pick on APPS
                    )

                    OnboardingStep.APPS -> StepPage(
                        icon = AppsIcon,
                        title = stringResource(R.string.onboarding_apps_title),
                        body = stringResource(R.string.onboarding_apps_body),
                        primaryLabel = stringResource(R.string.onboarding_continue),
                        onPrimary = { step = OnboardingStep.IPHONE },
                    )

                    OnboardingStep.IPHONE -> {
                        val settledPaired = selectedPeer != null &&
                            !needsEnrollment &&
                            !(bridgeEnabled && appSetup && setupPeer != null) &&
                            status != IosBridgeStatus.NEEDS_PAIRING
                        val selectedLabel = when {
                            selectedPeer == null -> null
                            needsEnrollment ->
                                pairedName?.let { stringResource(R.string.ios_pairing_needs_enrollment, it) }
                                    ?: stringResource(R.string.ios_pairing_needs_enrollment_unknown)
                            else ->
                                pairedName?.let { stringResource(R.string.ios_pairing_on, it) }
                                    ?: stringResource(R.string.ios_pairing_on_unknown)
                        }
                        when {
                            !bridgeEnabled -> StepPage(
                                icon = PhoneIphoneIcon,
                                title = stringResource(R.string.onboarding_iphone_title),
                                body = stringResource(R.string.onboarding_iphone_body),
                                status = selectedLabel,
                                primaryLabel = stringResource(
                                    if (btDeniedOnce && !btPermissionsGranted) R.string.devices_open_settings
                                    else R.string.onboarding_iphone_enable
                                ),
                                onPrimary = {
                                    if (btDeniedOnce && !btPermissionsGranted) {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null),
                                            )
                                        )
                                    } else {
                                        startIphonePairing()
                                    }
                                },
                                secondaryLabel = stringResource(
                                    if (selectedPeer != null) R.string.onboarding_continue
                                    else R.string.onboarding_skip
                                ),
                                onSecondary = { step = OnboardingStep.PAIR },
                            )

                            status == IosBridgeStatus.NO_ANCS -> StepPage(
                                icon = PhoneIphoneIcon,
                                title = stringResource(R.string.onboarding_iphone_title),
                                body = stringResource(R.string.ios_no_ancs_help),
                                status = iosStatusText(status),
                                primaryLabel = stringResource(R.string.ios_pairing_retry_sharing),
                                onPrimary = { graph.iosBridgeManager?.retrySharing() },
                                secondaryLabel = stringResource(R.string.onboarding_continue),
                                onSecondary = { step = OnboardingStep.PAIR },
                            )

                            settledPaired -> StepPage(
                                icon = PhoneIphoneIcon,
                                title = stringResource(R.string.onboarding_iphone_title),
                                body = stringResource(R.string.onboarding_iphone_body),
                                status = if (status == IosBridgeStatus.SHARING && pairedName != null) {
                                    "${iosStatusText(status)} · ${stringResource(R.string.ios_connected_device, pairedName)}"
                                } else {
                                    selectedLabel
                                },
                                primaryLabel = stringResource(R.string.onboarding_continue),
                                onPrimary = { step = OnboardingStep.PAIR },
                            )

                            else -> {
                                val currentSetupPeer = setupPeer
                                val canConfirmAppPeer = appSetup && currentSetupPeer != null
                                StepPage(
                                    icon = PhoneIphoneIcon,
                                    title = stringResource(R.string.onboarding_iphone_title),
                                    body = stringResource(
                                        when (status) {
                                            IosBridgeStatus.NEEDS_PAIRING -> R.string.ios_pairing_in_progress_help
                                            IosBridgeStatus.CONNECTING -> R.string.ios_pairing_connecting_help
                                            IosBridgeStatus.ERROR -> R.string.ios_pairing_error_hint
                                            IosBridgeStatus.UNSUPPORTED -> R.string.ios_status_unsupported
                                            else -> R.string.ios_pairing_off
                                        }
                                    ),
                                    status = iosStatusText(status),
                                    extraContent = if (status == IosBridgeStatus.ADVERTISING ||
                                        status == IosBridgeStatus.OFF ||
                                        canConfirmAppPeer
                                    ) {
                                        {
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                ),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(16.dp),
                                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    if (currentSetupPeer == null) {
                                                        IphoneSetupModeSelector(
                                                            appSetup = appSetup,
                                                            onModeChanged = {
                                                                if (bridgeEnabled) {
                                                                    graph.iosBridgeManager?.refreshSelection()
                                                                }
                                                            },
                                                        )
                                                    }
                                                    if (canConfirmAppPeer) {
                                                        Text(
                                                            stringResource(R.string.ios_app_setup_ready),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    } else {
                                                        IphoneSetupSteps(appSetup = appSetup)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    primaryLabel = if (canConfirmAppPeer) {
                                        stringResource(
                                            R.string.ios_app_setup_confirm,
                                            currentSetupPeer.name ?: currentSetupPeer.address,
                                        )
                                    } else {
                                        stringResource(R.string.onboarding_iphone_later)
                                    },
                                    onPrimary = {
                                        if (canConfirmAppPeer) {
                                            if (graph.iosBridgeManager?.confirmSetupPeer(currentSetupPeer.address) == true) {
                                                selectedPeer = IosCompanion.selectedPeer(context)
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    resources.getString(R.string.ios_pairing_bond_lost),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        } else {
                                            step = OnboardingStep.PAIR
                                        }
                                    },
                                    secondaryLabel = if (canConfirmAppPeer) {
                                        stringResource(R.string.onboarding_iphone_later)
                                    } else {
                                        null
                                    },
                                    onSecondary = { step = OnboardingStep.PAIR },
                                )
                            }
                        }
                    }

                    OnboardingStep.PAIR -> StepPage(
                        icon = QrCode2Icon,
                        title = stringResource(R.string.onboarding_pair_title),
                        body = stringResource(R.string.onboarding_pair_body),
                        primaryLabel = stringResource(R.string.onboarding_finish),
                        onPrimary = onFinish,
                    )
                }
            }
            StepDots(
                count = steps.size,
                selected = steps.indexOf(step).coerceAtLeast(0),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun StepPage(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    status: String? = null,
    extraContent: (@Composable () -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (status != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            if (extraContent != null) {
                Spacer(Modifier.height(16.dp))
                extraContent()
                Spacer(Modifier.height(12.dp))
            }
        }
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
            Text(primaryLabel)
        }
        // Fixed-height slot so the primary button doesn't jump between steps with and without a skip.
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (secondaryLabel != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}

@Composable
private fun StepDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}
