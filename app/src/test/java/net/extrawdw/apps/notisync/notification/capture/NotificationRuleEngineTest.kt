package net.extrawdw.apps.notisync.notification.capture

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRuleEngineTest {

    private val engine = NotificationRuleEngine()

    private fun notif(
        pkg: String,
        category: MirrorCategory = MirrorCategory.NONE,
        isConversation: Boolean = false,
        title: String? = null,
        channelId: String? = null,
    ) =
        CapturedNotification(
            sourceClientId = ClientId("x"),
            sourceKey = "k",
            packageName = pkg,
            appLabel = "L",
            title = title,
            category = category,
            isConversation = isConversation,
            channelId = channelId,
            postTime = 0L,
        )

    @Test
    fun defaultMirrorsTheLargeIcon() {
        val plan = engine.plan(notif("com.example.chat", category = MirrorCategory.MESSAGE))
        assertEquals(LargeIconHandling.MIRROR, plan.largeIcon)
        assertEquals(GraphicsSlot.PRIVATE, plan.bigPicture)
        assertEquals(GraphicsSlot.PRIVATE, plan.avatar)
    }

    @Test
    fun weChatMessageRoutesLargeIconToAvatar() {
        assertEquals(
            LargeIconHandling.AS_AVATAR,
            engine.plan(notif("com.tencent.mm", category = MirrorCategory.MESSAGE)).largeIcon
        )
        assertEquals(
            LargeIconHandling.AS_AVATAR,
            engine.plan(notif("com.tencent.mm", isConversation = true)).largeIcon
        )
    }

    @Test
    fun weChatNonConversationFallsBackToDefault() {
        // A non-message, non-conversation WeChat notification (e.g. a payment receipt) mirrors normally.
        assertEquals(
            LargeIconHandling.MIRROR,
            engine.plan(notif("com.tencent.mm", category = MirrorCategory.STATUS)).largeIcon
        )
    }

    @Test
    fun qqNormalMessageRoutesLargeIconToAvatarAndRemovesUnreadCountFromSender() {
        val plan = engine.plan(
            notif(
                "com.tencent.mobileqq",
                title = "开发群(28条新消息)",
                channelId = "CHANNEL_ID_SHOW_BADGE",
            )
        )

        assertEquals(LargeIconHandling.AS_AVATAR, plan.largeIcon)
        assertEquals("开发群", plan.conversationSenderOverride)
    }

    @Test
    fun qqMessageSupportsTitlesWithoutUnreadCountAndFullWidthSuffixes() {
        val plain = engine.plan(
            notif(
                "com.tencent.mobileqq",
                category = MirrorCategory.MESSAGE,
                title = "Alice",
            )
        )
        val fullWidth = engine.plan(
            notif(
                "com.tencent.mobileqq",
                title = "项目群（99+条新消息）",
                channelId = "CHANNEL_ID_SHOW_BADGE",
            )
        )

        assertEquals(LargeIconHandling.AS_AVATAR, plain.largeIcon)
        assertEquals("Alice", plain.conversationSenderOverride)
        assertEquals("项目群", fullWidth.conversationSenderOverride)
    }

    @Test
    fun qqNonMessageNotificationFallsBackToDefault() {
        val plan = engine.plan(
            notif(
                "com.tencent.mobileqq",
                category = MirrorCategory.STATUS,
                title = "QQ service update",
                channelId = "VPushChannel_1",
            )
        )

        assertEquals(LargeIconHandling.MIRROR, plan.largeIcon)
        assertEquals(null, plan.conversationSenderOverride)
    }

    @Test
    fun qqUnreadSuffixDoesNotAffectOtherApps() {
        val plan = engine.plan(
            notif(
                "com.example.chat",
                title = "开发群(28条新消息)",
            )
        )

        assertEquals(LargeIconHandling.MIRROR, plan.largeIcon)
        assertEquals(null, plan.conversationSenderOverride)
    }
}
