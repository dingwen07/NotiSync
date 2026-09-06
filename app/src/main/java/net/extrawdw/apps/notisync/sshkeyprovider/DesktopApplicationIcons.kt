package net.extrawdw.apps.notisync.sshkeyprovider

import androidx.annotation.DrawableRes
import net.extrawdw.apps.notisync.R

/** Bundled artwork is presentation metadata keyed by registry ID, never by executable path or display name. */
@DrawableRes
internal fun desktopApplicationIcon(applicationId: String?): Int? = when (applicationId) {
    "git" -> R.drawable.ic_desktop_application_git
    else -> null
}
