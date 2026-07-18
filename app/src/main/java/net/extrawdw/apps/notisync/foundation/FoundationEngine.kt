package net.extrawdw.apps.notisync.foundation

import kotlinx.coroutines.CoroutineScope
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.ports.FoundationEventSink
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.SignedBlob

/** Android presentation/persistence adapter around the platform-neutral foundation engine. */
class FoundationEngine(
    channel: SecureChannel,
    trust: TrustState,
    activityLog: ActivityLog,
    scope: CoroutineScope,
    onTrustPrompt: (subject: ClientId, prompt: TrustPrompt, byName: String) -> Unit,
    onAsset: (InboundMessage, DataSync) -> Unit,
    onFilter: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onNotificationSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onRunSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    activityText: ActivityText,
    selfKeyEpoch: () -> SignedBlob? = { null },
    fetchKeyEpoch: suspend (ClientId, Int?) -> SignedBlob? = { _, _ -> null },
    now: () -> Long = { System.currentTimeMillis() },
) : net.extrawdw.notisync.peer.foundation.FoundationEngine(
    channel = channel,
    trust = trust,
    scope = scope,
    onTrustPrompt = onTrustPrompt,
    onAsset = onAsset,
    onFilter = onFilter,
    onNotificationSync = onNotificationSync,
    onRunSync = onRunSync,
    eventSink = AndroidFoundationEventSink(activityLog, activityText, now),
    selfKeyEpoch = selfKeyEpoch,
    fetchKeyEpoch = fetchKeyEpoch,
    now = now,
)

private class AndroidFoundationEventSink(
    private val activityLog: ActivityLog,
    private val text: ActivityText,
    private val now: () -> Long,
) : FoundationEventSink {
    override fun profileBroadcast(recipientCount: Int) {
        activityLog.add(
            ActivityEvent.Kind.SENT,
            text.deviceNameTitle(),
            text.deviceNameUpdated(recipientCount),
            now(),
        )
    }

    override fun profileRenamed(newName: String, previousName: String, deliveryMode: DeliveryMode?) {
        activityLog.add(
            ActivityEvent.Kind.PAIRED,
            newName,
            text.renamedWas(previousName),
            now(),
            deliveryMode,
        )
    }

    override fun trustChanged(
        subject: ClientId,
        prompt: TrustPrompt,
        introducedBy: String,
        deliveryMode: DeliveryMode?,
        automaticallyApplied: Boolean,
    ) {
        activityLog.add(
            ActivityEvent.Kind.PAIRED,
            subject.shortForm(),
            text.trustUpdateFrom(introducedBy, prompt),
            now(),
            deliveryMode,
        )
    }
}
