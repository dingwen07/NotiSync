package net.extrawdw.notisync.peer.channel

import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType

/**
 * A message handed to a registered handler ONLY after the channel's full prologue passed: not a
 * duplicate, sender is a known peer, signature verified, and the body HPKE-opened. The handler can
 * therefore trust [senderId] is authenticated and decode [body] directly.
 *
 * [senderOwnDevice] is surfaced so a handler can apply its own/other authorization policy (the
 * channel never does). [body] is the decrypted CBOR payload; the channel stays payload-agnostic.
 * [deliveryMode] is local diagnostic metadata, not sender-authenticated payload content.
 *
 * [signerEpoch] is which key-KIND signed this envelope — 0 = the sender's identity (root) key, ≥1 = its
 * operational key of that epoch — already verified by the channel. The body-agnostic channel only
 * REPORTS it; a handler enforces the per-message signer policy (§2.3) above the channel — chiefly that a
 * `TrustTable` must be identity-signed (0), so a leaked operational key cannot drive roster gossip (§8 #12).
 *
 * [messageId] is the envelope's relay id — surfaced so a handler can later relay-ack the exact item
 * that delivered this message (e.g. when its mirror is dismissed). It is transport metadata, not
 * sender-authenticated content.
 *
 * [createdAt] is the signed envelope creation time. A handler may compare it with a body timestamp for
 * expiry/replay policy without trusting transport arrival time.
 */
class InboundMessage(
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
    val typ: MessageType,
    val body: ByteArray,
    val signerEpoch: Int = 0,
    val messageId: String = "",
    val deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN,
    val createdAt: Long = 0,
)
