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
import net.extrawdw.apps.notisync.data.Peer
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import kotlinx.coroutines.launch
import java.util.Base64

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

/**
 * Pairing via mutual QR exchange of self-signed client cards. The QR carries only public key
 * material; the optical channel is the trust anchor (no relay can substitute keys), and the
 * clientId fingerprint is the human-verifiable safety number. Each device scans the other's QR and
 * adds it as a trusted peer.
 */
class PairingManager(private val graph: AppGraph) {

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()
    private val encoder = Base64.getEncoder()

    /** This device's pairing payload (base64url of CBOR(signed client card)) for display as a QR. */
    fun myPayload(): String = urlEncoder.encodeToString(ProtocolCodec.encodeToCbor(graph.buildClientCardBlob()))

    /** Accept a scanned peer payload: verify the card and add the peer to the trusted group. */
    fun accept(scanned: String): Result<Peer> = runCatching {
        val blob = ProtocolCodec.decodeFromCbor<SignedBlob>(urlDecoder.decode(scanned.trim()))
        require(blob.typ == SignedType.CLIENT_CARD) { "not a client card" }
        val card = blob.decode<ClientCard>()
        require(card.clientId != graph.identity.clientId) { "cannot pair with self" }
        require(
            IdentityVerifier.verifyBound(blob.signerId, card.identityPublicKey, blob.payload, blob.sig)
        ) { "card signature invalid" }

        val peer = Peer(
            clientId = card.clientId,
            displayName = card.displayName,
            platform = card.platform,
            identityPublicKeyB64 = encoder.encodeToString(card.identityPublicKey),
            hpkePublicKeysetB64 = encoder.encodeToString(card.hpkePublicKeyset),
            addedAt = System.currentTimeMillis(),
        )
        graph.peers.add(peer)
        graph.activityLog.add(ActivityEvent.Kind.PAIRED, "Paired", card.displayName, System.currentTimeMillis())
        // Make sure our own card is published so the new peer (and broker) can resolve us.
        graph.scope.launch { runCatching { graph.transport.publishCard(graph.buildClientCardBlob()) } }
        peer
    }
}
