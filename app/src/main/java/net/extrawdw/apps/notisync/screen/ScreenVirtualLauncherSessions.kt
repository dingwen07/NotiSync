package net.extrawdw.apps.notisync.screen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Local launch authority, never accepted from a peer or persisted beyond the source session. */
internal object ScreenVirtualLauncherSessions {
    private class Session(val intent: PendingIntent, val authorized: () -> Boolean) {
        val displayId = AtomicInteger(-1)
    }
    private val sessions = ConcurrentHashMap<String, Session>()

    fun register(context: Context, ownerToken: String, authorized: () -> Boolean): PendingIntent {
        remove(ownerToken)
        val intent = Intent(context, ScreenVirtualLauncherActivity::class.java)
            .setData("notisync://virtual-launcher/$ownerToken".toUri())
            .putExtra(ScreenVirtualLauncherActivity.EXTRA_OWNER, ownerToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        sessions[ownerToken] = Session(pending, authorized)
        return pending
    }

    fun isAuthorized(ownerToken: String, displayId: Int): Boolean {
        if (displayId <= 0) return false
        val session = sessions[ownerToken] ?: return false
        if (!session.authorized()) return false
        session.displayId.compareAndSet(-1, displayId)
        return session.displayId.get() == displayId
    }

    fun remove(ownerToken: String) { sessions.remove(ownerToken)?.intent?.cancel() }
    fun clear() { sessions.keys.toList().forEach(::remove) }
}
