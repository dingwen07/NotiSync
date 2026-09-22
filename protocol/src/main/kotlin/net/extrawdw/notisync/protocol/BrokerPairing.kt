package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.cbor.ByteString

/** A separate, bounded rendezvous channel. The broker never receives the QR secret or plaintext CARD. */
object BrokerPairing {
    const val VERSION = 1
    const val PATH = "/v2/pairing"
    const val DEFAULT_BROKER = "https://notisync-api-v2.extrawdw.net"
    const val SESSION_MILLIS = 180_000L
    const val EXCHANGE_MILLIS = 30_000L
    const val MAX_FRAME_BYTES = 32 * 1024
    const val MAX_CARD_BYTES = 16 * 1024
    const val EXCHANGE_FRAMES = 4 // J-PAKE rounds 1, 2, 3, then one encrypted CARD delivery.

    fun validSessionId(value: String): Boolean =
        value.length == 22 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
}

@Serializable
data class PairingRelayRequest(
    @CborLabel(0) @EncodeDefault val version: Int = BrokerPairing.VERSION,
    @CborLabel(1) val sessionId: String? = null,
)

@Serializable
data class PairingRelayReady(
    @CborLabel(0) @EncodeDefault val version: Int = BrokerPairing.VERSION,
    @CborLabel(1) val sessionId: String,
)

/** Integers use canonical signed BigInteger bytes (including the possibly negative round-3 MAC). */
@Serializable
data class PairingPakeFrame(
    @CborLabel(0) val round: Int,
    @CborLabel(1) val participantId: String,
    @CborLabel(2) val values: List<ByteArray>,
)

@Serializable
data class PairingEncryptedCard(
    @CborLabel(0) @ByteString val nonce: ByteArray,
    @CborLabel(1) @ByteString val ciphertext: ByteArray,
)
