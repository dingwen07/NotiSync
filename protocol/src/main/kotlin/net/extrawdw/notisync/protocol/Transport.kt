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

    /** Publish this client's signed identity card to the broker cache. */
    suspend fun publishCard(card: SignedBlob)

    /** Publish one or more signed route claims (own routes, or relayed peer routes for recovery). */
    suspend fun publishRoutes(routes: List<SignedBlob>)

    /** Fetch a peer's signed client card. */
    suspend fun fetchCard(clientId: ClientId): SignedBlob?

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
