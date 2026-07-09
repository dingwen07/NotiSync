package net.extrawdw.apps.notisync.ancs

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MediaCustomAction
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.OriginPlatform

/**
 * The assembled AMS now-playing state: the latest value of every registered Player/Track attribute
 * ([Ams.PLAYER_ATTRS] / [Ams.TRACK_ATTRS]) plus the supported-command list, as accumulated by
 * [AmsMediaBridge] from Entity Update notifications.
 */
data class AmsNowPlaying(
    val playerName: String? = null,
    /** [Ams.PLAYBACK_PAUSED]…[Ams.PLAYBACK_FAST_FORWARDING]; null until the first PlaybackInfo. */
    val playbackState: Int? = null,
    val playbackRate: Float = 1f,
    /** Elapsed seconds into the track as of [elapsedAtMs] — the mapper extrapolates from there. */
    val elapsedSec: Double? = null,
    val elapsedAtMs: Long = 0L,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val durationSec: Double? = null,
    /** The AMS RemoteCommandIDs the active player currently accepts (Remote Command notifications). */
    val supportedCommands: List<Int> = emptyList(),
) {
    /** Whether there's anything to show — a track name (or at least an artist) is the card's content. */
    val hasTrack get() = !title.isNullOrBlank() || !artist.isNullOrBlank()

    /** Actively advancing (playing/rewinding/fast-forwarding), i.e. the card's play/pause shows "pause". */
    val isAdvancing get() = playbackState != null && playbackState != Ams.PLAYBACK_PAUSED
}

/**
 * Maps the AMS now-playing state to the platform-neutral [CapturedNotification] the media-mirroring
 * pipeline already understands ([NotificationStyle.MEDIA] + the mediaIsPlaying/Position/Duration/Actions
 * fields), exactly as [AncsNotificationMapper] does for notifications. Pure and unit-testable.
 *
 * AMS carries no artwork (text-only, like ANCS), so the card renders without album art — deliberately NOT
 * back-filled from a public catalog, since a match by artist+title isn't definitive. No app icon either:
 * the Player entity exposes only a localized display name, never a bundle id, so icon resolution can't run.
 */
object AmsNotificationMapper {
    /** Synthetic package for the mirrored now-playing card (AMS has no bundle id); one stable channel/tag. */
    const val MEDIA_PACKAGE = "ios.media"

    /** Source-key prefix for AMS captures — [AncsBleManager] routes MEDIA action events by it. */
    const val KEY_PREFIX = "ams"

    /** English fallback when the Player name hasn't arrived (labels ride the wire; v1 is not localized,
     *  matching Android-source custom actions, whose labels are whatever the source app baked in). */
    private const val FALLBACK_LABEL = "Media"

    // PlaybackState.ACTION_* values (== PlaybackStateCompat.ACTION_*), hardcoded so this stays free of
    // Android deps — the same equality [CapturedNotification.mediaActions] already documents.
    private const val ACTION_PAUSE = 0x2L
    private const val ACTION_PLAY = 0x4L
    private const val ACTION_SKIP_TO_PREVIOUS = 0x10L
    private const val ACTION_SKIP_TO_NEXT = 0x20L
    private const val ACTION_PLAY_PAUSE = 0x200L

    /** AMS commands that map to a standard transport control (the system-drawn button row). */
    private val STANDARD_ACTIONS = mapOf(
        Ams.CMD_PLAY to ACTION_PLAY,
        Ams.CMD_PAUSE to ACTION_PAUSE,
        Ams.CMD_TOGGLE_PLAY_PAUSE to ACTION_PLAY_PAUSE,
        Ams.CMD_NEXT_TRACK to ACTION_SKIP_TO_NEXT,
        Ams.CMD_PREVIOUS_TRACK to ACTION_SKIP_TO_PREVIOUS,
    )

