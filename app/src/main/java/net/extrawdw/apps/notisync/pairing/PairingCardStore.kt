package net.extrawdw.apps.notisync.pairing

import android.content.Context
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier

/**
 * Device-protected, no-backup cache of the last locally generated public pairing card.
 * Shared by QR, links, NFC, and peer CARD repair.
 *
 * HCE can start the process without an Activity and must answer its first APDU immediately. [preload] is
 * therefore called from Application.onCreate; HostApduService reads only the volatile memory snapshot.
 */
internal object PairingCardStore {
    @Volatile
    private var cachedPayload: CachedPayload? = null

    @Volatile
    private var loaded = false

    @Synchronized
    fun preload(context: Context) {
        if (loaded) return
        cachedPayload = pairingPreferences(context).getString(KEY_OUTGOING_PAYLOAD, null)?.let { encoded ->
            runCatching {
                CachedPayload(encoded, PairingNfcPayloadCodec.decode(encoded))
            }.getOrNull()
        }
        loaded = true
    }

    /** Canonical Base64URL form used by QR, links, NDEF, and the existing pairing verifier. */
    fun current(): String? = cachedPayload?.encoded

    /** Read-only decoded snapshot used directly by the memory-only HCE hot path. */
    fun currentWirePayload(): ByteArray? = cachedPayload?.wire

    /** Reuse the signed CARD for peer repair without another hardware-backed card signature. */
    fun currentCard(selfId: ClientId): SignedBlob? = decodeCachedPairingCard(cachedPayload?.wire, selfId)

    /** Called off-main when a new signed card is generated. */
    fun persist(context: Context, payload: String) {
        val decoded = PairingNfcPayloadCodec.decode(payload)
        check(pairingPreferences(context).edit().putString(KEY_OUTGOING_PAYLOAD, payload).commit()) {
            "could not persist the NFC pairing card"
        }
        cachedPayload = CachedPayload(payload, decoded)
        loaded = true
    }

    private data class CachedPayload(val encoded: String, val wire: ByteArray)

    private const val KEY_OUTGOING_PAYLOAD = "outgoing_payload"
}

/** Extract our cached pairing CARD for peer repair; verifies in software without signing again. */
internal fun decodeCachedPairingCard(wirePayload: ByteArray?, selfId: ClientId): SignedBlob? {
    if (wirePayload == null) return null
    return runCatching {
        val delivery = ProtocolCodec.decodeFromCbor<CardDelivery>(wirePayload)
        require(delivery.clientId == selfId)
        val blob = requireNotNull(delivery.card)
        require(blob.typ == SignedType.CLIENT_CARD && blob.signerId == selfId)
        val card = blob.decode<ClientCard>()
        require(card.clientId == selfId)
        require(IdentityVerifier.verifyBound(selfId, card.identityPublicKey, blob.payload, blob.sig))
        blob
    }.getOrNull()
}

internal fun pairingPreferences(context: Context) =
    context.createDeviceProtectedStorageContext()
        // Keep the existing file name so installed apps retain their cached card and NFC inbox.
        .getSharedPreferences("notisync_pairing_nfc", Context.MODE_PRIVATE)
