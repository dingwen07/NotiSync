package net.extrawdw.apps.notisync.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.security.TapjackingProtectionEffect
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink

/** One progress value drives both the Devices width and the pairing pane's position. */
@Stable
internal class PairingPaneState {
    val progress = Animatable(0f)
    var opening by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set

    suspend fun open() {
        closing = false
        opening = true
        try {
            progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
        } finally {
            opening = false
        }
    }

    suspend fun reset() {
        progress.snapTo(0f)
        opening = false
        closing = false
    }

    suspend fun close(onClose: () -> Unit) {
        if (closing) return
        closing = true
        progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
        onClose()
    }
}

/** Keeps pairing at its final width while its visible pane slides in and out. */
@Composable
internal fun PairingPane(
    state: PairingPaneState,
    width: Dp,
    onClose: () -> Unit,
    onPairingCandidate: (PairingCandidate) -> Unit,
    onBrokerPairing: (BrokerPairingLink) -> Unit,
    onBrokerPairingCandidate: (PairingCandidate) -> Unit,
) {
    TapjackingProtectionEffect()
    val scope = rememberCoroutineScope()
    BackHandler(enabled = state.opening || state.closing) {
        if (!state.closing) scope.launch { state.close(onClose) }
    }
    PredictiveBackHandler(enabled = !state.opening && !state.closing) { events ->
        try {
            events.collect { event ->
                state.progress.snapTo(1f - event.progress.coerceIn(0f, 1f))
            }
            state.close(onClose)
        } catch (cancelled: CancellationException) {
            // The gesture's coroutine is cancelled too; restore from the composition scope.
            scope.launch {
                state.progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
            }
            throw cancelled
        }
    }

    Layout(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        content = {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PairingScreen(
                    onBack = { scope.launch { state.close(onClose) } },
                    onPairingCandidate = onPairingCandidate,
                    onBrokerPairing = onBrokerPairing,
                    onBrokerPairingCandidate = onBrokerPairingCandidate,
                )
            }
        },
    ) { measurables, constraints ->
        val content = measurables.single().measure(
            Constraints.fixed(width.roundToPx(), constraints.maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) { content.place(0, 0) }
    }
}
