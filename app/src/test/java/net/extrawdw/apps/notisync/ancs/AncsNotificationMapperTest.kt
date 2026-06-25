package net.extrawdw.apps.notisync.ancs

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorImportance
import org.junit.Assert.assertEquals
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

    private fun mapWithFlags(flags: Int) = AncsNotificationMapper.map(
        clientId = ClientId("android-bridge"),
        record = AncsRecord(
            source = Ancs.SourcePacket(
                eventId = Ancs.EVENT_ADDED,
                eventFlags = flags,
                categoryId = Ancs.CAT_SOCIAL,
                categoryCount = 1,
                notificationUid = 42,
            ),
            bundleId = "net.whatsapp.WhatsApp",
            displayName = "WhatsApp",
            title = "Alice",
            subtitle = null,
            message = "Hello",
            date = null,
        ),
        iphoneId = "iphone",
        iphoneName = "Dingwen's iPhone",
        androidPackage = "com.whatsapp",
        now = 123L,
    )
}
