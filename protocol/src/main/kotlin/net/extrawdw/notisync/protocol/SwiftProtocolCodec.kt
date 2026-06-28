package net.extrawdw.notisync.protocol

/**
 * Typed Swift-facing codec facade.
 *
 * Kotlin/Native exports the generic ProtocolCodec helpers to Swift as erased Any-returning methods,
 * and decode exceptions are not pleasant to handle at that boundary. This facade keeps iOS on the
 * shared KMP serializers while exposing concrete methods that Swift can call and catch normally.
 */
object SwiftProtocolCodec {
    fun encodeIntegrityVerificationRequest(value: IntegrityVerificationRequest): String = ProtocolCodec.encodeToJson(value)
    fun encodeRelayAck(value: RelayAck): String = ProtocolCodec.encodeToJson(value)
    fun encodeWsAuth(value: WsAuth): String = ProtocolCodec.encodeToJson(value)
    fun encodeWsMessage(value: WsMessage): String = ProtocolCodec.encodeToJson(value)

    fun encodeEnvelopeAuth(value: EnvelopeAuth): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeAssetAad(value: AssetAad): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeSignedBlob(value: SignedBlob): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeClientKeyEpoch(value: ClientKeyEpoch): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeRouteClaim(value: RouteClaim): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeDismissEvent(value: DismissEvent): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeClientCard(value: ClientCard): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeCardDelivery(value: CardDelivery): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeDataSync(value: DataSync): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeEnvelope(value: Envelope): ByteArray = ProtocolCodec.encodeToCbor(value)
    fun encodeSignedBlobList(value: List<SignedBlob>): ByteArray = ProtocolCodec.encodeToCbor(value)

    @Throws(Exception::class)
    fun decodeSignedBlob(bytes: ByteArray): SignedBlob = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeHealthResponse(text: String): HealthResponse = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeVerificationStatusResponse(text: String): VerificationStatusResponse = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeIntegrityVerificationResponse(text: String): IntegrityVerificationResponse = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeRelayPending(text: String): RelayPending = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeWsChallenge(text: String): WsChallenge = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeWsMessage(text: String): WsMessage = ProtocolCodec.decodeFromJson(text)

    @Throws(Exception::class)
    fun decodeClientKeyEpoch(bytes: ByteArray): ClientKeyEpoch = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeEnvelope(bytes: ByteArray): Envelope = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeDismissEvent(bytes: ByteArray): DismissEvent = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeCapturedNotification(bytes: ByteArray): CapturedNotification = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeClientCard(bytes: ByteArray): ClientCard = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeCardDelivery(bytes: ByteArray): CardDelivery = ProtocolCodec.decodeFromCbor(bytes)

    @Throws(Exception::class)
    fun decodeDataSync(bytes: ByteArray): DataSync = ProtocolCodec.decodeFromCbor(bytes)
}
