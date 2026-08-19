package net.extrawdw.apps.notisync.sshagent

import android.hardware.biometrics.BiometricManager
import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SshAuthenticationPolicyTest {
    @Test
    fun keyMintAndPromptMasksStayInTheirOwnNamespaces() {
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG,
            SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS,
        )
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            SshAuthenticationPolicy.EXPORT_KEY_AUTHENTICATORS,
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            SshAuthenticationPolicy.REMEMBER_PROMPT_AUTHENTICATORS,
        )
        assertNotEquals(
            SshAuthenticationPolicy.SIGNING_KEY_AUTHENTICATORS,
            SshAuthenticationPolicy.SIGNING_PROMPT_AUTHENTICATORS,
        )
        assertNotEquals(
            SshAuthenticationPolicy.EXPORT_KEY_AUTHENTICATORS,
            SshAuthenticationPolicy.EXPORT_PROMPT_AUTHENTICATORS,
        )
    }
}
