package net.extrawdw.apps.notisync.notification.mirror

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat
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
 *
 * Each session is marked REMOTE-playback with a per-source volumeControlId; together with [MirrorRouter]'s
 * routing session that makes the card's output chip / Output Switcher name the SOURCE device instead of
 * this phone's own audio route. When the source reports an adjustable volume, the session exposes it —
 * hardware volume keys, the system volume panel, and the Output Switcher slider all work — and every
 * change relays to the origin as [MediaCommand.SET_VOLUME] / [MediaCommand.ADJUST_VOLUME] (the origin's
 * echo re-capture then confirms the level that actually landed). AMS now-playing is relative-only when
 * iOS advertises volume commands, so keys work there but the Output Switcher stays sliderless.
 */
class MirrorMediaSessions(
    context: Context,
    /** Names the source device on the card's output chip; null leaves the system's default routing UI. */
    private val router: MirrorRouter? = null,
    private val onCommand: (ClientId, String, MediaCommand, Long?, String?, Int?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** A live mirror session plus the volume-relay state its surfaces need. */
    private class Entry(val session: MediaSessionCompat, val clientId: ClientId, val sourceKey: String) {
        var provider: VolumeProviderCompat? = null

        /** Latest user-dragged target awaiting relay (trailing debounce, latest wins). */
        var pendingVolume: Int? = null
        var relayScheduled = false

        /** Elapsed-clock stamp of the last local volume action: captures arriving inside the grace window
         *  don't overwrite the optimistic slider position (the in-flight echo carries the true level). */
        var lastUserVolumeAt = 0L
    }

    /** tag -> entry, re-inserted on every [apply] so iteration order tracks recency ([setVolumeFromSwitcher]
     *  targets a source's most recently updated card). Touched only on the main thread (via [onMain]/[main]). */
    private val sessions = LinkedHashMap<String, Entry>()

    /** Create/update the session for [tag]; returns its token for `MediaStyle.setMediaSession`.
     *  [sourceDeviceName] labels the origin on the output chip via [MirrorRouter] (null = unknown yet). */
    fun apply(
        tag: String,
        notif: CapturedNotification,
        albumArt: Bitmap?,
        sourceDeviceName: String? = null,
    ): MediaSessionCompat.Token? =
        onMain {
            val entry = sessions.remove(tag) ?: Entry(
                MediaSessionCompat(appContext, MEDIA_SESSION_TAG).apply {
                    setCallback(callbackFor(notif.sourceClientId, notif.sourceKey))
                    isActive = true
                },
                notif.sourceClientId,
                notif.sourceKey,
            )
            sessions[tag] = entry // re-insert: iteration order tracks recency
            entry.session.setMetadata(metadataFor(notif, albumArt))
            entry.session.setPlaybackState(playbackStateFor(notif))
            val provider = applyVolume(entry, notif)
            router?.activate(
                tag, notif.sourceClientId, sourceDeviceName, notif.mediaIsPlaying == true,
                // Only an absolute scale gets a switcher-side slider; relative/fixed sessions stay fixed there.
                volumeMax = provider.maxVolume
                    .takeIf { provider.volumeControl == VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE } ?: 0,
                volume = provider.currentVolume,
            )
            entry.session.sessionToken
        }

    /** Deactivate + release the session for [tag] (playback stopped / mirror gone), clearing its media card. */
    fun release(tag: String) {
        main.post {
            router?.deactivate(tag)
            sessions.remove(tag)?.let { runCatching { it.session.isActive = false; it.session.release() } }
        }
    }

    /** The Output Switcher moved the slider for [clientId]'s route ([MirrorRouteProviderService] relayed it):
     *  same treatment as the volume panel, applied to that source's most recently updated card. */
    fun setVolumeFromSwitcher(clientId: ClientId, volume: Int) {
        main.post {
            sessions.values.lastOrNull { it.clientId == clientId }?.let { userSetVolume(it, volume) }
        }
    }

    /**
     * Reconcile the session's volume surface with the capture. An absolute control with a real scale gets an
     * adjustable provider (panel slider + volume keys); relative keeps keys only; anything else is fixed —
     * REMOTE playback type + the per-source controlId are what let SystemUI match the session to its
     * [MirrorRouter] routing session either way. The provider is recreated only when control/max change
     * (its constructor owns them); a bare level change flows through setCurrentVolume — skipped inside the
     * post-user-action grace window so a stale in-flight capture can't yank the slider back before the
     * origin's echo lands.
     */
    private fun applyVolume(entry: Entry, notif: CapturedNotification): VolumeProviderCompat {
        val max = (notif.mediaVolumeMax ?: 0).coerceAtLeast(0)
        val control = when {
            notif.mediaVolumeControl == VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE && max > 0 ->
                VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE
            notif.mediaVolumeControl == VolumeProviderCompat.VOLUME_CONTROL_RELATIVE ->
                VolumeProviderCompat.VOLUME_CONTROL_RELATIVE
            else -> VolumeProviderCompat.VOLUME_CONTROL_FIXED
        }
        val current = (notif.mediaVolumeCurrent ?: 0).coerceIn(0, max)
        val existing = entry.provider
        if (existing != null && existing.volumeControl == control && existing.maxVolume == max) {
            if (existing.currentVolume != current && !inVolumeGrace(entry)) existing.setCurrentVolume(current)
            return existing
        }
        val provider = object : VolumeProviderCompat(
            control, max, current,
            MirrorRouter.volumeControlIdFor(entry.clientId),
        ) {
            override fun onSetVolumeTo(volume: Int) = userSetVolume(entry, volume)
            override fun onAdjustVolume(direction: Int) = userAdjustVolume(entry, direction)
        }
        entry.provider = provider
        entry.session.setPlaybackToRemote(provider)
        return provider
    }

    /** The system volume slider (panel / switcher) set an absolute target: show it immediately, relay the
     *  landing spot. Runs on the main thread (the session was built there, so its callbacks arrive there). */
    private fun userSetVolume(entry: Entry, volume: Int) {
        val p = entry.provider ?: return
        if (p.volumeControl == VolumeProviderCompat.VOLUME_CONTROL_FIXED) return
        val v = volume.coerceIn(0, p.maxVolume)
        entry.lastUserVolumeAt = SystemClock.elapsedRealtime()
        p.setCurrentVolume(v) // optimistic: the slider tracks now; the origin's echo confirms/corrects
        router?.updateVolume(entry.clientId, v) // keep the Output Switcher slider in step too
        entry.pendingVolume = v
        if (entry.relayScheduled) return
        entry.relayScheduled = true
        // Trailing debounce, latest wins: a drag emits a burst, the origin only needs where it lands.
        main.postDelayed({
            entry.relayScheduled = false
            val target = entry.pendingVolume ?: return@postDelayed
            entry.pendingVolume = null
            onCommand(entry.clientId, entry.sourceKey, MediaCommand.SET_VOLUME, null, null, target)
        }, VOLUME_RELAY_DEBOUNCE_MS)
    }

    /** Hardware volume keys. An absolute scale folds into the same debounced absolute relay (key autorepeat
     *  coalesces); a relative-only source (casting with relative control) relays each ±1 step as-is. */
    private fun userAdjustVolume(entry: Entry, direction: Int) {
        val p = entry.provider ?: return
        val d = direction.coerceIn(-1, 1)
        if (d == 0) return
        when (p.volumeControl) {
            VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE -> userSetVolume(entry, p.currentVolume + d)
            VolumeProviderCompat.VOLUME_CONTROL_RELATIVE -> {
                entry.lastUserVolumeAt = SystemClock.elapsedRealtime()
                onCommand(entry.clientId, entry.sourceKey, MediaCommand.ADJUST_VOLUME, null, null, d)
            }
        }
    }

    private fun inVolumeGrace(entry: Entry): Boolean =
        SystemClock.elapsedRealtime() - entry.lastUserVolumeAt < VOLUME_SYNC_GRACE_MS

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
        override fun onPlay() = onCommand(clientId, sourceKey, MediaCommand.PLAY, null, null, null)
        override fun onPause() = onCommand(clientId, sourceKey, MediaCommand.PAUSE, null, null, null)
        override fun onSkipToNext() = onCommand(clientId, sourceKey, MediaCommand.NEXT, null, null, null)
        override fun onSkipToPrevious() = onCommand(clientId, sourceKey, MediaCommand.PREVIOUS, null, null, null)
        override fun onStop() = onCommand(clientId, sourceKey, MediaCommand.STOP, null, null, null)
        override fun onSeekTo(pos: Long) = onCommand(clientId, sourceKey, MediaCommand.SEEK, pos, null, null)
        override fun onCustomAction(action: String?, extras: Bundle?) {
            action?.let { onCommand(clientId, sourceKey, MediaCommand.CUSTOM, null, it, null) }
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

        /** Trailing debounce for volume relays: a slider drag emits a burst; the origin needs the landing
         *  spot, not the path (each relay is a HIGH-urgency unicast envelope). */
        const val VOLUME_RELAY_DEBOUNCE_MS = 200L

        /** After a local volume action, ignore capture-carried volume this long — the optimistic position
         *  stands until the origin's echo (or a later capture) confirms it, instead of snapping back. */
        const val VOLUME_SYNC_GRACE_MS = 2_000L
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
