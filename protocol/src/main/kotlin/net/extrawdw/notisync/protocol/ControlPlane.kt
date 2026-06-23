package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable

/**
 * JSON DTOs for the broker's REST control plane. Binary-bearing objects (signed cards/routes,
 * envelopes, blobs) are exchanged as CBOR bodies; these JSON types carry only routing metadata
 * and status, never plaintext notification content.
 */

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

/** Result of a /v1/send. Tells the caller which recipients delivered and what the broker is missing. */
@Serializable
data class SendResult(
    val accepted: Boolean,
    val delivered: List<ClientId> = emptyList(),
    /** Recipients with no usable route — the caller should supply signed route claims it has cached. */
    val missingRoutes: List<ClientId> = emptyList(),
    /** Recipients whose route the provider rejected as no longer valid — supply a newer claim. */
    val invalidRoutes: List<ClientId> = emptyList(),
    val staleRoutes: List<ClientId> = emptyList(),
)

@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null,
)

/**
 * Response body for the signed-only GET /v1/relay: the message ids currently queued for the caller.
 * The background-drain backstop pulls this list, then fetches + acks each via GET /v1/relay/{id}.
 */
@Serializable
data class RelayPending(
    val messageIds: List<String> = emptyList(),
)

/**
 * Request body for the signed-only POST /v1/relay/ack: message ids the caller has durably handled and
 * wants dropped from its relay queue in one round trip. The batch path for deliveries that don't ack
 * inline — chiefly FCM-inline pushes (the envelope arrives in the push, so it's never fetched) and a
 * local dismissal of a still-queued mirror. Acking is idempotent: an unknown/already-dropped id is a
 * no-op, so a retry after a partial failure is safe.
 */
@Serializable
data class RelayAck(
    val messageIds: List<String> = emptyList(),
) {
    companion object {
        /** Max ids per batch ack. Bounds one request's work and keeps the server's `IN (...)` delete
         *  under SQLite's 999-variable limit. The client batches by this; the server chunks by it. */
        const val MAX_BATCH = 500
    }
}

/** Request body for POST /v1/integrity/verify. */
@Serializable
data class PlayIntegrityVerificationRequest(
    val clientId: ClientId,
    /** Client-generated nonce bound into [requestHash]. */
    val requestNonce: String,
    /** Play Integrity Standard API requestHash: hash("notisync-play-integrity-v1\\n<clientId>\\n<requestNonce>"). */
    val requestHash: String,
    val integrityToken: String,
    /**
     * NS2 (`/v2`): the self-authenticating [ClientKeyEpoch] blob, sent on first contact / key refresh so the
     * broker learns this client's identity + operational keys. Replaces [clientCard] on the NS2 server.
     */
    val clientKeyEpoch: SignedBlob? = null,
    /** NS1 (`/v1`, legacy) only: the client card. The NS2 server ignores this; use [clientKeyEpoch]. */
    val clientCard: SignedBlob? = null,
    /** Debug-only HMAC proof over the attestation binding; never send the raw debug key. */
    val debugProof: String? = null,
)

@Serializable
data class PlayIntegrityVerificationResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val clientId: ClientId,
    val expiresAt: Long,
)

/** Response body for GET /v1/status — unauthenticated discovery of the broker's auth posture. */
@Serializable
data class VerificationStatusResponse(
    val version: String,
    /** Whether the broker requires Play Integrity attestation + signed/JWT auth at all. */
    val playIntegrityRequired: Boolean,
    /** True iff this request carried a currently-valid bearer token. */
    val verified: Boolean,
    /** The authenticated client id, present iff [verified]. */
    val clientId: ClientId? = null,
    /** Bearer token expiry (epoch millis), present iff [verified]. */
    val expiresAt: Long? = null,
)

/** WebSocket handshake: server -> client challenge, then client -> server signed response. */
@Serializable
data class WsChallenge(val nonce: String)

@Serializable
data class WsAuth(
    val clientId: ClientId,
    val nonce: String,
    /** ECDSA-P256 signature over the UTF-8 bytes of [nonce], by the key named by [epoch]. */
    val signatureB64: String,
    /** NS2: signing-key selector — 0 = identity key (NS1-compatible), ≥1 = operational [ClientKeyEpoch]. */
    val epoch: Int = 0,
)

/** Realtime frame over the dev WebSocket transport (flat to avoid polymorphic serialization). */
@Serializable
data class WsMessage(
    val kind: String,
    /** An envelope addressed to the connected client: CBOR bytes, base64 (kind=deliver). */
    val envelopeB64: String? = null,
    /** Message id being acknowledged (kind=ack). */
    val messageId: String? = null,
)

object WsKind {
    const val DELIVER = "deliver"
    const val ACK = "ack"
    const val PING = "ping"
    const val PONG = "pong"
}
