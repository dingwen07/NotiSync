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
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import net.extrawdw.apps.notisync.ui.icons.material.outlined.add as AddIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.chevron_right as ChevronRightIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.content_copy as ContentCopyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.content_paste as ContentPasteIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.delete as DeleteIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.edit as EditIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.close as CloseIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.error_outline as ErrorOutlineIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.file_download as FileDownloadIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.fingerprint as FingerprintIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.key as KeyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.send as SendIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.upload_file as UploadFileIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyProviderReviewActivity
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyExportActivity
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeySendActivity
import net.extrawdw.apps.notisync.sshkeyprovider.SshWebAuthnOpenSshIdentityExportActivity
import net.extrawdw.apps.notisync.sshkeyprovider.PreparedSshKeyStorage
import net.extrawdw.apps.notisync.sshkeyprovider.SshRequestListItem
import net.extrawdw.apps.notisync.sshkeyprovider.SshPrivateKeyFileParser
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyStorageResult
import net.extrawdw.apps.notisync.sshkeyprovider.SshWebAuthnCredential
import net.extrawdw.apps.notisync.sshkeyprovider.SshWebAuthnCredentialManager
import net.extrawdw.apps.notisync.sshkeyprovider.SshWebAuthnRecoverySource
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyPreview
import net.extrawdw.apps.notisync.sshkeyprovider.SshHistoryRequestDetail
import net.extrawdw.apps.notisync.sshkeyprovider.SshKnownHost
import net.extrawdw.apps.notisync.sshkeyprovider.SshRememberedAuthorization
import net.extrawdw.apps.notisync.sshkeyprovider.StoredSshProviderRequest
import net.extrawdw.apps.notisync.sshkeyprovider.isActiveRequest
import net.extrawdw.apps.notisync.sshkeyprovider.fingerprint
import net.extrawdw.apps.notisync.sshkeyprovider.toSshHostKeyFingerprint
import net.extrawdw.apps.notisync.sshkeyprovider.sshKeyStorageUserMessage
import net.extrawdw.apps.notisync.sshkeyprovider.eligibleSshKeyTransferPeers
import net.extrawdw.apps.notisync.ui.icons.material.outlined.passkey
import net.extrawdw.notisync.protocol.SshKeyAlgorithm
import net.extrawdw.notisync.protocol.SshKeyDescriptor
import net.extrawdw.notisync.protocol.SshKeyOrigin
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
fun SshKeyProviderScreen(
    initialHistoryRequestId: String? = null,
    onInitialHistoryRequestConsumed: () -> Unit = {},
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val resources = LocalResources.current
    val storageAuthUnavailable = stringResource(R.string.ssh_key_provider_storage_auth_unavailable)
    val defaultImportedKeyName = stringResource(R.string.ssh_key_provider_imported_key_default)
    val clipboardKeyTooLarge = stringResource(R.string.ssh_key_provider_clipboard_key_too_large)
    val scope = rememberCoroutineScope()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val activePeers by graph.trust.activePeers.collectAsStateWithLifecycle()
    val managementState by graph.sshKeyProviderManagement.state.collectAsStateWithLifecycle()
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
    var webAuthnSheetStep by remember { mutableStateOf<WebAuthnSheetStep?>(null) }
    var webAuthnFlowBusy by remember { mutableStateOf(false) }
    var webAuthnRecoveryPayload by remember { mutableStateOf("") }
    var webAuthnFlowError by remember { mutableStateOf<String?>(null) }
    var pendingWebAuthnRecoverySelection by remember {
        mutableStateOf<PendingWebAuthnRecoverySelection?>(null)
    }
    var webAuthnRecoveryActions by remember { mutableStateOf<WebAuthnRecoveryActions?>(null) }
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
                graph.sshKeyProviderManagement.refresh()
            } finally {
                loading = false
            }
        }
    }

    fun finishStorage(result: SshKeyStorageResult) {
        fun committed() {
            graph.sshKeyProviderEngine?.publishInventory()
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
                                        R.string.ssh_key_provider_storage_auth_failed,
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

    fun importWebAuthnRecovery(manualPayload: String?) {
        val activity = context as? Activity
        if (activity == null) {
            webAuthnSheetStep = WebAuthnSheetStep.RECOVERY_PAYLOAD
            webAuthnFlowError = resources.getString(R.string.ssh_key_provider_webauthn_activity_required)
            return
        }
        webAuthnFlowError = null
        webAuthnFlowBusy = true
        scope.launch {
            runCatching {
                val record: net.extrawdw.apps.notisync.sshkeyprovider.SshWebAuthnRecoveryRecord
                val selection: PendingWebAuthnRecoverySelection?
                if (manualPayload != null) {
                    record = withContext(Dispatchers.Default) {
                        SshWebAuthnCredential.decodeRecoveryRecord(manualPayload.trim())
                    }
                    selection = pendingWebAuthnRecoverySelection
                } else {
                    pendingWebAuthnRecoverySelection = null
                    val selectedCredential = try {
                        val prepared = withContext(Dispatchers.Default) { SshWebAuthnCredential.prepareRecovery() }
                        val assertionResponse = SshWebAuthnCredentialManager.get(activity, prepared.requestJson)
                        val credentialId = withContext(Dispatchers.Default) {
                            SshWebAuthnCredential.assertionCredentialId(assertionResponse)
                        }
                        val userHandle = withContext(Dispatchers.Default) {
                            SshWebAuthnCredential.assertionUserHandle(assertionResponse)
                        }
                        PendingWebAuthnRecoverySelection(
                            challenge = prepared.challenge.copyOf(),
                            assertionResponse = assertionResponse,
                            credentialId = credentialId.copyOf(),
                            userHandle = userHandle.copyOf(),
                        ).also { pendingWebAuthnRecoverySelection = it }
                    } catch (failure: Exception) {
                        throw WebAuthnRecoveryFallbackException(
                            resources.getString(R.string.ssh_key_provider_webauthn_authentication_failed),
                            failure,
                        )
                    }
                    selection = selectedCredential
                    record = try {
                        val encodedRecord = SshWebAuthnCredentialManager.getRecoveryRecord(
                            activity,
                            selectedCredential.userHandle,
                        )
                        withContext(Dispatchers.Default) {
                            SshWebAuthnCredential.decodeRecoveryRecord(encodedRecord)
                        }
                    } catch (failure: Exception) {
                        throw WebAuthnRecoveryFallbackException(
                            resources.getString(R.string.ssh_key_provider_webauthn_password_manager_failed),
                            failure,
                        )
                    }
                }
                val recoveredCredential = if (selection == null) {
                    record.registeredCredential()
                } else {
                    val mismatchMessage = resources.getString(R.string.ssh_key_provider_webauthn_recovery_mismatch)
                    require(record.credentialId.contentEquals(selection.credentialId)) { mismatchMessage }
                    require(record.userHandle.contentEquals(selection.userHandle)) { mismatchMessage }
                    val assertion = runCatching {
                        withContext(Dispatchers.Default) {
                            SshWebAuthnCredential.parseAssertion(
                                record.storedCredential(),
                                selection.challenge,
                                selection.assertionResponse,
                                SshWebAuthnCredentialManager.trustedOrigins(activity),
                            )
                        }
                    }.getOrElse { throw IllegalArgumentException(mismatchMessage, it) }
                    record.registeredCredential(assertion)
                }
                withContext(Dispatchers.IO) {
                    graph.sshKeyProviderStore.storeWebAuthnCredential(
                        credential = recoveredCredential,
                        displayName = record.displayName,
                        now = record.createdAt,
                        origin = SshKeyOrigin.WEBAUTHN_RECOVERED,
                    )
                }
            }.onSuccess {
                webAuthnRecoveryPayload = ""
                webAuthnFlowError = null
                pendingWebAuthnRecoverySelection = null
                webAuthnFlowBusy = false
                webAuthnSheetStep = null
                graph.sshKeyProviderEngine?.publishInventory()
                refresh()
            }.onFailure { failure ->
                webAuthnSheetStep = WebAuthnSheetStep.RECOVERY_PAYLOAD
                webAuthnFlowError = failure.message
                    ?: resources.getString(R.string.ssh_key_provider_webauthn_import_failed)
                webAuthnFlowBusy = false
            }
        }
    }

    fun generateWebAuthnKey(name: String) {
        val activity = context as? Activity
        if (activity == null) {
            webAuthnFlowError = resources.getString(R.string.ssh_key_provider_webauthn_activity_required)
            return
        }
        webAuthnFlowError = null
        webAuthnFlowBusy = true
        scope.launch {
            val creation = runCatching {
                val excludedCredentialIds = withContext(Dispatchers.IO) {
                    graph.sshKeyProviderStore.webAuthnCredentialIds()
                }
                val prepared = withContext(Dispatchers.Default) {
                    SshWebAuthnCredential.prepareRegistration(name, excludedCredentialIds)
                }
                val responseJson = SshWebAuthnCredentialManager.create(activity, prepared.requestJson)
                val credential = withContext(Dispatchers.Default) {
                    SshWebAuthnCredential.parseRegistration(
                        prepared,
                        responseJson,
                        SshWebAuthnCredentialManager.trustedOrigins(activity),
                    )
                }
                val createdAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    graph.sshKeyProviderStore.storeWebAuthnCredential(
                        credential,
                        name,
                        createdAt,
                    )
                }
                val recoveryPayload = withContext(Dispatchers.Default) {
                    SshWebAuthnCredential.encodeRecoveryRecord(credential, name, createdAt)
                }
                CreatedWebAuthnKey(credential, recoveryPayload)
            }
            val created = creation.getOrElse { failure ->
                if (failure !is CreateCredentialCancellationException) {
                    webAuthnFlowError = failure.message
                        ?: resources.getString(R.string.ssh_key_provider_webauthn_create_failed)
                }
                webAuthnFlowBusy = false
                return@launch
            }
            runCatching {
                SshWebAuthnCredentialManager.saveRecoveryRecord(
                    activity,
                    created.credential.userHandle,
                    created.recoveryPayload,
                )
            }.onFailure {
                error = resources.getString(R.string.ssh_key_provider_webauthn_recovery_not_saved)
            }
            webAuthnFlowBusy = false
            webAuthnSheetStep = null
            graph.sshKeyProviderEngine?.publishInventory()
            refresh()
        }
    }

    fun openWebAuthnRecoveryActions(key: SshKeyDescriptor) {
        loading = true
        scope.launch {
            runCatching {
                val source = withContext(Dispatchers.IO) {
                    requireNotNull(graph.sshKeyProviderStore.webAuthnRecoverySource(key.providerKeyId)) {
                        "WebAuthn SSH key was not found"
                    }
                }
                val payload = withContext(Dispatchers.Default) {
                    SshWebAuthnCredential.encodeRecoveryRecord(
                        source.credential,
                        source.displayName,
                        source.createdAt,
                    )
                }
                WebAuthnRecoveryActions(source, payload)
            }.onSuccess { webAuthnRecoveryActions = it }
                .onFailure { error = it.message ?: resources.getString(R.string.ssh_key_provider_webauthn_recovery_prepare_failed) }
            loading = false
        }
    }

    fun saveWebAuthnRecovery(actions: WebAuthnRecoveryActions) {
        val activity = context as? Activity
        if (activity == null) {
            error = resources.getString(R.string.ssh_key_provider_webauthn_activity_required)
            return
        }
        webAuthnRecoveryActions = null
        loading = true
        scope.launch {
            runCatching {
                SshWebAuthnCredentialManager.saveRecoveryRecord(
                    activity,
                    actions.source.credential.userHandle,
                    actions.payload,
                )
            }.onSuccess {
                Toast.makeText(
                    context,
                    R.string.ssh_key_provider_webauthn_recovery_saved,
                    Toast.LENGTH_SHORT,
                ).show()
                loading = false
            }.onFailure {
                error = resources.getString(R.string.ssh_key_provider_webauthn_recovery_not_saved)
                loading = false
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
    LaunchedEffect(Unit) { graph.sshKeyProviderManagement.refresh() }

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
        host.hostname?.takeIf(String::isNotBlank)?.let { host.fingerprint() to it }
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
                title = { Text(stringResource(R.string.ssh_key_provider_screen_title)) },
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
                        ready = graph.sshKeyProviderEngine != null,
                        keyCount = keys.size,
                        onGenerate = {
                            error = null
                            generating = true
                        },
                        onWebAuthn = {
                            error = null
                            webAuthnSheetStep = WebAuthnSheetStep.OPTIONS
                            webAuthnFlowBusy = false
                            webAuthnFlowError = null
                            webAuthnRecoveryPayload = ""
                            pendingWebAuthnRecoverySelection = null
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
                        SshKeyProviderErrorCard(message, onDismiss = { error = null })
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
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_key_provider_section_keys)) } }
            if (!showLoading && keys.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_key_provider_no_keys)) } }
            }
            items(keys, key = SshKeyDescriptor::providerKeyId) { key ->
                CenteredSshItem(padded = true) {
                    SshKeyCard(
                        key = key,
                        onClick = { selectedKeyId = key.providerKeyId },
                    )
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_key_provider_section_hosts)) } }
            if (!showLoading && knownHosts.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_key_provider_no_hosts)) } }
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
                item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_key_provider_section_active)) } }
                items(activeRequests, key = StoredSshProviderRequest::requestId) { request ->
                    CenteredSshItem {
                        SshRequestListItem(
                            request = request,
                            requesterName = roster.firstOrNull { it.clientId == request.requesterClientId }?.displayName
                                ?: request.requesterClientId.shortForm(),
                            knownHostname = request.history.destinationHostKeyFingerprint?.let(knownHostnames::get),
                            onClick = {
                                context.startActivity(SshKeyProviderReviewActivity.intent(context, request.requestId))
                            },
                        )
                    }
                }
            }
            item { CenteredSshItem(padded = true) { SectionTitle(stringResource(R.string.ssh_key_provider_section_history)) } }
            if (!showLoading && historyRequests.isEmpty()) {
                item { CenteredSshItem(padded = true) { EmptyCard(stringResource(R.string.ssh_key_provider_no_history)) } }
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
                onExport = when {
                    key.webAuthn?.backupEligible == false -> {
                        {
                            context.startActivity(
                                SshWebAuthnOpenSshIdentityExportActivity.intent(context, key.providerKeyId),
                            )
                        }
                    }
                    key.exportCopy != null -> {
                        {
                            context.startActivity(
                                SshKeyExportActivity.intent(context, key.providerKeyId, key.displayName),
                            )
                        }
                    }
                    else -> null
                },
                onWebAuthnRecovery = if (key.webAuthn != null) {
                    {
                        selectedKeyId = null
                        openWebAuthnRecoveryActions(key)
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
                            if (changed) graph.sshKeyProviderEngine?.publishInventory()
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
        EdgeToEdgeHistoryModalBottomSheet(onDismissRequest = { selectedHistoryRequestId = null }) {
            SshHistoryRequestDetail(
                request = request,
                requesterName = peer?.displayName ?: request.requesterClientId.shortForm(),
                requesterIdentityKeyFingerprint = peer?.identityKeyFingerprint,
                knownHostname = request.history.destinationHostKeyFingerprint?.let(knownHostnames::get),
                contentPadding = historySheetContentPadding(),
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

    webAuthnSheetStep?.let { step ->
        WebAuthnFlowSheet(
            step = step,
            busy = webAuthnFlowBusy,
            payload = webAuthnRecoveryPayload,
            error = webAuthnFlowError,
            onGenerateStep = {
                webAuthnFlowError = null
                webAuthnSheetStep = WebAuthnSheetStep.GENERATE
            },
            onGenerate = ::generateWebAuthnKey,
            onUseExisting = {
                webAuthnSheetStep = WebAuthnSheetStep.USE_EXISTING
                webAuthnRecoveryPayload = ""
                webAuthnFlowError = null
                pendingWebAuthnRecoverySelection = null
                importWebAuthnRecovery(null)
            },
            onPayloadChange = {
                if (it.length <= MAX_WEBAUTHN_RECOVERY_PAYLOAD_CHARS) {
                    webAuthnRecoveryPayload = it
                    webAuthnFlowError = null
                } else {
                    webAuthnFlowError = resources.getString(R.string.ssh_key_provider_webauthn_recovery_too_large)
                }
            },
            onPaste = {
                clipboardText(context)?.let {
                    if (it.length <= MAX_WEBAUTHN_RECOVERY_PAYLOAD_CHARS) {
                        webAuthnRecoveryPayload = it
                        webAuthnFlowError = null
                    } else {
                        webAuthnFlowError = resources.getString(R.string.ssh_key_provider_webauthn_recovery_too_large)
                    }
                }
            },
            onManualImport = { importWebAuthnRecovery(webAuthnRecoveryPayload) },
            onBack = {
                webAuthnSheetStep = WebAuthnSheetStep.OPTIONS
                webAuthnFlowError = null
                pendingWebAuthnRecoverySelection = null
            },
            onDismiss = {
                webAuthnSheetStep = null
                webAuthnFlowError = null
                pendingWebAuthnRecoverySelection = null
            },
        )
    }

    webAuthnRecoveryActions?.let { actions ->
        WebAuthnRecoveryActionsDialog(
            payload = actions.payload,
            onCopy = {
                copyRecoveryPayload(context, actions.payload)
                Toast.makeText(
                    context,
                    R.string.ssh_key_provider_webauthn_recovery_copied,
                    Toast.LENGTH_SHORT,
                ).show()
                webAuthnRecoveryActions = null
            },
            onSave = { saveWebAuthnRecovery(actions) },
            onDismiss = { webAuthnRecoveryActions = null },
        )
    }

    if (privateKeyText != null || pendingImport != null) {
        val pending = pendingImport
        SshKeyImportSheet(
            privateKeyText = privateKeyText,
            encrypted = pending?.encrypted ?: false,
            preview = pending?.preview,
            name = importName,
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
                            R.string.ssh_key_provider_invalid_private_key,
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
                        if (changed) graph.sshKeyProviderEngine?.publishInventory()
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
                            if (changed) graph.sshKeyProviderEngine?.refreshPendingNotifications()
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
            title = { Text(stringResource(R.string.ssh_key_provider_host_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ssh_key_provider_host_delete_body,
                        host.hostname?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.ssh_key_provider_unknown),
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
                                if (changed) graph.sshKeyProviderEngine?.refreshPendingNotifications()
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.ssh_key_provider_host_delete_confirm)) }
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
            title = { Text(stringResource(R.string.ssh_key_provider_remembered_delete_title)) },
            text = {
                Text(stringResource(R.string.ssh_key_provider_remembered_delete_body, peerName))
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
                                if (changed) graph.sshKeyProviderEngine?.publishInventory()
                            }.onFailure { error = it.message ?: it.javaClass.simpleName }
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.ssh_key_provider_remembered_delete_confirm)) }
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
            title = { Text(stringResource(R.string.ssh_key_provider_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (key.webAuthn != null) {
                            R.string.ssh_key_provider_webauthn_delete_body
                        } else {
                            R.string.ssh_key_provider_delete_body
                        },
                        key.displayName,
                    ),
                )
            },
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
                                if (changed) graph.sshKeyProviderEngine?.publishInventory()
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
        Text(
            host.hostname?.takeIf(String::isNotBlank) ?: stringResource(R.string.ssh_key_provider_unknown),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            host.fingerprint(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it },
            label = { Text(stringResource(R.string.ssh_key_provider_host_hostname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDelete) {
                Icon(DeleteIcon, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.ssh_key_provider_host_delete_confirm))
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
    onWebAuthn: () -> Unit,
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
            Text(stringResource(R.string.ssh_key_provider_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) pluralStringResource(
                    R.plurals.ssh_key_provider_ready,
                    keyCount,
                    keyCount,
                )
                else stringResource(R.string.ssh_key_provider_not_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onGenerate, enabled = ready) {
                    Icon(AddIcon, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_key_provider_generate))
                }
                OutlinedButton(onClick = onWebAuthn, enabled = ready) {
                    Icon(imageVector = passkey, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_key_provider_webauthn_action))
                }
                OutlinedButton(onClick = onImport, enabled = ready) {
                    Icon(
                        UploadFileIcon,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_key_provider_import_action))
                }
                OutlinedButton(onClick = onPaste, enabled = ready) {
                    Icon(
                        ContentPasteIcon,
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
private fun SshKeyProviderErrorCard(message: String, onDismiss: () -> Unit) {
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
            Icon(ErrorOutlineIcon, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) {
                Icon(CloseIcon, contentDescription = stringResource(R.string.ssh_key_provider_close))
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
                if (key.webAuthn != null) {
                    Icon(
                        imageVector = passkey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = KeyIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
                    ChevronRightIcon,
                    contentDescription = stringResource(R.string.ssh_key_provider_key_details),
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
            Icon(FingerprintIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    host.hostname?.takeIf(String::isNotBlank) ?: stringResource(R.string.ssh_key_provider_unknown),
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
                Icon(EditIcon, contentDescription = stringResource(R.string.ssh_key_provider_host_set_hostname))
            }
            IconButton(onClick = onDelete) {
                Icon(DeleteIcon, contentDescription = stringResource(R.string.ssh_key_provider_host_delete_confirm))
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
    onWebAuthnRecovery: (() -> Unit)?,
    onSend: (() -> Unit)?,
    onRename: () -> Unit,
    onApprovalPolicyChange: (SshApprovalPolicy) -> Unit,
    onDeleteAuthorization: (SshRememberedAuthorization) -> Unit,
    onDelete: () -> Unit,
) {
    val isWebAuthn = key.webAuthn != null
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
                            FileDownloadIcon,
                            contentDescription = stringResource(
                                if (isWebAuthn) {
                                    R.string.ssh_key_provider_webauthn_export_action
                                } else {
                                    R.string.ssh_key_provider_export
                                },
                            ),
                        )
                    }
                }
                if (onWebAuthnRecovery != null) {
                    IconButton(onClick = onWebAuthnRecovery) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_backup_restore),
                            contentDescription = stringResource(R.string.ssh_key_provider_webauthn_recovery_action),
                        )
                    }
                }
                if (onSend != null) {
                    IconButton(onClick = onSend) {
                        Icon(
                            SendIcon,
                            contentDescription = stringResource(R.string.ssh_key_provider_send),
                        )
                    }
                }
                IconButton(onClick = onRename) {
                    Icon(
                        EditIcon,
                        contentDescription = stringResource(R.string.ssh_key_provider_rename),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        DeleteIcon,
                        contentDescription = stringResource(R.string.action_remove),
                    )
                }
            }
        }
        item {
            SshKeyDetailValue(
                label = stringResource(R.string.ssh_key_provider_fingerprint),
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
                label = stringResource(R.string.ssh_key_provider_key_storage),
                value = key.storageLabel(),
            )
        }
        key.webAuthn?.let { webAuthn ->
            item {
                SshKeyDetailValue(
                    label = stringResource(R.string.ssh_key_provider_webauthn_rp_id),
                    value = webAuthn.rpId,
                    monospace = true,
                )
            }
            item {
                SshKeyDetailValue(
                    label = stringResource(R.string.ssh_key_provider_webauthn_portability),
                    value = stringResource(
                        when {
                            !webAuthn.backupEligible -> R.string.ssh_key_provider_webauthn_authenticator_bound
                            webAuthn.backupState -> R.string.ssh_key_provider_webauthn_backed_up
                            else -> R.string.ssh_key_provider_webauthn_backup_pending
                        },
                    ),
                )
            }
        }
        if (!isWebAuthn) {
            item {
                SshKeyDetailValue(
                    label = stringResource(R.string.ssh_key_provider_export_details),
                    value = key.exportCopy?.let { exportCopy ->
                        stringResource(R.string.ssh_key_provider_export_available) + "\n" +
                            stringResource(
                                R.string.ssh_key_provider_export_copy_protection,
                                stringResource(exportCopy.securityLevel.labelResource()),
                            )
                    } ?: stringResource(R.string.ssh_key_provider_non_exportable),
                )
            }
        }
        item { HorizontalDivider() }
        if (isWebAuthn) {
            item {
                Text(
                    stringResource(R.string.ssh_key_provider_webauthn_always_ask_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                SshKeyApprovalPolicy(
                    key = key,
                    busy = policyChangeBusy,
                    onChange = onApprovalPolicyChange,
                )
            }
            item {
                Text(
                    stringResource(R.string.ssh_key_provider_remembered_authorizations),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (rememberedAuthorizations.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.ssh_key_provider_no_remembered_authorizations),
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
        }
        if (key.operationalKey.userVerificationPolicy == SshUserVerificationPolicy.PER_USE) {
            item {
                Text(
                    stringResource(
                        if (isWebAuthn) {
                            R.string.ssh_key_provider_webauthn_each_use_enabled
                        } else {
                            R.string.ssh_key_provider_biometric_each_use_enabled
                        },
                    ),
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
        Text(stringResource(R.string.ssh_key_provider_approval_policy), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val choices = listOf(
                SshApprovalPolicy.ALWAYS_ASK to stringResource(R.string.ssh_key_provider_approval_always),
                SshApprovalPolicy.ALLOW_REMEMBER to stringResource(R.string.ssh_key_provider_approval_remember),
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
            stringResource(
                when (key.approvalPolicy) {
                    SshApprovalPolicy.ALLOW_REMEMBER -> R.string.ssh_key_provider_approval_help
                    SshApprovalPolicy.ALWAYS_ASK -> R.string.ssh_key_provider_approval_help_always_ask
                },
            ),
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
            Icon(KeyIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                        stringResource(R.string.ssh_key_provider_remembered_peer_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    net.extrawdw.notisync.protocol.SshRememberScope.PEER_HOST_KEY -> {
                        Text(
                            authorization.hostname
                                ?: authorization.hostKeySha256?.toSshHostKeyFingerprint()
                                ?: stringResource(R.string.ssh_key_provider_unavailable),
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
                    DeleteIcon,
                    contentDescription = stringResource(R.string.ssh_key_provider_remembered_delete_confirm),
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
            stringResource(R.string.ssh_key_provider_public_key),
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
                            ContentCopyIcon,
                            contentDescription = stringResource(R.string.ssh_key_provider_copy_public),
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
private fun WebAuthnFlowSheet(
    step: WebAuthnSheetStep,
    busy: Boolean,
    payload: String,
    error: String?,
    onGenerateStep: () -> Unit,
    onGenerate: (String) -> Unit,
    onUseExisting: () -> Unit,
    onPayloadChange: (String) -> Unit,
    onPaste: () -> Unit,
    onManualImport: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultName = stringResource(R.string.ssh_key_provider_webauthn_default_name)
    var name by remember { mutableStateOf(defaultName) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            when (step) {
                WebAuthnSheetStep.OPTIONS -> {
                    Text(
                        text = stringResource(R.string.ssh_key_provider_webauthn_options_title),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    ListItem(
                        supportingContent = { Text(stringResource(R.string.ssh_key_provider_webauthn_generate_help)) },
                        leadingContent = { Icon(AddIcon, contentDescription = null) },
                        trailingContent = { Icon(ChevronRightIcon, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = onGenerateStep),
                    ) { Text(stringResource(R.string.ssh_key_provider_webauthn_generate_action)) }
                    ListItem(
                        supportingContent = { Text(stringResource(R.string.ssh_key_provider_webauthn_use_existing_help)) },
                        leadingContent = {
                            Icon(imageVector = passkey, contentDescription = null)
                        },
                        trailingContent = { Icon(ChevronRightIcon, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = onUseExisting),
                    ) { Text(stringResource(R.string.ssh_key_provider_webauthn_use_existing_action)) }
                }

                WebAuthnSheetStep.GENERATE -> Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_create_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ssh_key_provider_key_name)) },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_create_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onBack, enabled = !busy) {
                            Text(stringResource(R.string.action_back))
                        }
                        Button(
                            onClick = { onGenerate(name.trim()) },
                            enabled = !busy && name.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.ssh_key_provider_webauthn_create_confirm))
                        }
                    }
                }

                WebAuthnSheetStep.USE_EXISTING -> Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_use_existing_action),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_use_existing_progress),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                WebAuthnSheetStep.RECOVERY_PAYLOAD -> Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_import_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.ssh_key_provider_webauthn_import_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = payload,
                        onValueChange = onPayloadChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ssh_key_provider_webauthn_recovery_payload)) },
                        minLines = 4,
                        maxLines = 8,
                        enabled = !busy,
                    )
                    TextButton(onClick = onPaste, enabled = !busy) {
                        Icon(ContentPasteIcon, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_paste))
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onBack, enabled = !busy) {
                            Text(stringResource(R.string.action_back))
                        }
                        Button(
                            onClick = onManualImport,
                            enabled = !busy && payload.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.ssh_key_provider_webauthn_import_payload))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebAuthnRecoveryActionsDialog(
    payload: String,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_key_provider_webauthn_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.ssh_key_provider_webauthn_recovery_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = payload,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ssh_key_provider_webauthn_recovery_payload)) },
                    readOnly = true,
                    minLines = 4,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopy) {
                    Icon(ContentCopyIcon, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ssh_key_provider_webauthn_recovery_copy))
                }
                Button(onClick = onSave) {
                    Text(stringResource(R.string.ssh_key_provider_webauthn_recovery_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: (SshKeyAlgorithm, Int, String, SshKeyStorageSelection) -> Unit,
) {
    var algorithm by remember { mutableStateOf(SshKeyAlgorithm.ECDSA_NISTP256) }
    var rsaKeySizeBits by remember { mutableIntStateOf(DEFAULT_RSA_KEY_SIZE_BITS) }
    val defaultName = stringResource(R.string.ssh_key_provider_generate_default_name)
    var name by remember { mutableStateOf(defaultName) }
    var storage by remember { mutableStateOf(SshKeyStorageSelection()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_key_provider_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_key_provider_key_name)) },
                    singleLine = true,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    REGULAR_SSH_KEY_ALGORITHMS.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = algorithm == candidate,
                            onClick = { algorithm = candidate },
                            shape = SegmentedButtonDefaults.itemShape(index, REGULAR_SSH_KEY_ALGORITHMS.size),
                            label = { Text(candidate.shortDisplayLabel()) },
                        )
                    }
                }
                if (algorithm == SshKeyAlgorithm.SSH_RSA) {
                    Text(stringResource(R.string.ssh_key_provider_rsa_key_size), style = MaterialTheme.typography.labelLarge)
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
                            R.string.ssh_key_provider_generated_device_bound
                        } else {
                            R.string.ssh_key_provider_generated_exportable
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
                Text(stringResource(R.string.ssh_key_provider_generate))
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
        title = { Text(stringResource(R.string.ssh_key_provider_key_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_key_provider_key_name)) },
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
    SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 -> "WebAuthn ECDSA-SK P-256"
}

private fun SshKeyAlgorithm.shortDisplayLabel(): String = when (this) {
    SshKeyAlgorithm.SSH_ED25519 -> "Ed25519"
    SshKeyAlgorithm.SSH_RSA -> "RSA"
    SshKeyAlgorithm.ECDSA_NISTP256 -> "P-256"
    SshKeyAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256 -> "WebAuthn P-256"
}

private const val DEFAULT_RSA_KEY_SIZE_BITS = 3072
private const val MAX_WEBAUTHN_RECOVERY_PAYLOAD_CHARS = 64 * 1024
private val RSA_KEY_SIZE_BITS = listOf(2048, DEFAULT_RSA_KEY_SIZE_BITS, 4096)
private val REGULAR_SSH_KEY_ALGORITHMS = listOf(
    SshKeyAlgorithm.SSH_ED25519,
    SshKeyAlgorithm.SSH_RSA,
    SshKeyAlgorithm.ECDSA_NISTP256,
)

@Composable
private fun SshKeyDescriptor.storageLabel(): String = stringResource(
    when (operationalKey.provider) {
        SshOperationalKeyProvider.ANDROID_KEYSTORE_PRIVATE_KEY ->
            R.string.ssh_key_provider_storage_android_keystore
        SshOperationalKeyProvider.ANDROID_KEYSTORE_AES_WRAPPED ->
            R.string.ssh_key_provider_storage_android_keystore_wrapped
        SshOperationalKeyProvider.CREDENTIAL_MANAGER_WEBAUTHN ->
            R.string.ssh_key_provider_storage_credential_manager_webauthn
        SshOperationalKeyProvider.APPLE_KEYCHAIN,
        SshOperationalKeyProvider.APPLE_AUTHENTICATION_SERVICES_WEBAUTHN,
        -> error("Apple SSH key providers cannot own an Android key")
    },
) + if (operationalKey.securityLevel == SshStorageSecurityLevel.CREDENTIAL_PROVIDER) {
    ""
} else {
    " · " + stringResource(operationalKey.securityLevel.labelResource())
}

private fun SshStorageSecurityLevel.labelResource(): Int = when (this) {
    SshStorageSecurityLevel.STRONGBOX -> R.string.ssh_key_provider_security_strongbox
    SshStorageSecurityLevel.TRUSTED_ENVIRONMENT -> R.string.ssh_key_provider_security_tee
    SshStorageSecurityLevel.CREDENTIAL_PROVIDER ->
        error("Credential-provider storage has no Android Keystore security level label")
    SshStorageSecurityLevel.KEYCHAIN ->
        error("Apple Keychain storage has no Android Keystore security level label")
}

private fun Context.reportSshKeyStorageFailure(
    failure: Throwable,
    @StringRes fallback: Int = R.string.error_unknown,
): String {
    Log.w("SshKeyProviderScreen", "SSH key storage failed", failure)
    return failure.sshKeyStorageUserMessage(this, fallback)
}

private fun copyPublicKey(context: Context, key: SshKeyDescriptor) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(key.displayName, key.authorizedPublicKey()))
}

