package net.extrawdw.apps.notisync.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp

/**
 * Brings the ANCS bridge back after the process would otherwise stay dead: on device reboot
 * ([Intent.ACTION_BOOT_COMPLETED]) and after the app is updated ([Intent.ACTION_MY_PACKAGE_REPLACED]).
 * Without this receiver the OS wouldn't even start our process on boot. If the user left the master switch
 * on, [AppGraph.resumeAncsBridgeIfEnabled] (re)starts the connectedDevice foreground service and re-arms
 * CompanionDeviceManager presence.
 *
 * `connectedDevice` is one of the FGS types still permitted to start from a BOOT_COMPLETED receiver on
 * Android 15+ (unlike `dataSync`/`camera`/`microphone`/`mediaPlayback`/`phoneCall`/`mediaProjection`). We
 * hold the broadcast open with [goAsync] across the DataStore read so the start lands inside the boot
 * exemption window; the start is still guarded, so a denial is harmless.
 */
class AncsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.awaitGraphReady()?.resumeAncsBridgeIfEnabled()
            } catch (t: Throwable) {
                Log.w("AncsBootReceiver", "resume failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
