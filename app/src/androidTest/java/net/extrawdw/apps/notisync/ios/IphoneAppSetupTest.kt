package net.extrawdw.apps.notisync.ios

import android.bluetooth.BluetoothGattCharacteristic
import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.extrawdw.apps.notisync.R
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class IphoneAppSetupTest {
    @Test fun repeatedLossNotificationDistinguishesFallbackFromShutdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        for (disabled in listOf(false, true)) {
            IosBridgeService.notifyAdvertisingLoss(context, bridgeDisabled = disabled)
            val title = context.getString(if (disabled) R.string.ios_bridge_disabled_title else R.string.ios_bridge_fallback_title)
            val message = context.getString(if (disabled) R.string.ios_bridge_disabled_message else R.string.ios_bridge_fallback_message)
            fun postedAlert() = manager.activeNotifications.find {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            }
            val alert = runBlocking {
                withTimeout(5_000) {
                    var posted = postedAlert()
                    while (posted == null) {
                        delay(50)
                        posted = postedAlert()
                    }
                    posted
                }
            }
            try {
                val channel = manager.getNotificationChannel(alert.notification.channelId)
                assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
                assertTrue(channel.shouldVibrate())
                assertEquals(message, alert.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertNotNull(alert.notification.contentIntent)
                assertFalse(alert.isOngoing)
            } finally {
                manager.cancel(alert.tag, alert.id)
            }
        }
    }

    @Test fun systemCanIssueSingleDeviceAccessPrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
        val request = IosCompanion.request("02:00:00:00:22:01")
        assertTrue(request.isSingleDevice)
        assertFalse(request.isSelfManaged)
        val pending = CompletableFuture<IntentSender>()
        IosCompanion.manager(context)!!.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) { pending.complete(intentSender) }
            override fun onFailure(message: CharSequence?) { pending.completeExceptionally(AssertionError(message)) }
        })
        assertNotNull(pending.get(10, TimeUnit.SECONDS))
    }

    @Test fun affectedPhonesAdvertiseOnlyTheirPublishedService() {
        for (manufacturer in listOf("Xiaomi", "vivo", "iQOO", "Redmi", "POCO")) {
            val appSetup = IphoneAppSetup.prefersAppSetup(37, manufacturer, "QTI")
            assertTrue(appSetup)
            val data = IphoneAppSetup.advertisement(IphoneAdvertisement.SETUP)
            val service = IphoneAppSetup.service()
            assertEquals(listOf(ParcelUuid(service.uuid)), data.serviceUuids)
            assertTrue(data.serviceSolicitationUuids.isEmpty())
            val version = service.getCharacteristic(IphoneAppSetup.versionId)
            assertEquals(BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED, version.permissions)
            assertEquals(BluetoothGattCharacteristic.PROPERTY_READ, version.properties)
        }
    }

    @Test fun reconnectAdvertisementOmitsPublicSetupAndAncsSolicitation() {
        val data = IphoneAppSetup.advertisement(IphoneAdvertisement.RECONNECT)
        assertEquals(listOf(ParcelUuid(IphoneAppSetup.reconnectId)), data.serviceUuids)
        assertFalse(data.includeDeviceName)
        assertTrue(data.serviceSolicitationUuids.isEmpty())
    }

    @Test fun settingsSetupRemainsAvailableOnOtherBuilds() {
        assertFalse(IphoneAppSetup.prefersAppSetup(37, "Google", "Google"))
        assertFalse(IphoneAppSetup.prefersAppSetup(36, "vivo", "QTI"))
        val data = IphoneAppSetup.advertisement(IphoneAdvertisement.NATIVE)
        assertEquals(listOf(ParcelUuid(Ancs.SERVICE_UUID)), data.serviceSolicitationUuids)
        assertTrue(data.serviceUuids.isEmpty())
    }
}
