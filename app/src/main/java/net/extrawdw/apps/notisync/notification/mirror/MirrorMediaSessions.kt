package net.extrawdw.apps.notisync.notification.mirror

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MediaCommand
import net.extrawdw.notisync.protocol.MediaCustomAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gives each mirrored MEDIA notification its own native [MediaSession] so Android renders it as actual
 * media playback: album art, transport controls, seek state, and the system media-controls surface.
 *
 * NotiSync does not play audio locally. Each session mirrors the origin's metadata and [PlaybackState],
 * while its callbacks relay transport commands to the real source player. The native session is a better
 * fit than a Media3 playback service here: these are synthetic, independently posted mirror notifications,
 * not locally hosted playback, and they need an explicitly active platform session at post time.
 *
 * Sessions are keyed by mirror tag, updated on each render, and released when the mirror is cleared or
 * dismissed. Every session mutation runs on the main thread; [apply] briefly waits for that main-thread
 * handoff so the notification can be built with a live [MediaSession.Token].
 *
 * Each session reports remote playback through [VolumeProvider]. Its per-source `volumeControlId` matches
 * [MirrorRouter]'s routing session so the media output chip names the origin device. Absolute sources expose
 * a slider plus volume keys, relative sources expose keys only, and fixed sources expose neither.
 */
