package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.util.Log
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshagent.SshAgentReviewActivity
import net.extrawdw.apps.notisync.sshagent.SshKeyExportActivity
import net.extrawdw.apps.notisync.sshagent.PreparedSshKeyStorage
import net.extrawdw.apps.notisync.sshagent.SshRequestListItem
import net.extrawdw.apps.notisync.sshagent.SshPrivateKeyFileParser
import net.extrawdw.apps.notisync.sshagent.SshKeyStorageResult
import net.extrawdw.apps.notisync.sshagent.SshKeyPreview
import net.extrawdw.apps.notisync.sshagent.SshHistoryRequestDetail
import net.extrawdw.apps.notisync.sshagent.StoredSshProviderRequest
import net.extrawdw.apps.notisync.sshagent.isActiveRequest
import net.extrawdw.apps.notisync.sshagent.sshKeyStorageUserMessage
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.SshOperationalKeyProvider
import net.extrawdw.notisync.protocol.SshStorageSecurityLevel
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.ssh.core.SshFingerprint
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher

/** In-app SSH key-provider management and durable request history. */
@Composable
fun SshAgentScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val storageAuthUnavailable = stringResource(R.string.ssh_agent_storage_auth_unavailable)
    val scope = rememberCoroutineScope()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val changeVersion by graph.sshKeyProviderStore.changeVersion.collectAsStateWithLifecycle()
    var keys by remember { mutableStateOf<List<SshKeyDescriptor>>(emptyList()) }
    var requests by remember { mutableStateOf<List<StoredSshProviderRequest>>(emptyList()) }
    var selectedKeyId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHistoryRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var pastingKey by remember { mutableStateOf(false) }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var validatingPaste by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingSshKeyImport?>(null) }
    val latestPendingImport by rememberUpdatedState(pendingImport)
    var importError by remember { mutableStateOf<String?>(null) }
    var previewingImport by remember { mutableStateOf(false) }
    var importingKey by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SshKeyDescriptor?>(null) }
    var deleting by remember { mutableStateOf<SshKeyDescriptor?>(null) }
    var pendingStorageAuthentication by remember { mutableStateOf<PreparedSshKeyStorage?>(null) }
    val latestPendingStorageAuthentication by rememberUpdatedState(pendingStorageAuthentication)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBoundedPrivateKey)
                            ?: error("The selected private-key file could not be opened")
                        try {
                            val inspected = SshPrivateKeyFileParser.inspect(bytes)
                            PendingSshKeyImport(bytes, inspected.encrypted, inspected.preview)
                        } catch (failure: Exception) {
                            bytes.fill(0)
                            throw failure
                        }
                    }
                }.onSuccess {
                    importError = null
                    pendingImport = it
                }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                loading = false
            }
        }
    }

    fun refresh() {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    graph.sshKeyProviderStore.snapshot(graph.identity.clientId, null, System.currentTimeMillis()).keys to
                        graph.sshKeyProviderStore.requests()
                }
            }
            result.onSuccess {
                keys = it.first
                requests = it.second
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun finishStorage(result: SshKeyStorageResult) {
        fun committed() {
            graph.sshAgentProviderEngine?.publishInventory()
            refresh()
        }
        when (result) {
            is SshKeyStorageResult.Stored -> committed()
            is SshKeyStorageResult.AuthenticationRequired -> {
                val activity = context as? Activity
                if (activity == null) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.cancelPreparedKeyStorage(result.prepared)
                        }
                        error = storageAuthUnavailable
                        loading = false
                    }
                    return
                }
                pendingStorageAuthentication = result.prepared
                authenticatePreparedStorage(
                    activity,
                    result.prepared,
                    onAuthenticated = { cipher, signature ->
                        if (pendingStorageAuthentication !== result.prepared) return@authenticatePreparedStorage
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    graph.sshKeyProviderStore.completePreparedKeyStorage(
                                        result.prepared,
                                        cipher,
                                        signature,
                                    )
                                }
                            }.onSuccess { next ->
                                pendingStorageAuthentication = null
                                finishStorage(next)
                            }
                                .onFailure {
                                    withContext(Dispatchers.IO) {
                                        graph.sshKeyProviderStore.cancelPreparedKeyStorage(result.prepared)
                                    }
                                    pendingStorageAuthentication = null
                                    error = context.reportSshKeyStorageFailure(
                                        it,
                                        R.string.ssh_agent_storage_auth_failed,
                                    )
                                    loading = false
                                }
                        }
                    },
                    onCancelled = { message ->
                        if (pendingStorageAuthentication !== result.prepared) return@authenticatePreparedStorage
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                graph.sshKeyProviderStore.cancelPreparedKeyStorage(result.prepared)
                            }
                            pendingStorageAuthentication = null
                            error = message
                            loading = false
                        }
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            latestPendingImport?.bytes?.fill(0)
            latestPendingStorageAuthentication?.let { prepared ->
                graph.scope.launch(Dispatchers.IO) {
                    graph.sshKeyProviderStore.cancelPreparedKeyStorage(prepared)
                }
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(changeVersion) { refresh() }

    val selectedKey = selectedKeyId?.let { keyId ->
        keys.firstOrNull { it.providerKeyId == keyId }
    }
    LaunchedEffect(selectedKeyId, selectedKey, loading) {
        if (!loading && selectedKeyId != null && selectedKey == null) {
            selectedKeyId = null
        }
    }
    val selectedHistory = selectedHistoryRequestId?.let { requestId ->
        requests.firstOrNull { it.requestId == requestId && !it.isActiveRequest() }
    }
    LaunchedEffect(selectedHistoryRequestId, selectedHistory, loading) {
        if (!loading && selectedHistoryRequestId != null && selectedHistory == null) {
            selectedHistoryRequestId = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_agent_name)) },
                navigationIcon = { FeatureDrawerNavigationIcon() },
            )
        },
    ) { padding ->
        val active = requests.filter(StoredSshProviderRequest::isActiveRequest)
        val history = requests.filterNot(StoredSshProviderRequest::isActiveRequest)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item {
                CenteredSshItem(padded = true) {
                    ProviderCard(
                        ready = graph.sshAgentProviderEngine != null,
                        keyCount = keys.size,
                        onGenerate = {
                            error = null
                            generating = true
                        },
                        onImport = {
                            error = null
                            // Key files are commonly exposed by document providers with vendor-specific,
                            // extension-derived, or no MIME type. The bounded parser is the authority.
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onPaste = {
                            error = null
                            pasteError = null
                            pastingKey = true
                        },
                    )
                }
            }
            error?.let { message ->
                item {
                    CenteredSshItem(padded = true) {
                        SshAgentErrorCard(message, onDismiss = { error = null })
                    }
                }
            }
            if (loading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_keys)) } }
            if (!loading && keys.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_agent_no_keys)) } }
            }
            items(keys, key = SshKeyDescriptor::providerKeyId) { key ->
                CenteredSshItem(padded = true) {
                    SshKeyCard(
                        key = key,
                        onClick = { selectedKeyId = key.providerKeyId },
                    )
                }
            }
            if (active.isNotEmpty()) {
                item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_active)) } }
                items(active, key = StoredSshProviderRequest::requestId) { request ->
                    CenteredSshItem {
                        SshRequestListItem(
                            request = request,
                            requesterName = roster.firstOrNull { it.clientId == request.requesterClientId }?.displayName
                                ?: request.requesterClientId.shortForm(),
                            onClick = {
                                context.startActivity(SshAgentReviewActivity.intent(context, request.requestId))
                            },
                        )
                    }
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_history)) } }
            if (!loading && history.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_agent_no_history)) } }
            }
            items(history, key = StoredSshProviderRequest::requestId) { request ->
                CenteredSshItem {
                    SshRequestListItem(
                        request = request,
                        requesterName = roster.firstOrNull { it.clientId == request.requesterClientId }?.displayName
                            ?: request.requesterClientId.shortForm(),
                        onClick = {
                            selectedHistoryRequestId = request.requestId
                        },
                    )
                }
            }
        }
    }

    selectedKey?.let { key ->
        ModalBottomSheet(onDismissRequest = { selectedKeyId = null }) {
            SshKeyDetailSheet(
                key = key,
                onCopy = { copyPublicKey(context, key) },
                onExport = if (key.exportCopy != null) {
                    {
                        context.startActivity(
                            SshKeyExportActivity.intent(context, key.providerKeyId, key.displayName),
                        )
                    }
                } else {
                    null
                },
                onRename = {
                    selectedKeyId = null
                    renaming = key
                },
                onDelete = {
                    selectedKeyId = null
                    deleting = key
                },
            )
        }
    }

    selectedHistory?.let { request ->
        val peer = roster.firstOrNull { it.clientId == request.requesterClientId }
        ModalBottomSheet(onDismissRequest = { selectedHistoryRequestId = null }) {
            SshHistoryRequestDetail(
                request = request,
                requesterName = peer?.displayName ?: request.requesterClientId.shortForm(),
                requesterIdentityKeyFingerprint = peer?.identityKeyFingerprint,
                onBack = { selectedHistoryRequestId = null },
            )
        }
    }

    if (generating) {
        GenerateKeyDialog(
            onDismiss = { generating = false },
            onGenerate = { algorithm, rsaKeySizeBits, name, storage ->
                generating = false
                loading = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.generateKey(
                                algorithm = algorithm,
                                displayName = name,
                                now = System.currentTimeMillis(),
                                allowExport = storage.allowExport,
                                exportCopyBackendPolicy = storage.exportCopyBackendPolicy,
                                userVerificationPolicy = storage.userVerificationPolicy,
                                rsaKeySizeBits = rsaKeySizeBits,
                            )
                        }
                    }.onSuccess(::finishStorage)
                        .onFailure {
                            error = context.reportSshKeyStorageFailure(it)
                            loading = false
                        }
                }
            },
        )
    }

    pendingImport?.let { pending ->
        val bytes = pending.bytes
        ImportKeyDialog(
            encrypted = pending.encrypted,
            preview = pending.preview,
            error = importError,
            previewing = previewingImport,
            importing = importingKey,
            onDismiss = {
                if (importingKey || previewingImport) return@ImportKeyDialog
                bytes.fill(0)
                importError = null
                previewingImport = false
                pendingImport = null
            },
            onPreviewInvalidated = {
                if (pendingImport?.bytes === bytes) pendingImport = pendingImport?.copy(preview = null)
            },
            onPreview = { passphrase ->
                previewingImport = true
                importError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.Default) {
                            try {
                                SshPrivateKeyFileParser.preview(bytes, passphrase)
                            } finally {
                                passphrase?.fill('\u0000')
                            }
                        }
                    }
                    previewingImport = false
                    result.onSuccess { preview ->
                        if (pendingImport?.bytes === bytes) {
                            pendingImport = pendingImport?.copy(preview = preview)
                        }
                    }.onFailure { importError = it.message ?: it.javaClass.simpleName }
                }
            },
            onImport = { name, passphrase, storage ->
                importingKey = true
                importError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            try {
                                graph.sshKeyProviderStore.importPrivateKeyFile(
                                    bytes,
                                    passphrase,
                                    name,
                                    System.currentTimeMillis(),
                                    storage.allowExport,
                                    storage.exportCopyBackendPolicy,
                                    storage.userVerificationPolicy,
                                )
                            } finally {
                                passphrase?.fill('\u0000')
                            }
                        }
                    }
                    result.onSuccess {
                        bytes.fill(0)
                        pendingImport = null
                        importingKey = false
                        finishStorage(it)
                    }.onFailure {
                        importingKey = false
                        importError = context.reportSshKeyStorageFailure(
                            it,
                            R.string.ssh_agent_invalid_private_key,
                        )
                    }
                }
            },
        )
    }

    if (pastingKey) {
        PastePrivateKeyDialog(
            error = pasteError,
            validating = validatingPaste,
            onDismiss = {
                if (!validatingPaste) {
                    pasteError = null
                    pastingKey = false
                }
            },
            onContinue = { text ->
                val bytes = text.encodeToByteArray()
                validatingPaste = true
                pasteError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            require(
                                bytes.isNotEmpty() &&
                                    bytes.size <= net.extrawdw.notisync.protocol.SshAgentLimits.MAX_IMPORT_BYTES,
                            ) { "SSH private-key text is outside the 256 KiB limit" }
                            val inspected = SshPrivateKeyFileParser.inspect(bytes)
                            PendingSshKeyImport(bytes, inspected.encrypted, inspected.preview)
                        }
                    }
                    validatingPaste = false
                    result.onSuccess {
                        pasteError = null
                        pastingKey = false
                        importError = null
                        pendingImport = it
                    }.onFailure {
                        bytes.fill(0)
                        pasteError = it.message ?: it.javaClass.simpleName
                    }
                }
            },
        )
    }

    renaming?.let { key ->
        RenameKeyDialog(
            key = key,
            onDismiss = { renaming = null },
            onSave = { name, approvalPolicy ->
                renaming = null
                loading = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.updateKeyMetadata(
                                key.providerKeyId,
                                name,
                                approvalPolicy,
                            )
                        }
                    }.onSuccess { changed ->
                        if (changed) graph.sshAgentProviderEngine?.publishInventory()
                    }.onFailure { error = it.message ?: it.javaClass.simpleName }
                    refresh()
                }
            },
        )
    }

    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ssh_agent_delete_title)) },
            text = { Text(stringResource(R.string.ssh_agent_delete_body, key.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        loading = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    graph.sshKeyProviderStore.deleteKey(key.providerKeyId)
                                }
                            }.onSuccess { changed ->
                                if (changed) graph.sshAgentProviderEngine?.publishInventory()
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    ready: Boolean,
    keyCount: Int,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ssh_agent_provider_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) pluralStringResource(
                    R.plurals.ssh_agent_provider_ready,
                    keyCount,
                    keyCount,
                )
                else stringResource(R.string.ssh_agent_provider_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onGenerate, enabled = ready) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_agent_generate))
                }
                OutlinedButton(onClick = onImport, enabled = ready) {
                    Icon(
                        Icons.Outlined.UploadFile,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_agent_import_action))
                }
                OutlinedButton(onClick = onPaste, enabled = ready) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_paste))
                }
            }
        }
    }
}

