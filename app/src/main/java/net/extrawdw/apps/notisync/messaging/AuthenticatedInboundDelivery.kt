package net.extrawdw.apps.notisync.messaging

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType

/**
 * Stable application-local token for the path by which a relay item reached this process.
 *
 * This is local diagnostic metadata, not sender-authenticated envelope content. Explicit tokens keep the
 * messaging boundary independent of peer-core transport types and give a later repository adapter a stable value
 * to map without persisting Kotlin enum names.
 */
internal enum class InboundDeliveryMode(val token: String) {
    UNKNOWN("unknown"),
    WEBSOCKET("websocket"),
    FCM_INLINE("fcm_inline"),
    FCM_RELAY_FETCH("fcm_relay_fetch"),
    RELAY_DRAIN("relay_drain");

    companion object {
        fun decode(token: String): InboundDeliveryMode =
            requireNotNull(entries.singleOrNull { it.token == token }) {
                "Unknown inbound delivery mode token"
            }
    }
}

/**
 * One decrypted delivery after peer-core authenticated the signed envelope and opened its body.
 *
 * The signed fields are copied from peer-core's authenticated result; [acceptedAt], [deliveryMode], and
 * [forceSilent] are receiver-local relay/reconciliation facts. [recipientEpoch] is authenticated as part of the
 * envelope recipient projection and is retained for the operational receive-generation/replay-fence decision.
 * Authentication, relay staging, persistence, ACK policy, and feature handling deliberately remain outside this
 * value.
 *
 * [encodedBody] is copied on entry and every read so neither a transport buffer nor a downstream consumer can
 * mutate the exact authenticated bytes retained by this delivery.
 */
internal class AuthenticatedInboundDelivery(
    val messageId: String,
    val messageType: MessageType,
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
    val signerEpoch: Int,
    val signedSequence: Long,
    val signedCreatedAt: Long,
    val recipientEpoch: Int,
    encodedBody: ByteArray,
    val acceptedAt: Long,
    val deliveryMode: InboundDeliveryMode,
    val forceSilent: Boolean,
) {
    init {
        // acceptedAt is trusted local staging metadata, not a peer-controlled protocol outcome. Reject an invalid
        // adapter call immediately instead of turning it into an ACK-eligible terminal message decision.
        require(acceptedAt > 0) { "acceptedAt must be positive" }
    }

    private val encodedBodySnapshot = encodedBody.copyOf()

    val encodedBody: ByteArray
        get() = encodedBodySnapshot.copyOf()
}
