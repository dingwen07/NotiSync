package net.extrawdw.apps.notisync.appicon

import net.extrawdw.notisync.protocol.OriginPlatform

/**
 * A resolved app identity for UI listing and icon resolution.
 *
 * For an Android app, [id] is the package name and [androidPackage] == [id]. For an iOS app, [id] is the
 * ANCS bundle identifier and [androidPackage] is the best-match Android package (if any) we can borrow a
 * locally-installed launcher icon from — see [BundleIdMap] / [IconResolver].
 */
data class AppIdentity(
    val platform: OriginPlatform,
    /** Package name (Android) or bundle identifier (iOS). */
    val id: String,
    val displayName: String,
    /** Best-match Android package for icon reuse; equals [id] for an Android app, may be null for iOS. */
    val androidPackage: String? = null,
) {
    companion object {
        fun android(packageName: String, label: String) =
            AppIdentity(OriginPlatform.ANDROID_LOCAL, packageName, label, packageName)

        fun ios(bundleId: String, displayName: String, androidPackage: String?) =
            AppIdentity(OriginPlatform.IOS_ANCS, bundleId, displayName, androidPackage)
    }
}
