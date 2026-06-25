package net.extrawdw.apps.notisync.appicon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The shipped app-icon resource pack: a small, curated set of real app icons bundled under
 * `assets/appicons/`, keyed by a normalized identity (an iOS bundle id and/or an Android package, lowercased).
 *
 * This is the **first** source in [IconResolver.colorIcon]: a crisp, offline, consistent icon for the most
 * common apps, ahead of any delivered asset, the App Store fetch, or a borrowed installed icon. It also
 * covers the handful of iOS surfaces the App Store has no entry for (e.g. Settings). The long tail is filled
 * in at runtime by [AppStoreIconProvider]; this pack is intentionally curated to keep the APK small.
 *
 * Files are populated by `scripts/fetch_shipped_icons.py` (see that script). Lookups are lazy and memoized
 * (including misses) so a given key touches `assets/` at most once. The Android `assets`/decode boundary is an
 * injectable seam, so the pure key-resolution ([ShippedIconKeys]) is unit-tested without Robolectric.
 */
class ShippedIcons(
    private val open: (path: String) -> InputStream?,
    private val decode: (InputStream) -> Bitmap? = { BitmapFactory.decodeStream(it) },
) {
    // key (file stem) -> decoded bitmap or empty (memoized miss). Optional, not nullable, to cache negatives.
    private val cache = ConcurrentHashMap<String, Optional<Bitmap>>()

    /** A bundled icon for the [iosBundleId] (preferred) or [packageName], or null if the pack has none. */
    fun bitmap(iosBundleId: String?, packageName: String?): Bitmap? {
        for (key in ShippedIconKeys.candidates(iosBundleId, packageName)) load(key)?.let { return it }
        return null
    }

    /** Whether the pack covers this bundle id — used to decide if an App Store fetch is even worth trying. */
    fun covers(iosBundleId: String): Boolean = bitmap(iosBundleId, null) != null

    private fun load(key: String): Bitmap? {
        cache[key]?.let { return it.orElse(null) }
        val bmp = open("$DIR/$key.webp")?.use { decode(it) }
        cache[key] = Optional.ofNullable(bmp)
        return bmp
    }

    companion object {
        private const val DIR = "appicons"

        /** Back the pack with the app's bundled `assets/appicons/` directory. */
        fun fromAssets(context: Context): ShippedIcons =
            ShippedIcons(open = { path -> runCatching { context.assets.open(path) }.getOrNull() })
    }
}

/**
 * Pure (Android-free) resolution of the ordered, de-duplicated candidate file stems to try under
 * `assets/appicons/` for a given identity. iOS bundle id first (the common ANCS case), then its curated
 * Android-package alias from [BundleIdMap], then the explicit package. All lowercased to match the shipped
 * filenames. Kept separate so it is trivially unit-testable.
 */
internal object ShippedIconKeys {
    fun candidates(iosBundleId: String?, packageName: String?): List<String> {
        val out = LinkedHashSet<String>()
        iosBundleId?.takeIf { it.isNotBlank() }?.let {
            out += it.lowercase()
            BundleIdMap.androidPackage(it)?.let { pkg -> out += pkg.lowercase() }
        }
        packageName?.takeIf { it.isNotBlank() }?.let { out += it.lowercase() }
        return out.toList()
    }
}
