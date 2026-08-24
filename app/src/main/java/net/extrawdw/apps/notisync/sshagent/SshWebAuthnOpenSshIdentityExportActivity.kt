package net.extrawdw.apps.notisync.sshagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

class SshWebAuthnOpenSshIdentityExportActivity : ComponentActivity() {
    private var providerKeyId = ""
    private var identityFile: ByteArray? = null
    private var status by mutableStateOf("")
    private var failed by mutableStateOf(false)
    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        if (uri == null) {
            finish()
        } else {
            writeIdentityFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerKeyId = intent.getStringExtra(EXTRA_PROVIDER_KEY_ID).orEmpty()
        if (providerKeyId.isEmpty()) return finish()
        enableEdgeToEdge()
        enableTapjackingProtection()
        status = getString(R.string.ssh_agent_webauthn_export_preparing)
        setContent {
            NotiSyncTheme {
                IdentityExportProgress(status, failed, ::finish)
            }
        }
        prepareIdentityFile()
    }

    override fun onDestroy() {
        identityFile?.fill(0)
        identityFile = null
        super.onDestroy()
    }

    private fun prepareIdentityFile() {
        lifecycleScope.launch {
            val graph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch fail(getString(R.string.ssh_agent_provider_unavailable))
            identityFile = runCatching {
                withContext(Dispatchers.IO) {
                    val source = graph.sshKeyProviderStore.webAuthnRecoverySource(providerKeyId)
                        ?: error(getString(R.string.ssh_agent_webauthn_export_not_available))
                    SshWebAuthnOpenSshIdentityFile.encode(source.credential, source.displayName)
                }
            }.getOrElse {
                return@launch fail(it.message ?: getString(R.string.ssh_agent_webauthn_export_failed))
            }
            createDocument.launch(SshWebAuthnOpenSshIdentityFile.DEFAULT_FILE_NAME)
        }
    }

    private fun writeIdentityFile(target: Uri) {
        status = getString(R.string.ssh_agent_webauthn_export_writing)
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = identityFile ?: error(getString(R.string.ssh_agent_webauthn_export_failed))
                    contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
                        ?: error(getString(R.string.ssh_agent_webauthn_export_open_failed))
                }
            }
            outcome.onSuccess {
                Toast.makeText(
                    this@SshWebAuthnOpenSshIdentityExportActivity,
                    R.string.ssh_agent_webauthn_export_complete,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }.onFailure {
                fail(it.message ?: getString(R.string.ssh_agent_webauthn_export_failed))
            }
        }
    }

    private fun fail(message: String) {
        failed = true
        status = message
    }

    companion object {
        private const val EXTRA_PROVIDER_KEY_ID = "ssh_provider_key_id"

        fun intent(context: Context, providerKeyId: String): Intent =
            Intent(context, SshWebAuthnOpenSshIdentityExportActivity::class.java)
                .putExtra(EXTRA_PROVIDER_KEY_ID, providerKeyId)
    }
}

@Composable
private fun IdentityExportProgress(status: String, failed: Boolean, onClose: () -> Unit) {
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
