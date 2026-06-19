package net.extrawdw.notisync.protocol

import kotlinx.coroutines.flow.Flow

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

    /** Cold stream of envelopes addressed to this client (push wake or live connection). */
    fun incoming(): Flow<Envelope>

    /** Upload an encrypted blob; returns its broker blob id. */
    suspend fun uploadBlob(bytes: ByteArray): String

    /** Fetch an encrypted blob by id, or null if the broker no longer has it. */
    suspend fun fetchBlob(blobId: String): ByteArray?
}
