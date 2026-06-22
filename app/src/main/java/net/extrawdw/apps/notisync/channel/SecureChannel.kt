package net.extrawdw.apps.notisync.channel

import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A generic, feature-agnostic end-to-end secure group-messaging substrate over a [Transport].
 *
 * Outbound: [send] seals an already-CBOR body to an audience (resolved via the [PeerDirectory]),
 * signs it, and hands it to the transport. Inbound: [deliver] runs the verified prologue — dedup →
 * known-sender lookup → signature verify → HPKE-open — then routes on [MessageType] (an OPAQUE
 * routing tag) to the handler registered with [onMessage]. The channel never decodes a body, never
 * inspects a sub-kind, and never applies an authorization (own/other) drop: those are caller concerns.
 *
 * Both inbound feeds — the live [Transport.incoming] stream and out-of-band [deliver] calls from the
 * FCM service — converge through ONE shared dedup ([recent] in memory, [dedup] across restarts), so
 * they are idempotent against each other and across an app restart. The single [seq] counter lives
 * here too: one channel, one per-sender sequence space.
 */
class SecureChannel(
    private val signer: IdentitySigner,
    private val myHpkePrivateKeyset: ByteArray,
    private val transport: Transport,
    private val directory: PeerDirectory,
    private val log: ChannelLogger,
    /**
     * Notified when an envelope is dropped because its signature failed — the one rejection that
     * surfaces a user-visible row today. The app maps it to an activity entry (the [Long] is the
     * channel's [now] so that row shares one clock with every other row); default is a no-op so the
     * channel stays free of any activity/UI coupling.
     */
    private val onBadSignature: (ClientId, Long, DeliveryMode) -> Unit = { _, _, _ -> },
    /**
     * Persisted cross-session idempotency. Null keeps the channel a pure in-memory substrate (tests,
     * and any caller that doesn't need restart-survival). When supplied, a message handled in a prior
     * process is recognised as a duplicate after a restart — the fix for redelivered relay items
     * re-posting notifications after an app update.
     */
    private val dedup: MessageDedup? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val seq = AtomicLong(now())

    /**
     * Bounded LRU of message ids handled THIS session — a cheap hot-path cache so a redelivery doesn't
     * hit [dedup] (disk) every time. The durable dedup is [dedup]; this just shortcuts it.
     */
    private val recent: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 512
        })
    )

    /**
     * Message ids currently being handled. A test-and-set guard that collapses a *concurrent* double
     * delivery (FCM + WebSocket racing on the same id) into a single handler run, without marking an
     * id "handled" before its handler has actually run (which [recent]/[dedup] only do on success).
     */
    private val inFlight: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** One handler per [MessageType] — mirrors the old hard `when (typ)` branch, one owner each. */
    private val handlers = ConcurrentHashMap<MessageType, (InboundMessage) -> Unit>()

    /**
     * Register the sole handler for a [MessageType]. Handlers are NON-suspend and run inline in
     * [deliver]; a handler that needs to do async work launches its own coroutine (as the old
     * `when (typ)` branches did). Register every handler during graph construction, BEFORE the first
     * [deliver] / live collection — an early wake to an unregistered type is silently dropped.
     */
    fun onMessage(typ: MessageType, handler: (InboundMessage) -> Unit) {
        handlers[typ] = handler
    }

    /**
     * Seal [body] (already CBOR) to [scope] and send it at [urgency]. Returns the recipient count, so
     * callers keep their empty-audience early-returns and their "delivered to N device(s)" rows.
     */
    suspend fun send(typ: MessageType, body: ByteArray, scope: Recipients, urgency: Urgency): Int =
        sendAll(typ, listOf(body), scope, urgency)

    /**
     * Seal each of [bodies] to a SINGLE audience resolved ONCE for [scope] and send them in order at
     * [urgency]; returns the recipient count. Resolving the audience once keeps a multi-message
     * broadcast (e.g. a trust table plus its cards) atomic against a roster change that lands
     * mid-broadcast — every message goes to the same device set, as the original did.
     */
    suspend fun sendAll(typ: MessageType, bodies: List<ByteArray>, scope: Recipients, urgency: Urgency): Int {
        val recipients = directory.recipients(scope)
        if (recipients.isEmpty()) return 0
        for (body in bodies) {
            val envelope = EnvelopeCrypto.seal(
                signer = signer,
                typ = typ,
                bodyPlaintext = body,
                recipients = recipients,
                messageId = UUID.randomUUID().toString(),
                seq = seq.incrementAndGet(),
                createdAt = now(),
            )
            transport.send(envelope, urgency)
        }
        return recipients.size
    }

    /**
     * Handle one inbound envelope (from the live stream or an FCM wake). NON-suspend by design: it
     * runs the prologue and dispatches inline so the FCM service thread's synchronous-completion
     * contract is preserved (a handler launches its own async tail if it needs one).
     *
     * Idempotent across processes: an id seen [recent]ly OR durably in [dedup] is a [DeliveryOutcome.DUPLICATE]
     * before any crypto work (dedup-first stays cheap-first). An id is marked handled ONLY after its
     * handler returns — so a crash mid-handle redelivers (a duplicate) rather than suppressing a
     * never-shown notification. The returned outcome lets the caller decide whether to relay-ack:
     * HANDLED/DUPLICATE are safe to ack; IN_FLIGHT (a racing thread hasn't committed yet) and DROPPED
     * (may yet deliver once trust/keys converge) are not.
     */
    fun deliver(envelope: Envelope, deliveryMode: DeliveryMode = DeliveryMode.UNKNOWN): DeliveryOutcome {
        val id = envelope.messageId
        if (id in recent) return DeliveryOutcome.DUPLICATE
        if (dedup?.seen(id) == true) {
            recent.add(id)
            return DeliveryOutcome.DUPLICATE
        }
        // Collapse a concurrent double-delivery: only the first thread for this id handles it; the
        // other backs off as IN_FLIGHT — not yet durably recorded, so its caller must NOT ack it (the
        // winning handler may still fail). Released in `finally` so a DROPPED id can be retried later.
        if (!inFlight.add(id)) return DeliveryOutcome.IN_FLIGHT
        try {
            val keys = directory.lookup(envelope.signerId)
            if (keys == null) {
                log.warn("dropping envelope from unknown sender ${envelope.signerId.shortForm()}")
                return DeliveryOutcome.DROPPED
            }
            if (!EnvelopeCrypto.verify(envelope, keys.identitySpki)) {
                log.warn("signature verification failed for $id")
                onBadSignature(envelope.signerId, now(), deliveryMode)
                return DeliveryOutcome.DROPPED
            }
            val body = runCatching {
                EnvelopeCrypto.open(envelope, signer.clientId, myHpkePrivateKeyset)
            }.getOrElse {
                log.warn("decrypt failed for $id: ${it.message}")
                return DeliveryOutcome.DROPPED
            }
            val handler = handlers[envelope.typ] ?: return DeliveryOutcome.DROPPED
            handler(InboundMessage(envelope.signerId, keys.ownDevice, envelope.typ, body, id, deliveryMode))
            // Mark handled only now (after the handler ran): in-memory for the hot path, then durably.
            recent.add(id)
            dedup?.record(id)
            return DeliveryOutcome.HANDLED
        } finally {
            inFlight.remove(id)
        }
    }

    /** This device's id — exposed so callers can recognise self without reaching for the signer. */
    val clientId: ClientId get() = signer.clientId
}
