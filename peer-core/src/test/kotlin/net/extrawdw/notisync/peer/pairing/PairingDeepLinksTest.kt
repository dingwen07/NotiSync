package net.extrawdw.notisync.peer.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingDeepLinksTest {
    @Test
    fun `desktop parser accepts pairing URL with or without trailing slash`() {
        assertEquals(
            "compact-payload",
            PairingDeepLinks.extractPayload(
                "https://notisync.apps.extrawdw.net/pair?payload=compact-payload",
            ),
        )
        assertEquals(
            "compact-payload",
            PairingDeepLinks.extractPayload(
                "https://notisync.apps.extrawdw.net/pair/?payload=compact-payload",
            ),
        )
    }
}
