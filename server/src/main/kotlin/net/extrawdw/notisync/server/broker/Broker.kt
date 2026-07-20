package net.extrawdw.notisync.server.broker

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.crypto.Verification
import net.extrawdw.notisync.server.delivery.PushOutcome
import net.extrawdw.notisync.server.delivery.PushTransport
import net.extrawdw.notisync.server.delivery.WebSocketHub
import net.extrawdw.notisync.server.data.EpochStore
import net.extrawdw.notisync.server.data.PrivateAssetStore
import net.extrawdw.notisync.server.data.RelayStore
import net.extrawdw.notisync.server.data.RouteStore
import net.extrawdw.notisync.server.data.StoredEpoch
import net.extrawdw.notisync.server.data.StoredRoute
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Broker orchestration: verify signed key-epochs/routes (never decrypt), store-and-forward encrypted
 * envelopes, and fan out via the best available route (live WebSocket, else APNs/FCM push, else
 * report a missing route so the caller can supply a signed claim).
 */
class Broker(
    private val routes: RouteStore,
    private val relay: RelayStore,
    private val assets: PrivateAssetStore,
    private val epochs: EpochStore,
    private val hub: WebSocketHub,
    private val push: PushTransport,
    private val config: ServerConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val b64 = Base64.getEncoder()

    /**
     * The client's IDENTITY public key (the cold root) — resolved from its latest stored key-epoch (every
     * key-epoch carries the immutable identity key). Used to verify identity-signed artifacts: key-epochs,
     * route claims, and signerEpoch-0 requests/WS. NOT interchangeable with [operationalSpki]; conflating
     * them would let a leaked operational key masquerade as the root.
     */
    suspend fun clientSpki(clientId: ClientId): ByteArray? {
        val stored = epochs.latest(clientId) ?: return null
        // Fail closed if the stored key-epoch carries no identity anchor (uploads of an identity-stripped
        // key-epoch are already rejected, so this is defensive): null ⇒ unknown_client, never an empty key.
        return runCatching {
            ProtocolCodec.decodeFromCbor<SignedBlob>(stored.signedBlob).decode<ClientKeyEpoch>().identityPublicKey
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The OPERATIONAL signing key for ([clientId], [epoch]), scoped to [purpose] — used to verify
     * signerEpoch≥1 requests/WS (`REQUEST_AUTH`). Returns null if the epoch is unknown, below the client's
     * monotonic floor (downgrade/rollback guard), outside its validity window (a pre-warmed key before
     * `notBefore`, or a retired key after `notAfter`, both with a [ServerConfig.signedRequestMaxSkewMillis]
     * clock-skew tolerance), or NOT authorized for [purpose] (closed-by-default purpose scoping). Enforcing
     * the window bounds a compromised epoch's usable lifetime to `notAfter`. The clientId↔key binding was
     * established when the key-epoch was verified and stored (it carries the identity key); this does not re-bind.
     */
    suspend fun operationalSpki(clientId: ClientId, epoch: Int, purpose: Purpose = Purpose.REQUEST_AUTH): ByteArray? {
        if (epoch < 1) return null
        // Floor check + row fetch in one transaction (no read-after-read gap a concurrent rotation could exploit).
        val stored = epochs.getIfAtOrAboveFloor(clientId, epoch) ?: return null
        val now = System.currentTimeMillis()
        val skew = config.signedRequestMaxSkewMillis
        if (stored.notBefore - skew > now) return null   // pre-warmed: not yet active
        if (now - stored.notAfter > skew) return null     // retired: past notAfter
        val ke = runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(stored.signedBlob).decode<ClientKeyEpoch>() }
            .getOrNull() ?: return null
        if (purpose !in ke.purposes) return null          // closed-by-default: the key must carry this purpose
        return ke.operationalSigningKey
    }

    /**
     * Ingest a self-contained, identity-signed key-epoch (NS2). Verifies it self-consistently, pins it
     * against the client's previously stored identity key if we hold one (no key swap), and stores it under the
     * monotonic floor. Returns whether it was accepted.
     */
    suspend fun uploadKeyEpoch(blob: SignedBlob): Boolean {
        val ke = Verification.verifyKeyEpoch(blob) ?: return false
        val pinned = clientSpki(ke.clientId)
        if (pinned != null && !pinned.contentEquals(ke.identityPublicKey)) return false
        return epochs.put(
            StoredEpoch(ke.clientId, ke.epoch, ke.minEpoch, ke.notBefore, ke.notAfter, ProtocolCodec.encodeToCbor(blob))
        )
    }

    /**
     * Serve a client's verbatim identity-signed key-epoch blob (CBOR) for peer pull. The bytes are
     * returned exactly as stored because the caller re-verifies the identity signature over them
     * ([net.extrawdw.notisync.protocol.crypto.KeyEpochs.verify]); re-encoding through a typed response
     * could perturb the signed bytes, so this stays a raw [ByteArray] (cf. the relay-pull endpoint).
     * With [epoch] set, returns that specific epoch — the path a peer uses to fetch the exact key that
     * signed a given envelope. With [epoch] null, returns the highest *minted* epoch, which during
     * pre-warm is the staged, not-yet-active key (future `notBefore`): a peer pre-caches it but MUST
     * check the validity window before trusting it on the active path.
     */
    suspend fun getKeyEpoch(clientId: ClientId, epoch: Int? = null): ByteArray? =
        (if (epoch != null) epochs.get(clientId, epoch) else epochs.latest(clientId))?.signedBlob

    /** The client's current downgrade floor — surfaced so a client that lost its counter can recover. */
    suspend fun epochFloor(clientId: ClientId): Int = epochs.floor(clientId)

    suspend fun uploadRoutes(signedRoutes: List<SignedBlob>): Int {
        var accepted = 0
        for (blob in signedRoutes) {
            val spki = clientSpki(blob.signerId)
            if (spki == null) {
                log.info("route claim skipped: no known signing key signer={}", blob.signerId.shortForm())
                continue
            }
            val claim = Verification.verifyRouteClaim(blob, spki)
            if (claim == null) {
                log.info("route claim skipped: verify failed signer={}", blob.signerId.shortForm())
                continue
            }
            if (claim.routeRef.isEmpty()) {
                log.info("route claim clear client={} transport={} epoch={}", claim.clientId.shortForm(), claim.transport, claim.epoch)
                routes.clear(claim.clientId, claim.transport)
                accepted++
                continue
            }
            log.info(
                "route claim upload client={} transport={} epoch={} inlineBudget={} env={}",
                claim.clientId.shortForm(), claim.transport, claim.epoch, claim.capabilities.inlinePayloadLimitBytes, claim.environment,
            )
            routes.put(
                StoredRoute(
                    claim.clientId,
                    claim.transport,
                    claim.environment,
                    claim.routeRef,
                    claim.epoch,
                    claim.capabilities.inlinePayloadLimitBytes,
                    ProtocolCodec.encodeToCbor(blob),
                )
            )
            accepted++
        }
        return accepted
    }

    suspend fun send(envelopeBytes: ByteArray, envelope: Envelope, urgency: Urgency): SendResult {
        val delivered = mutableListOf<ClientId>()
        val missing = mutableListOf<ClientId>()
        val invalid = mutableListOf<ClientId>()
        val stale = mutableListOf<ClientId>()
        val expiresAt = System.currentTimeMillis() + config.relayTtlMillis

        for (recipient in envelope.recipientIds()) {
            // Strip foreign key material so a device's payload doesn't grow with the roster size.
            val recipientBytes = envelopeFor(recipient, envelope, envelopeBytes)

            // Persist for store-and-forward (removed on explicit ack), regardless of live state.
            relay.add(recipient, envelope.messageId, recipientBytes, urgency.name, envelope.typ.name, expiresAt)

            // Pending-dismissal hint for the iOS NSE's piggyback drain. A dismissal must never ride its
            // own alert push — an NSE miss (BFU after reboot, memory kill, timeout) falls back to
            // displaying the payload, and a dismissal has no legitimate visible form — so it stays a quiet
            // envelope, and the NOTIFICATION alert advertises how many DISMISSALs are queued; the NSE
            // pulls exactly those (DATA_SYNC isn't worth NSE budget and stays for the app's drain paths).
            val pendingDismissals =
                if (envelope.typ == MessageType.NOTIFICATION) relay.countPending(recipient, MessageType.DISMISSAL.name)
                else 0L

            if (hub.isOnline(recipient)) {
                val frame = ProtocolCodec.encodeToJson(
                    WsMessage(kind = WsKind.DELIVER, envelopeB64 = b64.encodeToString(recipientBytes))
                )
                if (hub.deliverText(recipient, frame)) {
                    delivered.add(recipient)
                    continue
                }
            }

            val candidates = routes.routesFor(recipient)
                .filter { it.transport == TransportType.APNS || it.transport == TransportType.FCM }
                .sortedBy { pushPreference(it.transport) }
            if (candidates.isEmpty()) {
                missing.add(recipient)
                continue
            }
            var anyTransient = false
            var anyInvalid = false
            var pushed = false
            for (route in candidates) {
                val budget = inlineBudgetFor(route)
                val payload = buildPushData(envelope.messageId, envelope.typ, recipientBytes, budget, pendingDismissals)
                val outcome = push.wake(route, payload.data, urgency)
                log.info(
                    "push wake transport={} recipient={} mid={} inline={} ctChars={} budget={} urgency={} outcome={}",
                    route.transport,
                    recipient.shortForm(),
                    envelope.messageId,
                    payload.inline,
                    payload.ctChars,
                    budget,
                    urgency,
                    outcome,
                )
                when (outcome) {
                    PushOutcome.DELIVERED -> {
                        delivered.add(recipient)
                        pushed = true
                        break
                    }
                    PushOutcome.ROUTE_INVALID -> {
                        routes.invalidate(recipient, route.transport)
                        anyInvalid = true
                    }
                    PushOutcome.TRANSIENT_FAILURE -> anyTransient = true
                    // Route token is fine but the push can't succeed as sent (permanent), or the transport
                    // is off (disabled): either way the relay still holds the item for a future WS pickup.
                    PushOutcome.PERMANENT_FAILURE, PushOutcome.DISABLED -> Unit
                }
            }
            if (pushed) continue
            // A just-invalidated route is the more actionable signal, so it outranks a transient blip on a
            // different candidate; anything else (permanent/disabled/none) leaves the item for relay + WS.
            when {
                anyInvalid -> invalid.add(recipient)
                anyTransient -> stale.add(recipient)
                else -> missing.add(recipient)
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

    /** A built push payload plus the inline decision and ct size, surfaced so [send] can log them. */
    private data class PushPayload(val data: Map<String, String>, val inline: Boolean, val ctChars: Int)

    private fun buildPushData(
        messageId: String,
        messageType: MessageType,
        envelopeBytes: ByteArray,
        inlineBudget: Int,
        pendingDismissals: Long = 0,
    ): PushPayload {
        val b64env = b64.encodeToString(envelopeBytes)
        // "mtyp" carries the E2E message type (broker-visible on the envelope) so APNs can branch
        // alert+mutable-content (NOTIFICATION → NSE decrypts) vs silent background (DISMISSAL/DATA_SYNC).
        // FCM/Android ignores it. "pnc" (NOTIFICATION only, when > 0) is the pending-DISMISSAL count
        // computed in [send]; capped so the value can't grow the payload past its accounted overhead.
        val base = buildMap {
            put("mtyp", messageType.name)
            if (pendingDismissals > 0) put("pnc", pendingDismissals.coerceAtMost(999).toString())
        }
        // ctChars is the base64 envelope length on BOTH branches (the size the budget gates), so the log
        // shows how big the payload was even when it was too big to inline and fell back to a wake pointer.
        return if (b64env.length <= inlineBudget) {
            PushPayload(base + mapOf("typ" to "notif", "mid" to messageId, "ct" to b64env), inline = true, ctChars = b64env.length)
        } else {
            PushPayload(base + mapOf("typ" to "wake", "mid" to messageId), inline = false, ctChars = b64env.length) // too big; pulls from relay
        }
    }

    /**
     * Effective inline budget for a route: the limit the client advertised in its signed route claim
     * (decoded once in [RouteStore.routesFor], so this is just a field read), capped by the server's own
     * ceiling ([ServerConfig.inlineBudgetBytes]) so a client can't force a payload larger than the broker
     * is willing to push, and finally floored by the transport's hard wire ceiling ([hardInlineCtBudget])
     * so neither a client over-claim nor a mis-set server ceiling can ever produce a rejectable push.
     */
    private fun inlineBudgetFor(route: StoredRoute): Int =
        route.inlinePayloadLimitBytes
            .coerceAtMost(config.inlineBudgetBytes)
            .coerceAtMost(hardInlineCtBudget(route.transport))

    /**
     * Absolute ceiling on the inline ct (base64 chars) for [transport], independent of advertised or
     * configured budgets: the wire hard limit (4 KB on both FCM data messages and APNs alert payloads)
     * minus this transport's fixed envelope overhead ([buildPushData]'s other keys, plus the APNs `aps`
     * alert dict) and a safety margin. This is the guard that keeps oversize structurally impossible — an
     * over-budget item degrades to a wake pointer in [buildPushData] rather than being sent and rejected.
     * It matters because FCM reports an oversize push as INVALID_ARGUMENT, indistinguishable from a bad
     * token, which [net.extrawdw.notisync.server.delivery.push.FcmPushTransport] retires as a dead route.
     */
    private fun hardInlineCtBudget(transport: TransportType): Int = when (transport) {
        TransportType.FCM -> PUSH_INLINE_HARD_LIMIT - FCM_INLINE_OVERHEAD - PUSH_INLINE_MARGIN
        TransportType.APNS -> PUSH_INLINE_HARD_LIMIT - APNS_INLINE_OVERHEAD - PUSH_INLINE_MARGIN
        else -> Int.MAX_VALUE
    }

    private fun pushPreference(transport: TransportType): Int =
        when (transport) {
            TransportType.APNS -> 0
            TransportType.FCM -> 1
            else -> 100
        }

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

    /** Message ids currently queued for [clientId] — the background-drain backstop lists then pulls each.
     *  [typ] narrows to one message type (the NSE's piggyback drain lists queued DISMISSALs exactly). */
    suspend fun pendingMessageIds(clientId: ClientId, typ: MessageType? = null): List<String> =
        relay.pendingMessageIds(clientId, typ?.name)

    suspend fun ack(clientId: ClientId, messageId: String) = relay.ackByMessage(clientId, messageId)

    /** Batch-drop [messageIds] from [clientId]'s relay queue; returns how many were removed. */
    suspend fun ackMany(clientId: ClientId, messageIds: Collection<String>): Int =
        relay.ackManyByMessage(clientId, messageIds)

    suspend fun purgeExpired() {
        val now = System.currentTimeMillis()
        val r = relay.purgeExpired(now)
        val a = assets.purgeExpired(now)
        // Retain a retired key-epoch one relay-TTL past notAfter so a peer can still pull it to verify an
        // in-flight relayed envelope from that epoch; the latest epoch is always kept (floor + current key).
        val e = epochs.purgeExpired(now, config.relayTtlMillis)
        if (r > 0 || a > 0 || e > 0) log.info("purged expired relay={} assets={} epochs={}", r, a, e)
    }

    private companion object {
        const val ASSET_ID_BYTES = 24 // 192-bit opaque id; rejects content-derived/short ids
        val EMPTY_DEK = ByteArray(0) // placeholder sealedDek for non-target recipients

        // Per-transport hard ceiling for the inline ct, in base64 chars (see [hardInlineCtBudget]).
        const val PUSH_INLINE_HARD_LIMIT = 4096 // FCM data-message limit & APNs alert-payload limit (4 KB)
        const val PUSH_INLINE_MARGIN = 160      // slack for push-service framing / future envelope fields
        const val FCM_INLINE_OVERHEAD = 88      // mtyp/typ/mid/pnc keys+values + the "ct" key (~77 B, rounded up)
        const val APNS_INLINE_OVERHEAD = 256    // aps alert/category dict + JSON data keys incl. pnc (~238 B, rounded up)
    }
}
