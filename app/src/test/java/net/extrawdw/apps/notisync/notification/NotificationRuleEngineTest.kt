package net.extrawdw.apps.notisync.notification

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
        isConversation: Boolean = false
    ) =
        CapturedNotification(
            sourceClientId = ClientId("x"),
            sourceKey = "k",
            packageName = pkg,
            appLabel = "L",
            category = category,
            isConversation = isConversation,
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
}
