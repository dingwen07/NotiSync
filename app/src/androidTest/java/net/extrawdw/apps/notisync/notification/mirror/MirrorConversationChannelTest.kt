package net.extrawdw.apps.notisync.notification.mirror

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.appicon.IconResolver
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationAction
import net.extrawdw.notisync.protocol.NotificationStyle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MirrorConversationChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val peer = ClientId("channel-test-${UUID.randomUUID()}")
    private val assetDir = File(context.cacheDir, peer.value)
    private val assets = AssetCache(assetDir)
    private val poster = RemoteNotificationPoster(context, assets, IconResolver(context, assets))
    private val shortcutIds = mutableSetOf<String>()
    private val groupId = "group:${peer.value}:example.chat"

    private fun message(conversation: String = "alice") = CapturedNotification(
        sourceClientId = peer, sourceKey = conversation, packageName = "example.chat", appLabel = "Test Chat",
        postTime = System.currentTimeMillis(), category = MirrorCategory.MESSAGE,
        style = NotificationStyle.MESSAGING, isConversation = true, shortcutId = conversation,
        channelId = "messages", channelName = "Messages", channelImportance = MirrorImportance.HIGH,
        shouldVibrate = true, messages = listOf(ConversationMessage(sender = conversation, text = "Test message", timestamp = 1)),
        actions = listOf(NotificationAction(index = 0, title = "Reply", remoteInput = true)),
    )

    @Before
    fun grantNotifications() {
        // Let the application's startup trust/channel sweep finish before creating synthetic peers.
        runBlocking { (context as NotiSyncApp).awaitGraphReady() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun cleanUp() {
        manager.activeNotifications.filter { it.notification.channelId in testChannels().map { c -> c.id } }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.deleteNotificationChannelGroup(groupId)
        ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIds.toList())
        assetDir.deleteRecursively()
    }

    private fun testChannels() = manager.notificationChannels.filter { it.group == groupId }

    private fun post(capture: CapturedNotification) {
        mirroredConversationShortcutId(capture)?.let(shortcutIds::add)
        poster.render(capture, silent = true)
        await { manager.activeNotifications.any { it.notification.shortcutId == mirroredConversationShortcutId(capture) } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Notification state did not settle", condition())
    }

    private fun gc() {
        // Retain unrelated peers' bases; the migration deliberately retires legacy conversation channels.
        val peers = manager.notificationChannelGroups.mapNotNull { group ->
            group.id.takeIf { it.startsWith("group:") }?.substringAfter(':')?.substringBefore(':')
        }.toSet() + peer.value
        MirrorChannels.gc(context, peers)
    }

    @Test
    fun multipleConversationsShareOneChannelWithMessagingAndReplies() {
        val first = message()
        val second = message("bob").copy(isGroupConversation = true, conversationTitle = "Group")
        post(first)
        post(second)
        assertEquals(1, testChannels().size)
        val notifications = manager.activeNotifications.map { it.notification }.filter { it.shortcutId in shortcutIds }
        assertEquals(2, notifications.size)
        notifications.forEach { notification ->
            assertEquals(1, NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages?.size)
            assertTrue(notification.actions.any { it.remoteInputs?.isNotEmpty() == true })
        }
        assertFalse(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id in shortcutIds })
        val cachedIds = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_CACHED).map { it.id }.toSet()
        assertTrue(cachedIds.containsAll(shortcutIds))
    }

    @Test
    fun deletedLegacyConversationCannotBeRestoredByNewMessages() {
        val capture = message()
        val parent = MirrorChannels.ensure(context, capture, null)
        val oldId = "conversation:${peer.value}:example.chat:alice"
        manager.createNotificationChannel(NotificationChannel(oldId, "Old Alice", NotificationManager.IMPORTANCE_LOW).apply {
            group = groupId
            setConversationId(parent, "noticonv:${peer.value}:example.chat:alice")
        })
        val oldPlatformId = "old-platform-${UUID.randomUUID()}"
        manager.createNotificationChannel(NotificationChannel(oldPlatformId, "Old Bob", NotificationManager.IMPORTANCE_LOW).apply {
            group = groupId
            setConversationId(parent, "noticonv:${peer.value}:example.chat:bob")
        })
        gc()
        assertNull(manager.getNotificationChannel(oldId))
        assertNull(manager.getNotificationChannel(oldPlatformId))
        post(capture)
        post(capture.copy(postTime = System.currentTimeMillis()))
        assertNull(manager.getNotificationChannel(oldId))
        assertEquals(parent, requireNotNull(manager.getNotificationChannel(parent, requireNotNull(mirroredConversationShortcutId(capture)))).id)
        assertEquals(1, testChannels().size)
    }

    @Test
    fun futureConversationOverridesSurviveGcAndNeverBecomeSummaryChannels() {
        val capture = message()
        val parent = MirrorChannels.ensure(context, capture, null)
        val shortcut = requireNotNull(mirroredConversationShortcutId(capture))
        val child = "os-conversation-${UUID.randomUUID()}"
        manager.createNotificationChannel(NotificationChannel(child, "Alice", NotificationManager.IMPORTANCE_LOW).apply {
            group = groupId
            setConversationId(parent, shortcut)
            setSound(null, null)
        })
        post(message("bob"))
        post(capture)
        // Refresh after both notifications are visible so summary maintenance sees both children.
        post(capture)
        gc()
        assertEquals(child, requireNotNull(manager.getNotificationChannel(parent, shortcut)).id)
        await { manager.activeNotifications.any { it.notification.channelId == parent && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 } }
        val summary = manager.activeNotifications.first { it.notification.channelId == parent && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }.notification
        assertNull(summary.shortcutId)
        assertEquals(parent, MirrorChannels.baseChannelId(context, child))
        assertFalse(MirrorChannels.isLegacyConversationChannel(manager.getNotificationChannel(child)))
    }

    @Test
    fun quietUpdatesCannotLowerTheSharedChannel() {
        val capture = message()
        val id = MirrorChannels.ensure(context, capture, null)
        val sound = manager.getNotificationChannel(id).sound
        MirrorChannels.ensure(context, capture.copy(channelImportance = MirrorImportance.LOW, channelSilent = true,
            shouldVibrate = false, channelName = "Renamed messages"), null)
        val channel = manager.getNotificationChannel(id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(sound, channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals("Renamed messages", channel.name.toString())
    }

    @Test
    fun sourceChildPolicyStaysSeparateWhenParentMetadataIsUnavailable() {
        val parent = MirrorChannels.ensure(context, message(), null)
        val child = MirrorChannels.ensure(context, message().copy(channelId = "source-alice", parentChannelId = "messages",
            channelImportance = MirrorImportance.LOW, channelSilent = true, shouldVibrate = false), null)
        assertNotEquals(parent, child)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(parent).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(child).importance)
        assertNull(manager.getNotificationChannel(child).sound)
        assertNull(manager.getNotificationChannel(child).conversationId)
    }
}
