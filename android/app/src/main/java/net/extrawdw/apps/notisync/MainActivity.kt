package net.extrawdw.apps.notisync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.extrawdw.apps.notisync.ui.ActivityScreen
import net.extrawdw.apps.notisync.ui.AppsScreen
import net.extrawdw.apps.notisync.ui.DevicesScreen
import net.extrawdw.apps.notisync.ui.PairingScreen
import net.extrawdw.apps.notisync.ui.PermissionState
import net.extrawdw.apps.notisync.ui.SettingsScreen
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotiSyncTheme {
                NotiSyncRoot()
            }
        }
    }
}

enum class TopDestination(val label: String, val icon: ImageVector) {
    DEVICES("Devices", Icons.Outlined.Devices),
    APPS("Apps", Icons.Outlined.Apps),
    ACTIVITY("Activity", Icons.Outlined.History),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

private object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
}

@Composable
fun NotiSyncRoot() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable(Routes.HOME) {
            NotiSyncHome(
                onPair = {
                    navController.navigate(Routes.PAIRING) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.PAIRING) {
            PairingScreen(onBack = { navController.navigateUp() })
        }
    }
}

@Composable
private fun NotiSyncHome(onPair: () -> Unit) {
    val context = LocalContext.current
    var current by rememberSaveable { mutableStateOf(TopDestination.DEVICES) }

    // Re-check permissions whenever we return to the foreground (e.g. from system settings).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissions = remember(refresh) { readPermissions(context) }

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    val requestPostNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val openListenerSettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { dest ->
                item(
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    selected = dest == current,
                    onClick = { current = dest },
                )
            }
        },
    ) {
        when (current) {
            TopDestination.DEVICES -> DevicesScreen(
                permissions = permissions,
                onPair = onPair,
                onRequestPostNotifications = requestPostNotifications,
                onOpenListenerSettings = openListenerSettings,
            )
            TopDestination.APPS -> AppsScreen()
            TopDestination.ACTIVITY -> ActivityScreen()
            TopDestination.SETTINGS -> SettingsScreen()
        }
    }
}

private fun readPermissions(context: Context): PermissionState {
    val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val postGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return PermissionState(listenerEnabled = listenerEnabled, postNotificationsGranted = postGranted)
}
