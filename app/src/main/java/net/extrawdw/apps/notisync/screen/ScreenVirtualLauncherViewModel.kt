package net.extrawdw.apps.notisync.screen

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/** Retains the catalog and decoded icons while the virtual display changes configuration. */
internal class ScreenVirtualLauncherViewModel(
    private val loadApps: suspend (Locale) -> List<VirtualLauncherApp>?,
) : ViewModel() {
    private val mutableApps = MutableStateFlow<List<VirtualLauncherApp>?>(null)
    val apps = mutableApps.asStateFlow()
    private var locale: Locale? = null
    private var dirty = true
    private var loading = false

    constructor(application: Application) : this({ locale ->
        withContext(Dispatchers.IO) {
            try {
                loadVirtualLauncherApps(application.packageManager, locale)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ScreenVirtualLauncher", "Could not load launcher apps", error)
                null
            }
        }
    }) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = invalidate()
        }
        ContextCompat.registerReceiver(application, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Keep observing while a launched app is in front or the Activity is being recreated.
        addCloseable { application.unregisterReceiver(receiver) }
        ContextCompat.registerReceiver(application, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun ensureLoaded(locale: Locale) {
        if (this.locale != locale) {
            this.locale = locale
            dirty = true
        }
        refreshIfNeeded()
    }

    internal fun invalidate() {
        dirty = true
        refreshIfNeeded()
    }

    private fun refreshIfNeeded() {
        if (locale == null || !dirty || loading) return
        loading = true
        viewModelScope.launch {
            try {
                do {
                    dirty = false
                    val result = loadApps(checkNotNull(locale))
                    if (result == null) {
                        // Preserve the last good list and retry on the next resume/change.
                        dirty = true
                        break
                    }
                    // A package/language change during a scan requires one more pass.
                    if (!dirty) mutableApps.value = result
                } while (dirty)
            } finally {
                loading = false
            }
        }
    }
}

private fun loadVirtualLauncherApps(pm: PackageManager, locale: Locale): List<VirtualLauncherApp> {
    val collator = Collator.getInstance(locale)
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        PackageManager.ResolveInfoFlags.of(0))
        .filter { it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
        .map { resolved ->
            VirtualLauncherApp(resolved.activityInfo.packageName, resolved.activityInfo.name,
                resolved.loadLabel(pm).toString(),
                runCatching { resolved.loadIcon(pm).toBitmap(64, 64).asImageBitmap() }.getOrNull())
        }.sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 }
            ?: a.packageName.compareTo(b.packageName) }
}
