package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.DesktopWindowsElevationType
import net.extrawdw.notisync.protocol.DesktopWindowsTokenInfo

internal fun Context.windowsTokenInfoLabel(token: DesktopWindowsTokenInfo): String = listOfNotNull(
    token.elevated?.let {
        getString(if (it) R.string.desktop_process_elevated else R.string.desktop_process_not_elevated)
    },
    token.elevationType?.let {
        getString(when (it) {
            DesktopWindowsElevationType.DEFAULT -> R.string.desktop_process_token_default
            DesktopWindowsElevationType.FULL -> R.string.desktop_process_token_full
            DesktopWindowsElevationType.LIMITED -> R.string.desktop_process_token_limited
        })
    },
    token.integrityLevel?.let { level ->
        val label = when (level) {
            0L -> R.string.desktop_process_integrity_untrusted
            0x1000L -> R.string.desktop_process_integrity_low
            0x2000L -> R.string.desktop_process_integrity_medium
            0x2100L -> R.string.desktop_process_integrity_medium_plus
            0x3000L -> R.string.desktop_process_integrity_high
            0x4000L -> R.string.desktop_process_integrity_system
            0x5000L -> R.string.desktop_process_integrity_protected
            else -> null
        }
        getString(R.string.desktop_process_integrity, label?.let(::getString) ?: "0x${level.toString(16)}")
    },
    token.appContainer?.let {
        getString(if (it) R.string.desktop_process_app_container else R.string.desktop_process_no_app_container)
    },
).joinToString(" · ")
