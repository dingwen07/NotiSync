package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import net.extrawdw.apps.notisync.BuildConfig
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.icons.material.outlined.android as AndroidIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.arrow_back as ArrowBackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.code as CodeIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.feedback as FeedbackIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.privacy_tip as PrivacyPolicyIcon

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val uriHandler = LocalUriHandler.current
    val iconSize = 128.dp
    val iconSizePx = with(LocalDensity.current) { iconSize.roundToPx() }
    val appIcon = remember(context, configuration, iconSizePx) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(width = iconSizePx, height = iconSizePx)
            .asImageBitmap()
    }
    val optionColors = ListItemDefaults.colors(containerColor = Color.Transparent)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(ArrowBackIcon, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(appIcon, contentDescription = null, modifier = Modifier.size(iconSize))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                ListItem(
                    colors = optionColors,
                    leadingContent = { Icon(AndroidIcon, contentDescription = null) },
                    trailingContent = {
                        Text(
                            stringResource(
                                R.string.about_app_version_value,
                                BuildConfig.VERSION_CODE,
                                BuildConfig.VERSION_NAME,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.about_app_version)) }
                HorizontalDivider()
                ListItem(
                    colors = optionColors,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/dingwen07/NotiSync")
                    },
                    leadingContent = { Icon(CodeIcon, contentDescription = null) },
                ) { Text(stringResource(R.string.about_source_code)) }
                ListItem(
                    colors = optionColors,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/dingwen07/NotiSync/issues")
                    },
                    leadingContent = { Icon(FeedbackIcon, contentDescription = null) },
                ) { Text(stringResource(R.string.about_feedback)) }
                ListItem(
                    colors = optionColors,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://notisync.apps.extrawdw.net/privacy")
                    },
                    leadingContent = { Icon(PrivacyPolicyIcon, contentDescription = null) },
                ) { Text(stringResource(R.string.about_privacy_policy)) }
            }
        }
    }
}
