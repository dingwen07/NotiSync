package net.extrawdw.apps.notisync.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotiSyncMessagingServiceTest {
    @Test
    fun inlinePushUsesExplicitBrokerLocatorWithoutDecodingCiphertext() {
        assertEquals(
            "message-1",
            relayWakeMessageId(mapOf("ct" to "untrusted-inline-payload", "mid" to "message-1")),
        )
    }

    @Test
    fun wakeLocatorIsUsedWhenInlineCiphertextIsAbsent() {
        assertEquals("message-2", relayWakeMessageId(mapOf("mid" to "message-2")))
    }

    @Test
    fun malformedOrBlankLocatorsAreRejected() {
        assertEquals("message-2", relayWakeMessageId(mapOf("ct" to "not-base64", "mid" to "message-2")))
        assertNull(relayWakeMessageId(mapOf("ct" to "not-base64")))
        assertNull(relayWakeMessageId(mapOf("mid" to "")))
        assertNull(relayWakeMessageId(mapOf("mid" to "x".repeat(65))))
        assertNull(relayWakeMessageId(mapOf("accepted_at" to "42")))
    }
}
