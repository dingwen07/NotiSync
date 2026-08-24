package net.extrawdw.apps.notisync.pairing

import java.util.Base64
import net.extrawdw.apps.notisync.nfc.NotiSyncNfcProtocol

/** Converts the existing pairing text representation at the boundary of the binary NFC transport. */
internal object PairingNfcPayloadCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun decode(encodedPayload: String): ByteArray {
        require(encodedPayload.length in 2..MAX_ENCODED_PAYLOAD_CHARS) {
            "pairing payload is too large for NFC"
        }
        val wirePayload = runCatching { decoder.decode(encodedPayload) }
            .getOrElse { throw IllegalArgumentException("pairing payload is not base64url", it) }
        requireWirePayload(wirePayload)
        require(encoder.encodeToString(wirePayload) == encodedPayload) {
            "pairing payload is not canonical unpadded base64url"
        }
        return wirePayload
    }

    fun encode(wirePayload: ByteArray): String {
        requireWirePayload(wirePayload)
        return encoder.encodeToString(wirePayload)
    }

    private fun requireWirePayload(wirePayload: ByteArray) {
        require(wirePayload.size in 1..NotiSyncNfcProtocol.MAX_PAYLOAD_BYTES) {
            "pairing payload is too large for NFC"
        }
    }

    private const val MAX_ENCODED_PAYLOAD_CHARS =
        (NotiSyncNfcProtocol.MAX_PAYLOAD_BYTES * 4 + 2) / 3
}
