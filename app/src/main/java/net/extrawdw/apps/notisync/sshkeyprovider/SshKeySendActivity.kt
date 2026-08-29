package net.extrawdw.apps.notisync.sshkeyprovider

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.ClientId

class SshKeySendActivity : ComponentActivity() {
    private var providerKeyId = ""
    private var displayName = ""
    private var graph: AppGraph? = null
    private var peers by mutableStateOf<List<SshKeyTransferPeer>>(emptyList())
    private var selectedPeerId by mutableStateOf<ClientId?>(null)
    private var screen by mutableStateOf(SendScreen.LOADING)
    private var status by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var pendingExport: PreparedSshKeyExport? = null
    private var authenticationCancellation: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        providerKeyId = intent.getStringExtra(EXTRA_PROVIDER_KEY_ID).orEmpty()
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        if (providerKeyId.isEmpty() || displayName.isEmpty()) return finish()

        enableEdgeToEdge()
        enableTapjackingProtection()
        setContent {
            NotiSyncTheme {
                SshKeySendScreen(
                    screen = screen,
                    peers = peers,
                    selectedPeerId = selectedPeerId,
                    status = status,
                    error = error,
                    onSelect = {
                        selectedPeerId = it.clientId
                        error = null
                    },
                    onSend = ::beginSend,
                    onClose = ::finish,
                )
            }
        }

        lifecycleScope.launch {
            val readyGraph = (application as? NotiSyncApp)?.awaitGraphReady()
                ?: return@launch showError(getString(R.string.ssh_key_provider_not_ready))
            graph = readyGraph
            refreshPeers(readyGraph)
            screen = SendScreen.SELECT
        }
    }

    override fun onDestroy() {
        authenticationCancellation?.cancel()
        authenticationCancellation = null
        pendingExport?.let { prepared -> graph?.sshKeyProviderStore?.cancelExport(prepared) }
        pendingExport = null
        super.onDestroy()
    }

    private fun refreshPeers(graph: AppGraph) {
        peers = eligibleSshKeyTransferPeers(graph.trust.activePeers.value)
        if (selectedPeerId !in peers.map(SshKeyTransferPeer::clientId)) selectedPeerId = peers.singleOrNull()?.clientId
    }

    private fun beginSend() {
        val graph = graph ?: return showError(getString(R.string.ssh_key_provider_not_ready))
        val targetId = selectedPeerId ?: return
        refreshPeers(graph)
        val target = peers.firstOrNull { it.clientId == targetId }
            ?: return showError(getString(R.string.ssh_key_provider_send_peer_unavailable))
        val engine = graph.sshKeyProviderEngine
            ?: return showError(getString(R.string.ssh_key_provider_not_ready))

        screen = SendScreen.WORKING
        status = getString(R.string.ssh_key_provider_send_preparing)
        error = null
        lifecycleScope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) { graph.sshKeyProviderStore.prepareExport(providerKeyId) }
            }.getOrElse {
                return@launch showError(it.message ?: getString(R.string.ssh_key_provider_export_prepare_failed))
            } ?: return@launch showError(getString(R.string.ssh_key_provider_export_not_exportable))
            pendingExport = prepared
            authenticateAndSend(graph, engine, prepared, target)
        }
    }

    private fun authenticateAndSend(
        graph: AppGraph,
        engine: SshKeyProviderEngine,
        prepared: PreparedSshKeyExport,
        target: SshKeyTransferPeer,
    ) {
        val handled = AtomicBoolean(false)
        fun cancelPrepared(message: String) {
            if (pendingExport === prepared) pendingExport = null
            graph.sshKeyProviderStore.cancelExport(prepared)
            showError(message)
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.ssh_key_provider_send_auth_title))
            .setSubtitle(getString(R.string.ssh_key_provider_send_auth_subtitle, target.displayName))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        val cancellation = CancellationSignal()
        authenticationCancellation = cancellation
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher
                    ?: return cancelPrepared(getString(R.string.ssh_key_provider_export_auth_lost))
                if (!handled.compareAndSet(false, true)) return
                authenticationCancellation = null
                status = getString(R.string.ssh_key_provider_send_sending, target.displayName)
                lifecycleScope.launch {
                    val sent = runCatching {
                        withContext(Dispatchers.IO) {
                            val privateBytes = graph.sshKeyProviderStore.completeExport(prepared, cipher)
                                ?: error(getString(R.string.ssh_key_provider_export_key_changed))
                            try {
                                val pem = SshPrivateKeyExportCodec.encode(privateBytes, null)
                                try {
                                    engine.sendPrivateKeyImport(target.clientId, pem, displayName)
                                } finally {
                                    pem.fill(0)
                                }
                            } finally {
                                privateBytes.fill(0)
                            }
                        }
                    }
                    pendingExport = null
                    sent.onSuccess { accepted ->
                        if (accepted) {
                            status = getString(R.string.ssh_key_provider_send_complete_body, target.displayName)
                            screen = SendScreen.COMPLETE
                        } else {
                            showError(getString(R.string.ssh_key_provider_send_peer_unavailable))
                        }
                    }.onFailure {
                        graph.sshKeyProviderStore.cancelExport(prepared)
                        showError(it.message ?: getString(R.string.ssh_key_provider_send_failed))
                    }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (handled.compareAndSet(false, true)) {
                    authenticationCancellation = null
                    cancelPrepared(errString.toString())
                }
            }
        }
        try {
            prompt.authenticate(
                BiometricPrompt.CryptoObject(prepared.cipher),
                cancellation,
                mainExecutor,
                callback,
            )
        } catch (failure: Exception) {
            if (handled.compareAndSet(false, true)) {
                authenticationCancellation = null
                cancelPrepared(failure.message ?: getString(R.string.ssh_key_provider_export_prepare_failed))
            }
        }
    }

    private fun showError(message: String) {
        error = message
        status = ""
        screen = SendScreen.SELECT
    }

    companion object {
        private const val EXTRA_PROVIDER_KEY_ID = "ssh_provider_key_id"
        private const val EXTRA_DISPLAY_NAME = "ssh_display_name"

        fun intent(context: android.content.Context, providerKeyId: String, displayName: String) =
            android.content.Intent(context, SshKeySendActivity::class.java)
                .putExtra(EXTRA_PROVIDER_KEY_ID, providerKeyId)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
    }
}

