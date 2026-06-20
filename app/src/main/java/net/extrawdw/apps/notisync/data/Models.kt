package net.extrawdw.apps.notisync.data

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

/**
 * A trusted peer device in the group. For v1 the group is simply "me + my peers"; peers are added
 * through QR pairing. Public key material is held base64-encoded for easy persistence/use.
 *
 * [identityPublicKeyB64] and [hpkePublicKeysetB64] are immutable trust anchors fixed at pairing. The
 * remaining profile fields ([displayName], [platform], [capabilities]) are mutable and converge via
 * DATA_SYNC profile updates; [profileUpdatedAt] is the source-clock stamp of the last applied one
 * (last-writer-wins). New fields default so previously-persisted peer JSON still decodes.
 */
@Serializable
data class Peer(
    val clientId: ClientId,
    val displayName: String,
    val platform: String,
    val identityPublicKeyB64: String,
    val hpkePublicKeysetB64: String,
    val addedAt: Long,
    val lastSeenAt: Long = 0L,
    val capabilities: List<Capability> = emptyList(),
    val profileUpdatedAt: Long = 0L,
)

/** A user-facing record for the Activity screen. Never contains plaintext notification content. */
@Serializable
data class ActivityEvent(
    val kind: Kind,
    val title: String,
    val detail: String,
    val timestamp: Long,
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
