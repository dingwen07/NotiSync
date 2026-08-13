package net.extrawdw.apps.notisync.sign

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenPgpSigningProviderTest {
    @Test
    fun normalizesHighBitOpenPgpKeyIdAsUnsignedHex() {
        assertEquals("FEDCBA9876543210", normalizeOpenPgpKeyId(0xFEDCBA9876543210uL.toLong()))
    }

    @Test
    fun padsShortOpenPgpKeyIdToWireWidth() {
        assertEquals("000000000000001A", normalizeOpenPgpKeyId(0x1A))
    }
}