@Composable
private fun SshAgentErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.ssh_agent_close))
            }
        }
    }
}

@Composable
private fun SshKeyCard(
    key: SshKeyDescriptor,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        key.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(key.algorithmDisplayLabel(), style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = stringResource(R.string.ssh_agent_key_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                SshFingerprint.sha256(key.publicKeyBlob),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                key.authorizedPublicKey(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SshKeyDetailSheet(
    key: SshKeyDescriptor,
    onCopy: () -> Unit,
    onExport: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        key.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        key.algorithmDisplayLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onExport != null) {
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.ssh_agent_export),
                        )
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.ssh_agent_rename),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                    )
                }
            }
        }
        item {
            SshKeyDetailValue(
                label = stringResource(R.string.ssh_agent_fingerprint),
                value = SshFingerprint.sha256(key.publicKeyBlob),
                monospace = true,
            )
        }
        item {
            SshPublicKeyCodeBlock(
                value = key.authorizedPublicKey(),
                onCopy = onCopy,
            )
        }
        item { HorizontalDivider() }
        item {
            SshKeyDetailValue(
                label = stringResource(R.string.ssh_agent_key_storage),
                value = key.storageLabel(),
            )
        }
        item {
            SshKeyDetailValue(
                label = stringResource(R.string.ssh_agent_export_details),
                value = key.exportCopy?.let { exportCopy ->
                    stringResource(R.string.ssh_agent_export_available) + "\n" +
                        stringResource(
                            R.string.ssh_agent_export_copy_protection,
                            stringResource(exportCopy.securityLevel.labelResource()),
                        )
                } ?: stringResource(R.string.ssh_agent_non_exportable),
            )
        }
        if (key.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            item {
                Text(
                    stringResource(R.string.ssh_agent_biometric_each_use_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SshPublicKeyCodeBlock(
    value: String,
    onCopy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.ssh_agent_public_key),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.ssh_agent_copy_public),
                        )
                    }
                }
                SelectionContainer(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SshKeyDetailValue(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (SshKeyAlgorithm, Int, String, SshKeyStorageSelection) -> Unit,
) {
    var algorithm by remember { mutableStateOf(SshKeyAlgorithm.ECDSA_NISTP256) }
    var rsaKeySizeBits by remember { mutableIntStateOf(DEFAULT_RSA_KEY_SIZE_BITS) }
    var name by remember { mutableStateOf("NotiSync SSH key") }
    var storage by remember { mutableStateOf(SshKeyStorageSelection()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_agent_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_agent_key_name)) },
                    singleLine = true,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SshKeyAlgorithm.entries.forEachIndexed { index, candidate ->
                        SegmentedButton(
                        selected = algorithm == candidate,
                        onClick = { algorithm = candidate },
                            shape = SegmentedButtonDefaults.itemShape(index, SshKeyAlgorithm.entries.size),
                            label = { Text(candidate.shortDisplayLabel()) },
                        )
                    }
                }
                if (algorithm == SshKeyAlgorithm.SSH_RSA) {
                    Text(stringResource(R.string.ssh_agent_rsa_key_size), style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        RSA_KEY_SIZE_BITS.forEachIndexed { index, candidate ->
                            SegmentedButton(
                                selected = rsaKeySizeBits == candidate,
                                onClick = { rsaKeySizeBits = candidate },
                                shape = SegmentedButtonDefaults.itemShape(index, RSA_KEY_SIZE_BITS.size),
                                label = { Text(candidate.toString()) },
                            )
                        }
                    }
                }
                SshKeyStorageOptions(storage, { storage = it })
                Text(
                    stringResource(
                        if (!storage.allowExport) {
                            R.string.ssh_agent_generated_device_bound
                        } else {
                            R.string.ssh_agent_generated_exportable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(algorithm, rsaKeySizeBits, name.trim(), storage) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.ssh_agent_generate))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RenameKeyDialog(
    key: SshKeyDescriptor,
    onDismiss: () -> Unit,
    onSave: (String, SshApprovalPolicy) -> Unit,
) {
    var name by remember(key.providerKeyId) { mutableStateOf(key.displayName) }
    var approvalPolicy by remember(key.providerKeyId) { mutableStateOf(key.approvalPolicy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_agent_key_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_agent_key_name)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.ssh_agent_approval_policy), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val choices = listOf(
                        SshApprovalPolicy.ALWAYS_ASK to stringResource(R.string.ssh_agent_approval_always),
                        SshApprovalPolicy.ALLOW_REMEMBER to stringResource(R.string.ssh_agent_approval_remember),
                    )
                    choices.forEachIndexed { index, (candidate, label) ->
                        SegmentedButton(
                            selected = approvalPolicy == candidate,
                            onClick = { approvalPolicy = candidate },
                            enabled = candidate == SshApprovalPolicy.ALWAYS_ASK ||
                                key.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.NONE,
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.ssh_agent_approval_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), approvalPolicy) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ImportKeyDialog(
    encrypted: Boolean,
    preview: SshKeyPreview?,
    error: String?,
    previewing: Boolean,
    importing: Boolean,
    onDismiss: () -> Unit,
    onPreviewInvalidated: () -> Unit,
    onPreview: (CharArray?) -> Unit,
    onImport: (String, CharArray?, SshKeyStorageSelection) -> Unit,
) {
    var name by remember { mutableStateOf("Imported SSH key") }
    var passphrase by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf(SshKeyStorageSelection(allowExport = true)) }
    AlertDialog(
        onDismissRequest = { if (!previewing && !importing) onDismiss() },
        title = { Text(stringResource(R.string.ssh_agent_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_agent_key_name)) },
                    singleLine = true,
                )
                if (encrypted) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            if (preview != null) onPreviewInvalidated()
                        },
                        label = { Text(stringResource(R.string.ssh_agent_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                preview?.let {
                    SshKeyPreviewCard(
                        name = name,
                        preview = it,
                        showFullPublicKey = true,
                    )
                }
                SshKeyStorageOptions(storage, { storage = it })
                Text(
                    stringResource(R.string.ssh_agent_import_storage_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (previewing || importing) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val secret = if (encrypted) passphrase.toCharArray() else null
                    if (preview == null) {
                        onPreview(secret)
                    } else {
                        passphrase = ""
                        onImport(name.trim(), secret, storage)
                    }
                },
                enabled = !previewing && !importing && name.isNotBlank() && (!encrypted || passphrase.isNotBlank()),
            ) {
                Text(
                    stringResource(
                        if (preview == null) R.string.ssh_agent_review_key else R.string.ssh_agent_import_file,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !previewing && !importing) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun PastePrivateKeyDialog(
    error: String?,
    validating: Boolean,
    onDismiss: () -> Unit,
    onContinue: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboardKeyTooLarge = stringResource(R.string.ssh_agent_clipboard_key_too_large)
    var text by remember { mutableStateOf("") }
    var clipboardError by remember { mutableStateOf<String?>(null) }
    fun pasteClipboard(requirePrivateKey: Boolean) {
        val candidate = clipboardText(context) ?: return
        if (candidate.length > net.extrawdw.notisync.protocol.SshAgentLimits.MAX_IMPORT_BYTES) {
            clipboardError = clipboardKeyTooLarge
        } else if (!requirePrivateKey || looksLikePrivateKey(candidate)) {
            clipboardError = null
            text = candidate
        }
    }
    LaunchedEffect(Unit) { pasteClipboard(requirePrivateKey = true) }
    Dialog(
        onDismissRequest = { if (!validating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ssh_agent_import_text_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        TextButton(onClick = { pasteClipboard(requirePrivateKey = false) }, enabled = !validating) {
                            Text(stringResource(R.string.action_paste))
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            clipboardError = null
                        },
                        label = { Text(stringResource(R.string.ssh_agent_private_key_text)) },
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    (error ?: clipboardError)?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (validating) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss, enabled = !validating) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(
                            onClick = { onContinue(text) },
                            enabled = !validating && text.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.action_continue))
                        }
                    }
                }
            }
        }
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun looksLikePrivateKey(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") ||
        trimmed.startsWith("-----BEGIN PRIVATE KEY-----") ||
        trimmed.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") ||
        trimmed.startsWith("PuTTY-User-Key-File-")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EmptyCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredSshItem(padded: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.fillMaxWidth()
                .widthIn(max = 720.dp)
                .then(if (padded) Modifier.padding(horizontal = 20.dp, vertical = 6.dp) else Modifier),
        ) { content() }
    }
}

private fun SshKeyDescriptor.algorithmDisplayLabel(): String = when (algorithm) {
    SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
    SshKeyAlgorithm.SSH_RSA -> runCatching {
        (SshPublicKeyCodec.decode(publicKeyBlob).publicKey as RSAPublicKey).modulus.bitLength()
    }.getOrNull()?.let { "RSA $it" } ?: "RSA"
    SshKeyAlgorithm.ECDSA_NISTP256 -> "ECDSA P-256"
}

private fun SshKeyAlgorithm.shortDisplayLabel(): String = when (this) {
    SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
    SshKeyAlgorithm.SSH_RSA -> "RSA"
    SshKeyAlgorithm.ECDSA_NISTP256 -> "P-256"
}

private const val DEFAULT_RSA_KEY_SIZE_BITS = 3072
private val RSA_KEY_SIZE_BITS = listOf(2048, DEFAULT_RSA_KEY_SIZE_BITS, 4096)

@Composable
private fun SshKeyDescriptor.storageLabel(): String = stringResource(
    when (operationalKey.provider) {
        SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY ->
            R.string.ssh_agent_storage_android_keystore
        SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED ->
            R.string.ssh_agent_storage_android_keystore_wrapped
    },
) + " · " + stringResource(operationalKey.securityLevel.labelResource())

private fun SshStorageSecurityLevel.labelResource(): Int = when (this) {
    SshStorageSecurityLevel.STRONGBOX -> R.string.ssh_agent_security_strongbox
    SshStorageSecurityLevel.TRUSTED_ENVIRONMENT -> R.string.ssh_agent_security_tee
}

private fun Context.reportSshKeyStorageFailure(
    failure: Throwable,
    @StringRes fallback: Int = R.string.error_unknown,
): String {
    Log.w("SshAgentScreen", "SSH key storage failed", failure)
    return failure.sshKeyStorageUserMessage(this, fallback)
}

private fun copyPublicKey(context: Context, key: SshKeyDescriptor) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(key.displayName, key.authorizedPublicKey()))
}

