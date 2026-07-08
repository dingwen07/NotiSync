package net.extrawdw.apps.notisync.ui

import android.Manifest
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ancs.AncsCompanion

/** The runtime Bluetooth permissions the ANCS bridge needs (requested before the CDM pairing flow). */
private val ONBOARDING_BT_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_SCAN,
)

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
 * (grant or skip; granting continues into the choose-apps-to-mirror reminder) → iPhone/ANCS pairing
 * (skippable) → pair-your-other-devices guidance. Finishing lands on the Devices tab, which is the
 * NavHost start destination that composes right after [onFinish].
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    // Strings via LocalResources so they re-read on configuration changes; context is retained for
    // non-resource use (CompanionDeviceManager, Toast, mainExecutor) — same split as IosScreen.
    val resources = LocalResources.current

    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }

    // Re-read both permissions whenever we return to the foreground (back from the system settings
    // pages the grant buttons open) — the same idiom as DevicesDestination.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
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

    // --- iPhone pairing via CompanionDeviceManager, mirroring IosScreen's flow (association + bond +
    // presence + bridge start). Onboarding additionally front-loads the Bluetooth permissions so the
    // bridge the association enables can actually start.
    val cdm = remember { context.getSystemService(CompanionDeviceManager::class.java) }
    var cdmAssociated by remember { mutableStateOf(AncsCompanion.isAssociated(context)) }
    var cdmDeviceName by remember { mutableStateOf(AncsCompanion.associatedDeviceName(context)) }
    val intentSenderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Bond with the picked device here (the reliable path) — association alone doesn't pair the iPhone.
            val picked = AncsCompanion.deviceFromPickerResult(result.data)
            AncsCompanion.bondDevice(picked)
            cdmAssociated = AncsCompanion.isAssociated(context)
            cdmDeviceName = AncsCompanion.associatedDeviceName(context)
            if (cdmAssociated) {
                AncsCompanion.observePresence(context)
                graph.setAncsBridgeEnabled(true) // run the bridge so it connects once the iPhone bonds
            }
            // createBond() raises a pairing request on BOTH ends — remind the user to accept each.
            if (picked != null) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.ios_pairing_accept_prompt),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    fun startAssociation() {
        val mgr = cdm
        if (mgr == null) {
            Log.w("OnboardingScreen", "CompanionDeviceManager unavailable")
            Toast.makeText(
                context,
                resources.getString(R.string.ios_pairing_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            context,
            resources.getString(R.string.ios_pairing_open_bt),
            Toast.LENGTH_LONG
        ).show()
        fun launchPicker(intentSender: IntentSender) {
            runCatching {
                intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }.onFailure { Log.w("OnboardingScreen", "launching CDM picker failed", it) }
        }
        runCatching {
            mgr.associate(
                AncsCompanion.request(),
                context.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        launchPicker(intentSender)
                    }

                    @Suppress("OVERRIDE_DEPRECATION") // pre-33 entry point; delegate to the same picker launch
                    override fun onDeviceFound(intentSender: IntentSender) {
                        launchPicker(intentSender)
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        Log.i("OnboardingScreen", "CDM association created: ${associationInfo.id}")
                        cdmAssociated = true
                        cdmDeviceName = AncsCompanion.associatedDeviceName(context)
                        AncsCompanion.observePresence(context)
                        // Also bond here in case the result Intent didn't carry the device (belt-and-suspenders).
                        AncsCompanion.bondDevice(
                            runCatching { associationInfo.associatedDevice?.bluetoothDevice }.getOrNull()
                        )
                        graph.setAncsBridgeEnabled(true) // run the bridge so it connects once bonded
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.w("OnboardingScreen", "CDM association failed: $error")
                    }
                })
        }.onFailure {
            Log.w("OnboardingScreen", "associate() threw", it)
            Toast.makeText(
                context,
                resources.getString(R.string.ios_pairing_failed, it.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Same denied-once handling as POST_NOTIFICATIONS: a denied Bluetooth set auto-denies re-requests,
    // so "Pair iPhone" would no-op — route to the app's settings page (Permissions → Nearby devices).
    val btPermissionsGranted = remember(refresh) {
        ONBOARDING_BT_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    var btDeniedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(btPermissionsGranted) { if (btPermissionsGranted) btDeniedOnce = false }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh++
        if (result.values.all { it }) startAssociation() else btDeniedOnce = true
    }

    fun startIphonePairing() {
        if (ONBOARDING_BT_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            startAssociation()
        } else {
            btPermissionLauncher.launch(ONBOARDING_BT_PERMISSIONS)
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
                            Icons.AutoMirrored.Outlined.ArrowBack,
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
                        icon = Icons.Outlined.Devices,
                        title = stringResource(R.string.onboarding_welcome_title),
                        body = stringResource(R.string.onboarding_welcome_body),
                        primaryLabel = stringResource(R.string.onboarding_welcome_start),
                        onPrimary = { step = OnboardingStep.NOTIFICATIONS },
                    )

                    OnboardingStep.NOTIFICATIONS -> StepPage(
                        icon = Icons.Outlined.Notifications,
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
                        icon = Icons.Outlined.NotificationsActive,
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
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.onboarding_apps_title),
                        body = stringResource(R.string.onboarding_apps_body),
                        primaryLabel = stringResource(R.string.onboarding_continue),
                        onPrimary = { step = OnboardingStep.IPHONE },
                    )

                    OnboardingStep.IPHONE -> StepPage(
                        icon = Icons.Outlined.PhoneIphone,
                        title = stringResource(R.string.onboarding_iphone_title),
                        body = stringResource(R.string.onboarding_iphone_body),
                        status = when {
                            !cdmAssociated -> null
                            cdmDeviceName != null ->
                                stringResource(R.string.ios_pairing_on, cdmDeviceName ?: "")
                            else -> stringResource(R.string.ios_pairing_on_unknown)
                        },
                        primaryLabel = stringResource(
                            when {
                                cdmAssociated -> R.string.onboarding_continue
                                btDeniedOnce && !btPermissionsGranted -> R.string.devices_open_settings
                                else -> R.string.onboarding_iphone_pair
                            }
                        ),
                        onPrimary = {
                            when {
                                cdmAssociated -> step = OnboardingStep.PAIR
                                btDeniedOnce && !btPermissionsGranted -> context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                                else -> startIphonePairing()
                            }
                        },
                        secondaryLabel = if (cdmAssociated) null else stringResource(R.string.onboarding_skip),
                        onSecondary = { step = OnboardingStep.PAIR },
                    )

                    OnboardingStep.PAIR -> StepPage(
                        icon = Icons.Outlined.QrCode2,
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
