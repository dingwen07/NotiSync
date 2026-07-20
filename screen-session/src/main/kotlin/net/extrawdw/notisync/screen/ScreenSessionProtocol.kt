package net.extrawdw.notisync.screen

/** Stable application protocol layered over the authenticated screen-session transport. */
object ScreenSessionProtocol {
    const val VERSION: Int = 1
    const val ALPN: String = "notisync-screen/1"
}
