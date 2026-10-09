package net.extrawdw.apps.notisync.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.screen.ScreenMirrorAuthorizationStore
import net.extrawdw.notisync.protocol.ClientId

/** The prompt belongs to this device detail; leaving it or rotating cancels the pending grant. */
@Composable
internal fun rememberVirtualDisplayAuthorizationChange(
    peerId: ClientId,
    peerName: String,
    authorizations: ScreenMirrorAuthorizationStore,
    isEligible: () -> Boolean,
): (Boolean) -> Unit {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val currentEligibility by rememberUpdatedState(isEligible)
    var pending by remember(peerId, activity) { mutableStateOf<Job?>(null) }
    DisposableEffect(peerId, activity) {
        onDispose { pending?.cancel() }
    }
    return { allowed ->
        if (!allowed) {
            pending?.cancel()
            pending = null
            authorizations.revokeVirtualDisplay(peerId)
        } else if (pending?.isActive != true && activity != null) {
            pending = scope.launch {
                authorizations.authorizeVirtualDisplay(peerId, { currentEligibility() }) {
                    authenticateVirtualDisplayGrant(activity, peerName)
                }
            }
        }
    }
}

private suspend fun authenticateVirtualDisplayGrant(activity: Activity, peerName: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        fun unavailable() {
            if (!continuation.isActive) return
            Toast.makeText(activity, R.string.screen_virtual_auth_unavailable, Toast.LENGTH_LONG).show()
            continuation.resume(false)
        }
        try {
            val manager = activity.getSystemService(BiometricManager::class.java)
            if (manager?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                unavailable()
                return@suspendCancellableCoroutine
            }
            BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.screen_virtual_auth_title))
                .setSubtitle(activity.getString(R.string.screen_virtual_auth_subtitle, peerName))
                .setAllowedAuthenticators(authenticators)
                .build()
                .authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                        ) Toast.makeText(activity, errString, Toast.LENGTH_LONG).show()
                        continuation.resume(false)
                    }
                    // A failed biometric attempt keeps the system prompt open for retry or passcode.
                })
        } catch (_: Exception) {
            unavailable()
        }
    }
