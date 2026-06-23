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
import net.extrawdw.apps.notisync.crypto.KeyFingerprint
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs
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

/**
 * Whether the scanned payload's key-epoch passed signature verification — drives what the trust dialog shows.
 * VERIFIED: a valid epoch, show its key signatures. ABSENT: a bare card with no epoch (benign — keys sync
 * later). INVALID: an epoch was present but its signature did not verify against the card's identity — a
 * tampered QR; its (forged) key signatures must NOT be shown.
 */
enum class KeyEpochStatus { VERIFIED, ABSENT, INVALID }

data class PairingCandidate(
    val payload: String,
    val displayName: String,
    val platform: String,
    val clientId: ClientId,
    val safetyNumber: String,
    val identityKeyFingerprint: String,
    /** NS2 operational key (rotatable): its current epoch + the operational signing key + HPKE keyset, all
     *  delegated by the immutable identity key. Shown grouped, so the user sees what the identity authorized.
     *  Populated only when [keyEpochStatus] is VERIFIED. */
    val epoch: Int,
    val operationalKeyFingerprint: String,
    val hpkeKeyFingerprint: String,
    val keyEpochStatus: KeyEpochStatus,
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

    /**
     * This device's pairing payload (base64url of CBOR([CardDelivery])) for display as a QR. The optical
     * channel carries BOTH self-authenticating blobs at once — the client card (identity anchor + current
     * HPKE keyset + profile, for the trust prompt) and the [ClientKeyEpoch] key-epoch (operational keys) —
     * so a freshly paired peer is immediately sealable with no broker round-trip.
     */
    fun myPayload(): String = urlEncoder.encodeToString(
        ProtocolCodec.encodeToCbor(
            CardDelivery(
                clientId = graph.identity.clientId,
                card = graph.buildClientCardBlob(),
                // Strip the key-epoch's identity anchor for the QR only: the card alongside carries it, so the
                // scanner verifies the key-epoch against the card's identity. Shrinks the code; the full
                // self-contained key-epoch is pulled from the broker afterward (so it stays relayable).
                epochBlob = graph.buildClientKeyEpochBlob(stripIdentity = true),
            ),
        ),
    )

    /** This device's pairing deep link for display as a QR. */
    fun myLink(): String = PairingDeepLinks.create(myPayload())

    /** Verify a scanned peer payload/link and return displayable details before the user trusts it. */
    fun inspect(scanned: String): Result<PairingCandidate> = runCatching {
        val payload = PairingDeepLinks.extractPayload(scanned)
        val verified = decodeVerifiedDelivery(payload)
        val card = verified.card
        // The operational layer (epoch + signing key + HPKE keyset) comes from the key-epoch, which the card no
        // longer carries. VERIFY its signature against the just-verified card's identity (the pairing QR strips
        // the epoch's own identity anchor, so it can't self-verify) BEFORE surfacing any of its key signatures —
        // a present-but-unverifiable epoch is a tampered QR, and its forged operational/HPKE keys must never be
        // shown as if the identity had authorized them. Blank/0 unless the epoch is present and verifies.
        val ke = verified.epochBlob?.let { KeyEpochs.verify(it, pinnedIdentitySpki = card.identityPublicKey) }
        val keyEpochStatus = when {
            verified.epochBlob == null -> KeyEpochStatus.ABSENT
            ke == null -> KeyEpochStatus.INVALID
            else -> KeyEpochStatus.VERIFIED
        }
        PairingCandidate(
            payload = payload,
            displayName = card.displayName,
            platform = card.platform,
            clientId = card.clientId,
            safetyNumber = card.clientId.value,
            identityKeyFingerprint = KeyFingerprint.short(card.identityPublicKey),
            epoch = ke?.epoch ?: 0,
            operationalKeyFingerprint = ke?.let { KeyFingerprint.short(it.operationalSigningKey) } ?: "",
            hpkeKeyFingerprint = ke?.let { KeyFingerprint.short(it.hpkePublicKeyset) } ?: "",
            keyEpochStatus = keyEpochStatus,
        )
    }

    /**
     * Accept a scanned peer payload: verify the card, pin its keys + key-epoch, and trust it (local optical
     * add). [ownDevice] = false adds an "other" device — someone else's, tracked in a private contact list
     * that syncs across your own devices but exchanges no notifications.
     */
    fun accept(scanned: String, ownDevice: Boolean = true): Result<ClientCard> = runCatching {
        val verified = decodeVerifiedDelivery(PairingDeepLinks.extractPayload(scanned))
        require(graph.trust.addLocal(verified.cardBlob, System.currentTimeMillis(), ownDevice)) { "card verification failed" }
        // Apply the peer's key-epoch (verified standalone, pinned to the just-pinned identity) so the peer is
        // sealable at once — its operational + current HPKE keys come from here, not the card.
        verified.epochBlob?.let { graph.trust.applyKeyEpoch(verified.card.clientId, it) }
        graph.activityLog.add(ActivityEvent.Kind.PAIRED, graph.activityText.pairedTitle(), verified.card.displayName, System.currentTimeMillis())
        // Make sure our own key-epoch is published so the new peer (and broker) can resolve us.
        graph.scope.launch { runCatching { graph.transport.publishKeyEpoch(graph.buildClientKeyEpochBlob()) } }
        // Tell our own devices about the new device (own or other) so the shared roster + its keys converge.
        graph.broadcastTrust()
        verified.card
    }

    /**
     * Decode a scanned pairing payload into its verified card + (optional) key-epoch. The payload is a
     * [CardDelivery]; a bare client-card [SignedBlob] (no key-epoch) is also accepted for resilience —
     * then the peer becomes sealable only once its key-epoch converges via pull/push.
     */
    private fun decodeVerifiedDelivery(payload: String): VerifiedDelivery {
        val raw = urlDecoder.decode(payload.trim())
        val delivery = runCatching { ProtocolCodec.decodeFromCbor<CardDelivery>(raw) }.getOrNull()
            ?: CardDelivery(graph.identity.clientId, card = ProtocolCodec.decodeFromCbor<SignedBlob>(raw))
        val cardBlob = requireNotNull(delivery.card) { "pairing payload carries no client card" }
        require(cardBlob.typ == SignedType.CLIENT_CARD) { "not a client card" }
        val card = cardBlob.decode<ClientCard>()
        require(card.clientId == cardBlob.signerId) { "card id does not match signer" }
        require(card.clientId != graph.identity.clientId) { "cannot pair with self" }
        require(
            IdentityVerifier.verifyBound(cardBlob.signerId, card.identityPublicKey, cardBlob.payload, cardBlob.sig)
        ) { "card signature invalid" }
        return VerifiedDelivery(cardBlob, card, delivery.epochBlob)
    }

    private data class VerifiedDelivery(val cardBlob: SignedBlob, val card: ClientCard, val epochBlob: SignedBlob?)
}
