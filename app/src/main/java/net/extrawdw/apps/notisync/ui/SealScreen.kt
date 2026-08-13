package net.extrawdw.apps.notisync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollmentActivity
import net.extrawdw.apps.notisync.seal.OpenPgpSignReviewActivity
import net.extrawdw.apps.notisync.seal.SigningRequestDetail
import net.extrawdw.apps.notisync.seal.SigningRequestListItem
import net.extrawdw.apps.notisync.seal.StoredOpenPgpRequest
import net.extrawdw.apps.notisync.seal.isSealActive
import net.extrawdw.apps.notisync.seal.opensSealReview

/** Branded setup, active approvals, and durable decision history for NotiSync Seal. */
@Composable
fun SealScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enrollment by graph.openPgpEnrollment.enrollment.collectAsStateWithLifecycle()
    val requests by graph.openPgpSignStore.requests.collectAsStateWithLifecycle()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    var providerAvailable by remember { mutableStateOf(graph.openPgpProvider.isAvailable()) }
    var selectedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedRequestId?.let { id ->
        requests.firstOrNull { it.request.requestId == id }
    }
    val enroll = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        providerAvailable = graph.openPgpProvider.isAvailable()
    }

    LifecycleResumeEffect(Unit) {
        providerAvailable = graph.openPgpProvider.isAvailable()
        onPauseOrDispose { }
    }
    LaunchedEffect(selectedRequestId, selected) {
        if (selectedRequestId != null && selected == null) selectedRequestId = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.seal_name)) },
                navigationIcon = { FeatureDrawerNavigationIcon() },
            )
        },
    ) { padding ->
        SealRequestList(
            requests = requests,
            selectedRequestId = selectedRequestId,
            requesterNameOf = { request ->
                roster.firstOrNull { it.clientId == request.senderClientId }?.displayName
                    ?: request.senderClientId.shortForm()
            },
            enrollmentEnabled = enrollment.enabled,
            enrollmentIdentity = enrollment.displayIdentity,
            enrollmentKeyId = enrollment.primaryKeyId,
            providerAvailable = providerAvailable,
            scaffoldPadding = padding,
            onEnroll = { enroll.launch(OpenPgpEnrollmentActivity.intent(context)) },
            onRemoveEnrollment = { scope.launch { graph.openPgpEnrollment.clear() } },
            onSelect = { stored ->
                if (stored.opensSealReview()) {
                    context.startActivity(
                        OpenPgpSignReviewActivity.intent(context, stored.request.requestId)
                    )
                } else {
                    selectedRequestId = stored.request.requestId
                }
            },
        )
    }

    selected?.takeUnless { it.opensSealReview() }?.let { stored ->
        val peer = roster.firstOrNull { it.clientId == stored.senderClientId }
        val requesterName = peer?.displayName ?: stored.senderClientId.shortForm()
        val requesterFingerprint = peer?.identityKeyFingerprint ?: stored.senderClientId.value
        val identity = enrollment.displayIdentity
            ?.takeIf { enrollment.primaryKeyId == stored.request.primaryKeyId }
            ?: stringResource(R.string.seal_openpgp_identity)
        ModalBottomSheet(onDismissRequest = { selectedRequestId = null }) {
            SigningRequestDetail(
                stored = stored,
                requesterName = requesterName,
                requesterFingerprint = requesterFingerprint,
                signingIdentity = identity,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                showSheetHeader = true,
                onBack = { selectedRequestId = null },
            )
        }
    }
}

@Composable
private fun SealRequestList(
    requests: List<StoredOpenPgpRequest>,
    selectedRequestId: String?,
    requesterNameOf: (StoredOpenPgpRequest) -> String,
    enrollmentEnabled: Boolean,
    enrollmentIdentity: String?,
    enrollmentKeyId: String?,
    providerAvailable: Boolean,
    scaffoldPadding: PaddingValues,
    onEnroll: () -> Unit,
    onRemoveEnrollment: () -> Unit,
    onSelect: (StoredOpenPgpRequest) -> Unit,
) {
    val active = requests.filter(StoredOpenPgpRequest::isSealActive)
    val history = requests.filterNot(StoredOpenPgpRequest::isSealActive)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 96.dp,
        ),
    ) {
        item {
            CenteredSealItem {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        stringResource(R.string.seal_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SealIdentityCard(
                        enabled = enrollmentEnabled,
                        identity = enrollmentIdentity,
                        keyId = enrollmentKeyId,
                        providerAvailable = providerAvailable,
                        onEnroll = onEnroll,
                        onRemove = onRemoveEnrollment,
                    )
                }
            }
        }

        if (active.isNotEmpty()) {
            item { SealSectionHeader(stringResource(R.string.seal_section_active)) }
            items(active, key = { it.request.requestId }) { stored ->
                SigningRequestListItem(
                    stored = stored,
                    requesterName = requesterNameOf(stored),
                    onClick = { onSelect(stored) },
                )
            }
        }

        if (history.isNotEmpty()) {
            item { SealSectionHeader(stringResource(R.string.seal_section_history)) }
            items(history, key = { it.request.requestId }) { stored ->
                SigningRequestListItem(
                    stored = stored,
                    requesterName = requesterNameOf(stored),
                    onClick = { onSelect(stored) },
                )
            }
        }

        if (requests.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.seal_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.seal_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SealIdentityCard(
    enabled: Boolean,
    identity: String?,
    keyId: String?,
    providerAvailable: Boolean,
    onEnroll: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Key, contentDescription = null)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.seal_identity_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when {
                        enabled -> {
                            Text(identity.orEmpty(), maxLines = 2)
                            keyId?.let {
                                Text(
                                    stringResource(R.string.seal_identity_key, "0x$it"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        !providerAvailable -> Text(
                            stringResource(R.string.seal_provider_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            stringResource(R.string.seal_identity_setup),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onEnroll, enabled = providerAvailable) {
                    Text(
                        stringResource(
                            if (enabled) R.string.seal_identity_change else R.string.seal_identity_setup
                        )
                    )
                }
                if (enabled) {
                    OutlinedButton(onClick = onRemove) {
                        Text(stringResource(R.string.seal_identity_remove))
                    }
                }
            }
        }
    }
}

@Composable
private fun SealSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CenteredSealItem(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth().widthIn(max = 720.dp)) { content() }
    }
}
