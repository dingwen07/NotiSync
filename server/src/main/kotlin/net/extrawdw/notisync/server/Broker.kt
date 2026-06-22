package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
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
    private val assets: PrivateAssetStore,
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
            // Strip foreign key material so a device's payload doesn't grow with the roster size.
            val recipientBytes = envelopeFor(recipient, envelope, envelopeBytes)

            // Persist for store-and-forward (removed on explicit ack), regardless of live state.
            relay.add(recipient, envelope.messageId, recipientBytes, urgency.name, expiresAt)

            if (hub.isOnline(recipient)) {
                val frame = ProtocolCodec.encodeToJson(
                    WsMessage(kind = WsKind.DELIVER, envelopeB64 = b64.encodeToString(recipientBytes))
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
            val outcome = push.wake(fcm.routeRef, buildFcmData(envelope.messageId, recipientBytes, inlineBudgetFor(fcm)), urgency)
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

    /**
     * Re-encode [envelope] for a single [recipient], blanking every other recipient's sealedDek.
     * The source signature commits (via EnvelopeAuth) only to the recipient *ids* and a hash of the
     * body ciphertext — never the sealed DEKs — so dropping the foreign key material leaves both the
     * signature and the recipient's own key intact, while keeping the per-device payload from growing
     * with the roster. Single-recipient (or empty) envelopes are passed through untouched.
     */
    private fun envelopeFor(recipient: ClientId, envelope: Envelope, fullBytes: ByteArray): ByteArray {
        if (envelope.recipients.size <= 1) return fullBytes
        val stripped = envelope.copy(
            recipients = envelope.recipients.map {
                if (it.recipientId == recipient) it else it.copy(sealedDek = EMPTY_DEK)
            }
        )
        return ProtocolCodec.encodeToCbor(stripped)
    }

    private fun buildFcmData(messageId: String, envelopeBytes: ByteArray, inlineBudget: Int): Map<String, String> {
        val b64env = b64.encodeToString(envelopeBytes)
        return if (b64env.length <= inlineBudget) {
            mapOf("typ" to "notif", "mid" to messageId, "ct" to b64env)
        } else {
            mapOf("typ" to "wake", "mid" to messageId) // too big to inline; client pulls from relay
        }
    }

    /**
     * Effective inline budget for a route: the limit the client advertised in its signed route claim,
     * capped by the server's own ceiling ([ServerConfig.inlineBudgetBytes]) so a client can't force a
     * payload larger than the broker is willing to push. Falls back to the server ceiling if the stored
     * claim can't be decoded.
     */
    private fun inlineBudgetFor(route: StoredRoute): Int =
        runCatching {
            ProtocolCodec.decodeFromCbor<SignedBlob>(route.signedBlob).decode<RouteClaim>().capabilities.inlinePayloadLimitBytes
        }.getOrNull()?.coerceAtMost(config.inlineBudgetBytes) ?: config.inlineBudgetBytes

    /** Outcome of a private-asset upload, surfaced as HTTP status by the route. */
    enum class AssetStoreOutcome { STORED, EXISTS, TOO_LARGE, BAD_ID }

    /**
     * Store an opaque private-asset blob under ([sourceClientId], [assetId]). The broker never reads
     * it; it only enforces an opaque-id floor (random 192-bit Base32), a size cap, and overwrite-
     * reject. Confidentiality + integrity are the client's (the id+key are E2E-delivered; the
     * receiver verifies the plaintext hash).
     */
    suspend fun storeAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): AssetStoreOutcome {
        if (Base32.decode(assetId)?.size != ASSET_ID_BYTES) return AssetStoreOutcome.BAD_ID
        if (ciphertext.size > config.maxPrivateAssetBytes) return AssetStoreOutcome.TOO_LARGE
        val expiresAt = System.currentTimeMillis() + config.privateAssetTtlMillis
        return if (assets.putIfAbsent(sourceClientId, assetId, ciphertext, expiresAt)) AssetStoreOutcome.STORED
        else AssetStoreOutcome.EXISTS
    }

    suspend fun fetchAsset(sourceClientId: ClientId, assetId: String): ByteArray? = assets.get(sourceClientId, assetId)

    suspend fun flushPending(clientId: ClientId, sendFrame: suspend (String) -> Unit) {
        for (item in relay.pending(clientId)) {
            sendFrame(
                ProtocolCodec.encodeToJson(
                    WsMessage(kind = WsKind.DELIVER, envelopeB64 = b64.encodeToString(item.envelope))
                )
            )
        }
    }

    /** The single queued envelope addressed to [clientId] with [messageId], or null. */
    suspend fun relayMessage(clientId: ClientId, messageId: String): ByteArray? = relay.getByMessage(clientId, messageId)

    /** Message ids currently queued for [clientId] — the background-drain backstop lists then pulls each. */
    suspend fun pendingMessageIds(clientId: ClientId): List<String> = relay.pendingMessageIds(clientId)

    suspend fun ack(clientId: ClientId, messageId: String) = relay.ackByMessage(clientId, messageId)

    suspend fun purgeExpired() {
        val now = System.currentTimeMillis()
        val r = relay.purgeExpired(now)
        val a = assets.purgeExpired(now)
        if (r > 0 || a > 0) log.info("purged expired relay={} assets={}", r, a)
    }

    private companion object {
        const val ASSET_ID_BYTES = 24 // 192-bit opaque id; rejects content-derived/short ids
        val EMPTY_DEK = ByteArray(0) // placeholder sealedDek for non-target recipients
    }
}