class MirrorMediaSessions(
    context: Context,
    /** Names the source device on the card's output chip; null leaves the system's default routing UI. */
    private val router: MirrorRouter? = null,
    private val onCommand: (ClientId, String, MediaCommand, Long?, String?, Int?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var nextSessionId = 0L

    /** A live mirror session plus the volume-relay state its surfaces need. */
    private class Entry(val session: MediaSession, val clientId: ClientId, val sourceKey: String) {
        var provider: VolumeProvider? = null
        var pendingVolume: Int? = null
        var relayScheduled = false
        var lastUserVolumeAt = 0L
        var lastRemotePostTime = 0L
        var staleLocalPausePostTime: Long? = null
    }

    /** tag -> entry, re-inserted on every [apply] so iteration order tracks recency. Main thread only. */
    private val sessions = LinkedHashMap<String, Entry>()

    /** Create/update the session for [tag], returning the active native token for Notification.MediaStyle. */
    fun apply(
        tag: String,
        notif: CapturedNotification,
        albumArt: Bitmap?,
        sourceDeviceName: String? = null,
    ): MediaSession.Token? =
        onMain {
            val prior = sessions.remove(tag)
            val entry = if (prior == null ||
                prior.clientId != notif.sourceClientId || prior.sourceKey != notif.sourceKey
            ) {
                prior?.let(::releaseEntry)
                createEntry(notif)
            } else {
                prior
            }
            sessions[tag] = entry
            entry.session.setMetadata(metadataFor(notif, albumArt))
            if ((entry.staleLocalPausePostTime ?: Long.MIN_VALUE) < notif.postTime) {
                entry.staleLocalPausePostTime = null
            }
            entry.lastRemotePostTime = notif.postTime
            entry.session.setPlaybackState(playbackStateFor(entry, notif))
            val provider = applyVolume(entry, notif)
            // SystemUI only promotes a notification whose token belongs to a live, active session with a
            // non-null PlaybackState when NotificationManager receives it.
            entry.session.isActive = true
            router?.activate(
                tag,
                notif.sourceClientId,
                sourceDeviceName,
                notif.mediaIsPlaying == true,
                volumeMax = provider.maxVolume
                    .takeIf { provider.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE } ?: 0,
                volume = provider.currentVolume,
            )
            entry.session.sessionToken
        }

    /** Deactivate and release the session for [tag], clearing its system media card. */
    fun release(tag: String) {
        main.post {
            router?.deactivate(tag)
            sessions.remove(tag)?.let(::releaseEntry)
        }
    }

    /** Relay an Output Switcher slider move through the same debounced volume path as the system panel. */
    fun setVolumeFromSwitcher(clientId: ClientId, volume: Int) {
        main.post {
            sessions.values.lastOrNull { it.clientId == clientId }?.let { userSetVolume(it, volume) }
        }
    }

    private fun createEntry(notif: CapturedNotification): Entry {
        val session = MediaSession(appContext, "$MEDIA_SESSION_TAG-${nextSessionId++}")
        return Entry(session, notif.sourceClientId, notif.sourceKey).also { entry ->
            session.setCallback(callbackFor(entry), main)
        }
    }

    private fun releaseEntry(entry: Entry) {
        runCatching { entry.session.isActive = false }
        runCatching { entry.session.release() }
    }

    /** Reconcile the native remote-volume surface with the latest source capture. */
    private fun applyVolume(entry: Entry, notif: CapturedNotification): VolumeProvider {
        val max = (notif.mediaVolumeMax ?: 0).coerceAtLeast(0)
        val control = when {
            notif.mediaVolumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE && max > 0 ->
                VolumeProvider.VOLUME_CONTROL_ABSOLUTE
            notif.mediaVolumeControl == VolumeProvider.VOLUME_CONTROL_RELATIVE ->
                VolumeProvider.VOLUME_CONTROL_RELATIVE
            else -> VolumeProvider.VOLUME_CONTROL_FIXED
        }
        val current = (notif.mediaVolumeCurrent ?: 0).coerceIn(0, max)
        val existing = entry.provider
        if (existing != null && existing.volumeControl == control && existing.maxVolume == max) {
            if (existing.currentVolume != current && !inVolumeGrace(entry)) existing.currentVolume = current
            return existing
        }
        val provider = object : VolumeProvider(
            control,
            max,
            current,
            MirrorRouter.volumeControlIdFor(entry.clientId),
        ) {
            override fun onSetVolumeTo(volume: Int) = userSetVolume(entry, volume)
            override fun onAdjustVolume(direction: Int) = userAdjustVolume(entry, direction)
        }
        entry.provider = provider
        entry.session.setPlaybackToRemote(provider)
        return provider
    }

    private fun userSetVolume(entry: Entry, volume: Int) {
        val provider = entry.provider ?: return
        if (provider.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED) return
        val target = volume.coerceIn(0, provider.maxVolume)
        entry.lastUserVolumeAt = SystemClock.elapsedRealtime()
        provider.currentVolume = target
        router?.updateVolume(entry.clientId, target)
        entry.pendingVolume = target
        if (entry.relayScheduled) return
        entry.relayScheduled = true
        main.postDelayed({
            entry.relayScheduled = false
            val landing = entry.pendingVolume ?: return@postDelayed
            entry.pendingVolume = null
            onCommand(entry.clientId, entry.sourceKey, MediaCommand.SET_VOLUME, null, null, landing)
        }, VOLUME_RELAY_DEBOUNCE_MS)
    }

    private fun userAdjustVolume(entry: Entry, direction: Int) {
        val provider = entry.provider ?: return
        val step = direction.coerceIn(-1, 1)
        if (step == 0) return
        when (provider.volumeControl) {
            VolumeProvider.VOLUME_CONTROL_ABSOLUTE -> userSetVolume(entry, provider.currentVolume + step)
            VolumeProvider.VOLUME_CONTROL_RELATIVE -> {
                entry.lastUserVolumeAt = SystemClock.elapsedRealtime()
                onCommand(entry.clientId, entry.sourceKey, MediaCommand.ADJUST_VOLUME, null, null, step)
            }
        }
    }

    private fun inVolumeGrace(entry: Entry): Boolean =
        SystemClock.elapsedRealtime() - entry.lastUserVolumeAt < VOLUME_SYNC_GRACE_MS

    private fun metadataFor(notif: CapturedNotification, albumArt: Bitmap?): MediaMetadata =
        MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, notif.title ?: notif.appLabel)
            notif.text?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
            notif.subText?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            albumArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            putLong(MediaMetadata.METADATA_KEY_DURATION, notif.mediaDurationMs ?: 0L)
        }.build()

    private fun playbackStateFor(entry: Entry, notif: CapturedNotification): PlaybackState {
        val sourceState = if (notif.mediaIsPlaying == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val state = if (entry.staleLocalPausePostTime == notif.postTime) PlaybackState.STATE_PAUSED else sourceState
        val position = notif.mediaPositionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        val declared = notif.mediaActions ?: DEFAULT_ACTIONS
        val actions = (declared and SUPPORTED_ACTIONS).takeIf { it != 0L } ?: DEFAULT_ACTIONS
        return PlaybackState.Builder()
            .setState(state, position, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .setActions(actions)
            .apply {
                notif.mediaCustomActions.forEach { action ->
                    addCustomAction(
                        PlaybackState.CustomAction.Builder(
                            action.action,
                            action.name.ifBlank { action.action },
                            iconFor(action),
                        ).build(),
                    )
                }
            }
            .build()
    }

    private fun pauseLocallyIfRemoteLooksStale(entry: Entry) {
        if (!remoteLooksStale(entry.lastRemotePostTime)) return
        entry.staleLocalPausePostTime = entry.lastRemotePostTime
        val current = runCatching { entry.session.controller.playbackState }.getOrNull()
        runCatching { entry.session.setPlaybackState(current.withPlaybackState(PlaybackState.STATE_PAUSED)) }
    }

    private fun remoteLooksStale(remotePostTime: Long): Boolean =
        remotePostTime > 0 && System.currentTimeMillis() - remotePostTime >= STALE_REMOTE_MEDIA_MS

    private fun PlaybackState?.withPlaybackState(state: Int): PlaybackState {
        val speed = if (state == PlaybackState.STATE_PLAYING) {
            this?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        } else {
            0f
        }
        return PlaybackState.Builder()
            .setState(state, this?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed)
            .setActions(this?.actions ?: DEFAULT_ACTIONS)
            .apply { this@withPlaybackState?.customActions?.forEach(::addCustomAction) }
            .build()
    }

    private fun iconFor(action: MediaCustomAction): Int {
        val key = "${action.action} ${action.name}".lowercase()
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

    private fun callbackFor(entry: Entry) = object : MediaSession.Callback() {
        override fun onPlay() = relay(entry, MediaCommand.PLAY)
        override fun onPause() {
            pauseLocallyIfRemoteLooksStale(entry)
            relay(entry, MediaCommand.PAUSE)
        }
        override fun onSkipToNext() = relay(entry, MediaCommand.NEXT)
        override fun onSkipToPrevious() = relay(entry, MediaCommand.PREVIOUS)
        override fun onStop() = relay(entry, MediaCommand.STOP)
        override fun onSeekTo(pos: Long) = relay(entry, MediaCommand.SEEK, seekMs = pos)
        override fun onCustomAction(action: String, extras: Bundle?) {
            relay(entry, MediaCommand.CUSTOM, customAction = action)
        }
    }

    private fun relay(
        entry: Entry,
        command: MediaCommand,
        seekMs: Long? = null,
        customAction: String? = null,
    ) {
        onCommand(entry.clientId, entry.sourceKey, command, seekMs, customAction, null)
    }

    /** Run [block] on the main thread and return its result, bounded so notification rendering cannot hang. */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == main.looper) {
            return runCatching(block).onFailure(::logSessionFailure).getOrNull()
        }
        val latch = CountDownLatch(1)
        var result: T? = null
        main.post {
            result = runCatching(block).onFailure(::logSessionFailure).getOrNull()
            latch.countDown()
        }
        if (!latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "timed out waiting for the main thread; posting mirror without MediaStyle")
            return null
        }
        return result
    }

    private fun logSessionFailure(error: Throwable) {
        Log.w(TAG, "failed to create or update native mirror media session", error)
    }

    private companion object {
        const val TAG = "MirrorMediaSessions"
        const val MEDIA_SESSION_TAG = "NotiSyncMirror"
        const val MAIN_WAIT_MS = 1_500L
        const val VOLUME_RELAY_DEBOUNCE_MS = 200L
        const val VOLUME_SYNC_GRACE_MS = 2_000L
        const val STALE_REMOTE_MEDIA_MS = 15 * 60 * 1_000L

        const val PLAY_PAUSE_ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        const val SUPPORTED_ACTIONS =
            PLAY_PAUSE_ACTIONS or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
        const val DEFAULT_ACTIONS =
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
