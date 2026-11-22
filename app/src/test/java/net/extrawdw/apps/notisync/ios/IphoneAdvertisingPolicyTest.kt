package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneAdvertisingPolicyTest {
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