    /** AMS commands surfaced as custom actions ([MediaCustomAction.action] = `ams:<RemoteCommandID>`,
     *  relayed back as a [net.extrawdw.notisync.protocol.MediaCommand.CUSTOM] press). The names feed the
     *  consumer's icon heuristics (shuffle/repeat/like/dislike/bookmark all match bundled icons). Volume
     *  up/down are deliberately absent — a notification card has no volume affordance. */
    private val CUSTOM_ACTIONS = listOf(
        Ams.CMD_ADVANCE_SHUFFLE_MODE to "Shuffle",
        Ams.CMD_ADVANCE_REPEAT_MODE to "Repeat",
        Ams.CMD_SKIP_BACKWARD to "Skip Back",
        Ams.CMD_SKIP_FORWARD to "Skip Forward",
        Ams.CMD_LIKE_TRACK to "Like",
        Ams.CMD_DISLIKE_TRACK to "Dislike",
        Ams.CMD_BOOKMARK_TRACK to "Bookmark",
    )

    /** The stable per-iPhone source key: ONE now-playing card that updates in place across tracks. */
    fun sourceKey(iphoneId: String) = "$KEY_PREFIX|$iphoneId|nowplaying"

    /** The custom-action id relayed for an AMS command, and its reverse ([commandOfCustomAction]). */
    fun customActionId(commandId: Int) = "$KEY_PREFIX:$commandId"

    /** Parse a relayed [MediaCustomAction.action] back to its RemoteCommandID; null if not AMS-shaped. */
    fun commandOfCustomAction(action: String): Int? =
        action.removePrefix("$KEY_PREFIX:").takeIf { it != action }?.toIntOrNull()

    fun map(
        clientId: ClientId,
        state: AmsNowPlaying,
        iphoneId: String,
        iphoneName: String?,
        now: Long,
    ): CapturedNotification {
        // Extrapolate the position from the last PlaybackInfo snapshot: iOS reports elapsed time only on
        // state changes (play/pause/seek/track), so an advancing track's position is elapsed + wall time
        // at the reported rate. The consumer's PlaybackState does the same forward-projection from here.
        val positionMs = state.elapsedSec?.let { elapsed ->
            val projected =
                if (state.isAdvancing) elapsed + (now - state.elapsedAtMs) / 1000.0 * state.playbackRate
                else elapsed
            (projected * 1000).toLong().coerceAtLeast(0L)
        }
        val supported = state.supportedCommands
        // No supported-command list yet (Remote Command notify hasn't landed) → leave mediaActions null so
        // the consumer falls back to its default transport row instead of drawing zero buttons.
        val actionsMask = supported
            .mapNotNull { STANDARD_ACTIONS[it] }
            .fold(0L) { acc, bit -> acc or bit }
            .takeIf { supported.isNotEmpty() }
        return CapturedNotification(
            sourceClientId = clientId,
            sourceKey = sourceKey(iphoneId),
            packageName = MEDIA_PACKAGE,
            appLabel = state.playerName?.takeIf { it.isNotBlank() } ?: FALLBACK_LABEL,
            // The consumer maps title → media TITLE, text → ARTIST, subText → ALBUM (MirrorMediaSessions).
            title = state.title?.takeIf { it.isNotBlank() },
            text = state.artist?.takeIf { it.isNotBlank() },
            subText = state.album?.takeIf { it.isNotBlank() },
            style = NotificationStyle.MEDIA,
            category = MirrorCategory.TRANSPORT,
            // LOW + silent channel: a now-playing card must never alert. Constant by design (every AMS
            // capture says the same), unlike the runtime-flipping iOS silent flag that forced the ANCS
            // channels HIGH — see MirrorChannels.importanceOf for the media carve-out.
            importance = MirrorImportance.LOW,
            channelSilent = true,
            onlyAlertOnce = true,
            postTime = now,
            originPlatform = OriginPlatform.IOS_ANCS,
            originDeviceName = iphoneName,
            originDeviceId = iphoneId,
            mediaIsPlaying = state.isAdvancing,
            mediaPositionMs = positionMs,
            mediaDurationMs = state.durationSec?.let { (it * 1000).toLong() },
            mediaActions = actionsMask,
            mediaCustomActions = CUSTOM_ACTIONS
                .filter { (cmd, _) -> cmd in supported }
                .map { (cmd, name) -> MediaCustomAction(customActionId(cmd), name) },
            // hasContentIntent stays false (no "open on iPhone" command), and actions stays empty — the
            // transport row is system-drawn from the media session, not mirrored action buttons.
        )
    }
}
