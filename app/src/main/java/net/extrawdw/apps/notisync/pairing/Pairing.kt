package net.extrawdw.apps.notisync.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.peer.pairing.PairingPayloadCodec
import kotlinx.coroutines.launch

/** QR encode/decode helpers. */
object QrCodes {
    fun encode(content: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 10,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val moduleSize = ((size + matrix.width - 1) / matrix.width).coerceAtLeast(1)
        val bitmapSize = matrix.width * moduleSize
        val bmp = createBitmap(bitmapSize, bitmapSize)
        val pixels = IntArray(bitmapSize * bitmapSize)
        for (y in 0 until bitmapSize) {
            val matrixY = y / moduleSize
            val rowOffset = y * bitmapSize
            for (x in 0 until bitmapSize) {
                pixels[rowOffset + x] =
                    if (matrix[x / moduleSize, matrixY]) Color.BLACK else Color.WHITE
            }
        }
        bmp.setPixels(pixels, 0, bitmapSize, 0, 0, bitmapSize, bitmapSize)
        return bmp
    }
}

/**
 * Pairing via mutual QR exchange of self-signed client cards. The QR carries only public key
 * material; the optical channel is the trust anchor (no relay can substitute keys), and the
 * clientId fingerprint is the human-verifiable safety number. Each device scans the other's QR and
 * adds it as a trusted peer.
 */
class PairingManager(private val graph: AppGraph) {

    private val payloadCodec = PairingPayloadCodec(graph.identity.clientId)

    /**
     * This device's pairing payload (base64url of CBOR([CardDelivery])) for display as a QR. The optical
     * channel carries BOTH self-authenticating blobs at once — the client card (identity anchor + current
     * profile, for the trust prompt) and the [ClientKeyEpoch] key-epoch (operational keys) —
     * so a freshly paired peer is immediately sealable with no broker round-trip.
     */
    internal fun myLink(): PairingLink {
        val generatedCard = graph.generateClientCard()
        val payload = payloadCodec.encode(
            card = generatedCard.blob,
            // Strip the key-epoch's identity anchor for the QR only: the card alongside carries it, so the
            // scanner verifies the key-epoch against the card's identity. Shrinks the code; the full
            // self-contained key-epoch is pulled from the broker afterward (so it stays relayable).
            epochBlob = graph.buildClientKeyEpochBlob(stripIdentity = true),
        )
        return PairingLink(
            url = PairingDeepLinks.create(payload),
            automaticTimeEnabled = generatedCard.automaticTimeEnabled,
            createdAt = generatedCard.createdAt,
            timeZoneId = generatedCard.timeZoneId,
        )
    }

    /** Verify a scanned peer payload/link and return displayable details before the user trusts it. */
    fun inspect(scanned: String): Result<PairingCandidate> = payloadCodec.inspect(scanned)

    /**
     * Accept a scanned peer payload: verify the card, pin its keys + key-epoch, and trust it (local optical
     * add). [ownDevice] = false adds an "other" device — someone else's, tracked in a private contact list
     * that syncs across your own devices but exchanges no notifications.
     */
    fun accept(scanned: String, ownDevice: Boolean = true): Result<ClientCard> = runCatching {
        val verified = payloadCodec.decode(scanned).getOrThrow()
        require(
            graph.trust.addLocal(
                verified.cardBlob,
                System.currentTimeMillis(),
                ownDevice
            )
        ) { "card verification failed" }
        // Apply the peer's key-epoch (verified standalone, pinned to the just-pinned identity) so the peer is
        // sealable at once — its operational + current HPKE keys come from here, not the card.
        verified.epochBlob?.let { graph.trust.applyKeyEpoch(verified.card.clientId, it) }
        graph.activityLog.add(
            ActivityEvent.Kind.PAIRED,
            graph.activityText.pairedTitle(),
            verified.card.displayName,
            System.currentTimeMillis()
        )
        // Make sure our own key-epoch is published so the new peer (and broker) can resolve us.
        graph.scope.launch { runCatching { graph.transport.publishKeyEpoch(graph.buildClientKeyEpochBlob()) } }
        // Tell our own devices about the new device (own or other) so the shared roster + its keys converge.
        graph.broadcastTrust()
        verified.card
    }

}

/** Pairing URL and the exact system-clock context of the self card embedded in it. */
internal data class PairingLink(
    val url: String,
    val automaticTimeEnabled: Boolean?,
    val createdAt: Long,
    val timeZoneId: String,
)
