package net.extrawdw.notisync.protocol

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json

/**
 * Canonical serializers for the NotiSync protocol.
 *
 * CBOR is the wire + signing format for E2E payloads and signed cards. Encoding is deterministic
 * for a given class and config (fields are emitted in declaration order), which is what signature
 * verification relies on — and signatures are always verified over the *exact bytes* that were
 * transmitted (see [SignedBlob]), never over a re-encode, so we never depend on byte-for-byte
 * canonicalization across library versions.
 *
 * JSON is used only for the broker's REST control plane and dev/debug logging.
 */
object ProtocolCodec {
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encodeToCbor(value: T): ByteArray = cbor.encodeToByteArray(value)
    inline fun <reified T> decodeFromCbor(bytes: ByteArray): T = cbor.decodeFromByteArray(bytes)

    inline fun <reified T> encodeToJson(value: T): String = json.encodeToString(value)
    inline fun <reified T> decodeFromJson(text: String): T = json.decodeFromString(text)
}
