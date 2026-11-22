package net.extrawdw.apps.notisync.ios

/** Main-thread, bridge-session history. Times are elapsed realtime in milliseconds. */
internal class AdvertisingLossTracker {
    private var requestedAt: Long? = null
    private val losses = ArrayDeque<Long>()
    val tripped: Boolean get() = losses.size >= 3

    fun advertisingRequested(now: Long) {
        requestedAt = now
    }

    fun advertisingStopped() {
        requestedAt = null
    }

    /** Returns true once, on the loss that disables this bridge session. */
    fun adapterWentDown(now: Long): Boolean {
        val start = requestedAt ?: return false
        requestedAt = null // TURNING_OFF and OFF can both arrive for the same request.
        if (tripped || now - start > 5_000L) return false
        while (losses.isNotEmpty() && now - losses.first() > 60_000L) losses.removeFirst()
        losses.addLast(now)
        return tripped
    }

    fun reset() {
        requestedAt = null
        losses.clear()
    }
}
