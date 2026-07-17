package net.extrawdw.notisync.localapi

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalApiTest {
    @Test
    fun `unknown fields preserve rolling compatibility`() {
        val status = LocalApiJson.decodeFromString<DaemonStatus>(
            """{"version":"1","connectionState":"CONNECTED","future":42}""",
        )
        assertEquals(DaemonConnectionState.CONNECTED, status.connectionState)
    }

    @Test
    fun `notification request never contains a wire source identity`() {
        val encoded = LocalApiJson.encodeToString(
            NotificationRequest(
                sessionId = "session",
                generation = 1,
                phase = NotificationPhase.INITIAL,
                title = "Build",
                text = "Running",
                silent = true,
                ongoing = true,
                clearable = false,
            ),
        )
        assertFalse(encoded.contains("sourceClientId"))
        assertFalse(encoded.contains("sourceKey"))
    }

    @Test
    fun `action lifetime defaults to generation for older clients`() {
        val action = LocalApiJson.decodeFromString<LocalNotificationAction>(
            """{"id":"input","title":"Input","kind":"REMOTE_INPUT","generation":4}""",
        )

        assertEquals(NotificationActionLifetime.GENERATION, action.lifetime)
    }
}
