package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import androidx.annotation.StringRes
import net.extrawdw.apps.notisync.R

internal fun Throwable.sshKeyStorageUserMessage(
    context: Context,
    @StringRes fallback: Int = R.string.error_unknown,
): String = when {
    this is SshWebAuthnException.UntrustedOrigin -> context.getString(
        R.string.ssh_key_provider_webauthn_error_origin_not_trusted,
        received,
        expected,
    )
    this is SshWebAuthnException.IncompatibleClientData -> context.getString(
        R.string.ssh_key_provider_webauthn_error_client_data_format,
        clientDataJson,
    )
    this is SshWebAuthnException.InvalidAssertionSignature ->
        context.getString(R.string.ssh_key_provider_webauthn_error_assertion_signature_invalid)
    isHardwareBackedSshKeystoreUnavailable() ->
        context.getString(R.string.ssh_key_provider_hardware_keystore_unavailable)
    else -> message?.takeIf(String::isNotBlank) ?: context.getString(fallback)
}
