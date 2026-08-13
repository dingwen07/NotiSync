package net.extrawdw.apps.notisync.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.sign.OpenPgpEnrollmentActivity
import net.extrawdw.apps.notisync.sign.OpenPgpRequestState
import net.extrawdw.apps.notisync.sign.OpenPgpSignReviewActivity
import net.extrawdw.apps.notisync.sign.StoredOpenPgpRequest

/** Setup and durable decision history for the optional Android OpenPGP signer. */
@Composable
fun SignScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enrollment by graph.openPgpEnrollment.enrollment.collectAsStateWithLifecycle()
    val requests by graph.openPgpSignStore.requests.collectAsStateWithLifecycle()
    var providerAvailable by remember { mutableStateOf(graph.openPgpProvider.isAvailable()) }
    val enroll = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        providerAvailable = graph.openPgpProvider.isAvailable()
    }
    LifecycleResumeEffect(Unit) {
        providerAvailable = graph.openPgpProvider.isAvailable()
        onPauseOrDispose { }
    }

    NotiScaffold("Git signing") { rootModifier ->
        LazyColumn(
            rootModifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "Approve byte-exact Git commit signatures on this device using OpenKeychain.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Signing certificate", style = MaterialTheme.typography.titleMedium)
                        when {
                            enrollment.enabled -> {
                                Text(enrollment.displayIdentity.orEmpty())
                                Text(
                                    "Primary key 0x${enrollment.primaryKeyId}",
                                    fontFamily = FontFamily.Monospace,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = {
                                        enroll.launch(OpenPgpEnrollmentActivity.intent(context))
                                    }) { Text("Change") }
                                    OutlinedButton(onClick = {
                                        scope.launch { graph.openPgpEnrollment.clear() }
                                    }) { Text("Remove") }
                                }
                            }
                            !providerAvailable -> {
                                Text("OpenKeychain is not installed or its OpenPGP service is unavailable.")
                                Button(onClick = {}, enabled = false) { Text("Select certificate") }
                            }
                            else -> Button(onClick = {
                                enroll.launch(OpenPgpEnrollmentActivity.intent(context))
                            }) { Text("Select certificate") }
                        }
                    }
                }
            }
            item { Text("Request history", style = MaterialTheme.typography.titleMedium) }
            if (requests.isEmpty()) {
                item { Text("No Git signing requests yet.") }
            } else {
                items(requests, key = { it.request.requestId }) { stored ->
                    SigningHistoryRow(
                        stored = stored,
                        onOpen = {
                            if (stored.state == OpenPgpRequestState.PENDING_REVIEW) {
                                context.startActivity(
                                    OpenPgpSignReviewActivity.intent(context, stored.request.requestId)
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SigningHistoryRow(stored: StoredOpenPgpRequest, onOpen: () -> Unit) {
    val canOpen = stored.state == OpenPgpRequestState.PENDING_REVIEW
    Card(
        Modifier.fillMaxWidth().then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stored.state.userLabel(), style = MaterialTheme.typography.titleSmall)
            Text("Key 0x${stored.request.primaryKeyId}", fontFamily = FontFamily.Monospace)
            Text("Request ${stored.request.requestId.take(8)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun OpenPgpRequestState.userLabel(): String = when (this) {
    OpenPgpRequestState.PENDING_REVIEW -> "Waiting for approval"
    OpenPgpRequestState.USER_APPROVED, OpenPgpRequestState.PROVIDER_INTERACTION -> "Signing in progress"
    OpenPgpRequestState.SIGNED_PENDING_SEND -> "Approved - sending response"
    OpenPgpRequestState.REJECTED_PENDING_SEND -> "Rejected - sending response"
    OpenPgpRequestState.SENT -> "Completed"
    OpenPgpRequestState.CANCELLED -> "Cancelled by requesting computer"
    OpenPgpRequestState.EXPIRED -> "Expired"
    OpenPgpRequestState.FAILED -> "Failed"
}
