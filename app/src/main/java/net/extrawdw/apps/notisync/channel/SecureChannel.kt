package net.extrawdw.apps.notisync.channel

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
 * FCM service — converge through ONE shared [seen] LRU, so they are idempotent against each other.
 * The single [seq] counter lives here too: one channel, one per-sender sequence space.
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
    private val onBadSignature: (ClientId, Long) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val seq = AtomicLong(now())

    /** Bounded LRU of recently-seen message ids for idempotent delivery (dedupes FCM + WebSocket). */
    private val seen: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 512
        })
    )

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
     * contract is preserved (a handler launches its own async tail if it needs one). Idempotent:
     * duplicates are dropped BEFORE any signature/decrypt work (dedup-first stays cheap-first).
     */
    fun deliver(envelope: Envelope) {
        if (!seen.add(envelope.messageId)) return
        val keys = directory.lookup(envelope.signerId)
        if (keys == null) {
            log.warn("dropping envelope from unknown sender ${envelope.signerId.shortForm()}")
            return
        }
        if (!EnvelopeCrypto.verify(envelope, keys.identitySpki)) {
            log.warn("signature verification failed for ${envelope.messageId}")
            onBadSignature(envelope.signerId, now())
            return
        }
        val body = runCatching {
            EnvelopeCrypto.open(envelope, signer.clientId, myHpkePrivateKeyset)
        }.getOrElse {
            log.warn("decrypt failed for ${envelope.messageId}: ${it.message}")
            return
        }
        val handler = handlers[envelope.typ] ?: return
        handler(InboundMessage(envelope.signerId, keys.ownDevice, envelope.typ, body))
    }

    /** This device's id — exposed so callers can recognise self without reaching for the signer. */
    val clientId: ClientId get() = signer.clientId
}
