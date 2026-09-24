package net.extrawdw.apps.notisync.navigation

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.SignatureIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.apps as AppsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.devices as DevicesIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.history as HistoryIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.key as KeyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.phone_iphone as PhoneIphoneIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.settings as SettingsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.terminal as TerminalIcon

@get:StringRes
internal val MenuDestination.label: Int get() = when (this) {
    MenuDestination.DEVICES -> R.string.tab_devices
    MenuDestination.APPS -> R.string.tab_apps
    MenuDestination.IPHONE -> R.string.tab_ios
    MenuDestination.ACTIVITY -> R.string.tab_activity
    MenuDestination.SETTINGS -> R.string.tab_settings
    MenuDestination.RUN -> R.string.tab_run
    MenuDestination.SEAL -> R.string.tab_seal
    MenuDestination.SSH_KEYS -> R.string.ssh_key_provider_tools_label
}

internal val MenuDestination.icon: ImageVector get() = when (this) {
    MenuDestination.DEVICES -> DevicesIcon
    MenuDestination.APPS -> AppsIcon
    MenuDestination.IPHONE -> PhoneIphoneIcon
    MenuDestination.ACTIVITY -> HistoryIcon
    MenuDestination.SETTINGS -> SettingsIcon
    MenuDestination.RUN -> TerminalIcon
    MenuDestination.SEAL -> SignatureIcon
    MenuDestination.SSH_KEYS -> KeyIcon
}

@get:DrawableRes
internal val MenuDestination.shortcutIcon: Int get() = when (this) {
    MenuDestination.DEVICES -> R.drawable.ic_shortcut_devices
    MenuDestination.APPS -> if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        R.drawable.ic_shortcut_apps_samsung
    } else {
        R.drawable.ic_shortcut_apps
    }
    MenuDestination.IPHONE -> R.drawable.ic_shortcut_iphone
    MenuDestination.ACTIVITY -> R.drawable.ic_shortcut_activity
    MenuDestination.SETTINGS -> R.drawable.ic_shortcut_settings
    MenuDestination.RUN -> R.drawable.ic_shortcut_run
    MenuDestination.SEAL -> R.drawable.ic_shortcut_seal
    MenuDestination.SSH_KEYS -> R.drawable.ic_shortcut_ssh_keys
}
