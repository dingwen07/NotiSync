package net.extrawdw.apps.notisync.screen

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import com.genymobile.scrcpy.NotiSyncCaptureBackend
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Configuration passed to the scrcpy-derived privileged backend. */
data class PrivilegedCaptureConfig(
    val ownerToken: String,
    val codecId: Int,
    val maxDimension: Int,
    val maxFps: Int,
    val bitrateBps: Int,
    val allowControl: Boolean,
    val allowClipboard: Boolean,
)

/**
 * Adapter seam for the pinned scrcpy server. Implementations must own/close both descriptors after a
 * successful return, start asynchronously, enforce the control/clipboard allowlist server-side, and make
 * [stop] idempotent. Network parsing and session keys deliberately never cross this boundary.
 */
interface PrivilegedCaptureBackend : Closeable {
    val status: Int
    fun probeHardwareCodecs(): Int
    fun probeCapabilities(): Int
    fun start(
        config: PrivilegedCaptureConfig,
        videoWriteFd: ParcelFileDescriptor,
        controlFd: ParcelFileDescriptor,
    ): Int
    /** Returns only after the exact owner's capture workers and descriptors have been cleaned up. */
    fun stop(ownerToken: String): Boolean
    fun recoverVideo(ownerToken: String, bitrateBps: Int): Int
    fun restartVideo(ownerToken: String): Boolean
    override fun close()
}

/** Thin ownership/status adapter around the separately vendored, pinned scrcpy 4.1 module. */
private class ScrcpyCaptureBackend : PrivilegedCaptureBackend {
    private val delegate = NotiSyncCaptureBackend()
    override val status: Int = ScreenMirrorBackendStatus.READY
    override fun probeHardwareCodecs(): Int = NotiSyncCaptureBackend.probeHardwareCodecs()
    override fun probeCapabilities(): Int = NotiSyncCaptureBackend.probeCapabilities()
    override fun start(
        config: PrivilegedCaptureConfig,
        videoWriteFd: ParcelFileDescriptor,
        controlFd: ParcelFileDescriptor,
    ): Int {
        val result = delegate.startSession(
            config.ownerToken,
            config.codecId,
            config.maxDimension,
            config.maxFps,
            config.bitrateBps,
            config.allowControl,
            config.allowClipboard,
            videoWriteFd,
            controlFd,
        )
        if (result != NotiSyncCaptureBackend.STARTED) {
            // Upstream closes most rejected descriptors itself; BUSY deliberately returns early, so the
            // boundary closes defensively for every non-start result.
            videoWriteFd.closeQuietly()
            controlFd.closeQuietly()
        }
        return when (result) {
            NotiSyncCaptureBackend.STARTED -> ScreenMirrorBackendStatus.READY
            NotiSyncCaptureBackend.BUSY -> ScreenMirrorBackendStatus.BUSY
            NotiSyncCaptureBackend.INVALID_ARGUMENT -> ScreenMirrorBackendStatus.INVALID_ARGUMENT
            NotiSyncCaptureBackend.ROOT_UNSUPPORTED -> ScreenMirrorBackendStatus.ROOT_UNSUPPORTED
            NotiSyncCaptureBackend.CODEC_UNAVAILABLE -> ScreenMirrorBackendStatus.CODEC_UNAVAILABLE
            else -> ScreenMirrorBackendStatus.START_FAILED
        }
    }
    override fun stop(ownerToken: String): Boolean = delegate.stopSession(ownerToken)
    override fun recoverVideo(ownerToken: String, bitrateBps: Int): Int =
        delegate.recoverVideo(ownerToken, bitrateBps)
    override fun restartVideo(ownerToken: String): Boolean = delegate.restartVideo(ownerToken)
    override fun close() = delegate.destroy()
}

internal object PrivilegedCaptureBackendFactory {
    fun create(): PrivilegedCaptureBackend = ScrcpyCaptureBackend()
}

/**
 * Shizuku instantiates this class directly in a shell-uid app_process. It is intentionally absent from the
 * Android manifest: only Shizuku's provider may create it.
 */
class ScreenMirrorUserService() : IScreenMirrorUserService.Stub() {
    private val backend = AtomicReference(PrivilegedCaptureBackendFactory.create())
    private val destroyed = AtomicBoolean(false)

