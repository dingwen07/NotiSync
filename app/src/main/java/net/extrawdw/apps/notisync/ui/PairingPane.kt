package net.extrawdw.apps.notisync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.security.TapjackingProtectionEffect
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink

@Composable
internal fun PairingPane(
    state: SupportingPaneMotionState,
    width: Dp,
    onClose: () -> Unit,
    onPairingCandidate: (PairingCandidate) -> Unit,
    onBrokerPairing: (BrokerPairingLink) -> Unit,
    onBrokerPairingCandidate: (PairingCandidate) -> Unit,
) {
    TapjackingProtectionEffect()
    SlidingSupportingPane(state, width, onClose) { close ->
        PairingScreen(
            onBack = close,
            onPairingCandidate = onPairingCandidate,
            onBrokerPairing = onBrokerPairing,
            onBrokerPairingCandidate = onBrokerPairingCandidate,
        )
    }
}
