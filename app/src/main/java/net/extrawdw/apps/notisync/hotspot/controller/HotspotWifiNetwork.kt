package net.extrawdw.apps.notisync.hotspot.controller

import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings

/** System-owned consent saves/updates the network and triggers a connection attempt on success. */
internal fun SavedHotspot.saveNetworkIntent(context: Context): Intent {
    require(usable) { "Hotspot configuration unavailable" }
    val builder = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hiddenSsid)
    when (securityType) {
        0 -> Unit
        1, 2 -> builder.setWpa2Passphrase(requireNotNull(psk))
        3 -> builder.setWpa3Passphrase(requireNotNull(psk))
        4, 5 -> builder.setIsEnhancedOpen(true)
        else -> error("Unsupported hotspot security")
    }
    val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
    // A third-party activity must never intercept an implicit intent containing a hotspot password.
    val handler = context.packageManager.queryIntentActivities(
        intent, PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).toLong(),
        ),
    ).firstOrNull()?.activityInfo ?: error("System Wi-Fi settings unavailable")
    intent.component = ComponentName(handler.packageName, handler.name)
    return intent.putParcelableArrayListExtra(
        Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(builder.build()),
    )
}
