package net.extrawdw.apps.notisync.domain

import android.util.Log
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Renders / clears mirrored notifications. Implemented by the notification layer. */
interface MirrorRenderer {
    fun render(notif: CapturedNotification)
    fun clear(sourceClientId: ClientId, sourceKey: String)
}

/** Cancels a local original notification (provider side) when a remote dismissal arrives. */
fun interface OriginalCanceler {
    fun cancel(sourceKey: String)
}

/**
 * Orchestrates the E2E mirror flow:
 *  - capture (provider): seal a [CapturedNotification] to every peer and send it,
 *  - receive (consumer): verify the sender is a trusted peer, decrypt, and render or clear,
 *  - dismissal: propagate idempotent [DismissEvent]s both directions.
 * The broker only ever sees ciphertext + routing metadata.
 */
class MirrorEngine(
    private val signer: IdentitySigner,
    private val myHpkePrivateKeyset: ByteArray,
    private val transport: net.extrawdw.notisync.protocol.Transport,
    private val peersProvider: () -> List<Peer>,
    private val renderer: MirrorRenderer,
    private val activityLog: ActivityLog,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var originalCanceler: OriginalCanceler? = null

    private val b64 = Base64.getDecoder()
    private val seq = AtomicLong(now())

    /** Bounded LRU of recently-seen message ids for idempotent delivery (dedupes FCM + WebSocket). */
    private val seen: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean = size > 512
        })
    )

    private fun recipients(): List<RecipientKey> = peersProvider().map {
        RecipientKey(it.clientId, b64.decode(it.hpkePublicKeysetB64))
    }

    suspend fun captureLocal(notif: CapturedNotification) {
        val recipients = recipients()
        if (recipients.isEmpty()) return
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.NOTIFICATION,
            bodyPlaintext = ProtocolCodec.encodeToCbor(notif),
            recipients = recipients,
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.HIGH)
        activityLog.add(ActivityEvent.Kind.SENT, notif.appLabel, "mirrored to ${recipients.size} device(s)", now())
    }

    suspend fun dismissLocal(sourceClientId: ClientId, sourceKey: String) {
        // No local-suppression set here: echoes from our own cancelNotification() (issued on a remote
        // dismissal) carry REASON_LISTENER_CANCEL and are filtered out by the listener's reason
        // allowlist. A permanent suppression set would wrongly block re-dismissals of reused keys.
        val recipients = recipients()
        if (recipients.isEmpty()) return
        val event = DismissEvent(sourceClientId, sourceKey, now())
        val envelope = EnvelopeCrypto.seal(
            signer = signer,
            typ = MessageType.DISMISSAL,
            bodyPlaintext = ProtocolCodec.encodeToCbor(event),
            recipients = recipients,
            messageId = UUID.randomUUID().toString(),
            seq = seq.incrementAndGet(),
            createdAt = now(),
        )
        transport.send(envelope, Urgency.NORMAL)
        activityLog.add(ActivityEvent.Kind.DISMISSED, "Dismissal", "synced to ${recipients.size} device(s)", now())
    }

    /** Handle an envelope received via WebSocket or FCM. Idempotent: duplicates are dropped. */
    fun handleEnvelope(envelope: Envelope) {
        if (!seen.add(envelope.messageId)) return
        val sender = peersProvider().firstOrNull { it.clientId == envelope.signerId }
        if (sender == null) {
            Log.w(TAG, "dropping envelope from unknown sender ${envelope.signerId.shortForm()}")
            return
        }
        val senderSpki = b64.decode(sender.identityPublicKeyB64)
        if (!EnvelopeCrypto.verify(envelope, senderSpki)) {
            Log.w(TAG, "signature verification failed for ${envelope.messageId}")
            activityLog.add(ActivityEvent.Kind.ERROR, "Rejected", "bad signature from ${sender.displayName}", now())
            return
        }
        val body = runCatching {
            EnvelopeCrypto.open(envelope, signer.clientId, myHpkePrivateKeyset)
        }.getOrElse {
            Log.w(TAG, "decrypt failed for ${envelope.messageId}: ${it.message}")
            return
        }
        when (envelope.typ) {
            MessageType.NOTIFICATION -> {
                val notif = ProtocolCodec.decodeFromCbor<CapturedNotification>(body)
                renderer.render(notif)
                activityLog.add(ActivityEvent.Kind.RECEIVED, notif.appLabel, "from ${sender.displayName}", now())
            }
            MessageType.DISMISSAL -> {
                val event = ProtocolCodec.decodeFromCbor<DismissEvent>(body)
                renderer.clear(event.sourceClientId, event.sourceKey)
                originalCanceler?.cancel(event.sourceKey)
                activityLog.add(ActivityEvent.Kind.DISMISSED, "Dismissed", "by ${sender.displayName}", now())
            }
            MessageType.DATA_SYNC -> Unit
        }
    }

    companion object {
        private const val TAG = "MirrorEngine"
    }
}
