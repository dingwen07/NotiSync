package net.extrawdw.apps.notisync.hotspot.provider

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.notisync.BuildConfig
import net.extrawdw.notisync.protocol.HotspotResult
import net.extrawdw.notisync.protocol.HotspotSnapshot
import net.extrawdw.notisync.protocol.HotspotState
import rikka.shizuku.Shizuku

/** Uses the existing app-wide Shizuku grant, but owns an independent, non-daemon UserService. */
internal class HotspotShizukuManager(context: Context, private val changed: () -> Unit) : HotspotBackend {
    private val lock = Any()
    private var enabled = false
    private var connection: ServiceConnection? = null
    private val remote = MutableStateFlow<IHotspotUserService?>(null)
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) {
        Thread(it, "notisync-hotspot-binder").apply { isDaemon = true }
    }
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, HotspotUserService::class.java.name))
        .daemon(false).processNameSuffix("hotspot").tag("notisync-hotspot-v1")
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE * 1_000 + 1)
    private val observer = object : IHotspotObserver.Stub() {
        override fun onChanged() = changed()
    }
    private val received = Shizuku.OnBinderReceivedListener { changed() }
    private val died = Shizuku.OnBinderDeadListener {
        synchronized(lock) { remote.value = null; connection = null }
        changed()
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> changed() }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    fun setMonitoringEnabled(value: Boolean) {
        synchronized(lock) { enabled = value }
        // No eager bind: the next authorized refresh initializes monitoring. Losing permission does not
        // stop the system hotspot, and no status is sent to the revoked peer.
        if (value) changed()
    }

    override suspend fun query(): HotspotReading = call { it.query() }
    override suspend fun setEnabled(enabled: Boolean, stillAuthorized: () -> Boolean): HotspotReading = call {
        if (stillAuthorized()) it.setEnabled(enabled)
        else Bundle().apply { putString("result", "UNAUTHORIZED") }
    }

    private suspend fun call(action: (IHotspotUserService) -> Bundle): HotspotReading {
        if (Build.VERSION.SDK_INT < 36) return HotspotReading(HotspotResult.UNSUPPORTED)
        if (!synchronized(lock) { enabled } || !shizukuReady()) return HotspotReading(HotspotResult.UNAVAILABLE)
        bind()
        val service = remote.value ?: withTimeoutOrNull(8_000) { remote.filterNotNull().first() }
        if (service == null) {
            synchronized(lock) { if (remote.value == null) connection = null }
            return HotspotReading(HotspotResult.UNAVAILABLE)
        }
        return withContext(Dispatchers.IO) {
            if (!synchronized(lock) { enabled } || !shizukuReady()) return@withContext HotspotReading(HotspotResult.UNAVAILABLE)
            val task = runCatching { worker.submit<Bundle> { action(service) } }.getOrNull()
                ?: return@withContext HotspotReading(HotspotResult.BUSY)
            try {
                val bundle = task.get(30, TimeUnit.SECONDS)
                if (remote.value?.asBinder() !== service.asBinder()) HotspotReading(HotspotResult.UNAVAILABLE)
                else bundle.toReading()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: TimeoutException) { HotspotReading(HotspotResult.TIMEOUT) }
            catch (_: Exception) { HotspotReading(HotspotResult.UNAVAILABLE) }
            finally { task.cancel(true); worker.purge() }
        }
    }

    private fun shizukuReady() = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getVersion() >= 13 &&
            Shizuku.getUid() == Process.SHELL_UID && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun bind() = synchronized(lock) {
        if (connection != null || !enabled || !shizukuReady()) return@synchronized
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                synchronized(lock) {
                    if (connection !== this) return
                    remote.value = IHotspotUserService.Stub.asInterface(binder)
                }
                // Binder calls stay off the main thread, including observer registration.
                runCatching { worker.execute { runCatching { remote.value?.observe(observer) } } }
            }
            override fun onServiceDisconnected(name: ComponentName) = disconnect()
            override fun onBindingDied(name: ComponentName) = disconnect()
            override fun onNullBinding(name: ComponentName) = disconnect()
            private fun disconnect() {
                synchronized(lock) {
                    if (connection !== this) return
                    connection = null
                    remote.value = null
                }
                changed()
            }
        }
        connection = candidate
        try { Shizuku.bindUserService(args, candidate) }
        catch (_: Exception) { connection = null; remote.value = null }
    }
}

private fun Bundle.toReading(): HotspotReading {
    val result = runCatching { HotspotResult.valueOf(getString("result").orEmpty()) }.getOrDefault(HotspotResult.UNAVAILABLE)
    return HotspotReading(
        result = result,
        platformError = if (containsKey("platformError")) getInt("platformError") else null,
        snapshot = if (result != HotspotResult.OK) null else HotspotSnapshot(
            state = when (getInt("apState", -1)) {
                10 -> HotspotState.DISABLING
                11 -> HotspotState.DISABLED
                12 -> HotspotState.ENABLING
                13 -> HotspotState.ENABLED
                14 -> HotspotState.FAILED
                else -> HotspotState.UNKNOWN
            },
            ssid = getString("ssid"), psk = getString("psk"),
            securityType = if (containsKey("securityType")) getInt("securityType") else null,
            wifiTethered = getBoolean("wifiTethered"),
            hiddenSsid = getBoolean("hiddenSsid"),
        ),
    )
}
