package net.extrawdw.apps.notisync.ancs

import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
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
            style = if (isLong) NotifStyle.BIG_TEXT else NotifStyle.DEFAULT,
            category = mapCategory(record.source.categoryId),
            importance = mapImportance(record.source),
            postTime = Ancs.parseDate(record.date) ?: now,
            originPlatform = OriginPlatform.IOS_ANCS,
            originDeviceName = iphoneName,
            iosBundleId = record.bundleId,
            originDeviceId = iphoneId,
        )
    }

    private fun mapCategory(categoryId: Int): MirrorCategory = when (categoryId) {
        Ancs.CAT_INCOMING_CALL, Ancs.CAT_MISSED_CALL, Ancs.CAT_VOICEMAIL -> MirrorCategory.CALL
        Ancs.CAT_SOCIAL -> MirrorCategory.MESSAGE
        Ancs.CAT_SCHEDULE -> MirrorCategory.EVENT
        Ancs.CAT_EMAIL -> MirrorCategory.EMAIL
        Ancs.CAT_LOCATION -> MirrorCategory.NAVIGATION
        else -> MirrorCategory.NONE
    }

    private fun mapImportance(p: Ancs.SourcePacket): MirrorImportance = when {
        p.isImportant -> MirrorImportance.HIGH
        p.isSilent -> MirrorImportance.LOW
        else -> MirrorImportance.HIGH
    }
}
