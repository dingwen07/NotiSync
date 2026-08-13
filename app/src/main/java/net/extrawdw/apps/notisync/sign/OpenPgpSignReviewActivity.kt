package net.extrawdw.apps.notisync.sign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.GitCommitPayload
import net.extrawdw.notisync.protocol.GitCommitPayloadParser
import net.extrawdw.notisync.protocol.OpenPgpRejectReason

/** Private user-presence surface for one exact, authenticated Git commit signing request. */
class OpenPgpSignReviewActivity : ComponentActivity() {
    private var screen by mutableStateOf<ReviewScreenState>(ReviewScreenState.Loading)
    private var requestId: String = ""
    private var providerRunning = false
    private var awaitingInteraction = false
    private var interactionRequestId: String? = null
    private var interactionPayloadDigest: ByteArray? = null
    private var providerContinuation: Intent? = null

    private val providerInteraction = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        awaitingInteraction = false
        requestId = interactionRequestId ?: requestId
        screen = ReviewScreenState.Loading
        if (result.resultCode != Activity.RESULT_OK) {
            reject(OpenPgpRejectReason.PROVIDER_CANCELLED, requestId)
        } else {
            providerContinuation = result.data
            runProvider()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        awaitingInteraction = savedInstanceState?.getBoolean(STATE_AWAITING_INTERACTION) == true
        interactionRequestId = savedInstanceState?.getString(STATE_INTERACTION_REQUEST_ID)
        interactionPayloadDigest = savedInstanceState?.getByteArray(STATE_INTERACTION_DIGEST)
        providerContinuation = savedInstanceState?.getParcelable(
            STATE_PROVIDER_CONTINUATION,
            Intent::class.java,
        )
        enableEdgeToEdge()
        setContent {
            NotiSyncTheme {
                ReviewContent(
                    state = screen,
                    onApprove = ::approve,
                    onReject = { reject(OpenPgpRejectReason.USER_REJECTED) },
                    onClose = ::finish,
                )
            }
        }
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        providerRunning = false
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_INTERACTION, awaitingInteraction)
        outState.putString(STATE_INTERACTION_REQUEST_ID, interactionRequestId)
        outState.putByteArray(STATE_INTERACTION_DIGEST, interactionPayloadDigest)
        outState.putParcelable(STATE_PROVIDER_CONTINUATION, providerContinuation)
        super.onSaveInstanceState(outState)
    }

    private fun load() {
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
                ?: return@launch showTerminal("NotiSync is not ready")
            val stored = graph.openPgpSignStore.find(requestId)
                ?: return@launch showTerminal("This signing request is unavailable")
            val payload = stored.request.payload
            if (payload == null) {
                return@launch showTerminal(terminalMessage(stored.state))
            }
            val parsed = runCatching { GitCommitPayloadParser.parse(payload) }.getOrNull()
                ?: return@launch showTerminal("This signing request is invalid")
            val peer = graph.trust.roster.value.firstOrNull { it.clientId == stored.senderClientId }
            val enrollment = graph.openPgpEnrollment.enrollment.value
            screen = ReviewScreenState.Ready(
                request = stored,
                commit = parsed,
                requesterName = peer?.displayName ?: stored.senderClientId.shortForm(),
                requesterFingerprint = peer?.identityKeyFingerprint ?: stored.senderClientId.value,
                signingIdentity = enrollment.displayIdentity ?: "OpenPGP certificate",
                primaryKeyId = enrollment.primaryKeyId ?: stored.request.primaryKeyId,
            )
            if (
                stored.state in setOf(
                    OpenPgpRequestState.USER_APPROVED,
                    OpenPgpRequestState.PROVIDER_INTERACTION,
                ) && !awaitingInteraction
            ) runProvider()
        }
    }

    private fun approve() {
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return@launch
            if (!graph.openPgpSignStore.approve(requestId, System.currentTimeMillis())) {
                load()
                return@launch
            }
            runProvider()
        }
    }

    private fun runProvider() {
        if (providerRunning || awaitingInteraction) return
        providerRunning = true
        screen = (screen as? ReviewScreenState.Ready)?.copy(signing = true) ?: screen
        lifecycleScope.launch {
            try {
                val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
                    ?: return@launch showTerminal("NotiSync is not ready")
                val stored = graph.openPgpSignStore.find(requestId)
                    ?: return@launch showTerminal("This signing request is unavailable")
                val enrollment = graph.openPgpEnrollment.enrollment.value
                val payload = stored.request.payload
                if (
                    interactionRequestId == requestId &&
                    interactionPayloadDigest?.let {
                        !MessageDigest.isEqual(it, stored.request.payloadSha256)
                    } == true
                ) return@launch showTerminal("The signing request changed while OpenKeychain was open")
                if (
                    payload == null || System.currentTimeMillis() > stored.request.expiresAt ||
                    stored.state !in setOf(
                        OpenPgpRequestState.USER_APPROVED,
                        OpenPgpRequestState.PROVIDER_INTERACTION,
                    ) || !enrollment.enabled ||
                    enrollment.providerId != graph.openPgpProvider.providerId ||
                    enrollment.primaryKeyId != stored.request.primaryKeyId
                ) {
                    graph.openPgpSignStore.markExpired(requestId, System.currentTimeMillis())
                    graph.openPgpSignNotifications.dismiss(requestId)
                    return@launch showTerminal("This signing request has expired or was cancelled")
                }
                val keyReference = enrollment.providerKeyReference
                    ?: return@launch reject(OpenPgpRejectReason.UNSUPPORTED_KEY)
                val digestBefore = stored.request.payloadSha256.copyOf()
                val continuation = providerContinuation
                providerContinuation = null
                when (
                    val outcome = graph.openPgpProvider.detachedSign(
                        keyReference,
                        payload,
                        continuation,
                    )
                ) {
                    is ProviderOutcome.Success -> {
                        val current = graph.openPgpSignStore.find(requestId)
                        if (
                            current == null ||
                            !MessageDigest.isEqual(current.request.payloadSha256, digestBefore) ||
                            !graph.openPgpSignStore.storeResult(
                                requestId,
                                outcome.value,
                                System.currentTimeMillis(),
                            )
                        ) {
                            showTerminal("This signing request expired or was cancelled")
                            return@launch
                        }
                        graph.openPgpSignNotifications.dismiss(requestId)
                        OpenPgpSignResponseWorker.enqueue(applicationContext, requestId)
                        clearInteractionBinding()
                        showTerminal("Signature approved")
                    }
                    is ProviderOutcome.InteractionRequired -> {
                        if (!graph.openPgpSignStore.markProviderInteraction(requestId, System.currentTimeMillis())) {
                            return@launch showTerminal("This signing request expired or was cancelled")
                        }
                        awaitingInteraction = true
                        interactionRequestId = requestId
                        interactionPayloadDigest = digestBefore
                        providerInteraction.launch(
                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build()
                        )
                    }
                    ProviderOutcome.Cancelled -> reject(OpenPgpRejectReason.PROVIDER_CANCELLED)
                    ProviderOutcome.Unavailable -> reject(OpenPgpRejectReason.PROVIDER_UNAVAILABLE)
                    ProviderOutcome.UnsupportedKey -> reject(OpenPgpRejectReason.UNSUPPORTED_KEY)
                    is ProviderOutcome.Failure -> reject(OpenPgpRejectReason.PROVIDER_FAILURE)
                }
            } finally {
                providerRunning = false
            }
        }
    }

    private fun reject(reason: OpenPgpRejectReason, rejectedRequestId: String = requestId) {
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady() ?: return@launch
            if (graph.openPgpSignStore.storeReject(rejectedRequestId, reason, System.currentTimeMillis())) {
                graph.openPgpSignNotifications.dismiss(rejectedRequestId)
                OpenPgpSignResponseWorker.enqueue(applicationContext, rejectedRequestId)
            }
            clearInteractionBinding()
            showTerminal(if (reason == OpenPgpRejectReason.USER_REJECTED) "Request rejected" else "Signing cancelled")
        }
    }

    private fun clearInteractionBinding() {
        awaitingInteraction = false
        interactionRequestId = null
        interactionPayloadDigest = null
        providerContinuation = null
    }

    private fun showTerminal(message: String) {
        screen = ReviewScreenState.Terminal(message)
    }

    private fun terminalMessage(state: OpenPgpRequestState): String = when (state) {
        OpenPgpRequestState.SENT, OpenPgpRequestState.SIGNED_PENDING_SEND -> "Signature approved"
        OpenPgpRequestState.REJECTED_PENDING_SEND -> "Request rejected"
        OpenPgpRequestState.CANCELLED -> "The requesting computer cancelled this request"
        OpenPgpRequestState.EXPIRED -> "This signing request expired"
        else -> "This signing request is no longer available"
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "openpgp_request_id"
        private const val STATE_AWAITING_INTERACTION = "awaiting_provider_interaction"
        private const val STATE_INTERACTION_REQUEST_ID = "provider_interaction_request_id"
        private const val STATE_INTERACTION_DIGEST = "provider_interaction_payload_digest"
        private const val STATE_PROVIDER_CONTINUATION = "provider_continuation"

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, OpenPgpSignReviewActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private sealed interface ReviewScreenState {
    data object Loading : ReviewScreenState
    data class Ready(
        val request: StoredOpenPgpRequest,
        val commit: GitCommitPayload,
        val requesterName: String,
        val requesterFingerprint: String,
        val signingIdentity: String,
        val primaryKeyId: String,
        val signing: Boolean = false,
    ) : ReviewScreenState
    data class Terminal(val message: String) : ReviewScreenState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    state: ReviewScreenState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Review Git signature") }) }) { padding ->
        when (state) {
            ReviewScreenState.Loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            is ReviewScreenState.Terminal -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, style = MaterialTheme.typography.titleLarge)
                Button(onClick = onClose, modifier = Modifier.padding(top = 24.dp)) { Text("Close") }
            }
            is ReviewScreenState.Ready -> ReviewDetails(state, padding, onApprove, onReject)
        }
    }
}

