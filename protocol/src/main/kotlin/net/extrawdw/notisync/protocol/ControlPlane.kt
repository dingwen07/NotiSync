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
    /** Attachment hashes the broker does not have cached and needs uploaded. */
    val missingAssets: List<String> = emptyList(),
)

@Serializable
data class BlobUploadResponse(
    val blobId: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val detail: String? = null,
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
