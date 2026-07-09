package net.extrawdw.apps.notisync.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/**
 * Rings the phone for a mirrored INCOMING call the way a real dialer / VoIP app does. Those apps keep their
 * incoming-call notification channel on "None" (no channel sound) and play the ringtone themselves — so the
 * mirror is posted SILENT (see [RemoteNotificationPoster]) and this owns the audio + haptics instead.
 *
 * Follows system settings: the user's default ringtone, the ringer mode (silent / vibrate / normal, which
 * already reflects a DND ring-mute), and the vibrate-when-ringing preference. Music is ducked/paused via a
 * transient audio-focus request, as a real incoming call does.
 *
 * One ring covers any number of concurrent ringing mirrors, keyed by mirror tag: it starts on the first
 * ringing call and stops when the last one is answered / declined / dismissed / times out. A hard
 * [RING_MAX_MS] backstop guarantees it can never ring forever if every stop signal is somehow lost. Every
 * method is `@Synchronized` — [start] is called off the main thread (the render path), [stop] from the
 * notification receivers — over the small key set + the single Ringtone/Vibrator.
 */
class CallRinger(private val context: Context) {

    private val activeKeys = HashSet<String>()
    private var ringtone: Ringtone? = null
    private var audioFocus: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backstop = Runnable { stopAll() }

    /** Begin (or keep) ringing for [key]. Idempotent per key; the ringtone/vibration start only on the first. */
    @Synchronized
    fun start(key: String) {
        val wasIdle = activeKeys.isEmpty()
        if (!activeKeys.add(key)) return          // already ringing for this call
        if (wasIdle) beginRinging()
    }

    /** Stop ringing for [key]; the ringtone/vibration stop once the last ringing call is gone. No-op if unknown. */
    @Synchronized
    fun stop(key: String) {
        if (!activeKeys.remove(key)) return
        if (activeKeys.isEmpty()) endRinging()
    }

    @Synchronized
    fun stopAll() {
        val wasRinging = activeKeys.isNotEmpty()
        activeKeys.clear()
        if (wasRinging) endRinging()
    }

    private fun beginRinging() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // Honor the ringer mode: SILENT = nothing, VIBRATE = haptics only, NORMAL = ringtone (+ haptics when
        // the user's vibrate-when-ringing is on). getRingerMode already reflects a DND-muted ring.
        when (runCatching { am.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> startVibration()
            else -> {
                requestAudioFocus(am)
                startRingtone()
                if (vibrateWhenRinging()) startVibration()
            }
        }
        // Safety cap only: answer / decline / dismiss / answered-transition / timeout all stop the ring well
        // before this fires — it matters solely if every one of those signals is somehow lost.
        mainHandler.postDelayed(backstop, RING_MAX_MS)
    }

    private fun endRinging() {
        mainHandler.removeCallbacks(backstop)
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
        abandonAudioFocus()
    }

    private fun startRingtone() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: Settings.System.DEFAULT_RINGTONE_URI ?: return
            val rt = RingtoneManager.getRingtone(context, uri) ?: return
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            rt.isLooping = true                    // API 28+ (minSdk 34): ring until stopped
            rt.play()
            ringtone = rt
        }.onFailure { Log.w(TAG, "ringtone start failed", it) }
    }

    private fun startVibration() {
        runCatching {
            val vibrator = vibrator() ?: return
            val effect = VibrationEffect.createWaveform(RING_VIBRATION_PATTERN, /* repeat = */ 0)
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_RINGTONE)   // ring-stream haptics; respects system settings
                .build()
            vibrator.vibrate(effect, attrs)
        }.onFailure { Log.w(TAG, "vibration start failed", it) }
    }

    private fun requestAudioFocus(am: AudioManager) {
        runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            am.requestAudioFocus(req)
            audioFocus = req
        }
    }

    private fun abandonAudioFocus() {
        val req = audioFocus ?: return
        audioFocus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    private fun vibrator() =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    /** The system "also vibrate for calls" preference; defaults to vibrate if it can't be read. The setting is
     *  deprecated but still the only public read of this user toggle — no AudioManager equivalent exists. */
    @Suppress("DEPRECATION")
    private fun vibrateWhenRinging(): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.VIBRATE_WHEN_RINGING) == 1
    }.getOrDefault(true)

    private companion object {
        const val TAG = "CallRinger"

        /** Max ring duration backstop. A real ring resolves (answer/decline/dismiss/transition) well before this. */
        const val RING_MAX_MS = 60_000L

        /** Wait 0, buzz 1s, pause 1s — repeating from index 0: a phone-like ring cadence. */
        val RING_VIBRATION_PATTERN = longArrayOf(0L, 1000L, 1000L)
    }
}
