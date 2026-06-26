package net.extrawdw.apps.notisync.data

import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

/**
 * A trusted peer device in the group. For v1 the group is simply "me + my peers"; peers are added
 * through QR pairing. Public key material is held base64-encoded for easy persistence/use.
 *
 * [identityPublicKeyB64] is the immutable identity anchor pinned at pairing (first-verified-wins). In
 * NS2 the OPERATIONAL key material rotates per epoch: [hpkePublicKeyB64] is the *current* epoch's HPKE
 * public key and [currentEpoch] names it (≥1 once a key-epoch is held; 0 = the NS1 identity-era single key).
 * A peer is sealable only once a current key-epoch is held — the directory binds [currentEpoch] into each
 * outbound [net.extrawdw.notisync.protocol.crypto.RecipientKey]. The remaining profile fields
 * ([displayName], [platform], [capabilities]) are mutable and converge via DATA_SYNC profile updates;
 * [profileUpdatedAt] is the source-clock stamp of the last applied one (last-writer-wins). New fields
 * default so previously-persisted peer JSON still decodes.
 */
@Serializable
data class Peer(
    val clientId: ClientId,
    val displayName: String,
    val platform: String,
    val identityPublicKeyB64: String,
    val hpkePublicKeyB64: String,
    val addedAt: Long,
    val lastSeenAt: Long = 0L,
    val capabilities: List<Capability> = emptyList(),
    val profileUpdatedAt: Long = 0L,
    /** One of the user's own devices (full mirroring) vs an "other" device in the synced private contact list. */
    val ownDevice: Boolean = true,
    /** NS2: the epoch of [hpkePublicKeyB64] — the recipient HPKE epoch a sender seals to. 0 = NS1-era. */
    val currentEpoch: Int = 0,
)

/** A user-facing record for the Activity screen. Never contains plaintext notification content. */
@Serializable
data class ActivityEvent(
    val kind: Kind,
    val title: String,
    val detail: String,
    val timestamp: Long,
    val deliveryMode: DeliveryMode? = null,
) {
    @Serializable
    enum class Kind { CAPTURED, SENT, RECEIVED, DISMISSED, PAIRED, ROUTE_REPAIR, ERROR }
}

/** Snapshot of permission / connectivity state for the diagnostics surface. */
data class DiagnosticsState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val transportConnected: Boolean = false,
    val fcmTokenRegistered: Boolean = false,
    val keyBacking: String = "unknown",
    val clientId: String = "",
)
