package net.extrawdw.apps.notisync.pairing

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import net.extrawdw.notisync.peer.trust.RosterDevice

internal enum class PairingReviewSource {
    INTERACTIVE,
    DEEP_LINK,
    HCE,
    BROKER,
}

internal data class PairingReview(
    val candidate: PairingCandidate,
    val source: PairingReviewSource,
    val existingTrustedDevice: RosterDevice?,
)

/** Pending trust decisions stay in memory across Activity recreation, never in saved-state Bundles. */
internal class PairingViewModel : ViewModel() {
    val review = mutableStateOf<PairingReview?>(null)
    val brokerLink = mutableStateOf<BrokerPairingLink?>(null)
    val approvingOwnDevice = mutableStateOf<Boolean?>(null)
    val error = mutableStateOf<String?>(null)
    val completed = mutableStateOf<Pair<PairingReview, String>?>(null)

    fun approve(
        graph: AppGraph,
        pairing: PairingManager,
        context: Context,
        decision: PairingReview,
        ownDevice: Boolean,
    ) {
        if (approvingOwnDevice.value != null || completed.value != null) return
        approvingOwnDevice.value = ownDevice
        error.value = null
        viewModelScope.launch {
            try {
                val card = graph.durableTrustMutations.run {
                    pairing.accept(decision.candidate.payload, ownDevice).getOrThrow()
                }
                completed.value = decision to card.displayName
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = context.getString(R.string.pair_could_not_pair, failure.message)
            } finally {
                approvingOwnDevice.value = null
            }
        }
    }
}
