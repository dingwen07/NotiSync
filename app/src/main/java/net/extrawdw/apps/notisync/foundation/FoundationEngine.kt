package net.extrawdw.apps.notisync.foundation

import kotlinx.coroutines.CoroutineScope
import net.extrawdw.apps.notisync.data.TrustPrompt
import net.extrawdw.apps.notisync.data.TrustState
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.SignedBlob

/** Android presentation/persistence adapter around the platform-neutral foundation engine. */
class FoundationEngine(
    channel: SecureChannel,
    trust: TrustState,
    scope: CoroutineScope,
    onTrustPrompt: (subject: ClientId, prompt: TrustPrompt, byName: String) -> Unit,
    onAsset: (InboundMessage, DataSync) -> Unit,
    onFilter: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onNotificationSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onRunSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onScreenMirrorSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onOpenPgpSignSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
    onSshAgentSync: (InboundMessage, DataSync) -> Unit = { _, _ -> },
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
    onScreenMirrorSync = onScreenMirrorSync,
    onOpenPgpSignSync = onOpenPgpSignSync,
    onSshAgentSync = onSshAgentSync,
    selfKeyEpoch = selfKeyEpoch,
    fetchKeyEpoch = fetchKeyEpoch,
    now = now,
)
