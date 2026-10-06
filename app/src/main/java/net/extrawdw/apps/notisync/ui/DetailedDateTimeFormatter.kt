package net.extrawdw.apps.notisync.ui

import android.icu.text.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun rememberDetailedDateTimeFormatter(): DateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateFormat.getInstanceForSkeleton("yMMMdjmsSSS", locale)
    }
}
