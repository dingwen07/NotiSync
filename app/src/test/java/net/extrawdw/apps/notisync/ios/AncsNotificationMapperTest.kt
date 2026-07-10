package net.extrawdw.apps.notisync.ios

import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsNotificationMapperTest {

    @Test
    fun map_defaultAncsNotificationIsHighImportance() {
        val notif = mapWithFlags(0)

        assertEquals(MirrorImportance.HIGH, notif.importance)
    }

    @Test
    fun map_silentFlaggedAncsNotificationStaysHighImportance() {
        // The iOS per-notification "silent" flag must NOT lower the mirrored channel's importance — it flips at
        // runtime (iOS sets it whenever the iPhone is unlocked/in use) and, fed into the channel's importance,
        // would pin the channel to Silent permanently (the OS never raises a channel). ANCS notifications always
        // map to HIGH; quieting a single post (the connect-time backlog) is done via setSilent() at post time.
        val notif = mapWithFlags(Ancs.FLAG_SILENT)

        assertEquals(MirrorImportance.HIGH, notif.importance)
    }

    @Test
    fun map_pairedActionLabels_mirrorAsAncsActionIds() {
        // A call-style notification (Answer/Decline): both actions mirror, indexed by ANCS ActionID
        // so a peer's ActionEvent maps straight onto PerformNotificationAction.
        val notif = mapWithFlags(
            Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION,
            positiveLabel = "Answer",
            negativeLabel = "Decline",
        )
        assertEquals(listOf("Answer", "Decline"), notif.actions.map { it.title })
        assertEquals(listOf(Ancs.ACTION_POSITIVE, Ancs.ACTION_NEGATIVE), notif.actions.map { it.index })
        assertTrue(notif.actions.none { it.remoteInput }) // ANCS has no text input
        // ANCS has no "open on iPhone" command — peers must never offer tap-to-open.
        assertFalse(notif.hasContentIntent)
    }

    @Test
    fun map_positiveOnly_mirrorsSingleAction() {
        val notif = mapWithFlags(Ancs.FLAG_POSITIVE_ACTION, positiveLabel = "Accept")
        assertEquals(listOf(Ancs.ACTION_POSITIVE), notif.actions.map { it.index })
    }

    @Test
    fun map_loneNegativeAction_isNotMirrored() {
        // Nearly every iOS notification carries a bare negative ("Clear") action — dismissal sync
        // already performs it on a swipe, so mirroring it as a button on every mirror is pure noise.
        val notif = mapWithFlags(Ancs.FLAG_NEGATIVE_ACTION, negativeLabel = "Clear")
        assertTrue(notif.actions.isEmpty())
    }

    @Test
    fun map_incomingCall_marksCallTypeIncomingSoTheReceiverRings() {
        // A live incoming call is the one call-category notification that should ring: mark it INCOMING.
        val notif = mapWithFlags(
            Ancs.FLAG_POSITIVE_ACTION or Ancs.FLAG_NEGATIVE_ACTION,
            categoryId = Ancs.CAT_INCOMING_CALL,
            positiveLabel = "Answer",
            negativeLabel = "Decline",
        )
        assertEquals(MirrorCategory.CALL, notif.category)
        assertEquals(CallType.INCOMING, notif.callType)
    }

    @Test
    fun map_missedCall_isCallCategoryButNeverRings() {
        // A missed call shares MirrorCategory.CALL but must NOT ring — no callType, so the receiver mirrors it
        // as an ordinary notification instead of a ringing incoming call.
        val notif = mapWithFlags(0, categoryId = Ancs.CAT_MISSED_CALL)
        assertEquals(MirrorCategory.CALL, notif.category)
        assertNull(notif.callType)
    }

    @Test
    fun map_voicemail_isCallCategoryButNeverRings() {
        val notif = mapWithFlags(0, categoryId = Ancs.CAT_VOICEMAIL)
        assertEquals(MirrorCategory.CALL, notif.category)
        assertNull(notif.callType)
    }

    @Test
    fun map_socialNotification_hasNoCallType() {
        assertNull(mapWithFlags(0).callType)
    }

    private fun mapWithFlags(
        flags: Int,
        categoryId: Int = Ancs.CAT_SOCIAL,
        positiveLabel: String? = null,
        negativeLabel: String? = null,
    ) = AncsNotificationMapper.map(
        clientId = ClientId("android-bridge"),
        record = AncsRecord(
            source = Ancs.SourcePacket(
                eventId = Ancs.EVENT_ADDED,
                eventFlags = flags,
                categoryId = categoryId,
                categoryCount = 1,
                notificationUid = 42,
            ),
            bundleId = "net.whatsapp.WhatsApp",
            displayName = "WhatsApp",
            title = "Alice",
            subtitle = null,
            message = "Hello",
            date = null,
            positiveActionLabel = positiveLabel,
            negativeActionLabel = negativeLabel,
        ),
        iphoneId = "iphone",
        iphoneName = "Dingwen's iPhone",
        androidPackage = "com.whatsapp",
        now = 123L,
    )
}
