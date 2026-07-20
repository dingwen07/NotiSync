package net.extrawdw.apps.notisync.screen

/** Produces a bounded, single-line controller identity for persistent notifications and intents. */
internal object ScreenControllerLabel {
    private const val MAX_CHARS = 96
    private val forbidden = Regex("[\\p{Cc}\\p{Cf}]")
    private val whitespace = Regex("\\s+")

    fun create(displayName: String?, peerIdShortForm: String): String {
        val id = sanitize(peerIdShortForm) ?: error("peer identity is blank")
        val name = sanitize(displayName)
        if (name == null || name == id) return id
        val suffix = " ($id)"
        return "${name.take((MAX_CHARS - suffix.length).coerceAtLeast(0)).trim()}$suffix"
    }

    fun fromIntent(value: String?): String? = sanitize(value)

    private fun sanitize(value: String?): String? = value
        ?.replace(forbidden, " ")
        ?.replace(whitespace, " ")
        ?.trim()
        ?.take(MAX_CHARS)
        ?.takeIf(String::isNotEmpty)
}
