package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshagent.SshAgentReviewActivity
import net.extrawdw.apps.notisync.sshagent.SshKeyExportActivity
import net.extrawdw.apps.notisync.sshagent.SshKeySendActivity
import net.extrawdw.apps.notisync.sshagent.PreparedSshKeyStorage
import net.extrawdw.apps.notisync.sshagent.SshRequestListItem
import net.extrawdw.apps.notisync.sshagent.SshPrivateKeyFileParser
import net.extrawdw.apps.notisync.sshagent.SshKeyStorageResult
import net.extrawdw.apps.notisync.sshagent.SshKeyPreview
import net.extrawdw.apps.notisync.sshagent.SshHistoryRequestDetail
import net.extrawdw.apps.notisync.sshagent.SshKnownHost
import net.extrawdw.apps.notisync.sshagent.SshRememberedAuthorization
import net.extrawdw.apps.notisync.sshagent.StoredSshProviderRequest
import net.extrawdw.apps.notisync.sshagent.isActiveRequest
import net.extrawdw.apps.notisync.sshagent.fingerprint
import net.extrawdw.apps.notisync.sshagent.toSshHostKeyFingerprint
import net.extrawdw.apps.notisync.sshagent.sshKeyStorageUserMessage
import net.extrawdw.apps.notisync.sshagent.eligibleSshKeyTransferPeers
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
fun SshAgentScreen(
    initialHistoryRequestId: String? = null,
    onInitialHistoryRequestConsumed: () -> Unit = {},
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val storageAuthUnavailable = stringResource(R.string.ssh_agent_storage_auth_unavailable)
    val defaultImportedKeyName = stringResource(R.string.ssh_agent_imported_key_default)
    val clipboardKeyTooLarge = stringResource(R.string.ssh_agent_clipboard_key_too_large)
    val scope = rememberCoroutineScope()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val activePeers by graph.trust.activePeers.collectAsStateWithLifecycle()
    val managementState by graph.sshAgentManagement.state.collectAsStateWithLifecycle()
    val managementSnapshot = managementState.snapshot
    val keys = managementSnapshot?.keys.orEmpty()
    val requests = managementSnapshot?.requests.orEmpty()
    val knownHosts = managementSnapshot?.knownHosts.orEmpty()
    val rememberedAuthorizations = managementSnapshot?.rememberedAuthorizations.orEmpty()
    var selectedKeyId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHistoryRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var privateKeyText by remember { mutableStateOf<String?>(null) }
    var validatingImportSource by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingSshKeyImport?>(null) }
    val latestPendingImport by rememberUpdatedState(pendingImport)
    var importError by remember { mutableStateOf<String?>(null) }
    var previewingImport by remember { mutableStateOf(false) }
    var importingKey by remember { mutableStateOf(false) }
    var importName by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var importStorage by remember { mutableStateOf(SshKeyStorageSelection(allowExport = true)) }
    var renaming by remember { mutableStateOf<SshKeyDescriptor?>(null) }
    var selectedKnownHost by remember { mutableStateOf<SshKnownHost?>(null) }
    var deletingHost by remember { mutableStateOf<SshKnownHost?>(null) }
    var deletingAuthorization by remember { mutableStateOf<SshRememberedAuthorization?>(null) }
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
                            PendingSshKeyImport(
                                bytes = bytes,
                                encrypted = inspected.encrypted,
                                preview = inspected.preview,
                                suggestedName = inspected.comment
                                    ?: privateKeyDisplayName(context, uri)
                                    ?: defaultImportedKeyName,
                            )
                        } catch (failure: Exception) {
                            bytes.fill(0)
                            throw failure
                        }
                    }
                }.onSuccess {
                    importError = null
                    importName = it.suggestedName
                    importPassphrase = ""
                    importStorage = SshKeyStorageSelection(allowExport = true)
                    pendingImport = it
                }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                loading = false
            }
        }
    }

    fun refresh() {
        scope.launch {
            try {
                graph.sshAgentManagement.refresh()
            } finally {
                loading = false
            }
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

    fun pasteClipboardIntoImport(requirePrivateKey: Boolean) {
        val candidate = clipboardText(context) ?: return
        if (candidate.length > net.extrawdw.notisync.protocol.SshAgentLimits.MAX_IMPORT_BYTES) {
            importError = clipboardKeyTooLarge
        } else if (!requirePrivateKey || looksLikePrivateKey(candidate)) {
            importError = null
            privateKeyText = candidate
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

    LaunchedEffect(managementState.errorMessage) {
        managementState.errorMessage?.let { error = it }
    }
    // Normally a version-only no-op; this also retries a failed background refresh when the user returns here.
    LaunchedEffect(Unit) { graph.sshAgentManagement.refresh() }

    val showLoading = loading || (managementSnapshot == null && managementState.errorMessage == null)

    val selectedKey = selectedKeyId?.let { keyId ->
        keys.firstOrNull { it.providerKeyId == keyId }
    }
    LaunchedEffect(selectedKeyId, selectedKey, showLoading) {
        if (!showLoading && selectedKeyId != null && selectedKey == null) {
            selectedKeyId = null
        }
    }
    val selectedHistory = selectedHistoryRequestId?.let { requestId ->
        requests.firstOrNull { it.requestId == requestId && !it.isActiveRequest() }
    }
    val knownHostnames = knownHosts.mapNotNull { host ->
        host.hostname?.let { host.fingerprint() to it }
    }.toMap()
    val transferPeers = eligibleSshKeyTransferPeers(activePeers)
    LaunchedEffect(selectedHistoryRequestId, selectedHistory, showLoading) {
        if (!showLoading && selectedHistoryRequestId != null && selectedHistory == null) {
            selectedHistoryRequestId = null
        }
    }
    LaunchedEffect(initialHistoryRequestId, requests, showLoading) {
        if (initialHistoryRequestId != null && !showLoading) {
            selectedHistoryRequestId = requests.firstOrNull {
                it.requestId == initialHistoryRequestId && !it.isActiveRequest()
            }?.requestId
            onInitialHistoryRequestConsumed()
        }
    }
    val (activeRequests, historyRequests) = remember(requests) {
        requests.partition(StoredSshProviderRequest::isActiveRequest)
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
                            pendingImport?.bytes?.fill(0)
                            pendingImport = null
                            importError = null
                            importName = defaultImportedKeyName
                            importPassphrase = ""
                            importStorage = SshKeyStorageSelection(allowExport = true)
                            privateKeyText = ""
                            pasteClipboardIntoImport(requirePrivateKey = true)
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
            if (showLoading) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_keys)) } }
            if (!showLoading && keys.isEmpty()) {
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
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_hosts)) } }
            if (!showLoading && knownHosts.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_agent_no_hosts)) } }
            }
            items(knownHosts, key = { it.fingerprint() }) { host ->
                CenteredSshItem(padded = true) {
                    SshKnownHostCard(
                        host = host,
                        onEdit = { selectedKnownHost = host },
                        onDelete = { deletingHost = host },
                    )
                }
            }
            if (activeRequests.isNotEmpty()) {
                item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_active)) } }
                items(activeRequests, key = StoredSshProviderRequest::requestId) { request ->
                    CenteredSshItem {
                        SshRequestListItem(
                            request = request,
                            requesterName = roster.firstOrNull { it.clientId == request.requesterClientId }?.displayName
                                ?: request.requesterClientId.shortForm(),
                            knownHostname = request.history.destinationHostKeyFingerprint?.let(knownHostnames::get),
                            onClick = {
                                context.startActivity(SshAgentReviewActivity.intent(context, request.requestId))
                            },
                        )
                    }
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_agent_section_history)) } }
            if (!showLoading && historyRequests.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_agent_no_history)) } }
            }
            items(historyRequests, key = StoredSshProviderRequest::requestId) { request ->
                CenteredSshItem {
                    SshRequestListItem(
                        request = request,
                        requesterName = roster.firstOrNull { it.clientId == request.requesterClientId }?.displayName
                            ?: request.requesterClientId.shortForm(),
                        knownHostname = request.history.destinationHostKeyFingerprint?.let(knownHostnames::get),
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
                rememberedAuthorizations = rememberedAuthorizations.filter {
                    it.providerKeyId == key.providerKeyId
                },
                requesterName = { requester ->
                    roster.firstOrNull { it.clientId == requester }?.displayName ?: requester.shortForm()
                },
                policyChangeBusy = showLoading,
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
                onSend = if (key.exportCopy != null && transferPeers.isNotEmpty()) {
                    {
                        context.startActivity(
                            SshKeySendActivity.intent(context, key.providerKeyId, key.displayName),
                        )
                    }
                } else {
                    null
                },
                onRename = {
                    renaming = key
                },
                onApprovalPolicyChange = { approvalPolicy ->
                    loading = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                graph.sshKeyProviderStore.updateKeyMetadata(
                                    key.providerKeyId,
                                    key.displayName,
                                    approvalPolicy,
                                )
                            }
                        }.onSuccess { changed ->
                            if (changed) graph.sshAgentProviderEngine?.publishInventory()
                        }.onFailure { error = it.message ?: it.javaClass.simpleName }
                        refresh()
                    }
                },
                onDeleteAuthorization = { deletingAuthorization = it },
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
                knownHostname = request.history.destinationHostKeyFingerprint?.let(knownHostnames::get),
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

    if (privateKeyText != null || pendingImport != null) {
        val pending = pendingImport
        SshKeyImportSheet(
            privateKeyText = privateKeyText,
            encrypted = pending?.encrypted ?: false,
            preview = pending?.preview,
            name = importName,
            nameEditable = true,
            passphrase = importPassphrase,
            storage = importStorage,
            error = importError,
            previewing = validatingImportSource || previewingImport,
            importing = importingKey,
            onPrivateKeyTextChange = {
                privateKeyText = it
                importError = null
            },
            onPaste = { pasteClipboardIntoImport(requirePrivateKey = false) },
            onNameChange = { importName = it },
            onPassphraseChange = {
                importPassphrase = it
                if (pending?.preview != null) pendingImport = pending.copy(preview = null)
                importError = null
            },
            onStorageChange = { importStorage = it },
            onContinueClipboard = {
                val text = privateKeyText ?: return@SshKeyImportSheet
                val bytes = text.encodeToByteArray()
                validatingImportSource = true
                importError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            require(
                                bytes.isNotEmpty() &&
                                    bytes.size <= net.extrawdw.notisync.protocol.SshAgentLimits.MAX_IMPORT_BYTES,
                            ) { "SSH private-key text is outside the 256 KiB limit" }
                            val inspected = SshPrivateKeyFileParser.inspect(bytes)
                            PendingSshKeyImport(
                                bytes = bytes,
                                encrypted = inspected.encrypted,
                                preview = inspected.preview,
                                suggestedName = inspected.comment ?: defaultImportedKeyName,
                            )
                        }
                    }
                    validatingImportSource = false
                    result.onSuccess {
                        privateKeyText = null
                        importError = null
                        pendingImport = it
                    }.onFailure {
                        bytes.fill(0)
                        importError = it.message ?: it.javaClass.simpleName
                    }
                }
            },
            onPreview = {
                val bytes = pending?.bytes ?: return@SshKeyImportSheet
                val passphrase = importPassphrase.toCharArray()
                previewingImport = true
                importError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.Default) {
                            try {
                                SshPrivateKeyFileParser.preview(bytes, passphrase)
                            } finally {
                                passphrase.fill('\u0000')
                            }
                        }
                    }
                    previewingImport = false
                    result.onSuccess { preview ->
                        if (pendingImport?.bytes === bytes) {
                            pendingImport = pendingImport?.copy(preview = preview)
                            preview.comment?.let { importName = it }
                        }
                    }.onFailure { importError = it.message ?: it.javaClass.simpleName }
                }
            },
            onImport = {
                val bytes = pending?.bytes ?: return@SshKeyImportSheet
                val passphrase = if (pending.encrypted) importPassphrase.toCharArray() else null
                importingKey = true
                importError = null
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            try {
                                graph.sshKeyProviderStore.importPrivateKeyFile(
                                    bytes,
                                    passphrase,
                                    importName.trim(),
                                    System.currentTimeMillis(),
                                    importStorage.allowExport,
                                    importStorage.exportCopyBackendPolicy,
                                    importStorage.userVerificationPolicy,
                                )
                            } finally {
                                passphrase?.fill('\u0000')
                            }
                        }
                    }
                    result.onSuccess {
                        bytes.fill(0)
                        pendingImport = null
                        importPassphrase = ""
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
            onDismiss = {
                pending?.bytes?.fill(0)
                pendingImport = null
                privateKeyText = null
                importName = ""
                importPassphrase = ""
                importError = null
                previewingImport = false
                validatingImportSource = false
            },
        )
    }

    renaming?.let { key ->
        RenameKeyDialog(
            key = key,
            onDismiss = { renaming = null },
            onSave = { name ->
                renaming = null
                loading = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.updateKeyMetadata(
                                key.providerKeyId,
                                name,
                                key.approvalPolicy,
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

    selectedKnownHost?.let { host ->
        ModalBottomSheet(onDismissRequest = { selectedKnownHost = null }) {
            SshKnownHostDetailSheet(
                host = host,
                onDelete = {
                    selectedKnownHost = null
                    deletingHost = host
                },
                onSaveHostname = { hostname ->
                    selectedKnownHost = null
                    loading = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                graph.sshKeyProviderStore.updateKnownHostHostname(host.hostKeySha256, hostname)
                            }
                        }.onSuccess { changed ->
                            if (changed) graph.sshAgentProviderEngine?.refreshPendingNotifications()
                        }.onFailure { error = it.message ?: it.javaClass.simpleName }
                        refresh()
                    }
                },
            )
        }
    }

    deletingHost?.let { host ->
        AlertDialog(
            onDismissRequest = { deletingHost = null },
            title = { Text(stringResource(R.string.ssh_agent_host_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ssh_agent_host_delete_body,
                        host.hostname ?: host.fingerprint(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingHost = null
                        loading = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    graph.sshKeyProviderStore.deleteKnownHost(host.hostKeySha256)
                                }
                            }.onSuccess { changed ->
                                if (changed) graph.sshAgentProviderEngine?.refreshPendingNotifications()
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.ssh_agent_host_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingHost = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    deletingAuthorization?.let { authorization ->
        val peerName = roster.firstOrNull { it.clientId == authorization.requesterClientId }?.displayName
            ?: authorization.requesterClientId.shortForm()
        AlertDialog(
            onDismissRequest = { deletingAuthorization = null },
            title = { Text(stringResource(R.string.ssh_agent_remembered_delete_title)) },
            text = {
                Text(stringResource(R.string.ssh_agent_remembered_delete_body, peerName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingAuthorization = null
                        loading = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    graph.sshKeyProviderStore.deleteRememberedAuthorization(
                                        authorization.authorizationId,
                                    )
                                }
                            }.onSuccess { changed ->
                                if (changed) graph.sshAgentProviderEngine?.publishInventory()
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.ssh_agent_remembered_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAuthorization = null }) {
                    Text(stringResource(R.string.action_cancel))
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
private fun SshKnownHostDetailSheet(
    host: SshKnownHost,
    onDelete: () -> Unit,
    onSaveHostname: (String) -> Unit,
) {
    var hostname by remember(host.fingerprint()) { mutableStateOf(host.hostname.orEmpty()) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.ssh_agent_host_set_hostname), style = MaterialTheme.typography.titleLarge)
        Text(
            host.fingerprint(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it },
            label = { Text(stringResource(R.string.ssh_agent_host_hostname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ssh_agent_host_hostname_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.ssh_agent_host_delete_confirm))
            }
            Button(onClick = { onSaveHostname(hostname) }) {
                Text(stringResource(R.string.action_save))
            }
        }
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
private fun SshKnownHostCard(
    host: SshKnownHost,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onEdit,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    host.hostname ?: stringResource(R.string.ssh_agent_host_no_hostname),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    host.fingerprint(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.ssh_agent_host_set_hostname))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ssh_agent_host_delete_confirm))
            }
        }
    }
}

@Composable
private fun SshKeyDetailSheet(
    key: SshKeyDescriptor,
    rememberedAuthorizations: List<SshRememberedAuthorization>,
    requesterName: (net.extrawdw.notisync.protocol.ClientId) -> String,
    policyChangeBusy: Boolean,
    onCopy: () -> Unit,
    onExport: (() -> Unit)?,
    onSend: (() -> Unit)?,
    onRename: () -> Unit,
    onApprovalPolicyChange: (SshApprovalPolicy) -> Unit,
    onDeleteAuthorization: (SshRememberedAuthorization) -> Unit,
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
                if (onSend != null) {
                    IconButton(onClick = onSend) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = stringResource(R.string.ssh_agent_send),
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
        item { HorizontalDivider() }
        item {
            SshKeyApprovalPolicy(
                key = key,
                busy = policyChangeBusy,
                onChange = onApprovalPolicyChange,
            )
        }
        item {
            Text(
                stringResource(R.string.ssh_agent_remembered_authorizations),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (rememberedAuthorizations.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.ssh_agent_no_remembered_authorizations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(rememberedAuthorizations, key = SshRememberedAuthorization::authorizationId) { authorization ->
                SshRememberedAuthorizationRow(
                    authorization = authorization,
                    requesterName = requesterName(authorization.requesterClientId),
                    onDelete = { onDeleteAuthorization(authorization) },
                )
            }
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
private fun SshKeyApprovalPolicy(
    key: SshKeyDescriptor,
    busy: Boolean,
    onChange: (SshApprovalPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.ssh_agent_approval_policy), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val choices = listOf(
                SshApprovalPolicy.ALWAYS_ASK to stringResource(R.string.ssh_agent_approval_always),
                SshApprovalPolicy.ALLOW_REMEMBER to stringResource(R.string.ssh_agent_approval_remember),
            )
            choices.forEachIndexed { index, (candidate, label) ->
                SegmentedButton(
                    selected = key.approvalPolicy == candidate,
                    onClick = { if (key.approvalPolicy != candidate) onChange(candidate) },
                    enabled = !busy && (
                        candidate == SshApprovalPolicy.ALWAYS_ASK ||
                            key.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.NONE
                        ),
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
}

@Composable
private fun SshRememberedAuthorizationRow(
    authorization: SshRememberedAuthorization,
    requesterName: String,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(requesterName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.pair_verification_number,
                        authorization.requesterClientId.value,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (authorization.scope) {
                    net.extrawdw.notisync.protocol.SshRememberScope.PEER -> Text(
                        stringResource(R.string.ssh_agent_remembered_peer_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    net.extrawdw.notisync.protocol.SshRememberScope.PEER_HOST_KEY -> {
                        Text(
                            authorization.hostname
                                ?: authorization.hostKeySha256?.toSshHostKeyFingerprint()
                                ?: stringResource(R.string.ssh_agent_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (authorization.hostname == null) FontFamily.Monospace else FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    net.extrawdw.notisync.protocol.SshRememberScope.APPLICATION_PROCESS -> Unit
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.ssh_agent_remembered_delete_confirm),
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
    var name by remember { mutableStateOf("NotiSync SSH Key") }
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
    onSave: (String) -> Unit,
) {
    var name by remember(key.providerKeyId) { mutableStateOf(key.displayName) }
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
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

private fun privateKeyDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0 || cursor.isNull(index)) null else cursor.getString(index).trim().takeIf(String::isNotEmpty)
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
    val suggestedName: String,
)
