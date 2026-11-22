package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IosPeerSelectionTest {
    private val iphone = IosPeerSelection("AA:00:00:00:00:01", 3)
    private val buds = "AA:00:00:00:00:02"
    private val ble = "AA:00:00:00:00:03"

    @Test
    fun accessoryArrivingBeforeIphoneNeverClaimsSlotEvenIfBothAreBonded() {
        val selected = IosPeerSelection.resolve(null, false, listOf(iphone))!!
        fun target(vararg connected: String) = selected.findBonded(connected.toList(), { it }) { true }
        assertNull(target(buds))
        assertEquals(iphone.address, target(iphone.address))
        assertEquals(iphone.address, target(buds, iphone.address))
        assertEquals(iphone.address, target(iphone.address, buds))
        assertNull(selected.findBonded(listOf(iphone.address, buds), { it }) { it == buds })
    }

    @Test
    fun manuallyConfirmedBleAddressBelongsOnlyToItsAssociation() {
        val alias = IosPeerSelection(ble, iphone.associationId)
        val selected = IosPeerSelection.resolve(alias, true, listOf(iphone))!!
        assertEquals(ble.lowercase(), selected.findBonded(listOf(iphone.address, ble.lowercase()), { it }) { true })
        val replacement = iphone.copy(associationId = 4)
        assertNull(IosPeerSelection.resolve(alias, true, listOf(replacement)))
        assertNull(IosPeerSelection.resolve(alias, true, emptyList()))
        assertEquals(replacement, IosPeerSelection.resolve(replacement, true, listOf(iphone, replacement)))
    }

    @Test
    fun settingsPairingSurvivesRestartAndForgetSuppressesLegacyImport() {
        val manual = IosPeerSelection(ble)
        assertEquals(manual, IosPeerSelection.resolve(manual, true, emptyList()))
        assertNull(IosPeerSelection.resolve(null, true, listOf(iphone)))
        assertNull(IosPeerSelection.resolve(null, false, emptyList()))
        assertNull(IosPeerSelection.resolve(null, false, listOf(iphone, iphone.copy(address = ble, associationId = 4))))
    }

    @Test
    fun sharingRequiresSuccessfulNotificationSourceSubscription() {
        val first = AncsSubscription.begin(hasDataSource = true, hasNotificationSource = true)
        assertEquals(AncsSubscription.DATA_SOURCE, first.acknowledged(Ancs.NOTIFICATION_SOURCE))
        val second = first.acknowledged(Ancs.DATA_SOURCE)
        assertEquals(AncsSubscription.NOTIFICATION_SOURCE, second.acknowledged(Ancs.DATA_SOURCE))
        assertEquals(AncsSubscription.SHARING, second.acknowledged(Ancs.NOTIFICATION_SOURCE))
    }

    @Test
    fun serviceCanAppearLateAndDataSourceIsOptional() {
        val absent = AncsSubscription.begin(hasDataSource = true, hasNotificationSource = false)
        assertEquals(AncsSubscription.UNAVAILABLE, absent.acknowledged(Ancs.NOTIFICATION_SOURCE))
        val published = AncsSubscription.begin(hasDataSource = false, hasNotificationSource = true)
        assertEquals(AncsSubscription.SHARING, published.acknowledged(Ancs.NOTIFICATION_SOURCE))
    }
}
