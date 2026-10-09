package net.extrawdw.apps.notisync.screen

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.icons.material.outlined.close
import net.extrawdw.apps.notisync.ui.icons.material.outlined.search
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

internal data class VirtualLauncherApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

internal fun filterVirtualLauncherApps(apps: List<VirtualLauncherApp>, query: String): List<VirtualLauncherApp> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return apps.filter { app -> terms.all { app.label.contains(it, ignoreCase = true) || app.packageName.contains(it, ignoreCase = true) } }
}

/** A normal, non-exported app Activity shown only inside an authorized virtual display. */
class ScreenVirtualLauncherActivity : ComponentActivity() {
    private var ownerToken = ""
    private val appList by viewModels<ScreenVirtualLauncherViewModel> {
        viewModelFactory { initializer { ScreenVirtualLauncherViewModel(application) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownerToken = intent.getStringExtra(EXTRA_OWNER).orEmpty()
        if (!authorized()) { finish(); return }
        setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        setContent { NotiSyncTheme {
            val apps by appList.apps.collectAsStateWithLifecycle()
            if (authorized()) VirtualLauncherScreen(apps, ::launchApp)
            else LaunchedEffect(Unit) { finish() }
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_OWNER) != ownerToken || !authorized()) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!authorized()) { finish(); return }
        appList.ensureLoaded(resources.configuration.locales[0])
    }

    private fun authorized(): Boolean = ScreenVirtualLauncherSessions.isAuthorized(ownerToken, display?.displayId ?: -1)

    private fun launchApp(app: VirtualLauncherApp) {
        if (!authorized()) { finish(); return }
        runCatching {
            val component = ComponentName(app.packageName, app.activityName)
            val info = packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
            check(info.exported && info.enabled && info.applicationInfo.enabled)
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(checkNotNull(display).displayId).toBundle())
        }.onFailure { Toast.makeText(this, R.string.screen_virtual_launcher_open_failed, Toast.LENGTH_SHORT).show() }
    }

    companion object { internal const val EXTRA_OWNER = "virtual_launcher_owner" }
}

@Composable
private fun VirtualLauncherScreen(apps: List<VirtualLauncherApp>?, onLaunch: (VirtualLauncherApp) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps?.let { filterVirtualLauncherApps(it, query) } }
    // Keep the launcher available as the root of this display; Back first clears a search.
    BackHandler { query = "" }
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1280.dp).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.screen_virtual_launcher), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp))
                OutlinedTextField(query, { query = it }, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    singleLine = true, label = { Text(stringResource(R.string.screen_virtual_launcher_search)) },
                    leadingIcon = { Icon(search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(close, contentDescription = stringResource(R.string.screen_virtual_launcher_clear))
                    } })
                when {
                    filtered == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.screen_virtual_launcher_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyVerticalGrid(columns = GridCells.Adaptive(264.dp), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filtered, key = { "${it.packageName}/${it.activityName}" }) { app ->
                            Card(onClick = { onLaunch(app) }) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (app.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.size(48.dp))
                                    else Icon(net.extrawdw.apps.notisync.ui.icons.material.outlined.apps,
                                        contentDescription = null, modifier = Modifier.size(48.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "Launcher phone", widthDp = 393, heightDp = 852)
@Preview(name = "Launcher tablet", widthDp = 1000, heightDp = 700)
@Composable
private fun VirtualLauncherPreview() { NotiSyncTheme {
    VirtualLauncherScreen(listOf(VirtualLauncherApp("com.example.chat", "Chat", "Chat"),
        VirtualLauncherApp("com.example.browser", "Browser", "Browser")), {})
} }