@Composable
private fun ReviewDetails(
    state: ReviewScreenState.Ready,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.request.request.requestId) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((state.request.request.expiresAt - now).coerceAtLeast(0) + 999) / 1_000
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Approve only if these exact commit facts match what you expect.")
            Text("No code diff is included in this request.", color = MaterialTheme.colorScheme.error)
        }
        item { FactCard("Requesting device", state.requesterName, state.requesterFingerprint) }
        item {
            FactCard(
                "Signing certificate",
                state.signingIdentity,
                "Primary key 0x${state.primaryKeyId}",
            )
        }
        item { FactCard("Author", state.commit.author) }
        item { FactCard("Committer", state.commit.committer) }
        item { FactCard("Commit message", state.commit.message.ifEmpty { "(empty)" }) }
        item { FactCard("Tree", state.commit.treeId) }
        items(state.commit.parentIds) { parent -> FactCard("Parent", parent) }
        items(
            state.commit.headers.filterNot {
                it.name in setOf("tree", "parent", "author", "committer")
            }
        ) { header ->
            FactCard("Header: ${header.name}", header.value)
        }
        item {
            FactCard(
                "Exact payload",
                "${state.commit.bytes.size} bytes",
                state.request.request.payloadSha256.toHex(),
            )
        }
        item { Text("Expires in ${seconds}s", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReject, enabled = !state.signing, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
                Button(onClick = onApprove, enabled = !state.signing && seconds > 0, modifier = Modifier.weight(1f)) {
                    Text(if (state.signing) "Signing..." else "Approve")
                }
            }
        }
    }
}

@Composable
private fun FactCard(title: String, value: String, supporting: String? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value)
            supporting?.let { Text(it, fontFamily = FontFamily.Monospace) }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
