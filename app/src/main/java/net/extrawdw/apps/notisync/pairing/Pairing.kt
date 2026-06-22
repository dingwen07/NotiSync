package net.extrawdw.apps.notisync.pairing

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import kotlinx.coroutines.launch
import java.util.Base64
import java.security.MessageDigest

/** QR encode/decode helpers. */
object QrCodes {
    fun encode(content: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = createBitmap(size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }
}

/** Deep links carried by pairing QR codes. Legacy raw payloads are still accepted when scanned. */
object PairingDeepLinks {
    const val HTTPS_SCHEME = "https"
    const val HTTPS_HOST = "notisync.apps.extrawdw.net"
    const val CUSTOM_SCHEME = "notisync"
    const val CUSTOM_HOST = "pair"
    const val PAIRING_PATH = "/pair"
    const val PARAM_PAYLOAD = "payload"

    fun create(payload: String): String =
        Uri.Builder()
            .scheme(HTTPS_SCHEME)
            .authority(HTTPS_HOST)
            .path(PAIRING_PATH)
            .appendQueryParameter(PARAM_PAYLOAD, payload)
            .build()
            .toString()

    fun payloadFrom(uri: Uri?): String? {
        if (uri == null || !isPairingUri(uri)) return null
        return uri.getQueryParameter(PARAM_PAYLOAD)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun extractPayload(content: String): String {
        val trimmed = content.trim()
        val uri = runCatching { trimmed.toUri() }.getOrNull()
        if (uri != null && isPairingUri(uri)) {
            return payloadFrom(uri) ?: error("pairing link missing payload")
        }
        return trimmed
    }

    private fun isPairingUri(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        return when {
            scheme.equals(CUSTOM_SCHEME, ignoreCase = true) -> uri.host.equals(CUSTOM_HOST, ignoreCase = true)
            scheme.equals(HTTPS_SCHEME, ignoreCase = true) ->
                uri.host.equals(HTTPS_HOST, ignoreCase = true) && uri.path == PAIRING_PATH
            else -> false
        }
    }
}

data class PairingCandidate(
    val payload: String,
    val displayName: String,
    val platform: String,
    val clientId: ClientId,
    val safetyNumber: String,
    val identityKeyFingerprint: String,
    val hpkeKeyFingerprint: String,
)

/**
 * Pairing via mutual QR exchange of self-signed client cards. The QR carries only public key
 * material; the optical channel is the trust anchor (no relay can substitute keys), and the
 * clientId fingerprint is the human-verifiable safety number. Each device scans the other's QR and
 * adds it as a trusted peer.
 */
class PairingManager(private val graph: AppGraph) {

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** This device's pairing payload (base64url of CBOR(signed client card)) for display as a QR. */
    fun myPayload(): String = urlEncoder.encodeToString(ProtocolCodec.encodeToCbor(graph.buildClientCardBlob()))

    /** This device's pairing deep link for display as a QR. */
    fun myLink(): String = PairingDeepLinks.create(myPayload())

    /** Verify a scanned peer payload/link and return displayable details before the user trusts it. */
    fun inspect(scanned: String): Result<PairingCandidate> = runCatching {
        val payload = PairingDeepLinks.extractPayload(scanned)
        val card = decodeVerifiedClientCard(payload).card
        PairingCandidate(
            payload = payload,
            displayName = card.displayName,
            platform = card.platform,
            clientId = card.clientId,
            safetyNumber = card.clientId.value,
            identityKeyFingerprint = fingerprint(card.identityPublicKey),
            hpkeKeyFingerprint = fingerprint(card.hpkePublicKeyset),
        )
    }

    /**
     * Accept a scanned peer payload: verify the card, pin its keys, and trust it (local optical add).
     * [ownDevice] = false adds an "other" device — someone else's, tracked in a private contact list that
     * syncs across your own devices but exchanges no notifications.
     */
    fun accept(scanned: String, ownDevice: Boolean = true): Result<ClientCard> = runCatching {
        val verified = decodeVerifiedClientCard(PairingDeepLinks.extractPayload(scanned))
        require(graph.trust.addLocal(verified.blob, System.currentTimeMillis(), ownDevice)) { "card verification failed" }
        graph.activityLog.add(ActivityEvent.Kind.PAIRED, graph.activityText.pairedTitle(), verified.card.displayName, System.currentTimeMillis())
        // Make sure our own card is published so the new peer (and broker) can resolve us.
        graph.scope.launch { runCatching { graph.transport.publishCard(graph.buildClientCardBlob()) } }
        // Tell our own devices about the new device (own or other) so the shared roster + its card converge.
        graph.broadcastTrust()
        verified.card
    }

    private fun decodeVerifiedClientCard(payload: String): VerifiedClientCard {
        val blob = ProtocolCodec.decodeFromCbor<SignedBlob>(urlDecoder.decode(payload.trim()))
        require(blob.typ == SignedType.CLIENT_CARD) { "not a client card" }
        val card = blob.decode<ClientCard>()
        require(card.clientId == blob.signerId) { "card id does not match signer" }
        require(card.clientId != graph.identity.clientId) { "cannot pair with self" }
        require(
            IdentityVerifier.verifyBound(blob.signerId, card.identityPublicKey, blob.payload, blob.sig)
        ) { "card signature invalid" }
        return VerifiedClientCard(blob, card)
    }

    private fun fingerprint(bytes: ByteArray): String =
        Base32.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
            .take(32)
            .chunked(4)
            .joinToString(" ")

    private data class VerifiedClientCard(val blob: SignedBlob, val card: ClientCard)
}
