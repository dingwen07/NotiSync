package net.extrawdw.apps.notisync.data

import android.content.Context
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.SshAgentSyncKind

/** Message receipts, not claims that a request was authorized or completed. Retains no payloads. */
class MessageActivity(
    private val context: Context,
    private val log: ActivityLog,
    private val deviceName: (ClientId) -> String,
) {
    fun sent(type: MessageType, body: ByteArray, recipientCount: Int) {
        if (type != MessageType.DATA_SYNC || recipientCount <= 0) return
        val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(body) }.getOrNull() ?: return
        val labels = messageActivityLabels(sync) ?: return
        log.add(
            ActivityEvent.Kind.SENT,
            context.getString(labels.first),
            context.resources.getQuantityString(
                R.plurals.activity_message_sent, recipientCount,
                context.getString(labels.second), recipientCount,
            ),
            System.currentTimeMillis(),
        )
    }

    fun received(message: InboundMessage, sync: DataSync) {
        val labels = messageActivityLabels(sync) ?: return
        log.add(
            ActivityEvent.Kind.RECEIVED,
            context.getString(labels.first),
            context.getString(
                R.string.activity_message_received,
                context.getString(labels.second), deviceName(message.senderId),
            ),
            System.currentTimeMillis(),
            message.deliveryMode.takeUnless { it == DeliveryMode.UNKNOWN },
        )
    }
}

/** Only operation labels may reach the feed, never request content or peer-supplied diagnostics. */
internal fun messageActivityLabels(sync: DataSync): Pair<Int, Int>? = when (sync.kind) {
    DataSyncKind.SSH_AGENT -> sync.sshAgent?.let {
        R.string.activity_message_ssh to when (it.kind) {
            SshAgentSyncKind.KEYS_REQUEST -> R.string.activity_message_keys_request
            SshAgentSyncKind.KEYS_SNAPSHOT -> R.string.activity_message_keys_snapshot
            SshAgentSyncKind.SIGN_REQUEST -> R.string.activity_message_sign_request
            SshAgentSyncKind.SIGN_RESULT -> R.string.activity_message_sign_result
            SshAgentSyncKind.SIGN_REQUEST_CANCELLED -> R.string.activity_message_cancel
            SshAgentSyncKind.IMPORT_REQUEST -> R.string.activity_message_import_request
            SshAgentSyncKind.IMPORT_RESULT -> R.string.activity_message_import_result
            SshAgentSyncKind.FORGET_AUTHORIZATION -> R.string.activity_message_forget_request
            SshAgentSyncKind.FORGET_RESULT -> R.string.activity_message_forget_result
        }
    }
    DataSyncKind.OPENPGP_SIGN -> sync.openPgpSign?.let {
        R.string.activity_message_openpgp to when (it.action) {
            OpenPgpSignAction.REQUEST -> R.string.activity_message_sign_request
            OpenPgpSignAction.RESULT -> R.string.activity_message_sign_result
            OpenPgpSignAction.REJECT -> R.string.activity_message_reject
            OpenPgpSignAction.CANCEL -> R.string.activity_message_cancel
        }
    }
    DataSyncKind.SCREEN_MIRRORING -> sync.screenMirror?.let {
        R.string.activity_message_screen to when (it.action) {
            ScreenMirrorAction.REQUEST -> R.string.activity_message_screen_request
            ScreenMirrorAction.STATUS -> R.string.activity_message_status
            ScreenMirrorAction.CANCEL -> R.string.activity_message_cancel
            ScreenMirrorAction.END -> R.string.activity_message_end
        }
    }
    // These already have application-level activity entries, or are background maintenance traffic.
    else -> null
}
