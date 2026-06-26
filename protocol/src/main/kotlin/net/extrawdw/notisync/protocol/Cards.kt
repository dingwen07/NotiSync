package net.extrawdw.notisync.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/** Capabilities a client advertises, so heterogeneous platforms can participate differently. */
@Serializable
enum class Capability {
    CAPTURE,                // can listen for and forward local notifications
    DISPLAY,                // can render mirrored notifications
    DISMISS_SYNC,           // can sync dismissals
    PROVIDE_ASSETS,         // can supply public app assets on request
    BACKGROUND_WAKE,        // can receive background wake events (e.g. FCM data messages)
    FOREGROUND_CONNECTION,  // can hold a live foreground connection (e.g. WebSocket)
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
    val suite: String = CipherSuite.CURRENT_ID,
    val clientId: ClientId,
    @ByteString val identityPublicKey: ByteArray,   // X.509 SPKI, EC P-256 — the anchor (constant across epochs)
    val epoch: Int,                                  // ≥ 1, strictly monotonic per clientId
    @ByteString val operationalSigningKey: ByteArray, // X.509 SPKI, EC P-256 — the rotatable hot-path key
    @ByteString val hpkePublicKey: ByteArray,        // raw 32-byte X25519 public key (legacy peers: Tink keyset > 32 B)
    val purposes: List<Purpose>,
    val notBefore: Long,
    val notAfter: Long,
    val minEpoch: Int,
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
    val suite: String = CipherSuite.CURRENT_ID,
    val clientId: ClientId,
    @ByteString val identityPublicKey: ByteArray,   // X.509 SubjectPublicKeyInfo, EC P-256
    val displayName: String,
    val platform: String,
    val capabilities: List<Capability>,
    val createdAt: Long,
)
