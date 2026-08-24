package net.extrawdw.apps.notisync.sshagent

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import java.security.MessageDigest
import java.util.Base64

object SshWebAuthnCredentialManager {
    suspend fun create(activity: Activity, requestJson: String): String {
        val response = CredentialManager.create(activity).createCredential(
            context = activity,
            request = CreatePublicKeyCredentialRequest(requestJson),
        )
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: error("Credential Manager returned an unexpected WebAuthn credential creation response")
    }

    suspend fun get(activity: Activity, requestJson: String): String {
        val response = CredentialManager.create(activity).getCredential(
            context = activity,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(GetPublicKeyCredentialOption(requestJson))
                .build(),
        )
        return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: error("Credential Manager returned an unexpected WebAuthn credential assertion response")
    }

    fun trustedOrigins(context: Context): Set<String> {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signingInfo = requireNotNull(packageInfo.signingInfo) { "app signing information is unavailable" }
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            "android:apk-key-hash:${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
        }.also { require(it.isNotEmpty()) { "no trusted app signing origins are available" } }
    }
}
