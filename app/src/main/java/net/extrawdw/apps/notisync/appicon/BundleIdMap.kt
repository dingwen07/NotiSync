package net.extrawdw.apps.notisync.appicon

/**
 * Curated, shipped mapping from an iOS app **bundle identifier** to (a) its best-match **Android package**
 * — so a consumer can borrow a locally-installed launcher icon — and (b) a human **display name** fallback
 * for before/if ANCS returns the app's own DisplayName.
 *
 * ANCS gives us only the bundle id (and, on request, a display name) — never an icon. So recovering a
 * recognizable icon for an iOS notification leans on this table first (map to an installed Android app's
 * icon), then a label heuristic, then a future shipped icon pack (see [IconResolver]). Bundle ids are very
 * stable (they rarely change across app versions), which is what makes a static table viable.
 *
 * Deliberately a plain Kotlin object with no Android dependencies, so it is trivially unit-testable and
 * extensible. This is a best-effort core set, not exhaustive — add entries freely.
 */
object BundleIdMap {

    data class Entry(val androidPackage: String?, val displayName: String)

    /** Best-match Android package for [bundleId], or null if unknown / no Android counterpart. */
    fun androidPackage(bundleId: String): String? = lookup(bundleId)?.androidPackage

    /** Curated display name for [bundleId], or null if unknown (caller falls back to the ANCS DisplayName). */
    fun displayName(bundleId: String): String? = lookup(bundleId)?.displayName

    /** Case-exact lookup, then a case-insensitive fallback (ANCS sends the exact id; the fallback is belt-and-suspenders). */
    fun lookup(bundleId: String): Entry? = MAP[bundleId] ?: MAP_LC[bundleId.lowercase()]

    private val MAP: Map<String, Entry> = buildMap {
        // --- Apple first-party (no Android counterpart; display name only, for nicer labels) ---
        put("com.apple.MobileSMS", Entry(null, "Messages"))
        put("com.apple.mobilemail", Entry(null, "Mail"))
        put("com.apple.mobilephone", Entry(null, "Phone"))
        put("com.apple.facetime", Entry(null, "FaceTime"))
        put("com.apple.mobilecal", Entry(null, "Calendar"))
        put("com.apple.mobileslideshow", Entry(null, "Photos"))
        put("com.apple.camera", Entry(null, "Camera"))
        put("com.apple.Maps", Entry(null, "Maps"))
        put("com.apple.mobiletimer", Entry(null, "Clock"))
        put("com.apple.reminders", Entry(null, "Reminders"))
        put("com.apple.mobilenotes", Entry(null, "Notes"))
        put("com.apple.Music", Entry(null, "Music"))
        put("com.apple.podcasts", Entry(null, "Podcasts"))
        put("com.apple.Preferences", Entry(null, "Settings"))
        put("com.apple.mobilesafari", Entry(null, "Safari"))
        put("com.apple.Passbook", Entry(null, "Wallet"))
        put("com.apple.findmy", Entry(null, "Find My"))
        put("com.apple.Home", Entry(null, "Home"))
        put("com.apple.news", Entry(null, "News"))
        put("com.apple.weather", Entry(null, "Weather"))
        put("com.apple.stocks", Entry(null, "Stocks"))
        put("com.apple.Health", Entry(null, "Health"))
        put("com.apple.DocumentsApp", Entry(null, "Files"))
        put("com.apple.AppStore", Entry(null, "App Store"))
        put("com.apple.tv", Entry(null, "TV"))
        put("com.apple.iBooks", Entry(null, "Books"))
        // System surface (no App Store entry) — fires when an NFC NDEF tag is scanned; a shipped placeholder
        // covers it. Handy to trigger for testing the shipped-pack-first path.
        put("com.apple.barcodesupport.nfc", Entry(null, "NFC Tag Reader"))

        // --- Messaging / social (bundle id -> Android package) ---
        put("net.whatsapp.WhatsApp", Entry("com.whatsapp", "WhatsApp"))
        put("com.facebook.Messenger", Entry("com.facebook.orca", "Messenger"))
        put("com.facebook.Facebook", Entry("com.facebook.katana", "Facebook"))
        put("com.burbn.instagram", Entry("com.instagram.android", "Instagram"))
        put("ph.telegra.Telegraph", Entry("org.telegram.messenger", "Telegram"))
        put("org.whispersystems.signal", Entry("org.thoughtcrime.securesms", "Signal"))
        put("com.toyopagroup.picaboo", Entry("com.snapchat.android", "Snapchat"))
        put("com.atebits.Tweetie2", Entry("com.twitter.android", "X"))
        put("com.hammerandchisel.discord", Entry("com.discord", "Discord"))
        put("com.tinyspeck.chatlyio", Entry("com.Slack", "Slack"))
        put("com.reddit.Reddit", Entry("com.reddit.frontpage", "Reddit"))
        put("com.linkedin.LinkedIn", Entry("com.linkedin.android", "LinkedIn"))
        put("com.tencent.xin", Entry("com.tencent.mm", "WeChat"))
        put("jp.naver.line", Entry("jp.naver.line.android", "LINE"))
        put("com.viber", Entry("com.viber.voip", "Viber"))
        put("com.kakao.talk", Entry("com.kakao.talk", "KakaoTalk"))
        put("com.tinder", Entry("com.tinder", "Tinder"))
        put("pinterest", Entry("com.pinterest", "Pinterest"))

        // --- Google ---
        put("com.google.Gmail", Entry("com.google.android.gm", "Gmail"))
        put("com.google.Maps", Entry("com.google.android.apps.maps", "Google Maps"))
        put("com.google.ios.youtube", Entry("com.google.android.youtube", "YouTube"))
        put("com.google.chrome.ios", Entry("com.android.chrome", "Chrome"))
        put("com.google.calendar", Entry("com.google.android.calendar", "Google Calendar"))
        put("com.google.Drive", Entry("com.google.android.apps.docs", "Google Drive"))
        put("com.google.hangouts", Entry("com.google.android.apps.tachyon", "Google Meet"))
        put("com.google.photos", Entry("com.google.android.apps.photos", "Google Photos"))

        // --- Microsoft ---
        put("com.microsoft.Office.Outlook", Entry("com.microsoft.office.outlook", "Outlook"))
        put("com.microsoft.skype.teams", Entry("com.microsoft.teams", "Microsoft Teams"))
        put("com.skype.skype", Entry("com.skype.raider", "Skype"))

        // --- Media / shopping / misc ---
        put("com.spotify.client", Entry("com.spotify.music", "Spotify"))
        put("com.netflix.Netflix", Entry("com.netflix.mediaclient", "Netflix"))
        put("tv.twitch", Entry("tv.twitch.android.app", "Twitch"))
        put("com.amazon.Amazon", Entry("com.amazon.mShop.android.shopping", "Amazon"))
        put("com.ubercab.UberClient", Entry("com.ubercab", "Uber"))
        put("com.zhiliaoapp.musically", Entry("com.zhiliaoapp.musically", "TikTok"))
        put("com.paypal.PPClient", Entry("com.paypal.android.p2pmobile", "PayPal"))
    }

    /** Lowercased index backing the case-insensitive fallback in [lookup]. */
    private val MAP_LC: Map<String, Entry> = MAP.entries.associate { it.key.lowercase() to it.value }
}
