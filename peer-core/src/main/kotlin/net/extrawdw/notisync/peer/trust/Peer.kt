package net.extrawdw.notisync.peer.trust

import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId

/** A trusted, currently sealable peer assembled from a pinned card and operational key epoch. */
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
    val ownDevice: Boolean = true,
    val currentEpoch: Int = 0,
)
