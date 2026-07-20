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
        // NS2's compact wire contract uses stable integer labels on class fields. Keeping classes as
        // maps (rather than positional arrays) lets an older reader skip a future numbered field.
        preferCborLabelsOverNames = true
        // A definite-length map/array saves the trailing break byte and gives one deterministic shape.
        useDefiniteLengthEncoding = true
        // Omit fields equal to their default, shrinking the wire and the inline-push budget (FCM/APNs).
        // Safe across versions: every field has a default, so a missing key decodes back to it, and
        // ignoreUnknownKeys absorbs the reverse — old and new peers interoperate (mixed fleet). The two
        // structs re-encoded on both ends for crypto ([EnvelopeAuth], [AssetAad]) carry NO defaults, so
        // their signature/AAD bytes are identical regardless of this flag.
        // INVARIANT: never give a nullable field a non-null default (an absent key would be ambiguous).
        // Version/suite discriminators that must outlive a suite bump are pinned with @EncodeDefault(ALWAYS).
        encodeDefaults = false
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
