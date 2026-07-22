package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel

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

/**
 * Authenticated CBOR body for `/v2/send`. Keeping [urgency] beside the encrypted [envelope] binds the
 * requested delivery priority to the request signature while leaving the broker unable to inspect the
 * envelope's plaintext body.
 */
@Serializable
data class SendRequest(
    @CborLabel(0) val envelope: Envelope,
    @CborLabel(1) val urgency: Urgency,
)

/** Result of /v2/send. Tells the caller which recipients delivered and what the broker is missing. */
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
 * Response body for the signed-only GET /v2/relay: the message ids currently queued for the caller.
 * The background-drain backstop pulls this list, then fetches each via GET /v2/relay/{id}.
 */
@Serializable
data class RelayPending(
    val messageIds: List<String> = emptyList(),
)

/**
 * Request body for the signed-only POST /v2/relay/ack: message ids the caller has durably handled and
 * wants dropped from its relay queue in one round trip. The batch path for deliveries the broker cannot
 * observe being consumed inline: inline pushes, ack-after-handle relay fetches, and local dismissals of
 * still-queued mirrors. Acking is idempotent: an unknown/already-dropped id is a no-op, so a retry after
 * a partial failure is safe.
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

/**
 * The pluggable client-integrity methods the broker can verify, named by
 * [IntegrityVerificationRequest.attestationType]. [FIREBASE_APP_CHECK] is the live mobile method (App Check
 * uses Play Integrity / App Attest internally on the device); [APPLE_APP_ATTEST] is reserved for a future
 * native path. [NONE] explicitly represents a client without a platform attestor and is usable only when the
 * broker is lenient. [PLAY_INTEGRITY] is the retired legacy method: the broker no longer verifies it (the
 * direct Play Integrity decode was removed), but the constant is kept as the historical wire/metrics label
 * and the absent-field default for old requests.
 */
object AttestationType {
    /** No platform attestation is available. Accepted only by brokers configured for lenient integrity. */
    const val NONE = "none"
    /** Retired: the broker no longer has a Play Integrity verifier. Kept only as a legacy label/default. */
    const val PLAY_INTEGRITY = "playIntegrity"
    const val FIREBASE_APP_CHECK = "firebaseAppCheck"
    const val APPLE_APP_ATTEST = "appleAppAttest"
}

/**
 * Request body for POST /v2/integrity/verify. Carries whichever client-integrity proof the client uses;
 * the broker dispatches on [attestationType]. The whole body is SHA-256'd and identity-ECDSA-signed by the
 * request signature, so [attestationToken] is bound to this client/nonce/timestamp regardless of method.
 */
@Serializable
data class IntegrityVerificationRequest(
    val clientId: ClientId,
    /** Which attestation method this request uses; absent ⇒ legacy [AttestationType.PLAY_INTEGRITY]. */
    val attestationType: String = AttestationType.PLAY_INTEGRITY,
    /** Generic attestation token: a Firebase App Check JWT today; future methods can reuse this slot. */
    val attestationToken: String? = null,
    /** Reserved for App Attest's per-install hardware key id. Unused by App Check. */
    val attestationKeyId: String? = null,
    /** Retired Play Integrity field, ignored by the broker. Kept so in-flight legacy clients still decode. */
    val requestNonce: String = "",
    /** Retired Play Integrity field, ignored by the broker. Kept so in-flight legacy clients still decode. */
    val requestHash: String = "",
    /** Retired Play Integrity field, ignored by the broker. Kept so in-flight legacy clients still decode. */
    val integrityToken: String = "",
    /**
     * NS2 (`/v2`): the self-authenticating [ClientKeyEpoch] blob, sent on first contact / key refresh so the
     * broker learns this client's identity + operational keys. Replaces [clientCard] on the NS2 server.
     */
    val clientKeyEpoch: SignedBlob? = null,
    /** NS1 (`/v1`, legacy) only: the client card. The NS2 server ignores this; use [clientKeyEpoch]. */
    val clientCard: SignedBlob? = null,
    /** Retired Play Integrity debug-bypass proof, ignored by the broker. Kept for legacy-client decode. */
    val debugProof: String? = null,
)

/** Response body for POST /v2/integrity/verify: the broker bearer (ES256 JWT) issued on a passing attestation. */
@Serializable
data class IntegrityVerificationResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val clientId: ClientId,
    val expiresAt: Long,
)

/** Response body for GET /v2/status — unauthenticated discovery of the broker's auth posture. */
@Serializable
data class VerificationStatusResponse(
    val version: String,
    /**
     * DEPRECATED — pending removal. Legacy alias for [securityEnabled], carrying the same value
     */
    val playIntegrityRequired: Boolean,
    /** True iff this request carried a currently-valid bearer token. */
    val verified: Boolean,
    /** The authenticated client id, present iff [verified]. */
    val clientId: ClientId? = null,
    /** Bearer token expiry (epoch millis), present iff [verified]. */
    val expiresAt: Long? = null,
    /** Required hashcash difficulty for first-contact /integrity/verify (0 = PoW disabled). */
    val powDifficulty: Int = 0,
    /** Attestation methods this broker accepts (see [AttestationType]); the client picks one it supports. */
    val acceptedAttestationMethods: List<String> = emptyList(),
    /**
     * Whether the broker *requires* a passing client-integrity attestation (App Check / App Attest) to mint
     * a bearer (`NOTISYNC_INTEGRITY_REQUIRED`). When false the broker still accepts and records attestation
     * but issues a bearer to any validly-signed client. Defaults false so old clients reading an old server
     * (which omits this field) see the lenient posture.
     */
    val integrityRequired: Boolean = false,
    /**
     * Whether the broker enforces signed requests and JWT auth
     */
    val securityEnabled: Boolean = false,
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

/** Authenticated participant role for one opaque screen-relay channel. */
@Serializable
enum class ScreenRelayRole { REQUESTER, SOURCE }

/** The two independent v1 byte streams. Keeping them separate prevents control behind video. */
@Serializable
enum class ScreenRelayChannel { VIDEO, CONTROL }

/**
 * First frame after WebSocket authentication on `/v1/screen-relay`. [relayId] is a random capability
 * delivered only inside the E2E screen request. The broker additionally binds each role to its authenticated
 * [ClientId], then forwards binary frames without inspecting the nested PSK-TLS stream.
 */
@Serializable
data class ScreenRelayJoin(
    val relayId: String,
    val requesterPeerId: ClientId,
    val sourcePeerId: ClientId,
    val role: ScreenRelayRole,
    val channel: ScreenRelayChannel,
    val expiresAt: Long,
)

/** Server acknowledgement that the relay slot is registered and may begin buffering its TLS handshake. */
@Serializable
data class ScreenRelaySignal(
    val kind: String,
    val detail: String? = null,
)

object ScreenRelaySignalKind {
    const val REGISTERED = "registered"
}

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
