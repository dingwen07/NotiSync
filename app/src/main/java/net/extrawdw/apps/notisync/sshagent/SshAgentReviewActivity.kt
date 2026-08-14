package net.extrawdw.apps.notisync.sshagent

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.SshKeyStorageOptions
import net.extrawdw.apps.notisync.ui.SshKeyStorageSelection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshProviderFailureCode
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind

class SshAgentReviewActivity : ComponentActivity() {
    private var requestId = ""
    private var screen by mutableStateOf<SshReviewScreenState>(SshReviewScreenState.Loading)
    private var storage by mutableStateOf(SshKeyStorageSelection())
    private var passphrase by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var pendingImportStorage: Pair<SshAgentProviderEngine, PreparedSshImportStorage>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isEmpty()) return finish()
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        enableTapjackingProtection()
        setContent {
            NotiSyncTheme {
                SshReviewContent(
                    state = screen,
                    storage = storage,
                    passphrase = passphrase,
                    busy = busy,
                    onStorageChange = { storage = it },
                    onPassphraseChange = { passphrase = it },
                    onApprove = ::approve,
                    onReject = ::reject,
                    onRemember = ::chooseRememberScope,
                    onClose = ::finish,
                )
            }
        }
        load(intent.getBooleanExtra(EXTRA_APPROVE, false))
    }

    override fun onDestroy() {
        val pending = pendingImportStorage
        pendingImportStorage = null
        if (pending != null) {
            (application as? NotiSyncApp)?.graphIfReady?.scope?.launch(Dispatchers.IO) {
                pending.first.cancelPreparedImport(pending.second)
            }
        }
        super.onDestroy()
    }

    private fun load(approveFromNotification: Boolean) {
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showError("SSH key provider is not ready")
            val stored = withContext(Dispatchers.IO) { graph.sshKeyProviderStore.find(requestId) }
                ?: return@launch showError("This SSH request is no longer available")
            val rememberScopes = withContext(Dispatchers.IO) {
                graph.sshKeyProviderStore.availableRememberScopes(requestId)
            }
            val encrypted = runCatching {
                withContext(Dispatchers.Default) {
                    stored.importRequest?.takeIf { it.sourceType == SshImportSourceType.PRIVATE_KEY_FILE }
                        ?.fileBytes
                        ?.let(SshPrivateKeyFileParser::inspect)
                        ?: false
                }
            }.getOrElse {
                return@launch showError(it.message ?: "The SSH private key is invalid")
            }
            screen = SshReviewScreenState.Details(stored, rememberScopes, encrypted)
            // Import notifications always land on this choice screen. Signature quick-approve remains available.
            if (approveFromNotification && stored.kind == SshProviderRequestKind.SIGN) approve()
        }
    }

    private fun approve() {
        if (busy) return
        val details = screen as? SshReviewScreenState.Details ?: return
        if (details.encryptedImport && passphrase.isBlank()) return
        busy = true
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showError("SSH key provider is not ready")
            val engine = graph.sshAgentProviderEngine
                ?: return@launch showError("SSH key provider is not ready")
            when (details.request.kind) {
                SshProviderRequestKind.IMPORT -> {
                    val secret = if (details.encryptedImport) passphrase.toCharArray() else null
                    passphrase = ""
                    val result = try {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                engine.approveImport(
                                    requestId,
                                    storage.exportability,
                                    storage.preferStrongBox,
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
                                screen = details.copy(errorMessage = "This SSH import is no longer available")
                            }
                        }
                    }.onFailure {
                        busy = false
                        screen = details.copy(errorMessage = it.message ?: "The SSH private key is invalid")
                    }
                }
                SshProviderRequestKind.SIGN -> {
                    val perUse = withContext(Dispatchers.IO) {
                        graph.sshKeyProviderStore.requiresPerUseUserVerification(requestId)
                    }
                    if (!perUse) {
                        showSignResult(withContext(Dispatchers.IO) { engine.approve(requestId) })
                    } else {
                        val prepared = runCatching {
                            withContext(Dispatchers.IO) { engine.prepareUserVerifiedSignature(requestId) }
                        }.getOrElse {
                            showError(it.message ?: "Unable to prepare biometric signing")
                            return@launch
                        } ?: return@launch showError("This SSH signature request is no longer available")
                        authenticateSignature(engine, prepared)
                    }
                }
            }
        }
    }

    private fun authenticateSignature(engine: SshAgentProviderEngine, prepared: PreparedSshSignature) {
        val handled = AtomicBoolean(false)
        fun fail(code: SshProviderFailureCode) {
            if (!handled.compareAndSet(false, true)) return
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { engine.failUserVerification(requestId, code) }
                finish()
            }
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Authorize SSH signature")
            .setSubtitle("Use the selected device-bound SSH key once")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancel", mainExecutor) { _, _ ->
                fail(SshProviderFailureCode.USER_VERIFICATION_CANCELLED)
            }
            .build()
        val cryptoObject = prepared.signature?.let { BiometricPrompt.CryptoObject(it) }
            ?: BiometricPrompt.CryptoObject(requireNotNull(prepared.keyUnwrap).cipher)
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
                            engine.completeUserVerifiedSignature(prepared, authenticated.signature, authenticated.cipher)
                        }
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
    }

    private fun authenticateImportStorage(
        engine: SshAgentProviderEngine,
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
                screen = details.copy(errorMessage = message)
            }
        }
        BiometricPrompt.Builder(this)
            .setTitle(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_title))
            .setSubtitle(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(getString(net.extrawdw.apps.notisync.R.string.action_cancel), mainExecutor) { _, _ ->
                cancel(getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_cancelled))
            }
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(prepared.cipher),
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val cipher = result.cryptoObject?.cipher
                            ?: return cancel(
                                getString(net.extrawdw.apps.notisync.R.string.ssh_agent_storage_auth_lost),
                        )
                        if (!handled.compareAndSet(false, true)) return
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { engine.completePreparedImport(prepared, cipher) }
                            }.onSuccess { completed ->
                                if (completed) {
                                    pendingImportStorage = null
                                    finish()
                                }
                                else {
                                    withContext(Dispatchers.IO) { engine.cancelPreparedImport(prepared) }
                                    pendingImportStorage = null
                                    busy = false
                                    screen = details.copy(errorMessage = "This SSH import is no longer available")
                                }
                            }.onFailure {
                                withContext(Dispatchers.IO) { engine.cancelPreparedImport(prepared) }
                                pendingImportStorage = null
                                busy = false
                                screen = details.copy(
                                    errorMessage = it.message ?: "Unable to store the SSH private key",
                                )
                            }
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cancel(errString.toString())
                    }
                },
            )
    }

    private fun reject() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                (application as? NotiSyncApp)?.awaitGraphReady()?.sshAgentProviderEngine?.reject(requestId)
            }
            finish()
        }
    }

    private fun chooseRememberScope() {
        val details = screen as? SshReviewScreenState.Details ?: return
        val scopes = details.rememberScopes
        if (scopes.size == 1) return authenticateRemember(scopes.single())
        val choices = buildList {
            if (SshRememberScope.PARENT_PROCESS_SESSION in scopes) {
                add("This parent process session" to SshRememberScope.PARENT_PROCESS_SESSION)
            }
            if (SshRememberScope.PEER in scopes) add("This trusted computer" to SshRememberScope.PEER)
        }
        AlertDialog.Builder(this)
            .setTitle("Remember authorization for")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> authenticateRemember(choices[which].second) }
            .show()
    }

    private fun authenticateRemember(scope: SshRememberScope) {
        val subtitle = if (scope == SshRememberScope.PARENT_PROCESS_SESSION) {
            "Allow this parent process session to use the key without another approval"
        } else {
            "Allow this trusted computer to use the key without another approval"
        }
        BiometricPrompt.Builder(this)
            .setTitle("Authorize remembered SSH access")
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
            .authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        busy = true
                        lifecycleScope.launch {
                            val signResult = withContext(Dispatchers.IO) {
                                (application as? NotiSyncApp)?.awaitGraphReady()?.sshAgentProviderEngine
                                    ?.approveAndRemember(requestId, scope)
                            }
                            showSignResult(signResult)
                        }
                    }
                },
            )
    }

    private fun showError(message: String) {
        busy = false
        screen = SshReviewScreenState.Error(message)
    }

    private fun showSignResult(result: SshSignResult?) {
        when (result?.kind) {
            SshSignResultKind.SIGNED -> finish()
            SshSignResultKind.PROVIDER_FAILURE -> showError(getString(R.string.ssh_agent_sign_failed))
            SshSignResultKind.REJECTED_BY_USER -> finish()
            null -> showError(getString(R.string.ssh_agent_request_unavailable))
        }
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "ssh_agent_request_id"
        private const val EXTRA_APPROVE = "ssh_agent_approve"
        fun intent(context: Context, requestId: String) = Intent(context, SshAgentReviewActivity::class.java)
            .putExtra(EXTRA_REQUEST_ID, requestId)
        fun approveIntent(context: Context, requestId: String) = intent(context, requestId).putExtra(EXTRA_APPROVE, true)
    }
}

