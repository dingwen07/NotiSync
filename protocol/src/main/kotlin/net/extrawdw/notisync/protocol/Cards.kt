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

/** Reserved for future group-administration hierarchy. v1 issues everything as MEMBER. */
@Serializable
enum class MemberRole { MEMBER, ADMIN }

/**
 * A client's public identity, distributed (signed) to the group. The [clientId] is the fingerprint
 * of [identityPublicKey]; [hpkePublicKeyset] is the X25519 HPKE public keyset used to seal
 * per-recipient payload keys to this client.
 */
@Serializable
data class ClientCard(
    val suite: String = CipherSuite.CURRENT_ID,
    val clientId: ClientId,
    @ByteString val identityPublicKey: ByteArray,   // X.509 SubjectPublicKeyInfo, EC P-256
    @ByteString val hpkePublicKeyset: ByteArray,     // serialized Tink public keyset (HPKE X25519)
    val displayName: String,
    val platform: String,
    val capabilities: List<Capability>,
    val createdAt: Long,
)

/**
 * Signed proof that a member belongs to a group, issued by an existing trusted member during
 * pairing. Peers accept any membership card signed by a member they already trust. Distributed
 * via data sync so the whole group converges on the same roster.
 */
@Serializable
data class MembershipCard(
    val suite: String = CipherSuite.CURRENT_ID,
    val groupId: GroupId,
    val memberClientId: ClientId,
    @ByteString val memberIdentityPublicKey: ByteArray,
    val role: MemberRole = MemberRole.MEMBER,
    val issuerClientId: ClientId,
    val keyEpoch: Int,
    val issuedAt: Long,
)

/** Signed statement removing a member from the group (lost/stolen device, manual removal). */
@Serializable
data class RevocationCard(
    val suite: String = CipherSuite.CURRENT_ID,
    val groupId: GroupId,
    val revokedClientId: ClientId,
    val issuerClientId: ClientId,
    val keyEpoch: Int,
    val issuedAt: Long,
)
