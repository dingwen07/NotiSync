package net.extrawdw.notisync.peer.pairing

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs

object PairingDeepLinks {
    const val HTTPS_SCHEME = "https"
    const val HTTPS_HOST = "notisync.apps.extrawdw.net"
    const val CUSTOM_SCHEME = "notisync"
    const val CUSTOM_HOST = "pair"
    const val PAIRING_PATH = "/pair"
    const val PARAM_PAYLOAD = "payload"
    // Larger than a QR can hold, but bounded for externally supplied deep links and raw CARDs.
    internal const val MAX_INPUT_CHARS = 64 * 1024

    fun create(payload: String): String =
        "$HTTPS_SCHEME://$HTTPS_HOST$PAIRING_PATH?$PARAM_PAYLOAD=" +
            URLEncoder.encode(payload, StandardCharsets.UTF_8)

    fun payloadFrom(link: String?): String? {
        if (link == null || link.length > MAX_INPUT_CHARS) return null
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!isPairingUri(uri)) return null
        return runCatching { extractPayload(link) }.getOrNull()
    }

    fun extractPayload(content: String): String {
        require(content.length <= MAX_INPUT_CHARS) { "Pairing input is too large" }
        val trimmed = content.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri == null || !isPairingUri(uri)) return trimmed
        // A malformed broker link must not fall back to the optical trust path.
        require(uri.rawFragment.isNullOrEmpty()) { "Unexpected pairing link fragment" }
        val pair = uri.rawQuery.orEmpty().split('=', limit = 2)
        require(pair.size == 2 && pair[0] == PARAM_PAYLOAD && '&' !in pair[1]) {
            "pairing link requires a single payload"
        }
        val value = pair[1]
        return URLDecoder.decode(value, StandardCharsets.UTF_8).trim()
            .takeIf { it.isNotEmpty() } ?: error("pairing link missing payload")
    }

    private fun isPairingUri(uri: URI): Boolean = uri.rawUserInfo == null && uri.port == -1 && when {
        uri.scheme.equals(CUSTOM_SCHEME, ignoreCase = true) ->
            uri.host.equals(CUSTOM_HOST, ignoreCase = true) && uri.rawPath.orEmpty().isEmpty()
        uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true) ->
            uri.host.equals(HTTPS_HOST, ignoreCase = true) &&
                (uri.path == PAIRING_PATH || uri.path == "$PAIRING_PATH/")
        else -> false
    }
}

enum class KeyEpochStatus { VERIFIED, ABSENT, INVALID }

data class PairingCandidate(
    val payload: String,
    val displayName: String,
    val platform: String,
    val clientId: ClientId,
    val safetyNumber: String,
    val identityKeyFingerprint: String,
    val epoch: Int,
    val operationalKeyFingerprint: String,
    val hpkeKeyFingerprint: String,
    val keyEpochStatus: KeyEpochStatus,
)

data class VerifiedPairingDelivery(
    val cardBlob: SignedBlob,
    val card: ClientCard,
    val epochBlob: SignedBlob?,
)

/** Platform-neutral encoding and cryptographic verification for optical pairing payloads. */
class PairingPayloadCodec(private val selfId: ClientId) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(card: SignedBlob, epochBlob: SignedBlob? = null): String =
        encoder.encodeToString(
            ProtocolCodec.encodeToCbor(CardDelivery(selfId, card = card, epochBlob = epochBlob))
        )

    fun inspect(scanned: String): Result<PairingCandidate> = runCatching {
        val payload = PairingDeepLinks.extractPayload(scanned)
        val verified = decodePayload(payload)
        val card = verified.card
        val epoch = verified.epochBlob?.let {
            KeyEpochs.verify(it, pinnedIdentitySpki = card.identityPublicKey)
        }
        val status = when {
            verified.epochBlob == null -> KeyEpochStatus.ABSENT
            epoch == null -> KeyEpochStatus.INVALID
            else -> KeyEpochStatus.VERIFIED
        }
        PairingCandidate(
            payload = payload,
            displayName = card.displayName,
            platform = card.platform,
            clientId = card.clientId,
            safetyNumber = card.clientId.value,
            identityKeyFingerprint = fingerprint(card.identityPublicKey),
            epoch = epoch?.epoch ?: 0,
            operationalKeyFingerprint = epoch?.let { fingerprint(it.operationalSigningKey) }.orEmpty(),
            hpkeKeyFingerprint = epoch?.let { fingerprint(it.hpkePublicKey) }.orEmpty(),
            keyEpochStatus = status,
        )
    }

    fun decode(scanned: String): Result<VerifiedPairingDelivery> = runCatching {
        decodePayload(PairingDeepLinks.extractPayload(scanned))
    }

    private fun decodePayload(payload: String): VerifiedPairingDelivery = verifyPayload(payload).also {
        require(it.card.clientId != selfId) { "cannot pair with self" }
    }

    companion object {
        /** Verify the signed identity independently of whether it is our own or a peer's CARD. */
        internal fun verifyPayload(payload: String): VerifiedPairingDelivery {
            require(payload.length <= PairingDeepLinks.MAX_INPUT_CHARS) { "Pairing CARD is too large" }
            val raw = Base64.getUrlDecoder().decode(payload.trim())
            val delivery = runCatching { ProtocolCodec.decodeFromCbor<CardDelivery>(raw) }.getOrNull()
            val cardBlob = if (delivery != null) {
                requireNotNull(delivery.card) { "pairing payload carries no client card" }
            } else {
                ProtocolCodec.decodeFromCbor<SignedBlob>(raw)
            }
            require(cardBlob.typ == SignedType.CLIENT_CARD) { "not a client card" }
            val card = cardBlob.decode<ClientCard>()
            require(card.clientId == cardBlob.signerId) { "card id does not match signer" }
            require(
                IdentityVerifier.verifyBound(cardBlob.signerId, card.identityPublicKey, cardBlob.payload, cardBlob.sig)
            ) { "card signature invalid" }
            return VerifiedPairingDelivery(cardBlob, card, delivery?.epochBlob)
        }
    }

    private fun fingerprint(key: ByteArray, bytes: Int = 8): String =
        MessageDigest.getInstance("SHA-256").digest(key).copyOf(bytes)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
}
