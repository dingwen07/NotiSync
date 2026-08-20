package net.extrawdw.apps.notisync.sshagent

import android.content.Context
import androidx.annotation.StringRes
import net.extrawdw.apps.notisync.R

internal fun Throwable.sshKeyStorageUserMessage(
    context: Context,
    @StringRes fallback: Int = R.string.error_unknown,
): String = if (isHardwareBackedSshKeystoreUnavailable()) {
    context.getString(R.string.ssh_agent_hardware_keystore_unavailable)
} else {
    message?.takeIf(String::isNotBlank) ?: context.getString(fallback)
}
