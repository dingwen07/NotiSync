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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    var failed by remember(link) { mutableStateOf(false) }
    // A scan supplies the secret. Dismissing the sheet cancels this single-use exchange.
    LaunchedEffect(link) {
        try {
            onCandidate(pairing.exchange(link))
        } catch (_: TimeoutCancellationException) {
            failed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
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
            if (failed) {
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
