package net.extrawdw.apps.notisync.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Duration

/** Locale-aware compact duration used by NotiSync Run and other elapsed/countdown displays. */
@Composable
internal fun formatDuration(durationMs: Long): String {
    require(durationMs >= 0) { "durationMs must be non-negative" }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
    }

    val duration = Duration.ofMillis(durationMs)
    val days = duration.toDaysPart()
    val hours = duration.toHoursPart()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()
    val milliseconds = duration.toMillisPart()

    val measures = buildList {
        if (days > 0) add(Measure(days, MeasureUnit.DAY))
        if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
        if (minutes > 0) add(Measure(minutes, MeasureUnit.MINUTE))
        if (seconds > 0) add(Measure(seconds, MeasureUnit.SECOND))
        if (milliseconds > 0 || isEmpty()) add(Measure(milliseconds, MeasureUnit.MILLISECOND))
    }
    return formatter.formatMeasures(*measures.toTypedArray())
}
