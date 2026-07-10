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
        val actions = mapActions(record)
        val hasAnswer = actions.any { it.index == Ancs.ACTION_POSITIVE }
        val hasCallNegative = actions.any { it.index == Ancs.ACTION_NEGATIVE }
        // Call phase → callType. iOS answers a ringing CAT_INCOMING_CALL by REMOVING it and ADDing a fresh
        // CAT_ACTIVE_CALL (the undocumented cat 12 seen on-device) that carries only a Hang-up — so that category
        // is unconditionally ONGOING. Within CAT_INCOMING_CALL we still read the phase from the actions in case a
        // build answers in-place: an Answer ⇒ RINGING (incoming); only a negative and no Answer ⇒ ACTIVE (ongoing);
        // no actions ⇒ treat as incoming. All of these are live calls (rung / guarded); missed call / voicemail
        // is neither.
        val callType = when {
            record.source.categoryId == Ancs.CAT_ACTIVE_CALL -> CallType.ONGOING
            record.source.categoryId != Ancs.CAT_INCOMING_CALL -> null
            hasAnswer -> CallType.INCOMING
            hasCallNegative -> CallType.ONGOING
            else -> CallType.INCOMING
        }
        // A ringing call with BOTH Answer + Decline renders as CallStyle.forIncomingCall (green Answer / RED
        // Decline); an active call with a Hang-up renders as CallStyle.forOngoingCall (a single RED Hang up). Either
        // way the buttons relay the ANCS positive/negative actions (the render branch builds them from the call
        // indices below). Without the needed action(s) it stays a plain notification — still rung/guarded via callType.
        val isIncomingCallStyle = callType == CallType.INCOMING && hasAnswer && hasCallNegative
        val isOngoingCallStyle = callType == CallType.ONGOING && hasCallNegative
        val isCallStyle = isIncomingCallStyle || isOngoingCallStyle
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
            style = when {
                isCallStyle -> NotificationStyle.CALL
                isLong -> NotificationStyle.BIG_TEXT
                else -> NotificationStyle.DEFAULT
            },
            category = mapCategory(record.source.categoryId),
            // A ringing call is INCOMING (rings); an answered/active call is ONGOING (doesn't ring, shows Hang up);
            // a missed call / voicemail carries NO callType and mirrors as an ordinary notification that never rings.
            // (The receiver's non-CallStyle "any call-category post rings" fallback is Android-only — see
            // RemoteNotificationPoster.isRingingCall — since an ANCS capture is never a foreground service.)
            callType = callType,
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
            actions = actions,
            // The CallStyle buttons ARE the ANCS actions: Answer/Decline = positive/negative for a ringing call,
            // Hang up = negative for an active call; the caller's name (the ANCS title) titles the call card.
            callerName = if (isCallStyle) record.title else null,
            callAnswerIndex = if (isIncomingCallStyle) Ancs.ACTION_POSITIVE else null,
            callDeclineIndex = if (isIncomingCallStyle) Ancs.ACTION_NEGATIVE else null,
            callHangUpIndex = if (isOngoingCallStyle) Ancs.ACTION_NEGATIVE else null,
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
     * The ONE exception is an active call — a call category (incoming or [Ancs.CAT_ACTIVE_CALL]) with a negative
     * but no positive ("Answer" is gone once answered): there the lone negative is "Hang up", the only call
     * control, so it's kept to drive the ongoing-call Hang up button (its dismissal is guarded from firing it —
     * see IosBridgeManager).
     */
    private fun mapActions(record: AncsRecord): List<NotificationAction> {
        val positive = record.positiveActionLabel?.takeIf { it.isNotBlank() }
        val negative = record.negativeActionLabel?.takeIf { it.isNotBlank() }
        val isActiveCall =
            Ancs.isCallCategory(record.source.categoryId) && positive == null && negative != null
        if (positive == null && !isActiveCall) return emptyList()
        return buildList {
            if (positive != null) add(NotificationAction(index = Ancs.ACTION_POSITIVE, title = positive))
            if (negative != null) add(NotificationAction(index = Ancs.ACTION_NEGATIVE, title = negative))
        }
    }

    private fun mapCategory(categoryId: Int): MirrorCategory = when (categoryId) {
        Ancs.CAT_INCOMING_CALL, Ancs.CAT_MISSED_CALL, Ancs.CAT_VOICEMAIL, Ancs.CAT_ACTIVE_CALL -> MirrorCategory.CALL
        Ancs.CAT_SOCIAL -> MirrorCategory.MESSAGE
        Ancs.CAT_SCHEDULE -> MirrorCategory.EVENT
        Ancs.CAT_EMAIL -> MirrorCategory.EMAIL
        Ancs.CAT_LOCATION -> MirrorCategory.NAVIGATION
        else -> MirrorCategory.NONE
    }
}
