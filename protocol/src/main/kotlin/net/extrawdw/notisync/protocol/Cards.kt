package net.extrawdw.notisync.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.ALWAYS
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.CborDecoder
import kotlinx.serialization.cbor.CborEncoder
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Capabilities a client advertises, so heterogeneous platforms can participate differently. */
@Serializable
enum class Capability {
    CAPTURE,                // can originate/post notifications, from OS capture or a synthetic source
    DISPLAY,                // can render mirrored notifications
    DISMISS_SYNC,           // can sync dismissals
    PROVIDE_ASSETS,         // can supply public app assets on request
    BACKGROUND_WAKE,        // can receive background wake events (e.g. FCM data messages)
    FOREGROUND_CONNECTION,  // can hold a live foreground connection (e.g. WebSocket)
    CAPABILITY_ROUTING_V1,  // capability declaration is complete; senders may stop platform-based routing
    PUSH_FILTERING,         // can inspect and suppress/demote a push before user-visible presentation
    DISPLAY_NOTIFICATION_UPDATES, // can apply stable-id, silent/quiet notification replacements
    DISPLAY_ANDROID_GROUP_SUMMARIES, // can consume Android group-summary render-control captures
    PUBLISH_RUNS,           // can publish and host Runs, including handling Run controls
    RECEIVE_RUNS,           // can consume Run state/results and issue Run controls
}

/**
 * Compact, forward-compatible CBOR representation for a complete capability declaration.
 *
 * Capability names remain the JSON and Swift-facing representation. CBOR uses explicit integer ids;
 * they are a permanent wire registry and therefore deliberately do not use enum ordinals. A reader
 * silently discards ids it does not understand, while retaining and de-duplicating every known id in
 * first-seen order. Unknown capabilities consequently never grant routing behavior.
 */
@OptIn(ExperimentalSerializationApi::class)
object CapabilityListSerializer : KSerializer<List<Capability>> {
    private val named = ListSerializer(Capability.serializer())
    private val numbered = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = named.descriptor

    override fun serialize(encoder: Encoder, value: List<Capability>) {
        if (encoder is CborEncoder) {
            numbered.serialize(encoder, value.map { it.wireId() })
        } else {
            named.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): List<Capability> {
        if (decoder !is CborDecoder) return named.deserialize(decoder)

        val seen = mutableSetOf<Capability>()
        return numbered.deserialize(decoder).mapNotNull { id ->
            capabilityForWireId(id)?.takeIf(seen::add)
        }
    }

    private fun Capability.wireId(): Int = when (this) {
        Capability.CAPTURE -> 0
        Capability.DISPLAY -> 1
        Capability.DISMISS_SYNC -> 2
        Capability.PROVIDE_ASSETS -> 3
        Capability.BACKGROUND_WAKE -> 4
        Capability.FOREGROUND_CONNECTION -> 5
        Capability.CAPABILITY_ROUTING_V1 -> 6
        Capability.PUSH_FILTERING -> 7
        Capability.DISPLAY_NOTIFICATION_UPDATES -> 8
        Capability.DISPLAY_ANDROID_GROUP_SUMMARIES -> 9
        Capability.PUBLISH_RUNS -> 10
        Capability.RECEIVE_RUNS -> 11
    }

    private fun capabilityForWireId(id: Int): Capability? = when (id) {
        0 -> Capability.CAPTURE
        1 -> Capability.DISPLAY
        2 -> Capability.DISMISS_SYNC
        3 -> Capability.PROVIDE_ASSETS
        4 -> Capability.BACKGROUND_WAKE
        5 -> Capability.FOREGROUND_CONNECTION
        6 -> Capability.CAPABILITY_ROUTING_V1
        7 -> Capability.PUSH_FILTERING
        8 -> Capability.DISPLAY_NOTIFICATION_UPDATES
        9 -> Capability.DISPLAY_ANDROID_GROUP_SUMMARIES
        10 -> Capability.PUBLISH_RUNS
        11 -> Capability.RECEIVE_RUNS
        else -> null
    }
}

/** What an operational key in a [ClientKeyEpoch] is authorized for (NS2). */
@Serializable
enum class Purpose { ENVELOPE_SIGN, REQUEST_AUTH, HPKE_SEAL }

/**
 * NS2: a self-contained, individually identity-signed certificate of a client's OPERATIONAL keys for
 * one monotonic [epoch]. The identity key (the cold root) signs this, delegating the hot-path work to
 * a rotatable [operationalSigningKey] (TEE) and [hpkePublicKey]. It carries [identityPublicKey] so
 * it self-verifies ([clientId] == fingerprint(identityPublicKey)) and a peer that holds nothing about
 * this client can bootstrap the anchor from it alone. Wrapped in [SignedBlob] (typ = [SignedType.KEY_EPOCH],
 * signerId = clientId, sig = identity key) and stored/served by the broker; it is the unit rotation
 * distributes. [minEpoch] is a floor assertion (reject any epoch below it); [notBefore]/[notAfter]
 * bound the operational keys' validity window for staged rotation.
 */
@Serializable
data class ClientKeyEpoch(
    @CborLabel(0) @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
    @CborLabel(1) val clientId: ClientId,
    @CborLabel(2) @ByteString val identityPublicKey: ByteArray,   // X.509 SPKI, EC P-256 — the anchor (constant across epochs)
    @CborLabel(3) val epoch: Int,                                  // ≥ 1, strictly monotonic per clientId
    @CborLabel(4) @ByteString val operationalSigningKey: ByteArray, // X.509 SPKI, EC P-256 — the rotatable hot-path key
    @CborLabel(5) @ByteString val hpkePublicKey: ByteArray,        // raw 32-byte X25519 public key (legacy peers: Tink keyset > 32 B)
    @CborLabel(6) val purposes: List<Purpose>,
    @CborLabel(7) val notBefore: Long,
    @CborLabel(8) val notAfter: Long,
    @CborLabel(9) val minEpoch: Int,
)

/**
 * NS2 pairing / first-contact bundle: a client's identity anchor + human profile, identity-signed and
 * shown over the QR (optical, off-broker). The [clientId] is the fingerprint of [identityPublicKey].
 *
 * It carries NO key material beyond the identity anchor — the operational signing key and the (rotating)
 * HPKE keyset live in the self-contained [ClientKeyEpoch], which travels alongside the card in the pairing
 * [CardDelivery] and is the authoritative source for sealing. Keeping the HPKE keyset out of the card
 * removes a redundant copy (it was already in the key-epoch), shrinking the pairing QR, and keeps the card
 * pure identity + profile (data minimization — it never reaches the broker).
 */
@Serializable
data class ClientCard(
    @CborLabel(0) @EncodeDefault(ALWAYS) val suite: String = CipherSuite.CURRENT_ID,
    @CborLabel(1) val clientId: ClientId,
    @CborLabel(2) @ByteString val identityPublicKey: ByteArray,   // X.509 SubjectPublicKeyInfo, EC P-256
    @CborLabel(3) val displayName: String,
    @CborLabel(4) val platform: String,
    @CborLabel(5) @Serializable(with = CapabilityListSerializer::class) val capabilities: List<Capability>,
    @CborLabel(6) val createdAt: Long,
)
