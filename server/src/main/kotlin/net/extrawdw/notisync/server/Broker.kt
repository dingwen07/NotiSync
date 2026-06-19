package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.crypto.AssetHash
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Broker orchestration: verify signed cards/routes (never decrypt), store-and-forward encrypted
 * envelopes, and fan out via the best available route (live WebSocket, else FCM wake/inline, else
 * report a missing route so the caller can supply a signed claim).
 */
class Broker(
    private val cards: CardStore,
    private val routes: RouteStore,
    private val relay: RelayStore,
    private val blobs: BlobStore,
    private val hub: WebSocketHub,
    private val push: PushTransport,
    private val config: ServerConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val b64 = Base64.getEncoder()

    suspend fun uploadCard(blob: SignedBlob): Boolean {
        val card = Verification.verifyClientCard(blob) ?: return false
        cards.put(card.clientId, ProtocolCodec.encodeToCbor(blob))
        return true
    }

    suspend fun getCardBlob(clientId: ClientId): ByteArray? = cards.getSignedBlob(clientId)

    suspend fun clientSpki(clientId: ClientId): ByteArray? {
        val blobBytes = cards.getSignedBlob(clientId) ?: return null
        return runCatching {
            ProtocolCodec.decodeFromCbor<SignedBlob>(blobBytes).decode<ClientCard>().identityPublicKey
        }.getOrNull()
    }

    suspend fun uploadRoutes(signedRoutes: List<SignedBlob>): Int {
        var accepted = 0
        for (blob in signedRoutes) {
            val spki = clientSpki(blob.signerId) ?: continue
            val claim = Verification.verifyRouteClaim(blob, spki) ?: continue
            routes.put(StoredRoute(claim.clientId, claim.transport, claim.routeRef, claim.epoch, ProtocolCodec.encodeToCbor(blob)))
            accepted++
        }
        return accepted
    }

    suspend fun send(envelopeBytes: ByteArray, envelope: Envelope): SendResult {
        val urgency = if (envelope.typ == MessageType.NOTIFICATION) Urgency.HIGH else Urgency.NORMAL
        val delivered = mutableListOf<ClientId>()
        val missing = mutableListOf<ClientId>()
        val invalid = mutableListOf<ClientId>()
        val stale = mutableListOf<ClientId>()
        val expiresAt = System.currentTimeMillis() + config.relayTtlMillis

        for (recipient in envelope.recipientIds()) {
            // Persist for store-and-forward (removed on explicit ack), regardless of live state.
            relay.add(recipient, envelope.messageId, envelopeBytes, urgency.name, expiresAt)

            if (hub.isOnline(recipient)) {
                val frame = ProtocolCodec.encodeToJson(
                    WsMessage(kind = WsKind.DELIVER, envelopeB64 = b64.encodeToString(envelopeBytes))
                )
                if (hub.deliverText(recipient, frame)) {
                    delivered.add(recipient)
                    continue
                }
            }

            val fcm = routes.routesFor(recipient).firstOrNull { it.transport == TransportType.FCM }
            if (fcm == null) {
                missing.add(recipient)
                continue
            }
            val outcome = push.wake(fcm.routeRef, buildFcmData(envelope.messageId, envelopeBytes), urgency)
            log.info("fcm wake recipient={} mid={} outcome={}", recipient.shortForm(), envelope.messageId, outcome)
            when (outcome) {
                PushOutcome.DELIVERED -> delivered.add(recipient)
                PushOutcome.ROUTE_INVALID -> {
                    routes.invalidate(recipient, TransportType.FCM)
                    invalid.add(recipient)
                }
                PushOutcome.TRANSIENT_FAILURE -> stale.add(recipient)
                PushOutcome.DISABLED -> missing.add(recipient) // relay still holds it for a future WS pickup
            }
        }
        log.info(
            "send mid={} recipients={} delivered={} missing={} invalid={}",
            envelope.messageId, envelope.recipients.size, delivered.size, missing.size, invalid.size,
        )
        return SendResult(true, delivered, missing, invalid, stale)
    }

    private fun buildFcmData(messageId: String, envelopeBytes: ByteArray): Map<String, String> {
        val b64env = b64.encodeToString(envelopeBytes)
        return if (b64env.length <= config.inlineBudgetBytes) {
            mapOf("typ" to "notif", "mid" to messageId, "ct" to b64env)
        } else {
            mapOf("typ" to "wake", "mid" to messageId) // too big to inline; client pulls from relay
        }
    }

    suspend fun storeBlob(bytes: ByteArray): String {
        val id = AssetHash.of(bytes)
        blobs.put(id, bytes, System.currentTimeMillis() + config.blobTtlMillis)
        return id
    }

    suspend fun fetchBlob(id: String): ByteArray? = blobs.get(id)

    suspend fun flushPending(clientId: ClientId, sendFrame: suspend (String) -> Unit) {
        for (item in relay.pending(clientId)) {
            sendFrame(
                ProtocolCodec.encodeToJson(
                    WsMessage(kind = WsKind.DELIVER, envelopeB64 = b64.encodeToString(item.envelope))
                )
            )
        }
    }

    suspend fun ack(clientId: ClientId, messageId: String) = relay.ackByMessage(clientId, messageId)

    suspend fun purgeExpired() {
        val now = System.currentTimeMillis()
        val r = relay.purgeExpired(now)
        val b = blobs.purgeExpired(now)
        if (r > 0 || b > 0) log.info("purged expired relay={} blobs={}", r, b)
    }
}
