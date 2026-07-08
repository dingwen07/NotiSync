package net.extrawdw.apps.notisync.ancs

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) protocol constants, packet parsers, and request builders.
 *
 * ANCS is published by the **iOS device** as a GATT server; the accessory (this Android phone) is the
 * GATT *client* (the "Notification Consumer"). The accessory learns of notifications on the **Notification
 * Source** characteristic (8-byte packets), asks for details by writing **Control Point**, and receives
 * the answers on **Data Source** (which may be fragmented across multiple notifications and must be
 * reassembled). All three characteristics require a bonded (encrypted) link.
 *
 * Everything here is pure (no Android deps) so the wire format is unit-testable. Reference: Apple's
 * "Apple Notification Center Service (ANCS) Specification".
 */
object Ancs {
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Standard Client Characteristic Configuration descriptor (for enabling notifications). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // EventID (Notification Source byte 0)
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    // EventFlags bitmask (Notification Source byte 1)
    const val FLAG_SILENT = 0x01
    const val FLAG_IMPORTANT = 0x02
    const val FLAG_PRE_EXISTING = 0x04
    const val FLAG_POSITIVE_ACTION = 0x08
    const val FLAG_NEGATIVE_ACTION = 0x10

    // CategoryID (Notification Source byte 2)
    const val CAT_OTHER = 0
    const val CAT_INCOMING_CALL = 1
    const val CAT_MISSED_CALL = 2
    const val CAT_VOICEMAIL = 3
    const val CAT_SOCIAL = 4
    const val CAT_SCHEDULE = 5
    const val CAT_EMAIL = 6
    const val CAT_NEWS = 7
    const val CAT_HEALTH_FITNESS = 8
    const val CAT_BUSINESS_FINANCE = 9
    const val CAT_LOCATION = 10
    const val CAT_ENTERTAINMENT = 11

    // Control Point CommandID
    const val CMD_GET_NOTIFICATION_ATTRIBUTES = 0
    const val CMD_GET_APP_ATTRIBUTES = 1
    const val CMD_PERFORM_NOTIFICATION_ACTION = 2

    // NotificationAttributeID
    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    // AppAttributeID
    const val APP_ATTR_DISPLAY_NAME = 0

    // ActionID (PerformNotificationAction)
    const val ACTION_POSITIVE = 0
    const val ACTION_NEGATIVE = 1

    /** The attribute ids we request for each notification, in request order (drives response parsing). */
    val NOTIFICATION_ATTRS =
        intArrayOf(ATTR_APP_IDENTIFIER, ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE, ATTR_DATE)

    // ---- Notification Source ----

    data class SourcePacket(
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        val notificationUid: Int,
    ) {
        val isSilent get() = eventFlags and FLAG_SILENT != 0
        val isImportant get() = eventFlags and FLAG_IMPORTANT != 0
        val isPreExisting get() = eventFlags and FLAG_PRE_EXISTING != 0
        val hasPositiveAction get() = eventFlags and FLAG_POSITIVE_ACTION != 0
        val hasNegativeAction get() = eventFlags and FLAG_NEGATIVE_ACTION != 0
        val isAdded get() = eventId == EVENT_ADDED
        val isModified get() = eventId == EVENT_MODIFIED
        val isRemoved get() = eventId == EVENT_REMOVED
    }

    /** Parse an 8-byte Notification Source packet (UID is little-endian); null if malformed. */
    fun parseSource(b: ByteArray): SourcePacket? {
        if (b.size < 8) return null
        return SourcePacket(
            eventId = b[0].toInt() and 0xFF,
            eventFlags = b[1].toInt() and 0xFF,
            categoryId = b[2].toInt() and 0xFF,
            categoryCount = b[3].toInt() and 0xFF,
            notificationUid = le32(b, 4),
        )
    }

    // ---- Control Point requests ----

