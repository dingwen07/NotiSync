package net.extrawdw.apps.notisync.channel

import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which key signs an outbound envelope. The caller chooses EXPLICITLY per §2.3 — the body-agnostic
 * channel never infers it from the message type:
 *  - [OPERATIONAL]: the rotatable TEE key (`signerEpoch` ≥1) — the hot path (notifications, dismissals,
 *    asset sync, profile, standalone card pushes).
 *  - [IDENTITY]: the cold StrongBox root (`signerEpoch` = 0) — for a `TrustTable` (a roster assertion must
 *    bind to the immutable root and verify without epoch convergence) and any identity-anchored control body.
 */
enum class SignerSelection { OPERATIONAL, IDENTITY }

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
    /** The cold StrongBox identity root — signs [SignerSelection.IDENTITY] envelopes (signerEpoch 0). */
    private val signer: IdentitySigner,
    /**
     * Provider for the CURRENT operational signer (signerEpoch ≥1) — the hot path. A provider (not a fixed
     * instance) so a rotation can swap the active epoch under the channel without reconstructing it.
     */
    private val operationalSigner: () -> OperationalSigner,
    /**
     * The device's private HPKE keyset for a given epoch, or null if not retained. Backed by the
     * epoch-indexed ring so an envelope sealed to a now-rotated key still opens during the overlap window
     * ([EpochHpkeKeyManager]); selected on open by the recipient epoch the sender named.
     */
    private val myHpkePrivate: (epoch: Int) -> ByteArray?,
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
     * Notified when an envelope is dropped because the directory could not resolve the sender's key for the
     * claimed epoch — i.e. we hold no usable key-epoch for that sender (none, or the specific epoch it signed
     * with). The handler above the channel reacts by fetching the sender's current key-epoch (and falling
     * back to a roster broadcast), so an asymmetric or post-rotation gap self-heals. Default is a no-op.
     */
    private val onUnresolvedSender: (ClientId) -> Unit = { _ -> },
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
     * Seal [body] (already CBOR) to [scope] and send it at [urgency], signed with [signWith]. Returns the
     * recipient count, so callers keep their empty-audience early-returns and "delivered to N device(s)" rows.
     */
    suspend fun send(
        typ: MessageType,
        body: ByteArray,
        scope: Recipients,
        urgency: Urgency,
        signWith: SignerSelection = SignerSelection.OPERATIONAL,
    ): Int = sendAll(typ, listOf(body), scope, urgency, signWith)

    /**
     * Seal each of [bodies] to a SINGLE audience resolved ONCE for [scope] and send them in order at
     * [urgency], signed with [signWith]; returns the recipient count. Resolving the audience once keeps a
     * multi-message broadcast (e.g. a trust table plus its cards) atomic against a roster change that lands
     * mid-broadcast — every message goes to the same device set, as the original did.
     */
    suspend fun sendAll(
        typ: MessageType,
        bodies: List<ByteArray>,
        scope: Recipients,
        urgency: Urgency,
        signWith: SignerSelection = SignerSelection.OPERATIONAL,
    ): Int {
        val recipients = directory.recipients(scope)
        if (recipients.isEmpty()) return 0
        // Resolve the operational signer once per broadcast (stable across the batch); the identity root is
        // a fixed field. EnvelopeCrypto picks the overload by signer type, stamping signerEpoch accordingly.
        val op = if (signWith == SignerSelection.OPERATIONAL) operationalSigner() else null
        for (body in bodies) {
            val messageId = UUID.randomUUID().toString()
            val seqN = seq.incrementAndGet()
            val createdAt = now()
            val envelope = if (op != null) {
                EnvelopeCrypto.seal(op, typ, body, recipients, messageId, seqN, createdAt)
            } else {
                EnvelopeCrypto.seal(signer, typ, body, recipients, messageId, seqN, createdAt)
            }
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
            // Resolve the verify key for the CLAIMED signerEpoch. For an operational epoch this is the
            // anti-rollback gate: a retired/floored/non-ENVELOPE_SIGN epoch resolves to null and we drop
            // BEFORE any signature check, so a replayed stale-epoch envelope can never open (§8 #1).
            val sender = directory.resolveSender(envelope.signerId, envelope.signerEpoch)
            if (sender == null) {
                log.warn("dropping envelope from unresolvable sender ${envelope.signerId.shortForm()} epoch ${envelope.signerEpoch}")
                // We hold no usable key-epoch for this sender (none, or not the epoch it signed with) — ask the
                // handler to fetch it so the relayed copy can be re-delivered + verified once we converge.
                onUnresolvedSender(envelope.signerId)
                return DeliveryOutcome.DROPPED
            }
            if (!EnvelopeCrypto.verify(envelope, sender.verifySpki)) {
                log.warn("signature verification failed for $id")
                onBadSignature(envelope.signerId, now(), deliveryMode)
                return DeliveryOutcome.DROPPED
            }
            // Open with the private HPKE keyset for the epoch the sender sealed to us (selected from our
            // retained ring). A missing keyset (we no longer retain that epoch) is an undecryptable DROPPED —
            // deliberately left unacked (matching a decrypt failure) so the relay redelivers once we re-converge;
            // HPKE retention ≥ relay TTL (RotationManager) makes this rare. A faster pull-on-demand path
            // (EnvelopeResendRequest, §8 #10) is a Phase-6 optimization, not required for correctness.
            val myEpoch = envelope.recipients.firstOrNull { it.recipientId == signer.clientId }?.recipientEpoch
            val myKeyset = myEpoch?.let { myHpkePrivate(it) }
            if (myKeyset == null) {
                log.warn("no retained HPKE keyset for recipient epoch $myEpoch to open $id — dropping unacked for relay redelivery")
                return DeliveryOutcome.DROPPED
            }
            val body = runCatching {
                EnvelopeCrypto.open(envelope, signer.clientId, myKeyset)
            }.getOrElse {
                log.warn("decrypt failed for $id: ${it.message}")
                return DeliveryOutcome.DROPPED
            }
            val handler = handlers[envelope.typ] ?: return DeliveryOutcome.DROPPED
            // Report which key-kind signed (0 = identity, ≥1 = operational) so the handler can enforce the
            // per-message signer policy (§2.3) — the channel verified the signature but is body-agnostic.
            handler(InboundMessage(envelope.signerId, sender.ownDevice, envelope.typ, body, envelope.signerEpoch, id, deliveryMode))
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
