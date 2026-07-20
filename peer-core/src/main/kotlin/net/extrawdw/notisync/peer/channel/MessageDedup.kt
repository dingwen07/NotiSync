package net.extrawdw.notisync.peer.channel

import net.extrawdw.notisync.peer.ports.MessageDedupRepository

/**
 * Cross-session idempotency for [SecureChannel.deliver]. The channel's own dedup is an in-memory LRU
 * that is wiped on every process restart; a persisted implementation of this port lets a message
 * survived-once stay survived across a restart (notably an app update / package replace), so the
 * broker's at-least-once redelivery of a not-yet-acked relay item never re-posts a notification the
 * user already saw or dismissed.
 *
 * Safety contract: [record] is called ONLY after the message was durably handled (its handler ran),
 * never before — so a crash between handling and recording costs at most a redelivery (a duplicate),
 * never a suppressed-but-never-shown notification. [seen] must be cheap and synchronous; it is called
 * inline on the FCM/WebSocket/worker thread inside [SecureChannel.deliver].
 */
interface MessageDedup : MessageDedupRepository {
    /** True iff [messageId] was already durably handled (this session is irrelevant — persisted state). */
    override fun seen(messageId: String): Boolean

    /** Record that [messageId] has now been durably handled. Called after the handler returns. */
    override fun record(messageId: String)
}

/**
 * A handler uses this only when its durable commit failed transiently. Unlike malformed/poison
 * payload failures, this outcome must remain unacknowledged so relay redelivery can try again.
 */
class RetryableDeliveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Outcome of [SecureChannel.deliver], so a caller can decide whether the message is safe to relay-ack. */
enum class DeliveryOutcome {
    /** Prologue passed and the handler ran — durably handled this call. Safe to ack. */
    HANDLED,

    /** A message id we have already DURABLY handled (this session or a previous one — [recent] or
     *  [MessageDedup.seen]). Safe to ack. */
    DUPLICATE,

    /** A concurrent delivery of an id another thread is still handling, not yet durably recorded. NOT
     *  safe to ack — that thread's handler may yet fail, so leaving the item queued lets it redeliver. */
    IN_FLIGHT,

    /** Dropped before handling: unknown sender, bad signature, decrypt failure, or no handler. NOT
     *  safe to ack — leaving it queued lets a later delivery (once trust/keys converge) still land. */
    DROPPED,
}

val DeliveryOutcome.safeToAck: Boolean
    get() = this == DeliveryOutcome.HANDLED || this == DeliveryOutcome.DUPLICATE
