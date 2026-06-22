package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TransportType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.Base64

private val b64e: Base64.Encoder = Base64.getEncoder()
private val b64d: Base64.Decoder = Base64.getDecoder()

class CardStore(private val db: NotiSyncDb) {
    suspend fun put(clientId: ClientId, signedBlob: ByteArray) = db.tx {
        Cards.upsert {
            it[Cards.clientId] = clientId.value
            it[signedBlobB64] = b64e.encodeToString(signedBlob)
            it[updatedAt] = System.currentTimeMillis()
        }
        Unit
    }

    suspend fun getSignedBlob(clientId: ClientId): ByteArray? = db.tx {
        Cards.selectAll().where { Cards.clientId eq clientId.value }
            .firstOrNull()?.let { b64d.decode(it[Cards.signedBlobB64]) }
    }
}

data class StoredRoute(
    val clientId: ClientId,
    val transport: TransportType,
    val routeRef: String,
    val epoch: Int,
    val signedBlob: ByteArray,
)

class RouteStore(private val db: NotiSyncDb) {
    /** Replace any existing route of the same transport for this client (keeping the latest epoch). */
    suspend fun put(route: StoredRoute) = db.tx {
        val existingEpoch = Routes.selectAll()
            .where { (Routes.clientId eq route.clientId.value) and (Routes.transport eq route.transport.name) }
            .maxOfOrNull { it[Routes.epoch] }
        // Only reject strictly-older claims; an equal-epoch re-publish refreshes a rotated/stale token
        // (e.g. after a reinstall, where the client's epoch counter resets to 1).
        if (existingEpoch != null && existingEpoch > route.epoch) return@tx
        Routes.deleteWhere { (Routes.clientId eq route.clientId.value) and (Routes.transport eq route.transport.name) }
        Routes.insert {
            it[clientId] = route.clientId.value
            it[transport] = route.transport.name
            it[routeRef] = route.routeRef
            it[epoch] = route.epoch
            it[state] = "AVAILABLE"
            it[signedBlobB64] = b64e.encodeToString(route.signedBlob)
            it[updatedAt] = System.currentTimeMillis()
        }
        Unit
    }

    suspend fun routesFor(clientId: ClientId): List<StoredRoute> = db.tx {
        Routes.selectAll().where { Routes.clientId eq clientId.value }.map {
            StoredRoute(
                clientId = ClientId(it[Routes.clientId]),
                transport = TransportType.valueOf(it[Routes.transport]),
                routeRef = it[Routes.routeRef],
                epoch = it[Routes.epoch],
                signedBlob = b64d.decode(it[Routes.signedBlobB64]),
            )
        }
    }

    suspend fun invalidate(clientId: ClientId, transport: TransportType) = db.tx {
        Routes.deleteWhere { (Routes.clientId eq clientId.value) and (Routes.transport eq transport.name) }
        Unit
    }
}

data class RelayItem(val id: Long, val messageId: String, val envelope: ByteArray, val urgency: String)

class RelayStore(private val db: NotiSyncDb) {
    suspend fun add(recipientId: ClientId, messageId: String, envelope: ByteArray, urgency: String, expiresAt: Long) = db.tx {
        Relay.insert {
            it[Relay.recipientId] = recipientId.value
            it[Relay.messageId] = messageId
            it[envelopeB64] = b64e.encodeToString(envelope)
            it[Relay.urgency] = urgency
            it[createdAt] = System.currentTimeMillis()
            it[Relay.expiresAt] = expiresAt
        }
        Unit
    }

    suspend fun pending(recipientId: ClientId): List<RelayItem> = db.tx {
        Relay.selectAll().where { Relay.recipientId eq recipientId.value }.map {
            RelayItem(it[Relay.id], it[Relay.messageId], b64d.decode(it[Relay.envelopeB64]), it[Relay.urgency])
        }
    }

    /** The single queued envelope for ([recipientId], [messageId]), or null — the FCM-wake pull path. */
    suspend fun getByMessage(recipientId: ClientId, messageId: String): ByteArray? = db.tx {
        Relay.selectAll().where { (Relay.recipientId eq recipientId.value) and (Relay.messageId eq messageId) }
            .firstOrNull()?.let { b64d.decode(it[Relay.envelopeB64]) }
    }

    /** Just the message ids queued for [recipientId] — the background-drain backstop lists then pulls each. */
    suspend fun pendingMessageIds(recipientId: ClientId): List<String> = db.tx {
        Relay.selectAll().where { Relay.recipientId eq recipientId.value }.map { it[Relay.messageId] }
    }

    suspend fun ack(id: Long) = db.tx {
        Relay.deleteWhere { Relay.id eq id }
        Unit
    }

    suspend fun ackByMessage(recipientId: ClientId, messageId: String) = db.tx {
        Relay.deleteWhere { (Relay.recipientId eq recipientId.value) and (Relay.messageId eq messageId) }
        Unit
    }

    suspend fun purgeExpired(now: Long): Int = db.tx {
        Relay.deleteWhere { Relay.expiresAt less now }
    }
}

class PrivateAssetStore(private val db: NotiSyncDb) {
    /**
     * Store [ciphertext] under ([sourceClientId], [assetId]). Returns false if that key already
     * exists — overwrite-reject (the id is unguessable random, so first-writer-wins is safe and
     * keeps an in-flight asset from being clobbered). INSERT OR IGNORE → [insertedCount] is 0 on a
     * primary-key conflict.
     */
    suspend fun putIfAbsent(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray, expiresAt: Long): Boolean = db.tx {
        PrivateAssets.insertIgnore {
            it[PrivateAssets.sourceClientId] = sourceClientId.value
            it[PrivateAssets.assetId] = assetId
            it[dataB64] = b64e.encodeToString(ciphertext)
            it[sizeBytes] = ciphertext.size
            it[createdAt] = System.currentTimeMillis()
            it[PrivateAssets.expiresAt] = expiresAt
        }.insertedCount > 0
    }

    suspend fun get(sourceClientId: ClientId, assetId: String): ByteArray? = db.tx {
        PrivateAssets.selectAll()
            .where { (PrivateAssets.sourceClientId eq sourceClientId.value) and (PrivateAssets.assetId eq assetId) }
            .firstOrNull()?.let { b64d.decode(it[PrivateAssets.dataB64]) }
    }

    suspend fun purgeExpired(now: Long): Int = db.tx {
        PrivateAssets.deleteWhere { PrivateAssets.expiresAt less now }
    }
}
