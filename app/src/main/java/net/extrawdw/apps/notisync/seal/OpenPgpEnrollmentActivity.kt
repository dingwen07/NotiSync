package net.extrawdw.apps.notisync.seal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

class OpenPgpEnrollmentActivity : ComponentActivity() {
    private var message by mutableStateOf("Connecting to OpenKeychain…")
    private var step = Step.PERMISSION
    private var started = false
    private var awaitingInteraction = false
    private var providerContinuation: Intent? = null

    private val interaction = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        awaitingInteraction = false
        if (result.resultCode != Activity.RESULT_OK) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return@registerForActivityResult
        }
        providerContinuation = result.data
        started = false
        runStep()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getString(STATE_STEP)?.let(Step::valueOf) ?: Step.PERMISSION
        awaitingInteraction = savedInstanceState?.getBoolean(STATE_AWAITING_INTERACTION) == true
        providerContinuation = savedInstanceState?.getParcelable(
            STATE_PROVIDER_CONTINUATION,
            Intent::class.java,
        )
        enableEdgeToEdge()
        setContent {
            NotiSyncTheme {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(message, Modifier.padding(top = 24.dp))
                }
            }
        }
        if (!awaitingInteraction) runStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_STEP, step.name)
        outState.putBoolean(STATE_AWAITING_INTERACTION, awaitingInteraction)
        outState.putParcelable(STATE_PROVIDER_CONTINUATION, providerContinuation)
        super.onSaveInstanceState(outState)
    }

    private fun runStep() {
        if (started) return
        started = true
        lifecycleScope.launch {
            val graph = (applicationContext as NotiSyncApp).awaitGraphReady()
            if (graph == null) return@launch fail("NotiSync is not ready")
            val continuation = providerContinuation
            providerContinuation = null
            val outcome = when (step) {
                Step.PERMISSION -> graph.openPgpProvider.checkPermission(continuation)
                Step.SELECTION -> graph.openPgpProvider.selectSigningKey(continuation)
            }
            when (outcome) {
                is ProviderOutcome.InteractionRequired -> {
                    message = if (step == Step.PERMISSION) {
                        "Authorize NotiSync in OpenKeychain"
                    } else "Select an OpenPGP certificate"
                    awaitingInteraction = true
                    interaction.launch(IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build())
                }
                is ProviderOutcome.Success<*> -> when (step) {
                    Step.PERMISSION -> {
                        step = Step.SELECTION
                        started = false
                        message = "Select an OpenPGP certificate…"
                        runStep()
                    }
                    Step.SELECTION -> {
                        val selection = outcome.value as? OpenPgpKeySelection
                            ?: return@launch fail("OpenKeychain returned an invalid selection")
                        graph.openPgpEnrollment.save(selection)
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
                ProviderOutcome.Cancelled -> {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
                ProviderOutcome.Unavailable -> fail("OpenKeychain is unavailable")
                ProviderOutcome.UnsupportedKey -> fail("The selected certificate is unsupported")
                is ProviderOutcome.Failure -> fail("OpenKeychain could not complete setup")
            }
        }
    }

    private fun fail(text: String) {
        message = text
        setResult(Activity.RESULT_CANCELED)
        window.decorView.postDelayed({ finish() }, 1_500)
    }

    private enum class Step { PERMISSION, SELECTION }

    companion object {
        private const val STATE_STEP = "step"
        private const val STATE_AWAITING_INTERACTION = "awaiting_provider_interaction"
        private const val STATE_PROVIDER_CONTINUATION = "provider_continuation"
        fun intent(context: android.content.Context) = Intent(context, OpenPgpEnrollmentActivity::class.java)
    }
}
