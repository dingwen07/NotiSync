package net.extrawdw.notisync.protocol

/** Delivery urgency, mapped by each transport adapter (FCM high vs normal priority data messages). */
enum class Urgency { HIGH, NORMAL }

/**
 * Transport-neutral client interface. Adapters map these concepts onto a concrete provider (FCM,
 * a dev WebSocket, future APNs/Web Push). The protocol — wake requests, encrypted payloads, route
 * repair, sync hints — is described independently of any one provider.
 */
interface Transport {
    val type: TransportType

    /**
     * Publish this client's self-contained, identity-signed [ClientKeyEpoch] ([SignedType.KEY_EPOCH]) to
     * the broker (`POST /v2/keyepoch`). NS2's canonical key-distribution store: the broker holds it to
     * authenticate this client's requests/WS, and peers pull it. Replaces the NS1 card upload — the device
     * profile never reaches the broker (it travels via the pairing QR + [ProfileUpdate]).
     */
    suspend fun publishKeyEpoch(keyEpoch: SignedBlob)

    /** Publish one or more signed route claims (own routes, or relayed peer routes for recovery). */
    suspend fun publishRoutes(routes: List<SignedBlob>)

    /**
     * Fetch a peer's [ClientKeyEpoch] [SignedBlob] from the broker (`GET /v2/keyepoch/{id}`). With [epoch]
     * null the broker returns the latest minted epoch (which during pre-warm may have a future notBefore);
     * with [epoch] set it returns that specific generation (to verify an in-flight envelope from it). The
     * blob is self-authenticating, so the broker cannot forge or substitute keys.
     */
    suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int? = null): SignedBlob?

    /** Send an encrypted envelope to its recipients. The result reports missing/invalid routes. */
    suspend fun send(envelope: Envelope, urgency: Urgency): SendResult

    /**
     * Run the live connection, delivering each inbound envelope to [onEnvelope] and acknowledging it
     * to the broker ONLY AFTER [onEnvelope] returns. Suspends until cancelled, reconnecting with
     * backoff. Ack-after-handling is what preserves at-least-once delivery: a crash before [onEnvelope]
     * completes leaves the envelope queued in the relay for redelivery (the channel dedups by message
     * id, so a redelivery is harmless).
     */
    suspend fun runLiveDelivery(onEnvelope: (Envelope) -> Unit)

    /**
     * Upload an opaque private-asset blob (AEAD ciphertext) under ([sourceClientId], [assetId]).
     * Returns true if the broker now holds it — including a 409 "already exists" (overwrite-reject),
     * which the caller treats as success.
     */
    suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): Boolean

    /** Fetch an opaque private-asset blob, or null if the broker no longer has it. */
    suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray?
}
