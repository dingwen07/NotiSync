package net.extrawdw.notisync.gpg

sealed interface GitSigningInvocation {
    data class Remote(val selector: String) : GitSigningInvocation
    data object Delegate : GitSigningInvocation
}

object GitSigningInvocationParser {
    /** Git's OpenPGP adapter contract is deliberately treated as one exact golden invocation. */
    fun parse(arguments: List<String>): GitSigningInvocation {
        if (arguments.size != 3 || arguments[0] != "--status-fd=2" || arguments[1] != "-bsau") {
            return GitSigningInvocation.Delegate
        }
        val selector = arguments[2]
        if (selector.endsWith('!')) return GitSigningInvocation.Delegate
        val normalized = selector.removePrefix("0x").removePrefix("0X")
        if (!SUPPORTED_SELECTOR.matches(normalized)) return GitSigningInvocation.Delegate
        return GitSigningInvocation.Remote(selector)
    }

    private val SUPPORTED_SELECTOR = Regex("(?i)(?:[0-9a-f]{16}|[0-9a-f]{40}|[0-9a-f]{64})")
}
