package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.notification.ACTION_AUTO_OPEN_REQUEST_PAGE
import net.extrawdw.apps.notisync.notification.finishAutoOpenedRequestPage
import net.extrawdw.apps.notisync.notification.isAutomaticRequestPageLaunch
import net.extrawdw.apps.notisync.notification.requestPageObservationState
import net.extrawdw.apps.notisync.notification.retainAutomaticRequestPageOwnership
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.SshKeyImportSheet
import net.extrawdw.apps.notisync.ui.SshKeyStorageSelection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshProviderFailureCode
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind

class SshKeyProviderReviewActivity : ComponentActivity() {
    private var requestId = ""
    private var screen by mutableStateOf<SshReviewScreenState>(SshReviewScreenState.Loading)
    private var storage by mutableStateOf(SshKeyStorageSelection(allowExport = true))
    private var passphrase by mutableStateOf("")
    private var importName by mutableStateOf("")
    private var showImportSheet by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var pendingSignature: Pair<SshKeyProviderEngine, PreparedSshSignature>? = null
    private var pendingWebAuthnSignature: Pair<SshKeyProviderEngine, PreparedSshWebAuthnSignature>? = null
    private var pendingImportStorage: Pair<SshKeyProviderEngine, PreparedSshImportStorage>? = null
    private var renderGeneration = 0L
    private var autoLaunchOwned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = requestIdFrom(intent) ?: return finish()
        val approveAfterLoad = savedInstanceState == null && intent.action == ACTION_APPROVE
        autoLaunchOwned = savedInstanceState?.getBoolean(STATE_AUTO_LAUNCH_OWNED)
            ?: isAutomaticRequestPageLaunch(intent.action)
        intent.action = null
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        enableTapjackingProtection()
        setContent {
            NotiSyncTheme {
                SshReviewContent(
                    state = screen,
                    busy = busy,
                    onApprove = ::beginApproval,
                    onReject = ::reject,
                    onRemember = ::authenticateRemember,
                    onClose = ::finish,
                )
                val details = screen as? SshReviewScreenState.Details
                if (showImportSheet && details?.request?.kind == SshProviderRequestKind.IMPORT) {
                    SshKeyImportSheet(
                        privateKeyText = null,
                        encrypted = details.encryptedImport,
                        preview = details.keyPreview,
                        name = importName,
                        passphrase = passphrase,
                        storage = storage,
                        error = details.errorMessage,
                        previewing = busy,
                        importing = false,
                        onPrivateKeyTextChange = {},
                        onPaste = {},
                        onNameChange = { importName = it },
                        onPassphraseChange = ::changePassphrase,
                        onStorageChange = { storage = it },
                        onContinueClipboard = {},
                        onPreview = ::previewImport,
                        onImport = ::approve,
                        onDismiss = {
                            if (!busy) {
                                showImportSheet = false
                                changePassphrase("")
                            }
                        },
                    )
                }
            }
        }
        observeRequest(approveAfterLoad)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val reopenedRequestId = requestIdFrom(intent) ?: return finish()
        if (reopenedRequestId != requestId) return finish()
        val approveAfterLoad = intent.action == ACTION_APPROVE
        // Once a notification interaction takes ownership of this task, a later automatic
        // re-delivery must not make it auto-dismissible again.
        autoLaunchOwned = retainAutomaticRequestPageOwnership(autoLaunchOwned, intent.action)
        intent.action = null
        setIntent(intent)
        showImportSheet = false
        passphrase = ""
        screen = SshReviewScreenState.Loading
        load(approveAfterLoad)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AUTO_LAUNCH_OWNED, autoLaunchOwned)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        val signature = pendingSignature
        pendingSignature = null
        if (signature != null) {
            (application as? NotiSyncApp)?.graphIfReady?.scope?.launch(Dispatchers.IO) {
                signature.first.cancelPreparedSignature(signature.second)
            }
        }
        val webAuthnSignature = pendingWebAuthnSignature
        pendingWebAuthnSignature = null
        if (webAuthnSignature != null) {
            (application as? NotiSyncApp)?.graphIfReady?.scope?.launch(Dispatchers.IO) {
                webAuthnSignature.first.cancelPreparedWebAuthnSignature(webAuthnSignature.second)
            }
        }
        val pending = pendingImportStorage
        pendingImportStorage = null
        if (pending != null) {
            (application as? NotiSyncApp)?.graphIfReady?.scope?.launch(Dispatchers.IO) {
                pending.first.cancelPreparedImport(pending.second)
            }
        }
        super.onDestroy()
    }

    private fun observeRequest(approveAfterLoad: Boolean) {
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showError(getString(R.string.ssh_agent_not_ready))
            var approveOnFirstLoad = approveAfterLoad
            repeatOnLifecycle(requestPageObservationState(autoLaunchOwned)) {
                graph.sshKeyProviderStore.changeVersion
                    .mapLatest {
                        val generation = nextRenderGeneration()
                        generation to withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.find(requestId)
                        }
                    }
                    .collectLatest { (generation, stored) ->
                        renderRequest(graph, stored, approveOnFirstLoad, generation)
                        approveOnFirstLoad = false
                    }
            }
        }
    }

    private fun load(approveAfterLoad: Boolean = false) {
        lifecycleScope.launch {
            val generation = nextRenderGeneration()
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showErrorIfCurrent(generation, getString(R.string.ssh_agent_not_ready))
            val stored = withContext(Dispatchers.IO) { graph.sshKeyProviderStore.find(requestId) }
            renderRequest(graph, stored, approveAfterLoad, generation)
        }
    }

    private suspend fun renderRequest(
        graph: AppGraph,
        stored: StoredSshProviderRequest?,
        approveAfterLoad: Boolean,
        generation: Long,
    ) {
        stored ?: return showErrorIfCurrent(generation, getString(R.string.ssh_agent_review_unavailable))
        if (generation != renderGeneration) return
        if (stored.shouldCloseAutoOpenedReview(autoLaunchOwned)) {
            busy = false
            showImportSheet = false
            passphrase = ""
            finishAutoOpenedRequestPage()
            return
        }
        val rememberScopes = withContext(Dispatchers.IO) {
            graph.sshKeyProviderStore.availableRememberScopes(requestId)
        }
        val requiresWebAuthn = stored.kind == SshProviderRequestKind.SIGN && withContext(Dispatchers.IO) {
            graph.sshKeyProviderStore.requiresWebAuthnUserVerification(requestId)
        }
        val destinationHostname = stored.signRequest?.destinationContext?.let { destination ->
            withContext(Dispatchers.IO) {
                graph.sshKeyProviderStore.knownHostHostname(destination)
            }
        }
        val importInspection = runCatching {
            withContext(Dispatchers.Default) {
                stored.importRequest?.takeIf { it.sourceType == SshImportSourceType.PRIVATE_KEY_FILE }?.let {
                    SshPrivateKeyFileParser.inspect(requireNotNull(it.fileBytes))
                }
            }
        }.getOrElse {
            return showErrorIfCurrent(
                generation,
                it.message ?: getString(R.string.ssh_agent_invalid_private_key),
            )
        }
        val keyPreview = runCatching {
            withContext(Dispatchers.Default) {
                stored.history.publicKeyBlob?.let(SshImportPreviewParser::preview) ?: when (stored.kind) {
                    SshProviderRequestKind.SIGN -> stored.signRequest?.let {
                        SshImportPreviewParser.preview(it.publicKeyBlob)
                    }
                    SshProviderRequestKind.IMPORT -> stored.importRequest?.let { request ->
                        if (request.sourceType == SshImportSourceType.AGENT_IDENTITY) {
                            SshImportPreviewParser.parse(request, null)
                        } else {
                            importInspection?.preview
                        }
                    }
                }
            }
        }.getOrElse {
            return showErrorIfCurrent(generation, it.message ?: getString(R.string.ssh_agent_invalid_key))
        }
        if (stored.kind == SshProviderRequestKind.IMPORT && keyPreview != null &&
            stored.state == SshProviderRequestState.PENDING_REVIEW
        ) {
            val recorded = runCatching {
                withContext(Dispatchers.IO) {
                    graph.sshKeyProviderStore.recordImportPreview(requestId, keyPreview.publicKeyBlob)
                }
            }.getOrElse {
                return showErrorIfCurrent(generation, it.message ?: getString(R.string.ssh_agent_invalid_key))
            }
            if (!recorded) {
                return showErrorIfCurrent(generation, getString(R.string.ssh_agent_review_unavailable))
            }
        }
        val peer = graph.trust.roster.value.firstOrNull { it.clientId == stored.requesterClientId }
        val currentKeyName = keyPreview?.let { preview ->
            withContext(Dispatchers.IO) {
                graph.sshKeyProviderStore.keyDisplayName(preview.publicKeyBlob)
            }
        }
        val keyName = when (stored.kind) {
            SshProviderRequestKind.SIGN -> currentKeyName ?: stored.history.keyName
            SshProviderRequestKind.IMPORT -> stored.history.keyName ?: currentKeyName
        } ?: stored.history.suggestedName ?: getString(R.string.ssh_agent_imported_key_default)
        if (generation != renderGeneration) return
        if (stored.state != SshProviderRequestState.PENDING_REVIEW) {
            busy = false
            showImportSheet = false
            passphrase = ""
        }
        screen = SshReviewScreenState.Details(
            request = stored,
            rememberScopes = rememberScopes,
            encryptedImport = importInspection?.encrypted ?: stored.history.encryptedImport,
            keyPreview = keyPreview,
            keyName = keyName,
            requesterName = peer?.displayName ?: stored.requesterClientId.shortForm(),
            requesterIdentityKeyFingerprint = peer?.identityKeyFingerprint,
            destinationHostname = destinationHostname,
        )
        importName = keyName
        if (approveAfterLoad && !requiresWebAuthn && stored.state == SshProviderRequestState.PENDING_REVIEW) {
            beginApproval()
        }
    }

    private fun nextRenderGeneration(): Long = ++renderGeneration

    private fun showErrorIfCurrent(generation: Long, message: String) {
        if (generation == renderGeneration) showError(message)
    }

    private fun changePassphrase(value: String) {
        passphrase = value
        val details = screen as? SshReviewScreenState.Details ?: return
        if (details.encryptedImport && details.keyPreview != null) {
            screen = details.copy(keyPreview = null, errorMessage = null)
        }
    }

    private fun previewImport() {
        if (busy) return
        val details = screen as? SshReviewScreenState.Details ?: return
        val request = details.request.importRequest ?: return
        if (details.encryptedImport && passphrase.isBlank()) return
        busy = true
        val secret = if (details.encryptedImport) passphrase.toCharArray() else null
        lifecycleScope.launch {
            val result = try {
                runCatching {
                    withContext(Dispatchers.Default) { SshImportPreviewParser.parse(request, secret) }
                }
            } finally {
                secret?.fill('\u0000')
            }
            busy = false
            result.onSuccess { preview ->
                val recorded = runCatching {
                    withContext(Dispatchers.IO) {
                        (application as? NotiSyncApp)?.awaitGraphReady()?.sshKeyProviderStore
                            ?.recordImportPreview(requestId, preview.publicKeyBlob) == true
                    }
                }.getOrElse {
                    screen = details.copy(errorMessage = it.message ?: getString(R.string.ssh_agent_invalid_key))
                    return@onSuccess
                }
                if (!recorded) {
                    screen = details.copy(errorMessage = getString(R.string.ssh_agent_review_unavailable))
                    return@onSuccess
                }
                screen = details.copy(keyPreview = preview, errorMessage = null)
            }.onFailure { failure ->
                screen = details.copy(
                    errorMessage = failure.message ?: getString(R.string.ssh_agent_invalid_private_key),
                )
            }
        }
    }

    private fun beginApproval() {
        if (busy) return
        val details = screen as? SshReviewScreenState.Details ?: return
        if (details.request.state != SshProviderRequestState.PENDING_REVIEW) return
        if (details.request.kind == SshProviderRequestKind.IMPORT) {
            showImportSheet = true
        } else {
            approve()
        }
    }

    private fun approve() {
        if (busy) return
        val details = screen as? SshReviewScreenState.Details ?: return
        if (details.request.state != SshProviderRequestState.PENDING_REVIEW) return
        if (details.encryptedImport && passphrase.isBlank()) return
        if (details.request.kind == SshProviderRequestKind.IMPORT && importName.isBlank()) return
        if (details.request.kind == SshProviderRequestKind.IMPORT && details.keyPreview == null) {
            previewImport()
            return
        }
        busy = true
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showError(getString(R.string.ssh_agent_not_ready))
            val engine = graph.sshKeyProviderEngine
                ?: return@launch showError(getString(R.string.ssh_agent_not_ready))
            when (details.request.kind) {
                SshProviderRequestKind.IMPORT -> {
                    val secret = if (details.encryptedImport) passphrase.toCharArray() else null
                    passphrase = ""
                    val result = try {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                engine.approveImport(
                                    requestId,
                                    importName,
                                    storage.allowExport,
                                    storage.exportCopyBackendPolicy,
                                    storage.userVerificationPolicy,
                                    secret,
                                )
                            }
                        }
                    } finally {
                        secret?.fill('\u0000')
                    }
                    result.onSuccess { outcome ->
                        when (outcome) {
                            SshImportApprovalOutcome.Completed -> finish()
                            is SshImportApprovalOutcome.AuthenticationRequired ->
                                authenticateImportStorage(engine, outcome.prepared, details)
                            null -> {
                                busy = false
                                screen = details.afterImportFailure(
                                    getString(R.string.ssh_agent_import_unavailable),
                                )
                            }
                        }
                    }.onFailure {
                        busy = false
                        screen = details.afterImportFailure(
                            it.message ?: getString(R.string.ssh_agent_invalid_private_key),
                        )
                    }
                }
                SshProviderRequestKind.SIGN -> {
                    val webAuthn = withContext(Dispatchers.IO) {
                        graph.sshKeyProviderStore.requiresWebAuthnUserVerification(requestId)
                    }
                    if (webAuthn) {
                        val prepared = runCatching {
                            withContext(Dispatchers.IO) { engine.prepareWebAuthnSignature(requestId) }
                        }.getOrElse {
                            showError(it.message ?: getString(R.string.ssh_agent_prepare_auth_failed))
                            return@launch
                        } ?: return@launch showError(getString(R.string.ssh_agent_request_unavailable))
                        authenticateWebAuthnSignature(engine, prepared)
                    } else if (!withContext(Dispatchers.IO) {
                            graph.sshKeyProviderStore.requiresPerUseUserVerification(requestId)
                        }
                    ) {
                        showSignResult(withContext(Dispatchers.IO) { engine.approve(requestId) })
                    } else {
                        val prepared = runCatching {
                            withContext(Dispatchers.IO) { engine.prepareUserVerifiedSignature(requestId) }
                        }.getOrElse {
                            showError(it.message ?: getString(R.string.ssh_agent_prepare_auth_failed))
                            return@launch
                        } ?: return@launch showError(getString(R.string.ssh_agent_request_unavailable))
                        authenticateSignature(engine, prepared)
                    }
                }
            }
        }
    }

    private fun authenticateWebAuthnSignature(
        engine: SshKeyProviderEngine,
        prepared: PreparedSshWebAuthnSignature,
    ) {
        pendingWebAuthnSignature = engine to prepared
        lifecycleScope.launch {
            try {
                val responseJson = SshWebAuthnCredentialManager.get(this@SshKeyProviderReviewActivity, prepared.requestJson)
                val result = withContext(Dispatchers.IO) {
                    engine.completeWebAuthnSignature(prepared, responseJson)
                }
                pendingWebAuthnSignature = null
                showSignResult(result)
            } catch (failure: Exception) {
                val code = when (failure) {
                    is GetCredentialCancellationException -> SshProviderFailureCode.USER_VERIFICATION_CANCELLED
                    is NoCredentialException -> SshProviderFailureCode.KEY_NOT_FOUND
                    else -> SshProviderFailureCode.INTERNAL_FAILURE
                }
                withContext(Dispatchers.IO) {
                    engine.failPreparedWebAuthnSignature(prepared, code)
                }
                pendingWebAuthnSignature = null
                finish()
            }
        }
    }

    private fun authenticateSignature(engine: SshKeyProviderEngine, prepared: PreparedSshSignature) {
        pendingSignature = engine to prepared
        val handled = AtomicBoolean(false)
        fun fail(code: SshProviderFailureCode) {
            if (!handled.compareAndSet(false, true)) return
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { engine.failUserVerification(prepared, code) }
                pendingSignature = null
                finish()
            }
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.ssh_agent_sign_auth_title))
            .setSubtitle(getString(R.string.ssh_agent_sign_auth_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(getString(R.string.action_cancel), mainExecutor) { _, _ ->
                fail(SshProviderFailureCode.USER_VERIFICATION_CANCELLED)
            }
            .build()
        try {
            val cryptoObject = prepared.signature?.let(BiometricPrompt::CryptoObject)
                ?: BiometricPrompt.CryptoObject(requireNotNull(prepared.cipher))
            prompt.authenticate(
                cryptoObject,
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticated = result.cryptoObject
                            ?: return fail(SshProviderFailureCode.USER_VERIFICATION_CANCELLED)
                        if (!handled.compareAndSet(false, true)) return
                        lifecycleScope.launch {
                            val signResult = withContext(Dispatchers.IO) {
                                engine.completeUserVerifiedSignature(
                                    prepared,
                                    authenticated.signature,
                                    authenticated.cipher,
                                )
                            }
                            pendingSignature = null
                            showSignResult(signResult)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        fail(
                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ||
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
                            ) {
                                SshProviderFailureCode.USER_VERIFICATION_LOCKOUT
                            } else {
                                SshProviderFailureCode.USER_VERIFICATION_CANCELLED
                            },
                        )
                    }
                },
            )
        } catch (_: Exception) {
            fail(SshProviderFailureCode.USER_VERIFICATION_CANCELLED)
        }
    }

    private fun authenticateImportStorage(
        engine: SshKeyProviderEngine,
        prepared: PreparedSshImportStorage,
        details: SshReviewScreenState.Details,
    ) {
        pendingImportStorage = engine to prepared
        val handled = AtomicBoolean(false)
        fun cancel(message: String) {
            if (!handled.compareAndSet(false, true)) return
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { engine.cancelPreparedImport(prepared) }
                pendingImportStorage = null
                busy = false
                screen = details.afterImportFailure(message)
            }
        }
        val storage = prepared.keyStorage
        val allowsDeviceCredential = storage.promptAuthenticators and
            BiometricManager.Authenticators.DEVICE_CREDENTIAL != 0
        val builder = BiometricPrompt.Builder(this)
            .setTitle(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_title))
            .setSubtitle(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_subtitle))
            .setAllowedAuthenticators(storage.promptAuthenticators)
        if (!allowsDeviceCredential) {
            builder.setNegativeButton(
                getString(net.extrawdw.apps.notisync.R.string.action_cancel),
                mainExecutor,
            ) { _, _ ->
                cancel(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_cancelled))
            }
        }
        val cryptoObject = storage.signature?.let(BiometricPrompt::CryptoObject)
            ?: BiometricPrompt.CryptoObject(requireNotNull(storage.cipher))
        try {
            builder.build().authenticate(
                cryptoObject,
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticated = result.cryptoObject ?: return cancel(
                            getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_lost),
                        )
                        if (!handled.compareAndSet(false, true)) return
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    engine.completePreparedImport(
                                        prepared,
                                        authenticated.cipher,
                                        authenticated.signature,
                                    )
                                }
                            }.onSuccess { outcome ->
                                when (outcome) {
                                    SshImportApprovalOutcome.Completed -> {
                                        pendingImportStorage = null
                                        finish()
                                    }
                                    is SshImportApprovalOutcome.AuthenticationRequired -> {
                                        pendingImportStorage = null
                                        authenticateImportStorage(engine, outcome.prepared, details)
                                    }
                                    null -> {
                                        withContext(Dispatchers.IO) { engine.cancelPreparedImport(prepared) }
                        pendingImportStorage = null
                        busy = false
                        screen = details.afterImportFailure(
                            getString(R.string.ssh_agent_import_unavailable),
                        )
                                    }
                                }
                            }.onFailure {
                                withContext(Dispatchers.IO) { engine.cancelPreparedImport(prepared) }
                        pendingImportStorage = null
                        busy = false
                        screen = details.afterImportFailure(
                            it.sshKeyStorageUserMessage(
                                this@SshKeyProviderReviewActivity,
                                R.string.ssh_agent_store_private_key_failed,
                            ),
                                )
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cancel(errString.toString())
                    }
                },
            )
        } catch (failure: Exception) {
            cancel(failure.message ?: getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_failed))
        }
    }

    private fun reject() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                (application as? NotiSyncApp)?.awaitGraphReady()?.sshKeyProviderEngine?.reject(requestId)
            }
            finish()
        }
    }

    private fun authenticateRemember(scope: SshRememberScope) {
        if (busy) return
        val details = screen as? SshReviewScreenState.Details ?: return
        if (scope !in details.rememberScopes ||
            scope.authorizationStorage != SshRememberAuthorizationStorage.DISK
        ) return
        val subtitle = when (scope) {
            SshRememberScope.PEER -> getString(
                R.string.ssh_agent_remember_peer_subtitle,
                details.requesterName,
            )
            SshRememberScope.PEER_HOST_KEY -> getString(
                R.string.ssh_agent_remember_peer_host_subtitle,
                details.requesterName,
            )
            SshRememberScope.APPLICATION_PROCESS -> return
        }
        busy = true
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.ssh_agent_remember_auth_title))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                SshAuthenticationPolicy.REMEMBER_PROMPT_AUTHENTICATORS,
            )
            .build()
        try {
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        busy = true
                        lifecycleScope.launch {
                            val signResult = withContext(Dispatchers.IO) {
                                (application as? NotiSyncApp)?.awaitGraphReady()?.sshKeyProviderEngine
                                    ?.approveAndRemember(requestId, scope)
                            }
                            showSignResult(signResult)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        busy = false
                    }
                },
            )
        } catch (_: Exception) {
            showError(getString(R.string.ssh_agent_remember_auth_failed))
        }
    }

    private fun showError(message: String) {
        busy = false
        screen = SshReviewScreenState.Error(message)
    }

    private fun SshReviewScreenState.Details.afterImportFailure(message: String): SshReviewScreenState.Details =
        copy(
            keyPreview = keyPreview.takeUnless { encryptedImport },
            errorMessage = message,
        )

    private fun showSignResult(result: SshSignResult?) {
        when (result?.kind) {
            SshSignResultKind.SIGNED -> finish()
            SshSignResultKind.PROVIDER_FAILURE -> showError(getString(R.string.ssh_agent_sign_failed))
            SshSignResultKind.REJECTED_BY_USER -> finish()
            null -> load()
        }
    }

    companion object {
        private const val ACTION_APPROVE = "net.extrawdw.apps.notisync.action.SSH_AGENT_APPROVE"
        private const val EXTRA_REQUEST_ID = "ssh_agent_request_id"
        private const val STATE_AUTO_LAUNCH_OWNED = "auto_launch_owned"
        private const val REVIEW_SCHEME = "notisync"
        private const val REVIEW_AUTHORITY = "ssh-agent-review"

        fun intent(context: Context, requestId: String) = Intent(context, SshKeyProviderReviewActivity::class.java)
            .setData(reviewUri(requestId))
            .putExtra(EXTRA_REQUEST_ID, requestId)

        fun approveIntent(context: Context, requestId: String): Intent =
            intent(context, requestId).setAction(ACTION_APPROVE)

        fun autoOpenIntent(context: Context, requestId: String): Intent =
            intent(context, requestId).setAction(ACTION_AUTO_OPEN_REQUEST_PAGE)

        private fun requestIdFrom(intent: Intent): String? {
            if (intent.action !in setOf(null, ACTION_APPROVE, ACTION_AUTO_OPEN_REQUEST_PAGE)) return null
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return null
            return requestId.takeIf { intent.data == reviewUri(it) }
        }

        private fun reviewUri(requestId: String): Uri = Uri.Builder()
            .scheme(REVIEW_SCHEME)
            .authority(REVIEW_AUTHORITY)
            .appendPath(requestId)
            .build()
    }
}

internal sealed interface SshReviewScreenState {
    data object Loading : SshReviewScreenState
    data class Error(val message: String) : SshReviewScreenState
    data class Details(
        val request: StoredSshProviderRequest,
        val rememberScopes: Set<SshRememberScope>,
        val encryptedImport: Boolean,
        val keyPreview: SshKeyPreview?,
        val keyName: String,
        val requesterName: String,
        val requesterIdentityKeyFingerprint: String?,
        val destinationHostname: String? = null,
        val errorMessage: String? = null,
    ) : SshReviewScreenState
}
