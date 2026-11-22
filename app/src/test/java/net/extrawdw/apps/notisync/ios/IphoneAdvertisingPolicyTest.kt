package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IphoneAdvertisingPolicyTest {
    @Test fun onlyForgetReopensHelperSetupAfterSelection() {
        val saved = IosPeerSelection("02:00:00:00:00:AF")
        val other = "02:00:00:00:00:01"
        val both = setOf(other, saved.address.lowercase())
        assertEquals(IphoneAdvertisement.SETUP, IphoneAppSetup.advertisingMode(true, null, emptySet()))
        assertNull(IphoneAppSetup.advertisingMode(true, saved, both))
        assertEquals(IphoneAdvertisement.RECONNECT, IphoneAppSetup.advertisingMode(true, saved, setOf(other)))
        assertNull(IphoneAppSetup.advertisingMode(true, saved, both))
        assertEquals(IphoneAdvertisement.SETUP, IphoneAppSetup.advertisingMode(true, null, both))
        assertEquals(IphoneAdvertisement.NATIVE, IphoneAppSetup.advertisingMode(false, saved, both))
    }

    @Test fun android17WorkaroundDependsOnBothOemAndSocVendor() {
        assertTrue(IphoneAppSetup.prefersAppSetup(37, "Xiaomi", "QTI"))
        assertTrue(IphoneAppSetup.prefersAppSetup(37, "vivo", "Qualcomm Technologies, Inc."))
        assertTrue(IphoneAppSetup.prefersAppSetup(37, "iQOO", "qcom"))
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "Xiaomi", "XRing"))
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "Redmi", "MediaTek"))
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "samsung", "QTI"))
        assertFalse(IphoneAppSetup.prefersAppSetup(36, "Xiaomi", "QTI"))
    }

    @Test fun missingSocVendorKeepsBluetoothSettingsSetupAvailable() {
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "POCO", "unknown"))
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "vivo", ""))
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "samsung", "unknown"))
    }
}
