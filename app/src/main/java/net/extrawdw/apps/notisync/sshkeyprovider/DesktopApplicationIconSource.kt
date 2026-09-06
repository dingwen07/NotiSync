package net.extrawdw.apps.notisync.sshkeyprovider

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal sealed interface DesktopApplicationIconSource {
    data class AppStore(val appId: String, val country: String? = null) : DesktopApplicationIconSource
    data class BundleId(val bundleId: String) : DesktopApplicationIconSource
    data class ImageUrl(val url: String) : DesktopApplicationIconSource

    companion object {
        fun parse(input: String): DesktopApplicationIconSource? {
            val text = input.trim()
            appId(text)?.let { return AppStore(it) }
            if (text.matches(Regex("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+"))) return BundleId(text)
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (uri.userInfo != null) return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (host in setOf("apps.apple.com", "itunes.apple.com") && scheme in setOf("http", "https", "itms-apps")) {
                val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
                if ("app" !in segments && segments.lastOrNull() != "viewSoftware") return null
                val id = segments.lastOrNull()?.let(::appId)
                    ?: uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { parameter ->
                        val parts = parameter.split('=', limit = 2)
                        if (parts.size == 2 && parts[0] == "id") {
                            runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()?.let(::appId)
                        } else null
                    } ?: return null
                val country = segments.firstOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z]{2}")) }
                return AppStore(id, country)
            }
            if (scheme != "https") return null
            return ImageUrl("https:" + uri.toASCIIString().substringAfter(':'))
        }

        private fun appId(value: String): String? = value.removePrefix("id")
            .takeIf { it.matches(Regex("[1-9][0-9]*")) && it.toLongOrNull() != null }
    }
}

