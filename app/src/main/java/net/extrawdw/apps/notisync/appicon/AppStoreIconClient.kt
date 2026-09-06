package net.extrawdw.apps.notisync.appicon

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Outcome of an App Store icon fetch. The caller negative-caches [NotFound] (a real, durable "no store
 * entry") but never [TransientError] (a network/timeout/server failure that should simply be retried).
 */
sealed interface IconFetchResult {
    /** The icon bytes (a compact WebP). Not a data class: it holds a ByteArray (referential equality). */
    class Found(val bytes: ByteArray) : IconFetchResult

    /** Every storefront answered successfully, none had the app — a durable miss, safe to negative-cache. */
    data object NotFound : IconFetchResult

    /** A network/timeout/server error prevented a definitive answer — do not cache; retry later. */
    data object TransientError : IconFetchResult
}

/**
 * Fetches a real iOS app icon from Apple's public **iTunes Lookup API**, keyed by the exact bundle id ANCS
 * already gives us. No API key; the artwork CDN serves WebP at any size via a templated URL suffix, so the
 * bytes returned here are a compact WebP ready to cache as-is.
 *
 * The lookup covers third-party apps and nearly all Apple first-party apps (Messages, Mail, Phone, Wallet,
 * Health, …); a few pure-OS surfaces (Settings, App Store) have no store entry and resolve to null here —
 * those are covered by the [ShippedIcons] pack instead. Network errors / no entry both return null.
 *
 * [fetch] resolves bundle IDs for notification icons; [fetchAppId] resolves numeric IDs for explicit imports.
 * Both use the same storefront fallback, response parser and bounded image download.
 */
class AppStoreIconClient internal constructor(
    private val download: suspend (url: String, maxBytes: Int) -> ByteArray,
    /** App Store storefronts to try in order — the first with an entry wins. Default US then CN, since some
     *  apps are CN-store-only (e.g. Douyin, QQ) and never resolve against the US store. */
    private val countries: List<String> = DEFAULT_COUNTRIES,
) {
    constructor(
        client: HttpClient = defaultClient(),
        countries: List<String> = DEFAULT_COUNTRIES,
    ) : this({ url, maxBytes -> client.downloadIconBytes(url, maxBytes) }, countries)

    /** A compact WebP for [bundleId] at [sizePx] from the first storefront that has it; otherwise a real
     *  miss vs. a transient failure (see [IconFetchResult]). */
    suspend fun fetch(bundleId: String, sizePx: Int): IconFetchResult =
        fetchFromStorefronts(sizePx, countries) { country -> lookupUrl(bundleId, country) }

    /** Numeric IDs work for both iOS and macOS apps. Prefer the storefront in the pasted URL. */
    suspend fun fetchAppId(appId: String, sizePx: Int, country: String? = null): IconFetchResult {
        require(appId.toLongOrNull()?.let { it > 0 } == true)
        return fetchFromStorefronts(sizePx, (listOfNotNull(country) + countries).distinct(), appId) {
            lookupAppIdUrl(appId, it)
        }
    }

    private suspend fun fetchFromStorefronts(
        sizePx: Int,
        storefronts: List<String>,
        appId: String? = null,
        lookup: (String) -> String,
    ): IconFetchResult {
        var sawTransient = false
        for (country in storefronts) {
            when (val r = fetchFrom(lookup(country), sizePx, appId)) {
                is IconFetchResult.Found -> return r
                IconFetchResult.NotFound -> {} // definitively absent here — try the next storefront
                IconFetchResult.TransientError -> sawTransient = true
            }
        }
        // No storefront had it: a genuine miss only if every lookup actually succeeded. If any failed
        // transiently, report transient so the caller retries instead of caching a false negative.
        return if (sawTransient) IconFetchResult.TransientError else IconFetchResult.NotFound
    }

    private suspend fun fetchFrom(lookupUrl: String, sizePx: Int, appId: String?): IconFetchResult {
        return try {
            val lookup = download(lookupUrl, 1024 * 1024)
            val artwork = parseArtworkUrl(lookup.decodeToString(), appId)
                ?: return IconFetchResult.NotFound
            IconFetchResult.Found(download(toWebpUrl(artwork, sizePx), MAX_ICON_SOURCE_BYTES))
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Exception) {
            IconFetchResult.TransientError // network error / timeout — retryable
        }
    }

    @Serializable
    private data class Lookup(val results: List<App> = emptyList())

    @Serializable
    private data class App(
        val trackId: Long? = null,
        val wrapperType: String? = null,
        val artworkUrl512: String? = null,
        val artworkUrl100: String? = null,
        val artworkUrl60: String? = null,
    )

    internal companion object {
        private val DEFAULT_COUNTRIES = listOf("us", "cn")
        private val json = Json { ignoreUnknownKeys = true }

        // The trailing artwork transform segment, e.g. `/512x512bb.jpg` — Apple's CDN re-renders to whatever
        // size/format we substitute here, including `.webp`.
        private val ARTWORK_SUFFIX =
            Regex("""/\d+x\d+[a-z]*\.(?:jpg|jpeg|png|webp)$""", RegexOption.IGNORE_CASE)

        fun lookupUrl(bundleId: String, country: String): String =
            "https://itunes.apple.com/lookup?bundleId=$bundleId&country=$country&entity=software"

        fun lookupAppIdUrl(appId: String, country: String): String {
            require(appId.toLongOrNull()?.let { it > 0 } == true)
            require(country.matches(Regex("[a-zA-Z]{2}")))
            // No entity=software filter: a numeric ID can identify a macOS application too.
            return "https://itunes.apple.com/lookup?id=$appId&country=$country"
        }

        /** Highest-res artwork URL in a lookup body, or null for an empty/!malformed result (no store entry). */
        fun parseArtworkUrl(body: String, appId: String? = null): String? {
            val app = runCatching { json.decodeFromString<Lookup>(body) }.getOrNull()?.results?.firstOrNull {
                appId == null || (it.trackId?.toString() == appId && it.wrapperType == "software")
            }
            return app?.artworkUrl512 ?: app?.artworkUrl100 ?: app?.artworkUrl60
        }

        /** Rewrite the size/format suffix to pull a square WebP at [sizePx]; pass non-templated URLs through. */
        fun toWebpUrl(artworkUrl: String, sizePx: Int): String =
            if (ARTWORK_SUFFIX.containsMatchIn(artworkUrl)) ARTWORK_SUFFIX.replace(
                artworkUrl,
                "/${sizePx}x${sizePx}bb.webp"
            )
            else artworkUrl

        internal fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 6_000
            }
        }
    }
}
