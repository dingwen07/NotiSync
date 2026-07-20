package net.extrawdw.apps.notisync.screen

import net.extrawdw.notisync.protocol.ScreenMirrorConnectionCandidate
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidScreenTransportSelectionTest {
    @Test
    fun `transport detail drops remote Binder stack trace`() {
        assertEquals(
            "ConnectivityService: user has no permission to access restricted network.",
            sanitizeScreenTransportDetail(
                "ConnectivityService: user has no permission to access restricted network.\n" +
                    "Remote stack trace: at android.net.ConnectivityService.enforcePermission()",
            ),
        )
        assertNull(sanitizeScreenTransportDetail("Remote stack trace:\n at android.os.Binder"))
    }

    @Test
    fun `saturated request reserves DNS and Aware after direct LAN candidates`() {
        val lan = (1..10).map { index -> lanCandidate(index) }
        val dns = ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.DNS_SD,
            serviceName = "screen-dns",
        )
        val aware = ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.WIFI_AWARE,
            serviceName = awareServiceName(1),
            port = 31_891,
        )

        val selected = selectScreenMirrorRequestCandidates(lan, dns, listOf(aware))

        assertEquals(lan.take(6) + listOf(dns, aware), selected)
        assertEquals(8, selected.size)
    }

    @Test
    fun `each available discovery transport receives its reserved request slot`() {
        val lan = (1..10).map { index -> lanCandidate(index) }
        val dns = ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.DNS_SD,
            serviceName = "screen-dns",
        )
        val aware = ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.WIFI_AWARE,
            serviceName = awareServiceName(2),
            port = 31_892,
        )

        assertEquals(
            lan.take(7) + dns,
            selectScreenMirrorRequestCandidates(lan, dns, emptyList()),
        )
        assertEquals(
            lan.take(7) + aware,
            selectScreenMirrorRequestCandidates(lan, null, listOf(aware)),
        )
        assertEquals(
            lan.take(8),
            selectScreenMirrorRequestCandidates(lan, null, emptyList()),
        )
    }

    @Test
    fun `ambiguous Aware listener candidates are not advertised`() {
        val lan = (1..10).map { index -> lanCandidate(index) }
        val dns = ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.DNS_SD,
            serviceName = "screen-dns",
        )
        val awareCandidates = listOf(
            ScreenConnectionCandidate(
                kind = ScreenConnectionCandidate.WIFI_AWARE,
                serviceName = awareServiceName(3),
                port = 31_893,
            ),
            ScreenConnectionCandidate(
                kind = ScreenConnectionCandidate.WIFI_AWARE,
                serviceName = awareServiceName(4),
                port = 31_894,
            ),
        )

        val selected = selectScreenMirrorRequestCandidates(lan, dns, awareCandidates)

        assertEquals(lan.take(7) + dns, selected)
        assertFalse(selected.any { it.kind == ScreenConnectionCandidate.WIFI_AWARE })
    }

    @Test
    fun `source filters valid signed Aware candidates and preserves their order`() {
        val first = protocolAwareCandidate(5, 31_895)
        val second = protocolAwareCandidate(6, 31_896)
        val candidates = listOf(
            ScreenMirrorConnectionCandidate(
                kind = ScreenMirrorConnectionCandidate.LAN_TCP,
                host = "192.0.2.1",
                port = 31_890,
            ),
            first,
            protocolAwareCandidate(7, null),
            ScreenMirrorConnectionCandidate(
                kind = ScreenMirrorConnectionCandidate.WIFI_AWARE,
                serviceName = "notisync-screen-invalid",
                port = 31_897,
            ),
            second,
        )

        assertEquals(listOf(first, second), filterValidWifiAwareCandidates(candidates))
    }

    private fun lanCandidate(index: Int) = ScreenConnectionCandidate(
        kind = ScreenConnectionCandidate.LAN_TCP,
        host = "192.0.2.$index",
        port = 30_000 + index,
        interfaceName = "wlan0",
    )

    private fun protocolAwareCandidate(
        entropy: Int,
        port: Int?,
    ) = ScreenMirrorConnectionCandidate(
        kind = ScreenMirrorConnectionCandidate.WIFI_AWARE,
        serviceName = awareServiceName(entropy),
        port = port,
    )

    private fun awareServiceName(entropy: Int): String =
        "notisync-screen-${entropy.toString(16).padStart(32, '0')}"
}
