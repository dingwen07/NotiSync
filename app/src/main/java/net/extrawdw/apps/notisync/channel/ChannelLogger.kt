package net.extrawdw.apps.notisync.channel

/**
 * Channel-level warning sink. Keeps [SecureChannel] free of `android.util.Log` so the substrate has
 * no Android dependency — the app supplies a `Log`-backed implementation, tests a no-op.
 */
fun interface ChannelLogger {
    fun warn(msg: String)
}
