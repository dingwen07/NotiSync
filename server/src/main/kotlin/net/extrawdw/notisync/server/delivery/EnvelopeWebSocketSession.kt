package net.extrawdw.notisync.server.delivery

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage

/** Called after hub registration. Session teardown cancels replay, including a blocked outbound send. */
internal suspend fun DefaultWebSocketServerSession.runEnvelopeSession(
    replayPending: Boolean,
    flushPending: suspend () -> Unit,
    acknowledge: suspend (String) -> Unit,
) = coroutineScope {
    if (!replayPending) send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.READY))))
    val replay = if (replayPending) launch { flushPending() } else null
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = runCatching { ProtocolCodec.decodeFromJson<WsMessage>(frame.readText()) }.getOrNull()
            when (message?.kind) {
                WsKind.ACK -> message.messageId?.let { acknowledge(it) }
                WsKind.PING -> send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.PONG))))
            }
        }
    } finally {
        replay?.cancelAndJoin()
    }
}
