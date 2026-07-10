package net.extrawdw.apps.notisync.notification.mirror

import android.app.Notification
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.NotificationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingCallPresentationTest {
    private fun notification(
        callerName: String? = null,
        title: String? = null,
        text: String? = null,
        verification: String? = null,
        actions: List<NotificationAction> = emptyList(),
        answerIndex: Int? = null,
        declineIndex: Int? = null,
        hangUpIndex: Int? = null,
    ) = CapturedNotification(
        sourceClientId = ClientId("source-device"),
        sourceKey = "call-key",
        packageName = "com.example.calls",
        appLabel = "Example Calls",
        title = title,
        text = text,
        postTime = 123L,
        callerName = callerName,
        callVerificationText = verification,
        actions = actions,
        callAnswerIndex = answerIndex,
        callDeclineIndex = declineIndex,
        callHangUpIndex = hangUpIndex,
    )

    @Test
    fun presentationShowsCallerCallDetailsAndSourceDevice() {
        val presentation = notification(
            callerName = "Taylor",
            title = "Taylor",
            text = "Incoming video call",
            verification = "Verified caller",
        ).incomingCallPresentation("Dingwen's iPhone")

        assertEquals("Taylor", presentation.callerName)
        assertEquals("Incoming video call", presentation.callText)
        assertEquals("Verified caller", presentation.verificationText)
        assertEquals("Dingwen's iPhone", presentation.deviceName)
        assertEquals("Example Calls", presentation.appLabel)
    }

    @Test
    fun presentationFallsBackToTitleAndSuppressesDuplicateText() {
        val presentation = notification(
            callerName = " ",
            title = "Jordan",
            text = "Jordan",
        ).incomingCallPresentation("Pixel")

        assertEquals("Jordan", presentation.callerName)
        assertNull(presentation.callText)
    }

    @Test
    fun explicitCallActionIndicesBeatSemanticFallbacks() {
        val semanticAnswer = NotificationAction(
            index = 0,
            title = "Call",
            semanticAction = Notification.Action.SEMANTIC_ACTION_CALL,
        )
        val explicitAnswer = NotificationAction(index = 1, title = "Answer")
        val explicitDecline = NotificationAction(index = 2, title = "Decline")
        val selected = notification(
            actions = listOf(semanticAnswer, explicitDecline, explicitAnswer),
            answerIndex = 1,
            declineIndex = 2,
        ).incomingCallActions()

        assertEquals(explicitAnswer, selected.answer)
        assertEquals(explicitDecline, selected.decline)
    }

    @Test
    fun semanticCallAndHangUpAreSafeFallbacks() {
        val answer = NotificationAction(
            index = 4,
            title = "Accept",
            semanticAction = Notification.Action.SEMANTIC_ACTION_CALL,
        )
        val hangUp = NotificationAction(index = 7, title = "End")
        val selected = notification(
            actions = listOf(hangUp, answer),
            hangUpIndex = 7,
        ).incomingCallActions()

        assertEquals(answer, selected.answer)
        assertEquals(hangUp, selected.decline)
    }
}