    /** Preferred API-13 constructor; Keep prevents R8 from removing reflective construction. */
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun getBackendStatus(): Int =
        when {
            destroyed.get() -> ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE
            Os.getuid() == 0 -> ScreenMirrorBackendStatus.ROOT_UNSUPPORTED
            Os.getuid() != Process.SHELL_UID -> ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE
            else -> backend.get().status
        }

    override fun probeHardwareCodecs(): Int =
        if (destroyed.get() || Os.getuid() != Process.SHELL_UID) 0 else backend.get().probeHardwareCodecs()

    override fun probeCapabilities(): Int =
        if (destroyed.get() || Os.getuid() != Process.SHELL_UID) 0 else backend.get().probeCapabilities()

    override fun startSession(
        ownerToken: String,
        codecId: Int,
        maxDimension: Int,
        maxFps: Int,
        bitrateBps: Int,
        allowControl: Boolean,
        allowClipboard: Boolean,
        videoWriteFd: ParcelFileDescriptor,
        controlFd: ParcelFileDescriptor,
    ): Int {
        if (destroyed.get()) {
            videoWriteFd.closeQuietly()
            controlFd.closeQuietly()
            return ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE
        }
        if (Os.getuid() != Process.SHELL_UID) {
            videoWriteFd.closeQuietly()
            controlFd.closeQuietly()
            return if (Os.getuid() == 0) {
                ScreenMirrorBackendStatus.ROOT_UNSUPPORTED
            } else {
                ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE
            }
        }
        if (!validOwnerToken(ownerToken)) {
            videoWriteFd.closeQuietly()
            controlFd.closeQuietly()
            return ScreenMirrorBackendStatus.INVALID_ARGUMENT
        }
        return runCatching {
            backend.get().start(
                PrivilegedCaptureConfig(
                    ownerToken = ownerToken,
                    codecId = codecId,
                    maxDimension = maxDimension.coerceIn(240, 8192),
                    maxFps = maxFps.coerceIn(1, 240),
                    bitrateBps = bitrateBps.coerceIn(128_000, 100_000_000),
                    allowControl = allowControl,
                    allowClipboard = allowClipboard,
                ),
                videoWriteFd,
                controlFd,
            )
        }.getOrElse { error ->
            Log.e(TAG, "Could not start capture backend", error)
            videoWriteFd.closeQuietly()
            controlFd.closeQuietly()
            ScreenMirrorBackendStatus.START_FAILED
        }
    }

    override fun stopSession(ownerToken: String): Boolean =
        validOwnerToken(ownerToken) && runCatching { backend.get().stop(ownerToken) }.getOrDefault(false)

    override fun recoverVideo(ownerToken: String, bitrateBps: Int): Int =
        if (validOwnerToken(ownerToken) && bitrateBps in 128_000..100_000_000) {
            runCatching { backend.get().recoverVideo(ownerToken, bitrateBps) }.getOrDefault(0)
        } else {
            0
        }

    override fun restartVideo(ownerToken: String): Boolean =
        validOwnerToken(ownerToken) &&
            runCatching { backend.get().restartVideo(ownerToken) }.getOrDefault(false)

    /** Reserved Shizuku destroy transaction. Shizuku owns process teardown after unbind. */
    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) runCatching { backend.get().close() }
    }

    private fun validOwnerToken(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_OWNER_TOKEN_CHARS && value.none(Char::isISOControl)

    private companion object {
        const val TAG = "ScreenMirrorUserSvc"
        const val MAX_OWNER_TOKEN_CHARS = 128
    }
}

object ScreenMirrorBackendStatus {
    const val READY = 0
    const val BACKEND_UNAVAILABLE = 1
    const val ROOT_UNSUPPORTED = 2
    const val BUSY = 3
    const val INVALID_ARGUMENT = 4
    const val CODEC_UNAVAILABLE = 5
    const val START_FAILED = 6
}

object ScreenMirrorCodecBits {
    const val H264 = 1
    const val H265 = 1 shl 1
    const val AV1 = 1 shl 2
}

object ScreenMirrorProbeBits {
    const val DISPLAY_CAPTURE = 1
    const val INPUT_INJECTION = 1 shl 1
    const val REQUIRED = DISPLAY_CAPTURE or INPUT_INJECTION
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
