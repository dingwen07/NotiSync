package net.extrawdw.apps.notisync.hotspot

import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult
import net.extrawdw.apps.notisync.hotspot.controller.SavedHotspot
import org.junit.Assert.*
import org.junit.Test

class SavedHotspotTest {
    private val details = SavedHotspot("热点;\\,:\"", "test;\\,:\"password", 2, true, 123)

    @Test fun qrEscapesDelimitersAndPreservesUnicodeHiddenSsidAndPassword() {
        val result = ResultParser.parseResult(Result(details.qrPayload(), null, null, BarcodeFormat.QR_CODE)) as WifiParsedResult
        assertEquals(details.ssid, result.ssid)
        assertEquals(details.psk, result.password)
        assertEquals("WPA", result.networkEncryption)
        assertTrue(result.isHidden)
    }

    @Test fun securityTypesSelectCompatibleQrAuthentication() {
        assertTrue(details.copy(securityType = 3).qrPayload()!!.contains("T:SAE;"))
        for (type in listOf(0, 4, 5)) {
            val qr = details.copy(securityType = type, psk = null).qrPayload()!!
            assertTrue(qr.contains("T:nopass;"))
            assertFalse(qr.contains("P:"))
        }
    }

    @Test fun invalidConfigurationsCannotBeJoinedOrShared() {
        for (bad in listOf(details.copy(ssid = "界".repeat(11)), details.copy(ssid = ""),
            details.copy(ssid = "<unknown ssid>"), details.copy(ssid = "invalid\u0000name"),
            details.copy(psk = null), details.copy(psk = "short"), details.copy(psk = "界".repeat(8)),
            details.copy(securityType = 99), details.copy(securityType = 0))) {
            assertFalse(bad.usable)
            assertNull(bad.qrPayload())
        }
        assertFalse(details.toString().contains(details.psk!!))
        assertFalse(details.toString().contains(details.ssid))
    }
}
