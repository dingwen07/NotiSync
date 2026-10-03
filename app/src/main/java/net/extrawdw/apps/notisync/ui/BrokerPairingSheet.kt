package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.apps.notisync.security.TapjackingProtectionEffect
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink

@Composable
internal fun BrokerPairingSheet(
    link: BrokerPairingLink,
    pairing: PairingManager,
    onCandidate: (PairingCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    TapjackingProtectionEffect()
    val exchange = viewModel { BrokerPairingExchange() }
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(link) { exchange.start(link, pairing) }
    LaunchedEffect(exchange.candidate) {
        exchange.candidate?.let { candidate ->
            exchange.clear()
            onCandidate(candidate)
        }
    }
    DisposableEffect(exchange, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) exchange.clear()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.pair_broker_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pair_broker_instruction))
            Text(stringResource(R.string.pair_broker_address, link.brokerUrl), style = MaterialTheme.typography.bodySmall)
            if (exchange.failed) {
                Text(stringResource(R.string.pair_broker_failed), color = MaterialTheme.colorScheme.error)
            } else {
                CircularProgressIndicator()
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** A single-use exchange must not be cancelled/replayed just because the display folds. */
private class BrokerPairingExchange : ViewModel() {
    private var link: BrokerPairingLink? = null
    private var job: Job? = null
    var failed by mutableStateOf(false)
        private set
    var candidate by mutableStateOf<PairingCandidate?>(null)
        private set

    fun start(next: BrokerPairingLink, pairing: PairingManager) {
        if (link == next) return
        clear()
        link = next
        job = viewModelScope.launch {
            try {
                candidate = pairing.exchange(next)
            } catch (_: TimeoutCancellationException) {
                failed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        link = null
        candidate = null
        failed = false
    }
}
