package net.extrawdw.apps.notisync.ancs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class AncsPacketParseTest {

    @Test
    fun parseSource_decodesEventAndLittleEndianUid() {
        // Added · Important+PositiveAction · Social · count 3 · UID 0x04030201
        val packet = byteArrayOf(
            0,
            (Ancs.FLAG_IMPORTANT or Ancs.FLAG_POSITIVE_ACTION).toByte(),
            4,
            3,
            1,
            2,
            3,
            4
        )
        val p = Ancs.parseSource(packet)!!
        assertEquals(Ancs.EVENT_ADDED, p.eventId)
        assertTrue(p.isAdded)
        assertTrue(p.isImportant)
        assertEquals(false, p.isSilent)
        assertEquals(Ancs.CAT_SOCIAL, p.categoryId)
        assertEquals(3, p.categoryCount)
        assertEquals(0x04030201, p.notificationUid)
    }

    @Test
    fun parseSource_rejectsShortPacket() {
        assertNull(Ancs.parseSource(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun buildGetNotificationAttributes_onlyTextAttrsCarryLength() {
        val req = Ancs.buildGetNotificationAttributes(uid = 0x01020304, maxTextLen = 512)
        // [cmd=0][uid LE(4)] [appId][title][len=512 LE][subtitle][len][message][len][date]
        val expected = ByteArrayOutputStream().apply {
            write(0)
            write(byteArrayOf(0x04, 0x03, 0x02, 0x01)) // uid little-endian
            write(Ancs.ATTR_APP_IDENTIFIER)
            write(Ancs.ATTR_TITLE); write(0x00); write(0x02)    // 512 LE
            write(Ancs.ATTR_SUBTITLE); write(0x00); write(0x02)
            write(Ancs.ATTR_MESSAGE); write(0x00); write(0x02)
            write(Ancs.ATTR_DATE)
        }.toByteArray()
        assertArrayEquals(expected, req)
    }

    @Test
    fun parseNotificationAttributes_reassemblesAcrossFragmentsAndDecodesUtf8() {
        // Build a full response: cmd 0, uid, then appId/title/subtitle/message/date tuples.
        val emoji = "Hi 👋" // multi-byte UTF-8 to prove length is byte-based, not char-based
        val full = ByteArrayOutputStream().apply {
            write(0)
            write(byteArrayOf(0x01, 0x00, 0x00, 0x00)) // uid = 1
            tuple(Ancs.ATTR_APP_IDENTIFIER, "net.whatsapp.WhatsApp")
            tuple(Ancs.ATTR_TITLE, "Alice")
            tuple(Ancs.ATTR_SUBTITLE, "")
            tuple(Ancs.ATTR_MESSAGE, emoji)
            tuple(Ancs.ATTR_DATE, "20240912T194126")
        }.toByteArray()

        // A partial buffer (one byte short) must not parse yet.
        assertNull(
            Ancs.parseNotificationAttributes(
                full.copyOf(full.size - 1),
                Ancs.NOTIFICATION_ATTRS.size
            )
        )

        val parsed = Ancs.parseNotificationAttributes(full, Ancs.NOTIFICATION_ATTRS.size)!!
        assertEquals(1, parsed.uid)
        assertEquals("net.whatsapp.WhatsApp", parsed.appId)
        assertEquals("Alice", parsed.title)
        assertNull(parsed.subtitle) // empty string normalizes to null
        assertEquals(emoji, parsed.message)
        // Date is device-local, so assert it parses to a positive instant rather than a fixed epoch.
        assertTrue((Ancs.parseDate(parsed.date) ?: 0L) > 0L)
    }

    @Test
    fun parseAppAttributes_readsNulTerminatedIdAndDisplayName() {
        val buf = ByteArrayOutputStream().apply {
            write(1)
            write("net.whatsapp.WhatsApp".toByteArray()); write(0)
            tuple(Ancs.APP_ATTR_DISPLAY_NAME, "WhatsApp")
        }.toByteArray()
        val parsed = Ancs.parseAppAttributes(buf)!!
        assertEquals("net.whatsapp.WhatsApp", parsed.appId)
        assertEquals("WhatsApp", parsed.displayName)
    }

    @Test
    fun parseAppAttributes_incompleteReturnsNull() {
        // Header only, no attribute tuple yet.
        val buf = ByteArrayOutputStream().apply { write(1); write("a".toByteArray()); write(0) }
            .toByteArray()
        assertNull(Ancs.parseAppAttributes(buf))
    }

    @Test
    fun parseDate_handlesAncsFormatAndJunk() {
        assertNull(Ancs.parseDate(null))
        assertNull(Ancs.parseDate(""))
        assertNull(Ancs.parseDate("not-a-date"))
        // A valid ANCS date parses to some epoch millis.
        assertTrue((Ancs.parseDate("20240912T194126") ?: 0L) > 0L)
    }

    private fun ByteArrayOutputStream.tuple(attrId: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(attrId)
        write(bytes.size and 0xFF); write((bytes.size shr 8) and 0xFF)
        write(bytes)
    }
}