private fun SshKeyDescriptor.authorizedPublicKey(): String {
    val wireName = SshPublicKeyCodec.decode(publicKeyBlob).wireName
    return "$wireName ${Base64.getEncoder().encodeToString(publicKeyBlob)} $displayName"
}

private fun readBoundedPrivateKey(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    try {
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= net.extrawdw.notisync.protocol.SshAgentLimits.MAX_IMPORT_BYTES) {
                "SSH private-key files are limited to 256 KiB"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        buffer.fill(0)
    }
}

private fun authenticatePreparedStorage(
    activity: Activity,
    prepared: PreparedSshKeyStorage,
    onAuthenticated: (Cipher?, java.security.Signature?) -> Unit,
    onCancelled: (String) -> Unit,
) {
    val handled = AtomicBoolean(false)
    val allowsDeviceCredential = prepared.promptAuthenticators and
        BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0
    val builder = BiometricPrompt.Builder(activity)
        .setTitle(activity.getString(R.string.ssh_agent_storage_auth_title))
        .setSubtitle(activity.getString(R.string.ssh_agent_storage_auth_subtitle))
        .setAllowedAuthenticators(prepared.promptAuthenticators)
    if (!allowsDeviceCredential) {
        builder.setNegativeButton(activity.getString(R.string.action_cancel), activity.mainExecutor) { _, _ ->
            if (handled.compareAndSet(false, true)) {
                onCancelled(activity.getString(R.string.ssh_agent_storage_auth_cancelled))
            }
        }
    }
    val prompt = builder.build()
    val cryptoObject = prepared.signature?.let(BiometricPrompt::CryptoObject)
        ?: BiometricPrompt.CryptoObject(requireNotNull(prepared.cipher))
    try {
        prompt.authenticate(
            cryptoObject,
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject
                    if (authenticated == null) {
                        if (handled.compareAndSet(false, true)) {
                            onCancelled(activity.getString(R.string.ssh_agent_storage_auth_lost))
                        }
                        return
                    }
                    if (handled.compareAndSet(false, true)) {
                        onAuthenticated(authenticated.cipher, authenticated.signature)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (handled.compareAndSet(false, true)) onCancelled(errString.toString())
                }
            },
        )
    } catch (failure: Exception) {
        if (handled.compareAndSet(false, true)) {
            onCancelled(failure.message ?: activity.getString(R.string.ssh_agent_storage_auth_failed))
        }
    }
}

private data class PendingSshKeyImport(
    val bytes: ByteArray,
    val encrypted: Boolean,
    val preview: SshKeyPreview?,
)
