package net.extrawdw.apps.notisync.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class AutomaticTimeTest {
    @Test
    fun automaticTimeSetting_warnsOnlyForExplicitZero() {
        assertFalse(decodeAutomaticTimeSetting(0)!!)
        assertTrue(decodeAutomaticTimeSetting(1)!!)
        assertTrue(decodeAutomaticTimeSetting(2)!!)
        assertNull(decodeAutomaticTimeSetting(null))
    }

    @Test
    fun systemTime_includesOffsetAndTimeZoneId() {
        val formatted = formatPairingSystemTime(
            Instant.parse("2026-07-20T00:34:12Z").toEpochMilli(),
            "Asia/Singapore",
            Locale.US,
        )

        assertTrue(formatted.contains("Jul 20, 2026"))
        // CLDR may use a narrow no-break space before the day period.
        assertTrue(formatted.contains("8:34:12"))
        assertTrue(formatted.contains("AM"))
        assertTrue(formatted.contains("GMT+08:00"))
        assertTrue(formatted.contains("[Asia/Singapore]"))
        assertEquals(1, formatted.count { it == '·' })
    }
}
