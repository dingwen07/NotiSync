package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.NotificationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorConversationIdentityTest {
    private val message = CapturedNotification(
        sourceClientId = ClientId("peer"), sourceKey = "notification-1",
        packageName = "example.chat", appLabel = "Chat", postTime = 1,
        style = NotificationStyle.MESSAGING, isConversation = true, shortcutId = "alice",
    )

    @Test
    fun `conversation identity survives notification and channel changes`() {
        val id = mirroredConversationShortcutId(message)
        assertTrue(requireNotNull(id).startsWith("conversation:"))
        assertEquals(id, mirroredConversationShortcutId(message.copy(
            sourceKey = "notification-2", channelId = "custom", title = "Renamed contact",
        )))
        assertNotEquals(id, mirroredConversationShortcutId(message.copy(shortcutId = "bob")))
    }

    @Test
    fun `peers origins and packages cannot share a conversation shortcut`() {
        val id = mirroredConversationShortcutId(message)
        assertNotEquals(id, mirroredConversationShortcutId(message.copy(sourceClientId = ClientId("other"))))
        assertNotEquals(id, mirroredConversationShortcutId(message.copy(originDeviceId = "bridged")))
        assertNotEquals(id, mirroredConversationShortcutId(message.copy(packageName = "other.chat")))
        assertEquals(id, mirroredConversationShortcutId(message.copy(originDeviceId = " ")))
    }

    @Test
    fun `missing shortcuts use conversation metadata before notification identity`() {
        val fallback = message.copy(shortcutId = " ", conversationId = "alice")
        assertEquals(mirroredConversationShortcutId(message), mirroredConversationShortcutId(fallback))
        assertEquals(mirroredConversationShortcutId(fallback), mirroredConversationShortcutId(fallback.copy(sourceKey = "new")))
        val notificationOnly = fallback.copy(conversationId = null)
        assertNotEquals(mirroredConversationShortcutId(notificationOnly), mirroredConversationShortcutId(notificationOnly.copy(sourceKey = "new")))
    }

    @Test
    fun `only individual messaging notifications publish a conversation shortcut`() {
        assertNull(mirroredConversationShortcutId(message.copy(isConversation = false)))
        assertNull(mirroredConversationShortcutId(message.copy(isGroupSummary = true)))
        assertNull(mirroredConversationShortcutId(message.copy(style = NotificationStyle.BIG_TEXT)))
        assertNotEquals(null, mirroredConversationShortcutId(message.copy(isGroupConversation = true)))
    }
}
