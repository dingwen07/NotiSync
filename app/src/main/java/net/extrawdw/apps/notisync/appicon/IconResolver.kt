package net.extrawdw.apps.notisync.appicon

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import net.extrawdw.apps.notisync.assets.AssetCache

/**
 * Resolves a recognizable color icon for a (possibly remote, possibly iOS-origin) notification, and maps an
 * iOS bundle id to a best-match Android package. Shared by the receiver's mirror render ([colorIcon]), the
 * iOS tab's app list, and the ANCS capture mapper ([androidPackageForIos]).
 *
 * The Android bits (PackageManager, Bitmap) live here; the pure mapping table is [BundleIdMap], kept
 * Android-free and unit-tested separately.
 */
class IconResolver(
    private val context: Context,
    private val assetCache: AssetCache,
    /** The bundled icon pack — resolved first (crisp, offline, consistent for the curated common set). */
    private val shippedIcons: ShippedIcons? = null,
    /** Runtime App Store (iTunes Lookup) icons for the iOS long tail; cache-only here, fetch via [colorIconEnsuringRemote]. */
    private val appStoreIcons: AppStoreIconProvider? = null,
) {
    private val pm: PackageManager get() = context.packageManager

    // Lazily-built, cached index of installed launcher apps by lowercased label, for the display-name
    // heuristic. Cleared by [invalidate] (e.g. after an install changes the installed set).
    @Volatile private var labelIndex: Map<String, String>? = null

    /**
     * A color icon for an app, following the resolution chain (best, cheapest first):
     *  1. the shipped icon pack ([ShippedIcons]) — curated, offline, consistent;
     *  2. an already-fetched App Store icon for the iOS [iosBundleId] (cache-only — no network here);
     *  3. the delivered app-icon asset ([appIconHash]) — covers a consumer that lacks the app installed;
     *  4. the locally-installed app's launcher icon (by [packageName]);
     *  5. the curated bundle-id → Android package, if that package is installed (a borrowed-icon fallback);
     * else null — the caller may draw a monogram from the display name. The network step for (2) is
     * [colorIconEnsuringRemote] / [AppStoreIconProvider.ensureCached], invoked off the render path.
     */
    fun colorIcon(packageName: String?, iosBundleId: String? = null, appIconHash: String? = null): Bitmap? {
        shippedIcons?.bitmap(iosBundleId, packageName)?.let { return it }
        iosBundleId?.let { appStoreIcons?.cached(it) }?.let { return it }
        appIconHash?.let { hash -> assetCache.read(hash)?.let { decode(it) }?.let { return it } }
        packageName?.let { installedIcon(it) }?.let { return it }
        iosBundleId?.let { BundleIdMap.androidPackage(it) }?.let { installedIcon(it) }?.let { return it }
        return null
    }

    /**
     * Like [colorIcon] but, for an iOS [iosBundleId] not covered by the shipped pack, first ensures the real
     * App Store icon is fetched + cached (a one-time network call) so the returned bitmap is the authentic
     * icon rather than a borrowed/installed fallback. For the iOS app-list UI, which already runs off-main;
     * the render path uses the sync [colorIcon] + an [AppStoreIconProvider.ensureCached] re-render instead.
     */
    suspend fun colorIconEnsuringRemote(packageName: String?, iosBundleId: String? = null): Bitmap? {
        if (iosBundleId != null && shippedIcons?.covers(iosBundleId) != true) {
            appStoreIcons?.ensureCached(iosBundleId)
        }
        return colorIcon(packageName, iosBundleId)
    }

    /** Whether the shipped pack already has [iosBundleId] — lets a caller skip a pointless App Store fetch. */
    fun shippedCovers(iosBundleId: String): Boolean = shippedIcons?.covers(iosBundleId) == true

    /**
     * Best-match Android package for an iOS [bundleId]: the curated [BundleIdMap] (canonical even if the app
     * isn't installed here), else a label heuristic over installed apps using the ANCS [displayName]. Null if
     * nothing matches — the caller then keeps the bundle id itself as the package field.
     */
    fun androidPackageForIos(bundleId: String, displayName: String?): String? =
        BundleIdMap.androidPackage(bundleId) ?: displayName?.let { androidPackageByLabel(it) }

    /** First installed launcher app whose label equals [label] (case-insensitive), or null. */
    fun androidPackageByLabel(label: String): String? {
        val index = labelIndex ?: buildLabelIndex().also { labelIndex = it }
        return index[label.trim().lowercase()]
    }

    /** Drop the cached installed-app index so the next heuristic lookup rebuilds it (e.g. after an install). */
    fun invalidate() { labelIndex = null }

    private fun installedIcon(pkg: String): Bitmap? =
        runCatching { drawableToBitmap(pm.getApplicationIcon(pkg)) }.getOrNull()

    // AndroidManifest.xml <quires> android.intent.category.LAUNCHER
    @Suppress("QueryPermissionsNeeded")
    private fun buildLabelIndex(): Map<String, String> = runCatching {
        buildMap {
            for (info in pm.getInstalledApplications(0)) {
                if (info.packageName == context.packageName) continue
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()?.trim()?.lowercase()
                if (!label.isNullOrEmpty()) putIfAbsent(label, info.packageName)
            }
        }
    }.getOrDefault(emptyMap())

    private fun decode(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) drawable.bitmap?.let { return it }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_PX
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_PX
        val bitmap = createBitmap(w, h)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private companion object { const val ICON_PX = 128 }
}
