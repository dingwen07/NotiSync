package net.extrawdw.apps.notisync.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.net.toUri
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import net.extrawdw.apps.notisync.BuildConfig
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import rikka.shizuku.Shizuku

enum class ShizukuScreenStatus {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    ROOT_UNSUPPORTED,
    BINDING,
    BACKEND_UNAVAILABLE,
    READY,
    ERROR,
}

data class PrivilegedSessionPipes(
    val videoRead: ParcelFileDescriptor,
    val control: ParcelFileDescriptor,
    private val recoverVideoCallback: (Int) -> Boolean = { false },
) : Closeable {
    fun recoverVideo(bitrateBps: Int): Boolean = recoverVideoCallback(bitrateBps)

    override fun close() {
        videoRead.closeQuietly()
        control.closeQuietly()
    }
}

class PrivilegedCaptureStartException(val backendStatus: Int) :
    Exception("screen backend rejected session ($backendStatus)")

/** Owns Shizuku permission/readiness and the lifecycle of the non-daemon privileged UserService. */
class ScreenMirrorShizukuManager(private val context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val binding = AtomicBoolean(false)
    private val bindLock = Any()
    @Volatile private var bindGeneration = 0L
    @Volatile private var activeConnection: ServiceConnection? = null
    private val _status = MutableStateFlow(ShizukuScreenStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuScreenStatus> = _status.asStateFlow()
    private val _availableCodecBits = MutableStateFlow(0)
    val availableCodecBits: StateFlow<Int> = _availableCodecBits.asStateFlow()
    private val _probeBits = MutableStateFlow(0)
    val probeBits: StateFlow<Int> = _probeBits.asStateFlow()

    @Volatile
    private var service: IScreenMirrorUserService? = null
    private val serviceFlow = MutableStateFlow<IScreenMirrorUserService?>(null)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, ScreenMirrorUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("screen")
        .tag("notisync-screen-v1")
        .debuggable(BuildConfig.DEBUG)
        // Shizuku keeps a UserService process across ordinary app-process restarts. Keep an explicit
        // backend revision so same-version Android Studio installs cannot reconnect to stale capture code.
        .version(BuildConfig.VERSION_CODE * 1_000 + USER_SERVICE_REVISION)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshAndBind() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(bindLock) {
            bindGeneration++
            activeConnection = null
        }
        service = null
        serviceFlow.value = null
        binding.set(false)
        _availableCodecBits.value = 0
        _probeBits.value = 0
        _status.value = ShizukuScreenStatus.NOT_RUNNING
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) refreshAndBind()
        else _status.value = ShizukuScreenStatus.PERMISSION_REQUIRED
    }
    private fun connectionFor(generation: Long): ServiceConnection = object : ServiceConnection {
        private fun isCurrent(): Boolean = synchronized(bindLock) {
            generation == bindGeneration && activeConnection === this
        }

        private fun handleDisconnected() {
            // Shizuku may report both onBindingDied and onServiceDisconnected for the same binder.
            if (!isCurrent()) return
            synchronized(bindLock) {
                if (generation != bindGeneration || activeConnection !== this) return
                bindGeneration++
                activeConnection = null
            }
            service = null
            serviceFlow.value = null
            binding.set(false)
            _availableCodecBits.value = 0
            _probeBits.value = 0
            _status.value = if (Shizuku.pingBinder()) {
                ShizukuScreenStatus.BACKEND_UNAVAILABLE
            } else {
                ShizukuScreenStatus.NOT_RUNNING
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!isCurrent()) return
            binding.set(false)
            if (!binder.pingBinder()) {
                service = null
                _status.value = ShizukuScreenStatus.ERROR
                return
            }
            service = IScreenMirrorUserService.Stub.asInterface(binder)
            serviceFlow.value = service
            refreshBackendStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) = handleDisconnected()
        override fun onBindingDied(name: ComponentName) = handleDisconnected()
        override fun onNullBinding(name: ComponentName) = handleDisconnected()
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    /** Returns true when permission is already granted; otherwise prompts through Shizuku Manager. */
    fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder()) {
            _status.value = ShizukuScreenStatus.NOT_RUNNING
            return false
        }
        return runCatching {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < MIN_SHIZUKU_API) {
                _status.value = ShizukuScreenStatus.ERROR
                false
            } else if (Shizuku.getUid() != Process.SHELL_UID) {
                _status.value = ShizukuScreenStatus.ROOT_UNSUPPORTED
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                // An explicit Settings opt-in must wait for a fresh privileged probe rather than use
                // capability bits cached after the previous non-daemon UserService was removed.
                if (service == null) _status.value = ShizukuScreenStatus.BINDING
                refreshAndBind()
                true
            } else {
                _status.value = ShizukuScreenStatus.PERMISSION_REQUIRED
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                false
            }
        }.getOrElse {
            _status.value = ShizukuScreenStatus.ERROR
            false
        }
    }

    /** Opens the external Shizuku Manager (or its Play listing) when its binder is not running. */
    fun openManager(): Boolean {
        val launch = appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch ?: Intent(
            Intent.ACTION_VIEW,
            "market://details?id=$SHIZUKU_PACKAGE".toUri(),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
    }

    fun refresh() {
        if (Shizuku.pingBinder()) refreshAndBind() else _status.value = ShizukuScreenStatus.NOT_RUNNING
    }

    suspend fun startPrivilegedSession(
        request: ScreenMirrorSync,
        ownerToken: String,
    ): Result<PrivilegedSessionPipes> = runCatching {
        if (service == null) refreshAndBind()
        val remote = service ?: withTimeout(SERVICE_BIND_TIMEOUT_MS) {
            serviceFlow.filterNotNull().first()
        }
        val backendStatus = remote.backendStatus
        if (backendStatus != ScreenMirrorBackendStatus.READY) {
            throw PrivilegedCaptureStartException(backendStatus)
        }
        val codec = requireNotNull(request.codec)
        var videoPair: Array<ParcelFileDescriptor>? = null
        var controlPair: Array<ParcelFileDescriptor>? = null
        var transferLocalOwnership = false
        try {
            val createdVideoPair = ParcelFileDescriptor.createSocketPair()
            videoPair = createdVideoPair
            val createdControlPair = ParcelFileDescriptor.createSocketPair()
            controlPair = createdControlPair
            val local = PrivilegedSessionPipes(
                videoRead = createdVideoPair[0],
                control = createdControlPair[0],
                recoverVideoCallback = { bitrateBps ->
                    runCatching { remote.recoverVideo(ownerToken, bitrateBps) }.getOrDefault(false)
                },
            )
            val result = remote.startSession(
                ownerToken,
                codec.codecId(),
                (request.maxDimension ?: DEFAULT_MAX_DIMENSION).coerceIn(240, 8192),
                (request.maxFps ?: DEFAULT_MAX_FPS).coerceIn(1, 240),
                (request.videoBitrateBps ?: DEFAULT_BITRATE_BPS).coerceIn(128_000, 100_000_000),
                request.requestControl,
                request.requestClipboard,
                createdVideoPair[1],
                createdControlPair[1],
            )
            if (result != ScreenMirrorBackendStatus.READY) {
                throw PrivilegedCaptureStartException(result)
            }
            transferLocalOwnership = true
            local
        } finally {
            // Binder duplicated these into the remote app_process; this process must release its copies.
            videoPair?.getOrNull(1)?.closeQuietly()
            controlPair?.getOrNull(1)?.closeQuietly()
            if (!transferLocalOwnership) {
                videoPair?.getOrNull(0)?.closeQuietly()
                controlPair?.getOrNull(0)?.closeQuietly()
            }
        }
    }

    /**
     * Stops only [ownerToken]'s privileged capture and returns after the backend acknowledges that
     * all capture workers and descriptors are gone. A missing binder means its process is already gone.
     */
    fun stopPrivilegedSession(ownerToken: String): Boolean =
        runCatching { service?.stopSession(ownerToken) ?: true }.getOrDefault(false)

    /** Removes the non-daemon user service after a session; the next request receives a fresh process. */
    fun removeUserService() {
        val connection = synchronized(bindLock) {
            bindGeneration++
            activeConnection.also { activeConnection = null }
        }
        service = null
        serviceFlow.value = null
        binding.set(false)
        connection?.let { runCatching { Shizuku.unbindUserService(userServiceArgs, it, true) } }
        _status.value = if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            ShizukuScreenStatus.READY
        } else {
            ShizukuScreenStatus.NOT_RUNNING
        }
    }

    override fun close() {
        val connection = synchronized(bindLock) {
            bindGeneration++
            activeConnection.also { activeConnection = null }
        }
        service = null
        serviceFlow.value = null
        binding.set(false)
        connection?.let { runCatching { Shizuku.unbindUserService(userServiceArgs, it, true) } }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun refreshAndBind() {
        runCatching {
            when {
                Shizuku.isPreV11() || Shizuku.getVersion() < MIN_SHIZUKU_API ->
                    _status.value = ShizukuScreenStatus.ERROR
                Shizuku.getUid() != Process.SHELL_UID ->
                    _status.value = ShizukuScreenStatus.ROOT_UNSUPPORTED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    _status.value = ShizukuScreenStatus.PERMISSION_REQUIRED
                service != null -> refreshBackendStatus()
                binding.compareAndSet(false, true) -> {
                    if (_status.value != ShizukuScreenStatus.READY) {
                        _status.value = ShizukuScreenStatus.BINDING
                    }
                    val connection = synchronized(bindLock) {
                        val generation = ++bindGeneration
                        connectionFor(generation).also { activeConnection = it }
                    }
                    Shizuku.bindUserService(userServiceArgs, connection)
                }
            }
        }.onFailure {
            binding.set(false)
            synchronized(bindLock) {
                bindGeneration++
                activeConnection = null
            }
            _status.value = ShizukuScreenStatus.ERROR
        }
    }

    private fun refreshBackendStatus() {
        val backendStatus = runCatching { service?.backendStatus }.getOrNull()
        if (backendStatus == ScreenMirrorBackendStatus.READY) {
            // Publish READY only after every setup probe has completed, so opt-in cannot observe a
            // transient zero bitmask while the privileged binder calls are still in flight.
            _availableCodecBits.value = runCatching { service?.probeHardwareCodecs() ?: 0 }.getOrDefault(0)
            _probeBits.value = runCatching { service?.probeCapabilities() ?: 0 }.getOrDefault(0)
            _status.value = ShizukuScreenStatus.READY
            return
        }
        _status.value = when (backendStatus) {
            ScreenMirrorBackendStatus.ROOT_UNSUPPORTED -> ShizukuScreenStatus.ROOT_UNSUPPORTED
            ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE,
            ScreenMirrorBackendStatus.CODEC_UNAVAILABLE,
            null -> ShizukuScreenStatus.BACKEND_UNAVAILABLE
            else -> ShizukuScreenStatus.ERROR
        }
        _availableCodecBits.value = 0
        _probeBits.value = 0
    }

    private fun ScreenMirrorCodec.codecId(): Int = when (this) {
        ScreenMirrorCodec.H264 -> ScreenMirrorCodecBits.H264
        ScreenMirrorCodec.H265 -> ScreenMirrorCodecBits.H265
        ScreenMirrorCodec.AV1 -> ScreenMirrorCodecBits.AV1
    }

    private companion object {
        const val MIN_SHIZUKU_API = 13
        const val USER_SERVICE_REVISION = 9
        const val PERMISSION_REQUEST_CODE = 0x5343
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val DEFAULT_MAX_DIMENSION = 1920
        const val DEFAULT_MAX_FPS = 60
        const val DEFAULT_BITRATE_BPS = 8_000_000
        const val SERVICE_BIND_TIMEOUT_MS = 10_000L
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
