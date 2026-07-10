package net.extrawdw.apps.notisync.ios

import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.NotificationAction
import net.extrawdw.notisync.protocol.OriginPlatform

/** A fully-assembled ANCS notification: the source packet + fetched attributes + resolved app name. */
data class AncsRecord(
    val source: Ancs.SourcePacket,
    val bundleId: String,
    val displayName: String,
    val title: String?,
    val subtitle: String?,
    val message: String?,
    val date: String?,
    /** ANCS Positive/Negative Action Labels — fetched only when the EventFlags advertise an action. */
    val positiveActionLabel: String? = null,
    val negativeActionLabel: String? = null,
)

/**
 * Maps an [AncsRecord] to the platform-neutral [CapturedNotification] the rest of the pipeline already
 * understands. Pure (the Android-dependent bundle-id → package resolution is done by the caller and passed
 * in as [androidPackage]), so it's unit-testable.
 *
 * ANCS carries no icons or rich structure, so the output is text + category/importance only; the app icon is
 * resolved/attached separately (a delivered [AssetRole.APP_ICON][net.extrawdw.notisync.protocol.AssetRole]
 * asset when the bridge has the mapped app installed, else the consumer's own resolution chain).
 */
object AncsNotificationMapper {
    private const val BIG_TEXT_THRESHOLD = 80

    fun map(
        clientId: ClientId,
        record: AncsRecord,
        iphoneId: String,
        iphoneName: String?,
        androidPackage: String?,
        now: Long,
    ): CapturedNotification {
        val message = record.message
        val body = message ?: record.subtitle
        val isLong = (message?.length ?: 0) > BIG_TEXT_THRESHOLD
        return CapturedNotification(
            sourceClientId = clientId,
            // The UID is session-scoped (it resets when the ANCS link drops) — fine for in-session dedup +
            // dismissal keying, which is all we need. The id stays stable for the life of one connection.
            sourceKey = "ancs|$iphoneId|${record.bundleId}|${record.source.notificationUid}",
            packageName = androidPackage ?: record.bundleId,
            appLabel = record.displayName,
            title = record.title ?: record.displayName,
            text = body,
            bigText = if (isLong) message else null,
            subText = record.subtitle?.takeIf { it != record.title && it != body },
            style = if (isLong) NotificationStyle.BIG_TEXT else NotificationStyle.DEFAULT,
            category = mapCategory(record.source.categoryId),
            // A live incoming call is marked INCOMING so the receiver rings it; a missed call / voicemail share
            // MirrorCategory.CALL but carry NO callType, so they mirror as ordinary notifications that never ring
            // (the receiver's non-CallStyle "any call-category post rings" heuristic is Android-only — see
            // RemoteNotificationPoster.isRingingCall — because an ANCS capture is never a foreground service).
            callType = mapCallType(record.source.categoryId),
            // Always HIGH: iOS shows notifications as banners, so the mirrored channel must stay banner-capable.
            // The per-notification iOS "silent" flag is deliberately ignored here — it flips at runtime (e.g. iOS
            // sets it whenever the iPhone is unlocked/in use), and feeding it into the channel's importance would
            // pin the channel to Silent permanently (the OS never raises a channel). The connect-time backlog is
            // quieted at post time via setSilent() instead, never by lowering importance. See MirrorChannels.
            importance = MirrorImportance.HIGH,
            postTime = Ancs.parseDate(record.date) ?: now,
            originPlatform = OriginPlatform.IOS_ANCS,
            originDeviceName = iphoneName,
            iosBundleId = record.bundleId,
            originDeviceId = iphoneId,
            actions = mapActions(record),
            // hasContentIntent stays false: ANCS has no "open on iPhone" command, so peers must not
            // offer tap-to-open for a bridged capture.
        )
    }

    /**
     * Best-effort ANCS action mirroring, indexed by ANCS ActionID (0 = positive, 1 = negative) so a
     * peer's [net.extrawdw.notisync.protocol.ActionEvent] maps straight onto PerformNotificationAction.
     * A lone negative action is deliberately NOT mirrored: for most iOS notifications it's just
     * "Clear", which dismissal sync already performs on a swipe — a redundant button on every mirror.
     * Paired with a positive action it's a real choice (incoming call: Answer/Decline), so both ship.
     */
    private fun mapActions(record: AncsRecord): List<NotificationAction> {
        val positive = record.positiveActionLabel?.takeIf { it.isNotBlank() } ?: return emptyList()
        val negative = record.negativeActionLabel?.takeIf { it.isNotBlank() }
        return buildList {
            add(NotificationAction(index = Ancs.ACTION_POSITIVE, title = positive))
            if (negative != null) add(NotificationAction(index = Ancs.ACTION_NEGATIVE, title = negative))
        }
    }

    /** ANCS lumps incoming call / missed call / voicemail into one [MirrorCategory.CALL] bucket, but only a
     *  live incoming call should ring. Marking just that one [CallType.INCOMING] lets the receiver ring it while
     *  a missed call / voicemail (no callType) mirrors as a normal, silent notification. */
    private fun mapCallType(categoryId: Int): CallType? =
        if (categoryId == Ancs.CAT_INCOMING_CALL) CallType.INCOMING else null

    private fun mapCategory(categoryId: Int): MirrorCategory = when (categoryId) {
        Ancs.CAT_INCOMING_CALL, Ancs.CAT_MISSED_CALL, Ancs.CAT_VOICEMAIL -> MirrorCategory.CALL
        Ancs.CAT_SOCIAL -> MirrorCategory.MESSAGE
        Ancs.CAT_SCHEDULE -> MirrorCategory.EVENT
        Ancs.CAT_EMAIL -> MirrorCategory.EMAIL
        Ancs.CAT_LOCATION -> MirrorCategory.NAVIGATION
        else -> MirrorCategory.NONE
    }
}
