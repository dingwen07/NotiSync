package net.extrawdw.apps.notisync.notification

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MediaCommand
import net.extrawdw.notisync.protocol.MediaCustomAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gives each mirrored MEDIA notification its own [MediaSessionCompat] so the platform renders it as a REAL
 * media notification — album art, a transport row, and the system media-controls slot (Quick Settings /
 * lock screen), which on Android 13+ only appears when the notification carries a media-session token.
 *
 * No foreground service is needed (we play no audio — the session exists only to drive the controls UI, and
 * the always-bound listener process keeps it alive) and it makes no sound. The session mirrors the source's
 * playback: title/artist/art metadata plus a [PlaybackStateCompat] carrying the source's play/pause state,
 * position (a seekbar), and the transport actions it declared — so the system draws standard
 * prev/play-pause/next buttons with its own icons. A press fires a callback here, which relays a
 * [MediaCommand] to the origin via [onCommand]; the origin replays it on the real source session.
 *
 * Sessions are keyed by mirror tag, updated on each render, and released when the mirror is cleared or
 * dismissed (otherwise the media-controls card lingers as a ghost). MediaSessionCompat callbacks need a
 * Looper, so every session object is created/mutated on the MAIN thread; [apply] briefly blocks the (off-main)
 * render caller to hand back the token the notification build needs.
 */
class MirrorMediaSessions(
    context: Context,
    private val onCommand: (ClientId, String, MediaCommand, Long?, String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** tag -> session. Touched only on the main thread (via [onMain]/[main]). */
    private val sessions = HashMap<String, MediaSessionCompat>()

    /** Create/update the session for [tag]; returns its token for `MediaStyle.setMediaSession`. */
    fun apply(tag: String, notif: CapturedNotification, albumArt: Bitmap?): MediaSessionCompat.Token? =
        onMain {
            val session = sessions.getOrPut(tag) {
                MediaSessionCompat(appContext, MEDIA_SESSION_TAG).apply {
                    setCallback(callbackFor(notif.sourceClientId, notif.sourceKey))
                    isActive = true
                }
            }
            session.setMetadata(metadataFor(notif, albumArt))
            session.setPlaybackState(playbackStateFor(notif))
            session.sessionToken
        }

    /** Deactivate + release the session for [tag] (playback stopped / mirror gone), clearing its media card. */
    fun release(tag: String) {
        main.post {
            sessions.remove(tag)?.let { runCatching { it.isActive = false; it.release() } }
        }
    }

    private fun metadataFor(notif: CapturedNotification, albumArt: Bitmap?): MediaMetadataCompat =
        MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, notif.title ?: notif.appLabel)
            notif.text?.let { putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
            notif.subText?.let { putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
            albumArt?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            // Duration drives the seekbar's extent; 0 = unknown (no seekbar), which is fine.
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, notif.mediaDurationMs ?: 0L)
        }.build()

    private fun playbackStateFor(notif: CapturedNotification): PlaybackStateCompat {
        val playing = notif.mediaIsPlaying == true
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = notif.mediaPositionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        // Only advertise transport controls we can actually service (they relay to the origin). Intersect the
        // source's declared actions with that set; if the source reported none, fall back to the common set so
        // buttons still appear. PlaybackState.ACTION_* values equal these PlaybackStateCompat.ACTION_* values.
        val declared = notif.mediaActions ?: DEFAULT_ACTIONS
        val actions = (declared and SUPPORTED_ACTIONS).takeIf { it != 0L } ?: (DEFAULT_ACTIONS and SUPPORTED_ACTIONS)
        return PlaybackStateCompat.Builder()
            .setState(state, position, if (playing) 1f else 0f)
            .setActions(actions)
            .apply {
                // App-specific buttons (star/like/shuffle/…): the system draws these from the custom actions.
                // A custom action's icon MUST be a drawable in OUR package (there's no bitmap API, and the
                // source's resId is meaningless here), so we heuristic-map the common ones to bundled icons
                // with a generic fallback. The press relays back by action id and runs faithfully on the origin.
                notif.mediaCustomActions.forEach { ca ->
                    addCustomAction(
                        PlaybackStateCompat.CustomAction
                            .Builder(ca.action, ca.name.ifBlank { ca.action }, iconFor(ca))
                            .build()
                    )
                }
            }
            .build()
    }

    /** Best-effort icon for a custom action, keyed on its id + name (the source app's own icon can't cross
     *  devices). Order matters: "dislike" contains "like", so thumbs-down is matched before favorite. */
    private fun iconFor(ca: MediaCustomAction): Int {
        val key = "${ca.action} ${ca.name}".lowercase()
        return when {
            "shuffle" in key -> R.drawable.ic_media_shuffle
            "repeat" in key || "loop" in key -> R.drawable.ic_media_repeat
            "dislike" in key || "thumb_down" in key || "thumbsdown" in key || "thumbs_down" in key ->
                R.drawable.ic_media_thumb_down
            "thumb_up" in key || "thumbsup" in key || "thumbs_up" in key -> R.drawable.ic_media_thumb_up
            "favorite" in key || "like" in key || "heart" in key || "love" in key ||
                "star" in key || "save" in key || "bookmark" in key -> R.drawable.ic_media_favorite
            else -> R.drawable.ic_media_custom
        }
    }

    private fun callbackFor(clientId: ClientId, sourceKey: String) = object : MediaSessionCompat.Callback() {
        override fun onPlay() = onCommand(clientId, sourceKey, MediaCommand.PLAY, null, null)
        override fun onPause() = onCommand(clientId, sourceKey, MediaCommand.PAUSE, null, null)
        override fun onSkipToNext() = onCommand(clientId, sourceKey, MediaCommand.NEXT, null, null)
        override fun onSkipToPrevious() = onCommand(clientId, sourceKey, MediaCommand.PREVIOUS, null, null)
        override fun onStop() = onCommand(clientId, sourceKey, MediaCommand.STOP, null, null)
        override fun onSeekTo(pos: Long) = onCommand(clientId, sourceKey, MediaCommand.SEEK, pos, null)
        override fun onCustomAction(action: String?, extras: Bundle?) {
            action?.let { onCommand(clientId, sourceKey, MediaCommand.CUSTOM, null, it) }
        }
    }

    /** Run [block] on the main thread and return its result. render() runs off-main, but session objects must
     *  be touched on a Looper thread; the block is a few fast binder calls, bounded by [MAIN_WAIT_MS]. */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val latch = CountDownLatch(1)
        var result: T? = null
        main.post {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        return if (latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) result else null
    }

    private companion object {
        const val MEDIA_SESSION_TAG = "NotiSyncMirror"
        const val MAIN_WAIT_MS = 500L
        val SUPPORTED_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
        val DEFAULT_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
    }
}
