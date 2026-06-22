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

/** Request body for POST /v1/integrity/verify. */
@Serializable
data class PlayIntegrityVerificationRequest(
    val clientId: ClientId,
    /** Client-generated nonce bound into [requestHash]. */
    val requestNonce: String,
    /** Play Integrity Standard API requestHash: hash("notisync-play-integrity-v1\\n<clientId>\\n<requestNonce>"). */
    val requestHash: String,
    val integrityToken: String,
    /** Present on first verification or key refresh so the broker can learn the client's public key. */
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
    /** ECDSA-P256 signature over the UTF-8 bytes of [nonce], by the client's identity key. */
    val signatureB64: String,
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
