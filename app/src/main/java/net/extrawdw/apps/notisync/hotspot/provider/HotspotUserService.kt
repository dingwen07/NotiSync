package net.extrawdw.apps.notisync.hotspot.provider

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/** Separate from capture: ending a screen session must not interrupt a hotspot operation. */
@Keep
@RequiresApi(36)
class HotspotUserService() : IHotspotUserService.Stub() {
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val lock = ReentrantLock()
    private var platform: HotspotPlatform? = null
    @Volatile private var observer: IHotspotObserver? = null

    override fun observe(observer: IHotspotObserver?) { this.observer = observer }
    override fun query(): Bundle = execute { it.query() }
    override fun setEnabled(enabled: Boolean): Bundle = execute { it.setEnabled(enabled) }

    private fun execute(action: (HotspotPlatform) -> Bundle): Bundle {
        if (Process.myUid() != Process.SHELL_UID) return HotspotPlatform.failure("UNAVAILABLE", null)
        if (!lock.tryLock()) return HotspotPlatform.failure("BUSY", null)
        val identity = Binder.clearCallingIdentity()
        return try {
            val backend = platform ?: HotspotPlatform {
                runCatching { observer?.onChanged() }
            }.also { platform = it }
            action(backend)
        } catch (_: Exception) {
            // Framework exceptions can contain configuration; only bounded result codes cross Binder.
            HotspotPlatform.failure("UNAVAILABLE", null)
        } finally {
            Binder.restoreCallingIdentity(identity)
            lock.unlock()
        }
    }

    override fun destroy() {
        observer = null
        lock.withLock { runCatching { platform?.close() }; platform = null }
        // Closing a service never stops the system hotspot.
        exitProcess(0)
    }
}
