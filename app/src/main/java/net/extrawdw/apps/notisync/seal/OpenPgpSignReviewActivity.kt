package net.extrawdw.apps.notisync.seal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.notification.ACTION_AUTO_OPEN_REQUEST_PAGE
import net.extrawdw.apps.notisync.notification.finishAutoOpenedRequestPage
import net.extrawdw.apps.notisync.notification.isAutomaticRequestPageLaunch
import net.extrawdw.apps.notisync.notification.requestPageObservationState
import net.extrawdw.apps.notisync.notification.retainAutomaticRequestPageOwnership
import net.extrawdw.apps.notisync.security.enableTapjackingProtection
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
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
    private var autoLaunchOwned = false

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
        requestId = requestIdFrom(intent) ?: return finish()
        val approveAfterLoad = savedInstanceState == null && intent.action == ACTION_APPROVE
        autoLaunchOwned = savedInstanceState?.getBoolean(STATE_AUTO_LAUNCH_OWNED)
            ?: isAutomaticRequestPageLaunch(intent.action)
        intent.action = null
        awaitingInteraction = savedInstanceState?.getBoolean(STATE_AWAITING_INTERACTION) == true
        interactionRequestId = savedInstanceState?.getString(STATE_INTERACTION_REQUEST_ID)
        interactionPayloadDigest = savedInstanceState?.getByteArray(STATE_INTERACTION_DIGEST)
        providerContinuation = savedInstanceState?.getParcelable(
            STATE_PROVIDER_CONTINUATION,
            Intent::class.java,
        )
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        enableTapjackingProtection()
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
        screen = ReviewScreenState.Loading
        load(approveAfterLoad)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_INTERACTION, awaitingInteraction)
        outState.putString(STATE_INTERACTION_REQUEST_ID, interactionRequestId)
        outState.putByteArray(STATE_INTERACTION_DIGEST, interactionPayloadDigest)
        outState.putParcelable(STATE_PROVIDER_CONTINUATION, providerContinuation)
        outState.putBoolean(STATE_AUTO_LAUNCH_OWNED, autoLaunchOwned)
        super.onSaveInstanceState(outState)
    }

    private fun observeRequest(approveAfterLoad: Boolean) {
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
                ?: return@launch showError(getString(R.string.seal_not_ready))
            var approveOnFirstLoad = approveAfterLoad
            repeatOnLifecycle(requestPageObservationState(autoLaunchOwned)) {
                graph.openPgpSignStore.requests
                    .map { requests -> requests.firstOrNull { it.request.requestId == requestId } }
                    .distinctUntilChanged()
                    .collectLatest { stored ->
                        renderRequest(graph, stored, approveOnFirstLoad)
                        approveOnFirstLoad = false
                    }
            }
        }
    }

    private fun load(approveAfterLoad: Boolean = false) {
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
                ?: return@launch showError(getString(R.string.seal_not_ready))
            renderRequest(graph, graph.openPgpSignStore.find(requestId), approveAfterLoad)
        }
    }

    private fun renderRequest(
        graph: AppGraph,
        stored: StoredOpenPgpRequest?,
        approveAfterLoad: Boolean,
    ) {
        stored ?: return showError(getString(R.string.seal_request_unavailable))
        if (stored.shouldCloseAutoOpenedReview(autoLaunchOwned)) {
            clearInteractionBinding()
            finishAutoOpenedRequestPage()
            return
        }
        val commit = stored.commit ?: stored.request.payload?.toDisplaySnapshot()
            ?: return showError(getString(R.string.seal_request_invalid))
        val peer = graph.trust.roster.value.firstOrNull { it.clientId == stored.senderClientId }
        val enrollment = graph.openPgpEnrollment.enrollment.value
        if (!stored.opensSealReview()) clearInteractionBinding()
        screen = ReviewScreenState.Details(
            request = if (stored.commit == null) stored.copy(commit = commit) else stored,
            requesterName = peer?.displayName ?: stored.senderClientId.shortForm(),
            requesterIdentityKeyFingerprint = peer?.identityKeyFingerprint,
            signingIdentity = enrollment.displayIdentity ?: getString(R.string.seal_openpgp_identity),
        )
        if (approveAfterLoad && stored.state == OpenPgpRequestState.PENDING_REVIEW) {
            approve()
            return
        }
        if (
            stored.state in setOf(
                OpenPgpRequestState.USER_APPROVED,
                OpenPgpRequestState.PROVIDER_INTERACTION,
            ) && !awaitingInteraction
        ) runProvider()
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
        screen = (screen as? ReviewScreenState.Details)?.copy(signing = true) ?: screen
        lifecycleScope.launch {
            try {
                val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
                    ?: return@launch showError(getString(R.string.seal_not_ready))
                val stored = graph.openPgpSignStore.find(requestId)
                    ?: return@launch showError(getString(R.string.seal_request_unavailable))
                val enrollment = graph.openPgpEnrollment.enrollment.value
                val payload = stored.request.payload
                if (
                    interactionRequestId == requestId &&
                    interactionPayloadDigest?.let {
                        !MessageDigest.isEqual(it, stored.request.payloadSha256)
                    } == true
                ) return@launch showError(getString(R.string.seal_request_changed))
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
                    load()
                    return@launch
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
                            load()
                            return@launch
                        }
                        graph.openPgpSignNotifications.dismiss(requestId)
                        OpenPgpSignResponseWorker.enqueue(applicationContext, requestId)
                        clearInteractionBinding()
                        load()
                    }
                    is ProviderOutcome.InteractionRequired -> {
                        if (!graph.openPgpSignStore.markProviderInteraction(requestId, System.currentTimeMillis())) {
                            load()
                            return@launch
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
            load()
        }
    }

    private fun clearInteractionBinding() {
        awaitingInteraction = false
        interactionRequestId = null
        interactionPayloadDigest = null
        providerContinuation = null
    }

    private fun showError(message: String) {
        screen = ReviewScreenState.Error(message)
    }

    companion object {
        private const val ACTION_APPROVE = "net.extrawdw.apps.notisync.action.SEAL_APPROVE"
        private const val EXTRA_REQUEST_ID = "openpgp_request_id"
        private const val STATE_AWAITING_INTERACTION = "awaiting_provider_interaction"
        private const val STATE_INTERACTION_REQUEST_ID = "provider_interaction_request_id"
        private const val STATE_INTERACTION_DIGEST = "provider_interaction_payload_digest"
        private const val STATE_PROVIDER_CONTINUATION = "provider_continuation"
        private const val STATE_AUTO_LAUNCH_OWNED = "auto_launch_owned"
        private const val REVIEW_SCHEME = "notisync"
        private const val REVIEW_AUTHORITY = "seal-review"

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, OpenPgpSignReviewActivity::class.java)
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

private sealed interface ReviewScreenState {
    data object Loading : ReviewScreenState
    data class Details(
        val request: StoredOpenPgpRequest,
        val requesterName: String,
        val requesterIdentityKeyFingerprint: String?,
        val signingIdentity: String,
        val signing: Boolean = false,
    ) : ReviewScreenState
    data class Error(val message: String) : ReviewScreenState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    state: ReviewScreenState,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    val details = state as? ReviewScreenState.Details
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.seal_name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.seal_close),
                        )
                    }
                },
            )
        },
        bottomBar = {
            when {
                details != null && details.request.opensSealReview() -> ReviewActionBar(
                    stored = details.request,
                    signing = details.signing ||
                        details.request.state != OpenPgpRequestState.PENDING_REVIEW,
                    onApprove = onApprove,
                    onReject = onReject,
                )
                details != null || state is ReviewScreenState.Error -> CloseActionBar(onClose)
            }
        },
    ) { padding ->
        when (state) {
            ReviewScreenState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is ReviewScreenState.Error -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.message,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            is ReviewScreenState.Details -> SigningRequestDetail(
                stored = state.request,
                requesterName = state.requesterName,
                requesterIdentityKeyFingerprint = state.requesterIdentityKeyFingerprint,
                signingIdentity = state.signingIdentity,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    end = 20.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            )
        }
    }
}

@Composable
private fun ReviewActionBar(
    stored: StoredOpenPgpRequest,
    signing: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    var now by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stored.request.requestId) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((stored.request.expiresAt - now).coerceAtLeast(0) + 999) / 1_000
    BottomAppBar(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (signing) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.seal_signing_action),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                pluralStringResource(R.plurals.seal_expires_in, seconds.toInt(), seconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.seal_review_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    pluralStringResource(R.plurals.seal_expires_in, seconds.toInt(), seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onReject) {
                Text(stringResource(R.string.action_reject))
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onApprove, enabled = seconds > 0) {
                Text(stringResource(R.string.action_approve))
            }
        }
    }
}

@Composable
private fun CloseActionBar(onClose: () -> Unit) {
    BottomAppBar(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onClose) {
            Text(stringResource(R.string.seal_close))
        }
    }
}