@Composable
private fun SshReviewContent(
    state: SshReviewScreenState,
    storage: SshKeyStorageSelection,
    passphrase: String,
    busy: Boolean,
    onStorageChange: (SshKeyStorageSelection) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRemember: () -> Unit,
    onClose: () -> Unit,
) {
    val details = state as? SshReviewScreenState.Details
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (details?.request?.kind == SshProviderRequestKind.IMPORT) "SSH key import" else "SSH signature")
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                },
            )
        },
    ) { padding ->
        when (state) {
            SshReviewScreenState.Loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is SshReviewScreenState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onClose) { Text("Close") }
            }
            is SshReviewScreenState.Details -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Requested by ${state.request.requesterClientId.shortForm()}", style = MaterialTheme.typography.titleMedium)
                when (state.request.kind) {
                    SshProviderRequestKind.SIGN -> {
                        val request = requireNotNull(state.request.signRequest)
                        request.processContext.directParent?.let {
                            Text("Process: ${it.displayName ?: it.executablePath} (PID ${it.pid})")
                        }
                        request.destinationContext.hostAliases.firstOrNull()?.value?.let { Text("Destination: $it") }
                        Text("Key: ${MessageDigest.getInstance("SHA-256").digest(request.publicKeyBlob).toHex().take(16)}")
                    }
                    SshProviderRequestKind.IMPORT -> {
                        val request = requireNotNull(state.request.importRequest)
                        Text(request.suggestedName ?: "Imported SSH key")
                        Text(
                            if (request.sourceType == SshImportSourceType.AGENT_IDENTITY) {
                                "Remote ssh-add identity"
                            } else {
                                "Remote private-key file"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.encryptedImport) {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = onPassphraseChange,
                                label = { Text("Passphrase") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SshKeyStorageOptions(storage, onStorageChange)
                        state.errorMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onReject, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Reject")
                    }
                    Button(
                        onClick = onApprove,
                        enabled = !busy && (!state.encryptedImport || passphrase.isNotBlank()),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.request.kind == SshProviderRequestKind.IMPORT) "Import" else "Approve") }
                }
                if (state.rememberScopes.isNotEmpty()) {
                    TextButton(onClick = onRemember, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Approve and remember")
                    }
                }
            }
        }
    }
}

private sealed interface SshReviewScreenState {
    data object Loading : SshReviewScreenState
    data class Error(val message: String) : SshReviewScreenState
    data class Details(
        val request: StoredSshProviderRequest,
        val rememberScopes: Set<SshRememberScope>,
        val encryptedImport: Boolean,
        val errorMessage: String? = null,
    ) : SshReviewScreenState
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
