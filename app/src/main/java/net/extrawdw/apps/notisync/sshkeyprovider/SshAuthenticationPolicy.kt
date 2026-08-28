package net.extrawdw.apps.notisync.sshkeyprovider

import android.hardware.biometrics.BiometricManager
import android.security.keystore.KeyProperties

/** Keeps KeyMint authorization masks and BiometricPrompt authenticator masks in their distinct namespaces. */
internal object SshAuthenticationPolicy {
    const val SIGNING_KEY_AUTHENTICATORS = KeyProperties.AUTH_BIOMETRIC_STRONG
    const val EXPORT_KEY_AUTHENTICATORS =
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

    const val SIGNING_PROMPT_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    const val EXPORT_PROMPT_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    const val REMEMBER_PROMPT_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