private enum class SendScreen { LOADING, SELECT, WORKING, COMPLETE }

@Composable
private fun SshKeySendScreen(
    screen: SendScreen,
    peers: List<SshKeyTransferPeer>,
    selectedPeerId: ClientId?,
    status: String,
    error: String?,
    onSelect: (SshKeyTransferPeer) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_key_provider_send_title)) },
                actions = { TextButton(onClick = onClose) { Text(stringResource(R.string.ssh_key_provider_close)) } },
            )
        },
    ) { padding ->
        when (screen) {
            SendScreen.LOADING, SendScreen.WORKING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    status.takeIf(String::isNotBlank)?.let {
                        Text(it, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            SendScreen.SELECT -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Text(
                    stringResource(R.string.ssh_key_provider_send_help),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (peers.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.ssh_key_provider_send_no_peers),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(peers, key = { it.clientId.value }) { peer ->
                            val selected = peer.clientId == selectedPeerId
                            Card(
                                onClick = { onSelect(peer) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = { onSelect(peer) })
                                    Text(peer.displayName, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = onSend,
                    enabled = selectedPeerId != null && peers.isNotEmpty(),
                    modifier = Modifier.align(Alignment.End).padding(24.dp),
                ) {
                    Text(stringResource(R.string.ssh_key_provider_send_action))
                }
            }

            SendScreen.COMPLETE -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.ssh_key_provider_send_complete_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        status,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onClose, modifier = Modifier.padding(top = 20.dp)) {
                        Text(stringResource(R.string.ssh_key_provider_close))
                    }
                }
            }
        }
    }
}
