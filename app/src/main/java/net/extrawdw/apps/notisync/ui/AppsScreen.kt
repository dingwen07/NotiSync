package net.extrawdw.apps.notisync.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ICON_PX = 128

internal data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * Backs the Apps picker. The shown set is the union of:
 *  - launchable apps that can post notifications (scanned once and cached), and
 *  - apps observed actually posting (`AppSelectionRepository.lastSeen`) — the only path by which
 *    non-launcher sources (system apps like Play services / System UI) reach the picker.
 *
 * Scoped to the Apps navigation entry, so with the back stack's saveState/restoreState the expensive
 * PackageManager scan (labels + icon decode) runs once instead of on every return to the tab.
 */
internal class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val selection = (app as NotiSyncApp).graph.appSelection

    // null = still loading the launchable set; a (possibly empty) list = loaded.
    private val launchable = MutableStateFlow<List<InstalledApp>?>(null)
    // Resolved entries for seen packages absent from the launchable set, cached so each resolves once.
    private val seenOnly = MutableStateFlow<Map<String, InstalledApp>>(emptyMap())

    val apps: StateFlow<List<InstalledApp>?> =
        combine(launchable, seenOnly) { launch, seen ->
            launch?.let { base ->
                val have = base.mapTo(HashSet()) { it.packageName }
                base + seen.values.filter { it.packageName !in have }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            launchable.value = withContext(Dispatchers.IO) { loadLaunchableApps(getApplication()) }
        }
        // Surface non-launcher apps seen posting (Play services, System UI, …), resolving each once.
        // Reactive: a newly-active source appears without rescanning the launchable list.
        selection?.let { sel ->
            viewModelScope.launch {
                sel.lastSeen.collect { seen ->
                    val own = getApplication<Application>().packageName
                    val known = launchable.value.orEmpty().mapTo(HashSet()) { it.packageName }
                        .apply { addAll(seenOnly.value.keys); add(own) }
                    val missing = seen.keys.filter { it !in known }
                    if (missing.isEmpty()) return@collect
                    val pm = getApplication<Application>().packageManager
                    val resolved = withContext(Dispatchers.IO) { missing.associateWith { resolveApp(pm, it) } }
                    seenOnly.value = seenOnly.value + resolved
                }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.packageName != context.packageName }
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null } // user-facing apps
        .filter { canPostNotifications(pm, it) }                         // capable of notifying
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = runCatching { pm.getApplicationIcon(info).toImageBitmap(ICON_PX) }.getOrNull(),
            )
        }
        .toList()
}

/**
 * Resolve label + icon for a package by name, for non-launcher apps seen posting notifications.
 * Succeeds when the package is visible — a launcher app, or one of the system apps explicitly named
 * in the manifest `<queries>`. For anything outside that scope PackageManager throws, and we fall
 * back to the bare package name with a placeholder icon. The row still works: the listener keys
 * mirroring on the package name, which is always available regardless of package visibility.
 */
private fun resolveApp(pm: PackageManager, pkg: String): InstalledApp = runCatching {
    val info = pm.getApplicationInfo(pkg, 0)
    InstalledApp(
        packageName = pkg,
        label = pm.getApplicationLabel(info).toString(),
        icon = runCatching { pm.getApplicationIcon(info).toImageBitmap(ICON_PX) }.getOrNull(),
    )
}.getOrElse { InstalledApp(pkg, pkg, null) }

/**
 * Whether [info] could ever post a notification — used to trim the picker. Apps targeting API 33+
 * must declare POST_NOTIFICATIONS to post at all, so a modern-target app that doesn't is never a
 * source and is hidden. Apps targeting <33 post without it, so its absence is uninformative — keep
 * them. If permissions can't be read, keep the app rather than hide it on error.
 */
private fun canPostNotifications(pm: PackageManager, info: ApplicationInfo): Boolean {
    if (info.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) return true
    return runCatching {
        pm.getPackageInfo(info.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
    }.getOrDefault(true)
}

@Composable
fun AppsScreen() {
    val graph = rememberGraph()
    val selection = graph.appSelection!!
    val enabled by selection.enabled.collectAsStateWithLifecycle()
    val lastSeen by selection.lastSeen.collectAsStateWithLifecycle()
    val loaded by viewModel<AppsViewModel>().apps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val loading = loaded == null
    val apps = loaded.orEmpty()

    fun norm(s: String) = s.lowercase(Locale.getDefault())
    val q = norm(query.trim())
    fun matches(a: InstalledApp) = q.isEmpty() || norm(a.label).contains(q) || norm(a.packageName).contains(q)

    val enabledApps = apps.filter { it.packageName in enabled && matches(it) }.sortedBy { norm(it.label) }
    val otherApps = apps.filter { it.packageName !in enabled && matches(it) }
        .sortedWith(compareByDescending<InstalledApp> { lastSeen[it.packageName] ?: 0L }.thenBy { norm(it.label) })
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    NotiScaffold("Apps") { modifier ->
        Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                // A persistent 8.dp gap (outside the scrolling list) so rows never scroll up flush
                // against the search bar. Combined with the sticky header's 8.dp top padding, the
                // resting search-bar-to-header gap is 16.dp — matching the top-app-bar-to-search gap.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                enabledApps.isEmpty() && otherApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isEmpty()) "No apps found" else "No apps match \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (enabledApps.isNotEmpty()) {
                        stickyHeader(key = "header:mirroring") { SectionHeader("Mirroring", enabledApps.size) }
                        items(enabledApps, key = { "on:${it.packageName}" }) { AppRow(it, true, lastSeen[it.packageName], fmt, selection) }
                    }
                    stickyHeader(key = "header:all") { SectionHeader(if (q.isEmpty()) "All apps" else "Other results", otherApps.size) }
                    items(otherApps, key = { "off:${it.packageName}" }) { AppRow(it, false, lastSeen[it.packageName], fmt, selection) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        // Opaque background matching the screen so list rows scroll *under* the pinned header
        // rather than showing through it.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun AppRow(app: InstalledApp, isOn: Boolean, lastSeen: Long?, fmt: SimpleDateFormat, selection: net.extrawdw.apps.notisync.data.AppSelectionRepository) {
    ListItem(
        modifier = Modifier.clickable { selection.setEnabled(app.packageName, !isOn) },
        leadingContent = { AppIcon(app.icon) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (lastSeen != null) "Last notification ${fmt.format(Date(lastSeen))}" else app.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(checked = isOn, onCheckedChange = { on -> selection.setEnabled(app.packageName, on) })
        },
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    (this as? BitmapDrawable)?.bitmap?.let { return Bitmap.createScaledBitmap(it, sizePx, sizePx, true).asImageBitmap() }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
