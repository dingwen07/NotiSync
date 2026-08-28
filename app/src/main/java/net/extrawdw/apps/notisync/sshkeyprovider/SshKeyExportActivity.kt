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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

class SshKeyExportActivity : ComponentActivity() {
    private var providerKeyId = ""
    private var suggestedFileName = "notisync-ssh-key.pem"
    private var screen by mutableStateOf(ExportScreen.SETUP)
    private var status by mutableStateOf("")
    private var failed by mutableStateOf(false)
    private var exportPassword: CharArray? = null
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        if (uri == null) {
            clearExportPassword()
            screen = ExportScreen.SETUP
        } else {
            prepareExport(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerKeyId = intent.getStringExtra(EXTRA_PROVIDER_KEY_ID).orEmpty()
        if (providerKeyId.isEmpty()) return finish()
        suggestedFileName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifEmpty { "notisync-ssh-key" } + ".pem"
        enableEdgeToEdge()
        enableTapjackingProtection()
        setContent {
            NotiSyncTheme {
                when (screen) {
                    ExportScreen.SETUP -> ExportSetup(::chooseDestination)
                    ExportScreen.WORKING -> ExportProgress(status, failed, ::finish)
                }
            }
        }
    }

    override fun onDestroy() {
        clearExportPassword()
        super.onDestroy()
    }

    private fun chooseDestination(password: CharArray?) {
        clearExportPassword()
        exportPassword = password
        failed = false
        status = getString(R.string.ssh_agent_export_choose_location)
        screen = ExportScreen.WORKING
        createDocument.launch(suggestedFileName)
    }

    private fun prepareExport(target: Uri) {
        status = getString(R.string.ssh_agent_export_preparing)
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch fail(getString(R.string.ssh_agent_provider_unavailable))
            val prepared = runCatching {
                withContext(Dispatchers.IO) { graph.sshKeyProviderStore.prepareExport(providerKeyId) }
            }.getOrElse {
                return@launch fail(it.message ?: getString(R.string.ssh_agent_export_prepare_failed))
            } ?: return@launch fail(getString(R.string.ssh_agent_export_not_exportable))
            authenticateAndWrite(graph.sshKeyProviderStore, prepared, target)
        }
    }

    private fun authenticateAndWrite(
        store: SshKeyProviderStore,
        prepared: PreparedSshKeyExport,
        target: Uri,
    ) {
        val handled = AtomicBoolean(false)
        fun cancelPrepared(message: String) {
            store.cancelExport(prepared)
            fail(message)
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.ssh_agent_export_auth_title))
            .setSubtitle(getString(R.string.ssh_agent_export_auth_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        val cancellationSignal = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                    ?: return cancelPrepared(getString(R.string.ssh_agent_export_auth_lost))
                if (!handled.compareAndSet(false, true)) return
                status = getString(R.string.ssh_agent_export_writing)
                lifecycleScope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            val privateBytes = store.completeExport(prepared, cipher)
                                ?: error(getString(R.string.ssh_agent_export_key_changed))
                            try {
                                writePkcs8Pem(target, privateBytes, exportPassword)
                            } finally {
                                privateBytes.fill(0)
                            }
                        }
                    }
                    clearExportPassword()
                    outcome.onSuccess {
                        status = getString(R.string.ssh_agent_export_complete)
                        finish()
                    }.onFailure {
                        fail(it.message ?: getString(R.string.ssh_agent_export_failed))
                    }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (handled.compareAndSet(false, true)) cancelPrepared(errString.toString())
            }
        }
        try {
            prompt.authenticate(
                BiometricPrompt.CryptoObject(prepared.cipher),
                cancellationSignal,
                mainExecutor,
                callback,
            )
        } catch (failure: Exception) {
            if (handled.compareAndSet(false, true)) {
                cancelPrepared(failure.message ?: getString(R.string.ssh_agent_export_prepare_failed))
            }
        }
    }

    private fun writePkcs8Pem(target: Uri, privateBytes: ByteArray, password: CharArray?) {
        val pem = SshPrivateKeyExportCodec.encode(privateBytes, password)
        try {
            contentResolver.openOutputStream(target, "wt")?.use { it.write(pem) }
                ?: error(getString(R.string.ssh_agent_export_open_failed))
        } finally {
            pem.fill(0)
        }
    }

    private fun clearExportPassword() {
        exportPassword?.fill('\u0000')
        exportPassword = null
    }

    private fun fail(message: String) {
        clearExportPassword()
        failed = true
        status = message
        screen = ExportScreen.WORKING
    }

    companion object {
        private const val EXTRA_PROVIDER_KEY_ID = "ssh_provider_key_id"
        private const val EXTRA_DISPLAY_NAME = "ssh_display_name"

        fun intent(context: Context, providerKeyId: String, displayName: String): Intent =
            Intent(context, SshKeyExportActivity::class.java)
                .putExtra(EXTRA_PROVIDER_KEY_ID, providerKeyId)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
    }
}

private enum class ExportScreen { SETUP, WORKING }

@Composable
private fun ExportSetup(onContinue: (CharArray?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val matches = password == confirmation
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ssh_agent_export_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.ssh_agent_export_password_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ssh_agent_export_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = confirmation,
                onValueChange = { confirmation = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ssh_agent_export_password_confirm)) },
                singleLine = true,
                isError = confirmation.isNotEmpty() && !matches,
                supportingText = if (confirmation.isNotEmpty() && !matches) {
                    { Text(stringResource(R.string.ssh_agent_export_password_mismatch)) }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (password.isEmpty()) {
                Text(
                    stringResource(R.string.ssh_agent_export_plaintext_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = { onContinue(password.takeIf(String::isNotEmpty)?.toCharArray()) },
                modifier = Modifier.align(Alignment.End),
                enabled = matches,
            ) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun ExportProgress(status: String, failed: Boolean, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!failed) CircularProgressIndicator()
        Text(status, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
        if (failed) {
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
