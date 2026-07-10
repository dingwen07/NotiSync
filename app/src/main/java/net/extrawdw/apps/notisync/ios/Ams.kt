package net.extrawdw.apps.notisync.ios

import java.util.UUID
import kotlin.math.roundToInt

/**
 * Apple Media Service (AMS) protocol constants, packet parsers, and request builders.
 *
 * AMS is the media sibling of ANCS: published by the **iOS device** as a GATT server on the same
 * bonded link, consumed by the accessory (this Android phone) as the GATT *client* (the "Media
 * Remote"). The accessory subscribes to **Entity Update** and writes one registration per entity
 * (Player / Track) naming the attributes it wants; iOS then notifies each attribute's current value
 * and every change. Transport presses are written to **Remote Command** (one command byte), whose
 * own notifications carry the currently-supported command list. A value too long for one
 * notification arrives with the Truncated flag; the full value is fetched by writing **Entity
 * Attribute** and reading it back.
 *
 * Everything here is pure (no Android deps) so the wire format is unit-testable. Reference: Apple's
 * "Apple Media Service Reference".
 */
object Ams {
    val SERVICE_UUID: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

    // RemoteCommandID
    const val CMD_PLAY = 0
    const val CMD_PAUSE = 1
    const val CMD_TOGGLE_PLAY_PAUSE = 2
    const val CMD_NEXT_TRACK = 3
    const val CMD_PREVIOUS_TRACK = 4
    const val CMD_VOLUME_UP = 5
    const val CMD_VOLUME_DOWN = 6
    const val CMD_ADVANCE_REPEAT_MODE = 7
    const val CMD_ADVANCE_SHUFFLE_MODE = 8
    const val CMD_SKIP_FORWARD = 9
    const val CMD_SKIP_BACKWARD = 10
    const val CMD_LIKE_TRACK = 11
    const val CMD_DISLIKE_TRACK = 12
    const val CMD_BOOKMARK_TRACK = 13

    // EntityID
    const val ENTITY_PLAYER = 0
    const val ENTITY_QUEUE = 1
    const val ENTITY_TRACK = 2

    // PlayerAttributeID
    const val PLAYER_ATTR_NAME = 0
    const val PLAYER_ATTR_PLAYBACK_INFO = 1
    const val PLAYER_ATTR_VOLUME = 2

    // TrackAttributeID
    const val TRACK_ATTR_ARTIST = 0
    const val TRACK_ATTR_ALBUM = 1
    const val TRACK_ATTR_TITLE = 2
    const val TRACK_ATTR_DURATION = 3

    // EntityUpdateFlags (Entity Update notification byte 2)
    const val FLAG_TRUNCATED = 0x01

    // PlaybackState (first field of the PlaybackInfo attribute)
    const val PLAYBACK_PAUSED = 0
    const val PLAYBACK_PLAYING = 1
    const val PLAYBACK_REWINDING = 2
    const val PLAYBACK_FAST_FORWARDING = 3

    /** The Player attributes we register for: name, play state, and relative volume feedback. */
    val PLAYER_ATTRS = intArrayOf(PLAYER_ATTR_NAME, PLAYER_ATTR_PLAYBACK_INFO, PLAYER_ATTR_VOLUME)

    /** The Track attributes we register for, i.e. everything the now-playing card renders. */
    val TRACK_ATTRS =
        intArrayOf(TRACK_ATTR_ARTIST, TRACK_ATTR_ALBUM, TRACK_ATTR_TITLE, TRACK_ATTR_DURATION)

    // ---- Entity Update notifications ----

    /** One Entity Update: [entityId][attributeId][flags][value…] — the value is UTF-8, possibly empty
     *  (an attribute that became absent, e.g. the player closed) and possibly [truncated]. */
    data class EntityUpdate(
        val entityId: Int,
        val attributeId: Int,
        val flags: Int,
        val value: String,
    ) {
        /** The value didn't fit the notification — fetch the full one over Entity Attribute. */
        val truncated get() = flags and FLAG_TRUNCATED != 0
    }

    /** Parse an Entity Update notification; null if malformed (needs the 3-byte header). */
    fun parseEntityUpdate(b: ByteArray): EntityUpdate? {
        if (b.size < 3) return null
        return EntityUpdate(
            entityId = b[0].toInt() and 0xFF,
            attributeId = b[1].toInt() and 0xFF,
            flags = b[2].toInt() and 0xFF,
            value = String(b, 3, b.size - 3, Charsets.UTF_8),
        )
    }

    /** Parse a Remote Command notification: the currently-supported RemoteCommandIDs, one per byte. */
    fun parseSupportedCommands(b: ByteArray): List<Int> = b.map { it.toInt() and 0xFF }

    // ---- Player PlaybackInfo attribute ----

    /** The parsed PlaybackInfo attribute: `"<PlaybackState>,<PlaybackRate>,<ElapsedTime>"`. */
    data class PlaybackInfo(
        val state: Int,
        /** Playback rate (1.0 = normal); the seekbar extrapolates position at this rate. */
        val rate: Float,
        /** Elapsed time into the track, in seconds, at the moment iOS sent the update. */
        val elapsedSec: Double,
    ) {
        /** Actively advancing (playing / rewinding / fast-forwarding) as opposed to paused. */
        val isAdvancing get() = state != PLAYBACK_PAUSED
    }

    /** Parse a PlaybackInfo value; null when malformed or empty (no active player). */
    fun parsePlaybackInfo(s: String): PlaybackInfo? {
        val parts = s.split(',')
        if (parts.size < 3) return null
        return PlaybackInfo(
            state = parts[0].toIntOrNull() ?: return null,
            rate = parts[1].toFloatOrNull() ?: return null,
            elapsedSec = parts[2].toDoubleOrNull() ?: return null,
        )
    }

    /** Parse the Player Volume attribute into a 0..100 UI scale. AMS sends a normalized 0.0..1.0 value;
     *  accept percentage-shaped values too so a vendor quirk does not disable volume controls. */
    fun parseVolumePercent(s: String): Int? {
        val raw = s.trim().toDoubleOrNull() ?: return null
        val percent = if (raw <= 1.0) raw * 100.0 else raw
        return percent.roundToInt().coerceIn(0, 100)
    }

    // ---- Requests ----

    /** Entity Update registration: subscribe to [attrs] of [entityId] (write to Entity Update). */
    fun buildEntityUpdateRegistration(entityId: Int, attrs: IntArray): ByteArray {
        val out = ByteArray(1 + attrs.size)
        out[0] = entityId.toByte()
        attrs.forEachIndexed { i, attr -> out[1 + i] = attr.toByte() }
        return out
    }

    /** Entity Attribute request: name the attribute whose full (untruncated) value to expose for read. */
    fun buildEntityAttributeRequest(entityId: Int, attributeId: Int): ByteArray =
        byteArrayOf(entityId.toByte(), attributeId.toByte())

    /** Remote Command request: one RemoteCommandID byte (write to Remote Command). */
    fun buildRemoteCommand(commandId: Int): ByteArray = byteArrayOf(commandId.toByte())
}