private fun copyRecoveryPayload(context: Context, payload: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("NotiSync WebAuthn SSH recovery payload", payload))
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
        .setTitle(activity.getString(R.string.ssh_key_provider_storage_auth_title))
        .setSubtitle(activity.getString(R.string.ssh_key_provider_storage_auth_subtitle))
        .setAllowedAuthenticators(prepared.promptAuthenticators)
    if (!allowsDeviceCredential) {
        builder.setNegativeButton(activity.getString(R.string.action_cancel), activity.mainExecutor) { _, _ ->
            if (handled.compareAndSet(false, true)) {
                onCancelled(activity.getString(R.string.ssh_key_provider_storage_auth_cancelled))
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
                            onCancelled(activity.getString(R.string.ssh_key_provider_storage_auth_lost))
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
            onCancelled(failure.message ?: activity.getString(R.string.ssh_key_provider_storage_auth_failed))
        }
    }
}

private data class CreatedWebAuthnKey(
    val credential: net.extrawdw.apps.notisync.sshkeyprovider.RegisteredSshWebAuthnCredential,
    val recoveryPayload: String,
)

private data class WebAuthnRecoveryActions(
    val source: SshWebAuthnRecoverySource,
    val payload: String,
)

private data class PendingWebAuthnRecoverySelection(
    val challenge: ByteArray,
    val assertionResponse: String,
    val credentialId: ByteArray,
    val userHandle: ByteArray,
)

private enum class WebAuthnSheetStep {
    OPTIONS,
    GENERATE,
    USE_EXISTING,
    RECOVERY_PAYLOAD,
}

private class WebAuthnRecoveryFallbackException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private data class PendingSshKeyImport(
    val bytes: ByteArray,
    val encrypted: Boolean,
    val preview: SshKeyPreview?,
    val suggestedName: String,
)
