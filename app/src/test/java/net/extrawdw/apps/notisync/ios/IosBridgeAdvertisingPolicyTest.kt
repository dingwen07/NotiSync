package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IosBridgeAdvertisingPolicyTest {
    @Test fun onlyForgetReopensHelperSetupAfterSelection() {
        val saved = IosPeerSelection("02:00:00:00:00:AF")
        val other = "02:00:00:00:00:01"
        val both = setOf(other, saved.address.lowercase())
        assertEquals(IosBridgeAdvertisingMode.SETUP, IosBridgeSetup.advertisingMode(true, null, emptySet()))
        assertNull(IosBridgeSetup.advertisingMode(true, saved, both))
        assertEquals(IosBridgeAdvertisingMode.RECONNECT, IosBridgeSetup.advertisingMode(true, saved, setOf(other)))
        assertNull(IosBridgeSetup.advertisingMode(true, saved, both))
        assertEquals(IosBridgeAdvertisingMode.SETUP, IosBridgeSetup.advertisingMode(true, null, both))
        assertEquals(IosBridgeAdvertisingMode.NATIVE, IosBridgeSetup.advertisingMode(false, saved, both))
    }

    @Test fun android17WorkaroundDependsOnBothOemAndSocVendor() {
        assertTrue(IosBridgeSetup.prefersAppSetup(37, "Xiaomi", "QTI"))
        assertTrue(IosBridgeSetup.prefersAppSetup(37, "vivo", "Qualcomm Technologies, Inc."))
        assertTrue(IosBridgeSetup.prefersAppSetup(37, "iQOO", "qcom"))
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "Xiaomi", "XRing"))
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "Redmi", "MediaTek"))
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "samsung", "QTI"))
        assertFalse(IosBridgeSetup.prefersAppSetup(36, "Xiaomi", "QTI"))
    }

    @Test fun missingSocVendorKeepsBluetoothSettingsSetupAvailable() {
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "POCO", "unknown"))
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "vivo", ""))
        assertFalse(IosBridgeSetup.prefersAppSetup(37, "samsung", "unknown"))
    }
}
