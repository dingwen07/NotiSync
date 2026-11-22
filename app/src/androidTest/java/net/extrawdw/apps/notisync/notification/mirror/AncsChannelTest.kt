package net.extrawdw.apps.notisync.notification.mirror

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AncsChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val bridge = ClientId("ancs-channel-test-${UUID.randomUUID()}")
    private val otherBridge = ClientId("${bridge.value}-other")
    private val phone = CapturedNotification(
        sourceClientId = bridge, sourceKey = "notification", packageName = "example.chat", appLabel = "Chat",
        postTime = 1L, originPlatform = OriginPlatform.IOS_ANCS,
        originDeviceId = "123456abcdef", originDeviceName = "First iPhone",
    )

    @Before
    fun awaitStartupChannelSweep() {
        runBlocking { (context as NotiSyncApp).awaitGraphReady() }
    }

    @After
    fun removeTestGroups() {
        manager.notificationChannelGroups.filter {
            it.id.startsWith("group:${bridge.value}:") || it.id.startsWith("group:${otherBridge.value}:")
        }.forEach { manager.deleteNotificationChannelGroup(it.id) }
    }

    @Test
    fun sharingReusesChannelsOnlyWithinTheSameBridgeAndApp() {
        val nextPhone = phone.copy(originDeviceId = "fedcba654321", originDeviceName = "Second iPhone")
        val original = MirrorChannels.ensure(context, phone, "Pixel")
        assertEquals("channel:${bridge.value}:123456abcdef:example.chat:_ios", original)
        assertNotEquals(original, MirrorChannels.ensure(context, nextPhone, "Pixel"))

        val shared = MirrorChannels.ensure(context, phone, "Pixel", shareIphoneAppChannels = true)
        assertNotEquals(original, shared)
        assertEquals(shared, MirrorChannels.ensure(context, nextPhone, "Pixel", true))
        val sharedGroup = manager.getNotificationChannel(shared).group
        assertNotEquals(manager.getNotificationChannel(original).group, sharedGroup)
        assertEquals(context.getString(R.string.mirror_shared_iphone_apps, "Pixel"),
            manager.getNotificationChannelGroup(sharedGroup).name.toString())
        val otherApp = MirrorChannels.ensure(context, phone.copy(packageName = "example.mail"), "Pixel", true)
        assertNotEquals(shared, otherApp)
        assertEquals(sharedGroup, manager.getNotificationChannel(otherApp).group)
        val otherSender = MirrorChannels.ensure(context, phone.copy(sourceClientId = otherBridge), "Pixel", true)
        assertNotEquals(shared, otherSender)
        assertNotEquals(sharedGroup, manager.getNotificationChannel(otherSender).group)
        assertEquals(original, MirrorChannels.ensure(context, phone, "Pixel"))
    }

    @Test
    fun deleteAncsRemovesSharedPerPhoneAndLegacyGroupsOnly() {
        // This diagnostic operates on the whole installation. Only exercise it with a clean mirror inventory.
        assumeTrue(manager.notificationChannelGroups.none { it.id.startsWith("group:") })
        val android = phone.copy(originPlatform = OriginPlatform.ANDROID_LOCAL, originDeviceId = null,
            originDeviceName = null, channelId = "source:with:colons:_ios")
        val androidChannel = MirrorChannels.ensure(context, android, "Pixel", true)
        assertEquals(androidChannel, MirrorChannels.ensure(context, android, "Pixel"))
        val perPhone = MirrorChannels.ensure(context, phone, "Pixel")
        val shared = MirrorChannels.ensure(context, phone.copy(sourceClientId = otherBridge), "Tablet", true)
        val legacyGroup = "group:${bridge.value}:123456abcdef:example.chat"
        val legacyChannel = "channel:${bridge.value}:123456abcdef:example.chat:_default"
        manager.createNotificationChannelGroup(NotificationChannelGroup(legacyGroup, "Old chat"))
        manager.createNotificationChannel(NotificationChannel(legacyChannel, "Chat", NotificationManager.IMPORTANCE_LOW).apply {
            group = legacyGroup
        })
        val deletedGroups = listOf(perPhone, shared, legacyChannel).map { manager.getNotificationChannel(it).group }

        assertEquals(3, MirrorChannels.deleteAncs(context))
        deletedGroups.forEach { assertNull(manager.getNotificationChannelGroup(it)) }
        listOf(perPhone, shared, legacyChannel).forEach { assertNull(manager.getNotificationChannel(it)) }
        assertNotNull(manager.getNotificationChannel(androidChannel))
        assertEquals(0, MirrorChannels.deleteAncs(context))
        assertEquals(shared, MirrorChannels.ensure(context, phone.copy(sourceClientId = otherBridge), "Tablet", true))
    }
}