    /**
     * GetNotificationAttributes request: AppIdentifier, Title, Subtitle, Message, Date — plus, when
     * [includeActionLabels], the Positive/Negative Action Labels (requested only for a notification whose
     * EventFlags advertise an action; iOS answers an inapplicable label as zero-length). Per the spec only
     * the Title/Subtitle/Message attributes take a 2-byte max-length parameter ([maxTextLen]); AppIdentifier,
     * Date, and the action labels do not. Must be kept in sync with [NOTIFICATION_ATTRS] (parse order);
     * [notificationAttrCount] is the matching response-attribute count.
     */
    fun buildGetNotificationAttributes(
        uid: Int,
        maxTextLen: Int = 2048,
        includeActionLabels: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_NOTIFICATION_ATTRIBUTES)
        writeLe32(out, uid)
        out.write(ATTR_APP_IDENTIFIER)
        out.write(ATTR_TITLE); writeLe16(out, maxTextLen)
        out.write(ATTR_SUBTITLE); writeLe16(out, maxTextLen)
        out.write(ATTR_MESSAGE); writeLe16(out, maxTextLen)
        out.write(ATTR_DATE)
        if (includeActionLabels) {
            out.write(ATTR_POSITIVE_ACTION_LABEL)
            out.write(ATTR_NEGATIVE_ACTION_LABEL)
        }
        return out.toByteArray()
    }

    /** Response-attribute count for a [buildGetNotificationAttributes] request with the same flag. */
    fun notificationAttrCount(includeActionLabels: Boolean): Int =
        NOTIFICATION_ATTRS.size + if (includeActionLabels) 2 else 0

    /** GetAppAttributes request: the NUL-terminated app identifier followed by the DisplayName attribute id. */
    fun buildGetAppAttributes(appId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_APP_ATTRIBUTES)
        out.write(appId.toByteArray(Charsets.UTF_8))
        out.write(0) // NUL terminator
        out.write(APP_ATTR_DISPLAY_NAME)
        return out.toByteArray()
    }

    /** PerformNotificationAction: best-effort positive/negative action (e.g. a "Clear" negative action). */
    fun buildPerformAction(uid: Int, actionId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_PERFORM_NOTIFICATION_ACTION)
        writeLe32(out, uid)
        out.write(actionId)
        return out.toByteArray()
    }

    // ---- Data Source responses (may be reassembled from fragments before parsing) ----

    data class NotificationAttributes(val uid: Int, val values: Map<Int, String>) {
        val appId get() = values[ATTR_APP_IDENTIFIER]
        val title get() = values[ATTR_TITLE]?.takeIf { it.isNotEmpty() }
        val subtitle get() = values[ATTR_SUBTITLE]?.takeIf { it.isNotEmpty() }
        val message get() = values[ATTR_MESSAGE]?.takeIf { it.isNotEmpty() }
        val date get() = values[ATTR_DATE]?.takeIf { it.isNotEmpty() }
        val positiveActionLabel get() = values[ATTR_POSITIVE_ACTION_LABEL]?.takeIf { it.isNotEmpty() }
        val negativeActionLabel get() = values[ATTR_NEGATIVE_ACTION_LABEL]?.takeIf { it.isNotEmpty() }
    }

    /**
     * Parse a (possibly partial) GetNotificationAttributes response. Returns null until [buf] holds the full
     * response for [attrCount] attributes — the caller appends Data Source fragments and re-parses each time.
     * Layout: `[cmd=0][uid(4)]` then repeating `[attrId(1)][len(2 LE)][value(len)]`.
     */
    fun parseNotificationAttributes(buf: ByteArray, attrCount: Int): NotificationAttributes? {
        if (buf.size < 5 || (buf[0].toInt() and 0xFF) != CMD_GET_NOTIFICATION_ATTRIBUTES) return null
        var i = 5
        val values = HashMap<Int, String>(attrCount)
        repeat(attrCount) {
            if (i + 3 > buf.size) return null
            val attrId = buf[i].toInt() and 0xFF
            val len = le16(buf, i + 1)
            i += 3
            if (i + len > buf.size) return null
            values[attrId] = String(buf, i, len, Charsets.UTF_8)
            i += len
        }
        return NotificationAttributes(le32(buf, 1), values)
    }

    data class AppAttributes(val appId: String, val displayName: String?)

    /**
     * Parse a (possibly partial) GetAppAttributes response, or null until complete. Layout:
     * `[cmd=1][appId NUL-terminated]` then repeating `[attrId(1)][len(2 LE)][value(len)]`.
     */
    fun parseAppAttributes(buf: ByteArray, attrCount: Int = 1): AppAttributes? {
        if (buf.isEmpty() || (buf[0].toInt() and 0xFF) != CMD_GET_APP_ATTRIBUTES) return null
        var nul = -1
        for (j in 1 until buf.size) if (buf[j].toInt() == 0) {
            nul = j; break
        }
        if (nul < 0) return null
        val appId = String(buf, 1, nul - 1, Charsets.UTF_8)
        var i = nul + 1
        var displayName: String? = null
        repeat(attrCount) {
            if (i + 3 > buf.size) return null
            val attrId = buf[i].toInt() and 0xFF
            val len = le16(buf, i + 1)
            i += 3
            if (i + len > buf.size) return null
            if (attrId == APP_ATTR_DISPLAY_NAME) displayName = String(buf, i, len, Charsets.UTF_8)
            i += len
        }
        return AppAttributes(appId, displayName?.takeIf { it.isNotEmpty() })
    }

    /** The leading command id of a Data Source buffer (to route reassembly), or -1 if empty. */
    fun dataSourceCommand(buf: ByteArray): Int = if (buf.isEmpty()) -1 else buf[0].toInt() and 0xFF

    /** The notification UID echoed in a GetNotificationAttributes response header (`[cmd][uid(4 LE)]`), or
     *  null if [buf] isn't (yet) such a response — used to correlate a Data Source fragment with its request. */
    fun notificationResponseUid(buf: ByteArray): Int? =
        if (buf.size >= 5 && (buf[0].toInt() and 0xFF) == CMD_GET_NOTIFICATION_ATTRIBUTES) le32(
            buf,
            1
        ) else null

    /** Parse the ANCS Date attribute (`yyyyMMdd'T'HHmmss`, device-local) to epoch millis, or null. */
    fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat(
                "yyyyMMdd'T'HHmmss",
                Locale.US
            ).parse(s)?.time
        }.getOrNull()
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLe16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write(
            (v shr 24) and 0xFF
        )
    }
}
