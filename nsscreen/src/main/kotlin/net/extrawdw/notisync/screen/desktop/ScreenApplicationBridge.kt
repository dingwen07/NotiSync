package net.extrawdw.notisync.screen.desktop

import java.util.Base64
import kotlin.math.abs
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorStatus
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import net.extrawdw.notisync.protocol.Urgency

internal class ScreenApplicationBridge(
    private val api: DaemonLocalApi,
) {
    fun register(): ClientId {
        api.putApplication(
            APPLICATION_ID,
            ApplicationRegistrationRequest(displayName = DISPLAY_NAME, version = "1"),
        )
        return api.status().clientId?.takeIf(String::isNotBlank)?.let(::ClientId)
            ?: error("notisyncd has no active client identity")
    }

    fun openSessionStream(sessionId: String): SessionReceiveStream {
        val request = ReceiveRequest(
            applicationId = APPLICATION_ID,
            messageTypes = listOf(MessageType.DATA_SYNC),
            filters = listOf(
                MessageFilter(
                    MessageType.DATA_SYNC,
                    "/kind",
                    listOf(JsonPrimitive(DataSyncKind.SCREEN_MIRRORING.name)),
                ),
                MessageFilter(
                    MessageType.DATA_SYNC,
                    "/screenMirror/sessionId",
                    listOf(JsonPrimitive(sessionId)),
                ),
            ),
        )
        return SessionReceiveStream(api, request, api.openReceive(request))
    }

    fun sendRequest(request: ScreenMirrorSync) {
        require(request.action == ScreenMirrorAction.REQUEST)
        val sync = DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = request)
        api.send(
            SendRequest(
                applicationId = APPLICATION_ID,
                messageType = MessageType.DATA_SYNC,
                body = encode(sync),
                scope = Recipients.OnlyCapable(request.sourcePeerId, request.requiredSourceCapabilities()),
                urgency = Urgency.HIGH,
                signWith = SignerSelection.OPERATIONAL,
                submissionId = "screen/${request.sessionId}/request",
            ),
        )
    }

    fun sendCancel(request: ScreenMirrorSync, detail: String? = null) {
        sendTerminal(request, ScreenMirrorAction.CANCEL, null, detail)
    }

    fun sendEnd(request: ScreenMirrorSync, detail: String? = null) {
        sendTerminal(request, ScreenMirrorAction.END, ScreenMirrorStatus.ENDED, detail)
    }

    private fun sendTerminal(
        request: ScreenMirrorSync,
        action: ScreenMirrorAction,
        status: ScreenMirrorStatus?,
        detail: String?,
    ) {
        require(action == ScreenMirrorAction.CANCEL || action == ScreenMirrorAction.END)
        val terminal = ScreenMirrorSync(
            action = action,
            sessionId = request.sessionId,
            requesterPeerId = request.requesterPeerId,
            sourcePeerId = request.sourcePeerId,
            issuedAt = System.currentTimeMillis(),
            status = status,
            detail = detail?.take(512),
        )
        api.send(
            SendRequest(
                applicationId = APPLICATION_ID,
                messageType = MessageType.DATA_SYNC,
                body = encode(DataSync(DataSyncKind.SCREEN_MIRRORING, screenMirror = terminal)),
                scope = Recipients.Only(request.sourcePeerId),
                urgency = Urgency.NORMAL,
                signWith = SignerSelection.OPERATIONAL,
                submissionId = "screen/${request.sessionId}/${action.name.lowercase()}",
            ),
        )
    }

    private fun encode(value: DataSync): String = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(value))

    companion object {
        const val APPLICATION_ID: String = "nsscreen"
        private const val DISPLAY_NAME = "NotiSync Screen"
    }
}

internal class SessionReceiveStream(
    private val api: DaemonLocalApi,
    private val request: ReceiveRequest,
    private val stream: ReceiveStream,
) : AutoCloseable {
    fun next(sourcePeerId: ClientId): ScreenMirrorSync? {
        while (true) {
            val record = stream.next() ?: return null
            if (record.recordType == ReceiveRecordType.HEARTBEAT) continue
            val envelopeId = record.envelopeId ?: continue
            try {
                val decoded = decode(record, sourcePeerId)
                api.ack(ScreenApplicationBridge.APPLICATION_ID, envelopeId)
                if (decoded != null) return decoded
            } catch (_: Exception) {
                // Malformed or unrelated application records are acknowledged and ignored.
                api.ack(ScreenApplicationBridge.APPLICATION_ID, envelopeId)
            }
        }
    }

    private fun decode(record: ReceiveRecord, sourcePeerId: ClientId): ScreenMirrorSync? {
        if (record.applicationId != ScreenApplicationBridge.APPLICATION_ID ||
            record.messageType != MessageType.DATA_SYNC ||
            record.senderOwnDevice != true ||
            record.senderClientId != sourcePeerId.value
        ) return null
        val bytes = Base64.getDecoder().decode(record.body ?: return null)
        val sync = ProtocolCodec.decodeFromCbor<DataSync>(bytes)
        val screen = sync.takeIf { it.kind == DataSyncKind.SCREEN_MIRRORING }?.screenMirror ?: return null
        val envelopeCreatedAt = record.envelopeCreatedAtEpochMillis ?: return null
        if (
            screen.action == ScreenMirrorAction.REQUEST || screen.protocolVersion != 1 ||
            screen.issuedAt <= 0 || envelopeCreatedAt <= 0 ||
            abs(screen.issuedAt - envelopeCreatedAt) > MAX_SIGNED_TIMESTAMP_DELTA_MS
        ) return null
        return screen
    }

    override fun close() {
        runCatching { stream.close() }
        runCatching { api.unregisterReceive(request) }
    }

    private companion object {
        const val MAX_SIGNED_TIMESTAMP_DELTA_MS = 2L * 60 * 1000
    }
}
