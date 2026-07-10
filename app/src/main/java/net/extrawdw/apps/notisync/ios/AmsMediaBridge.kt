package net.extrawdw.apps.notisync.ios

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId

/**
 * Accumulates AMS Entity Update / Remote Command notifications into one coherent [AmsNowPlaying] and
 * dispatches it as a mirrored now-playing card — the iPhone-media analogue of what [IosBridgeManager] does
 * for notifications. One card per iPhone (stable source key), updated in place across track changes.
 *
 * Dispatch policy mirrors an Android media capture ([NotificationCapture]): the FIRST post of a session
 * goes out as a normal NOTIFICATION ([captureToMesh], prompt delivery); every later change is a quiet
 * in-place update ([sendQuietToMesh] — DATA_SYNC at NORMAL urgency with last-writer-wins postTime). A
 * short trailing debounce coalesces the initial subscription burst (each attribute arrives as its own
 * notification), and a minimum gap keeps a scrub-storm from flooding the mesh. Truncated attributes are
 * re-fetched in full over Entity Attribute ([readAttribute]) and re-applied.
 *
 * The card clears when the track goes away (player closed / attributes emptied), when the master toggle
 * turns off, and when the BLE link drops ([onGone]). All state is session-scoped.
 */
class AmsMediaBridge(
    private val scope: CoroutineScope,
    private val clientId: ClientId,
    /** Media-mirroring master toggle; off = no cards (and an existing one is torn down on next event). */
    private val enabled: () -> Boolean,
    private val localDisplayEnabled: () -> Boolean,
    private val meshMirrorEnabled: () -> Boolean,
    private val iphoneId: () -> String,
    private val iphoneName: () -> String?,
    /** Full-value fetch for a truncated attribute ([IosGattClient.readEntityAttribute]). */
    private val readAttribute: suspend (entityId: Int, attributeId: Int) -> String?,
    /** Post/refresh the card locally ([RemoteNotificationPoster.render]); silent = in-place update. */
    private val renderLocal: (CapturedNotification, Boolean) -> Unit,
    /** Remove the locally-posted card ([RemoteNotificationPoster.clear]). */
    private val clearLocal: (ClientId, String) -> Unit,
    /** First post of a session — alerting NOTIFICATION path ([MirrorEngine.captureLocal]). */
    private val captureToMesh: suspend (CapturedNotification) -> Int,
    /** Later updates — quiet DATA_SYNC path ([MirrorEngine.sendNotificationQuiet]). */
    private val sendQuietToMesh: suspend (CapturedNotification) -> Int,
    /** Broadcast the card's dismissal when playback ends ([MirrorEngine.dismissLocal]). */
    private val dismissMesh: suspend (ClientId, String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // ---- Entity state (mutated on BLE binder threads; guarded by synchronized(this)) ----
    private var state = AmsNowPlaying()

    // ---- Dispatch state (guarded by [dispatchMutex] so an emit can't race the teardown clear) ----
    private val dispatchMutex = Mutex()
    private var shown = false
    private var mirroredToMesh = false
    private var lastPostedKey: String? = null

    @Volatile
    private var lastEmitAt = 0L

    /** Guarded by @Synchronized [scheduleEmit]/[cancelScheduledEmit] — callers span the BLE binder
     *  thread and the truncation-fetch coroutine, and a race would leak a second live emit job. */
    private var emitJob: Job? = null

    /** An AMS Entity Update landed: apply it and (debounced) re-emit the card. */
    fun onEntityUpdate(u: Ams.EntityUpdate) {
        apply(u.entityId, u.attributeId, u.value)
        if (u.truncated) {
            // The truncated prefix above keeps the card current even if this full fetch fails.
            scope.launch {
                runCatching { readAttribute(u.entityId, u.attributeId) }.getOrNull()?.let { full ->
                    apply(u.entityId, u.attributeId, full)
                    scheduleEmit()
                }
            }
        }
        scheduleEmit()
    }

    /** iOS notified the supported-command list for the active player (changes as players come and go). */
    fun onSupportedCommands(commands: List<Int>) {
        synchronized(this) { state = state.copy(supportedCommands = commands) }
        scheduleEmit()
    }

    /** The BLE link (or the bridge) is going away — take the card down and reset for the next session. */
    fun onGone() {
        cancelScheduledEmit()
        synchronized(this) { state = AmsNowPlaying() }
        scope.launch { clearIfShown() }
    }

    /** The master toggle turned off — take an existing card down now rather than on the next event. */
    fun onDisabled() {
        cancelScheduledEmit()
        scope.launch { clearIfShown() }
    }

    private fun apply(entityId: Int, attributeId: Int, value: String) = synchronized(this) {
        state = when (entityId) {
            Ams.ENTITY_PLAYER -> when (attributeId) {
                Ams.PLAYER_ATTR_NAME -> state.copy(playerName = value.takeIf { it.isNotBlank() })
                Ams.PLAYER_ATTR_PLAYBACK_INFO -> {
                    val info = Ams.parsePlaybackInfo(value)
                    // An empty/malformed PlaybackInfo means no active player — clear the play state so a
                    // stale "playing" card doesn't survive the app it belonged to.
                    state.copy(
                        playbackState = info?.state,
                        playbackRate = info?.rate ?: 1f,
                        elapsedSec = info?.elapsedSec,
                        elapsedAtMs = now(),
                    )
                }

                else -> state
            }

            Ams.ENTITY_TRACK -> when (attributeId) {
                Ams.TRACK_ATTR_ARTIST -> state.copy(artist = value)
                Ams.TRACK_ATTR_ALBUM -> state.copy(album = value)
                Ams.TRACK_ATTR_TITLE -> state.copy(title = value)
                Ams.TRACK_ATTR_DURATION -> state.copy(durationSec = value.toDoubleOrNull())
                else -> state
            }

            else -> state
        }
    }

    /**
     * Debounced trailing emit: the subscription handshake and every track change deliver one notification
     * per attribute, so the card is built once the burst settles rather than N times. The minimum gap
     * additionally spaces mesh updates out (matching the capture side's media-update throttle).
     */
    @Synchronized
    private fun scheduleEmit() {
        emitJob?.cancel()
        val wait = maxOf(EMIT_DEBOUNCE_MS, MIN_EMIT_GAP_MS - (now() - lastEmitAt))
        emitJob = scope.launch {
            delay(wait)
            runCatching { emit() }.onFailure { Log.w(TAG, "AMS emit failed", it) }
        }
    }

    @Synchronized
    private fun cancelScheduledEmit() {
        emitJob?.cancel()
        emitJob = null
    }

    private suspend fun emit() {
        val snapshot = synchronized(this) { state }
        dispatchMutex.withLock {
            if (!enabled() || !snapshot.hasTrack) {
                clearIfShownLocked()
                return
            }
            val notif = AmsNotificationMapper.map(clientId, snapshot, iphoneId(), iphoneName(), now())
            lastEmitAt = now()
            val first = !shown
            shown = true
            lastPostedKey = notif.sourceKey
            if (localDisplayEnabled()) renderLocal(notif, !first)
            if (meshMirrorEnabled()) {
                if (!mirroredToMesh) {
                    mirroredToMesh = true
                    runCatching { captureToMesh(notif) }
                } else {
                    runCatching { sendQuietToMesh(notif) }
                }
            }
        }
    }

    private suspend fun clearIfShown() = dispatchMutex.withLock { clearIfShownLocked() }

    private fun clearIfShownLocked() {
        if (!shown) return
        shown = false
        val wasMirrored = mirroredToMesh
        mirroredToMesh = false
        // The key the card was POSTED under — not a fresh iphoneId() read, which may already have reset
        // to its fallback after a disconnect (the clear must match the post or the card lingers).
        val key = lastPostedKey ?: return
        lastPostedKey = null
        clearLocal(clientId, key)
        if (wasMirrored) scope.launch { runCatching { dismissMesh(clientId, key) } }
    }

    private companion object {
        const val TAG = "AmsMediaBridge"

        /** Trailing settle for the per-attribute notification burst of one logical change. */
        const val EMIT_DEBOUNCE_MS = 400L

        /** Floor between emits — the AMS analogue of the capture side's MEDIA_UPDATE_THROTTLE_MS. */
        const val MIN_EMIT_GAP_MS = 1_000L
    }
}
