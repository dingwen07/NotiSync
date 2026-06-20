package net.extrawdw.apps.notisync.channel

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType

/**
 * A message handed to a registered handler ONLY after the channel's full prologue passed: not a
 * duplicate, sender is a known peer, signature verified, and the body HPKE-opened. The handler can
 * therefore trust [senderId] is authenticated and decode [body] directly.
 *
 * [senderOwnDevice] is surfaced so a handler can apply its own/other authorization policy (the
 * channel never does). [body] is the decrypted CBOR payload; the channel stays payload-agnostic.
 */
class InboundMessage(
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
    val typ: MessageType,
    val body: ByteArray,
)
