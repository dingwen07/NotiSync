package net.extrawdw.apps.notisync.pairing

import android.content.ContentResolver
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Read the user-controlled wall-clock policy without treating an unavailable setting as disabled. */
internal fun automaticTimeEnabled(contentResolver: ContentResolver): Boolean? =
    decodeAutomaticTimeSetting(
        runCatching {
            Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME)
        }.getOrNull()
    )

internal fun decodeAutomaticTimeSetting(rawValue: Int?): Boolean? = rawValue?.let { it != 0 }

/** Format the exact wall-clock instant and zone captured when the signed pairing card was generated. */
internal fun formatPairingSystemTime(
    epochMillis: Long,
    timeZoneId: String,
    locale: Locale,
): String {
    val zone = runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val localDateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(time)
    val zoneDescription = DateTimeFormatter.ofPattern("OOOO '['VV']'", locale).format(time)
    return "$localDateTime · $zoneDescription"
}
