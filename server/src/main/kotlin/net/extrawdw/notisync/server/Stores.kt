package net.extrawdw.notisync.server

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TransportType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.Base64

private val b64e: Base64.Encoder = Base64.getEncoder()
private val b64d: Base64.Decoder = Base64.getDecoder()

/** A stored NS2 key-epoch: the verbatim identity-signed blob plus the columns the broker gates on. */
data class StoredEpoch(
    val clientId: ClientId,
    val epoch: Int,
    val minEpoch: Int,
    val notBefore: Long,
    val notAfter: Long,
    val signedBlob: ByteArray,
)

class EpochStore(private val db: NotiSyncDb) {
    /**
     * Upsert a key-epoch, enforcing the monotonic downgrade floor: reject any epoch below the highest
     * [minEpoch] this client has ever asserted (a replayed retired epoch), or a self-inconsistent bundle
     * (epoch < its own minEpoch, or < 1). An equal-epoch re-publish is idempotent (PK upsert). Returns
     * whether it was accepted. NOTE: this best-effort broker floor is NOT the security floor — the
     * authoritative anti-rollback floor is the client's identity-signed trust store (Phase 4+).
     */
    suspend fun put(e: StoredEpoch): Boolean = db.tx {
        if (e.epoch < 1 || e.epoch < e.minEpoch) return@tx false
        val floor = Epochs.selectAll().where { Epochs.clientId eq e.clientId.value }
            .maxOfOrNull { it[Epochs.minEpoch] } ?: 0
        // The floor is monotonic non-decreasing: reject a below-floor epoch AND a below-floor minEpoch.
        // The latter stops a later (or equal-epoch idempotent) bundle from asserting a STALE minEpoch that
        // would lower the floor — a replayed/forged-but-validly-signed downgrade.
        if (e.epoch < floor || e.minEpoch < floor) return@tx false
        Epochs.upsert {
            it[clientId] = e.clientId.value
            it[epoch] = e.epoch
            it[minEpoch] = e.minEpoch
            it[notBefore] = e.notBefore
            it[notAfter] = e.notAfter
            it[signedBlobB64] = b64e.encodeToString(e.signedBlob)
            it[updatedAt] = System.currentTimeMillis()
        }
        true
    }

    suspend fun get(clientId: ClientId, epoch: Int): StoredEpoch? = db.tx {
        Epochs.selectAll().where { (Epochs.clientId eq clientId.value) and (Epochs.epoch eq epoch) }
            .firstOrNull()?.let(::toStoredEpoch)
    }

    /** The highest-epoch row for this client (its current key-epoch), or null. */
    suspend fun latest(clientId: ClientId): StoredEpoch? = db.tx {
        Epochs.selectAll().where { Epochs.clientId eq clientId.value }
            .maxByOrNull { it[Epochs.epoch] }?.let(::toStoredEpoch)
    }

    /** The downgrade floor: the highest [minEpoch] this client has asserted (0 if none). */
    suspend fun floor(clientId: ClientId): Int = db.tx {
        Epochs.selectAll().where { Epochs.clientId eq clientId.value }
            .maxOfOrNull { it[Epochs.minEpoch] } ?: 0
    }

    /**
     * Fetch ([clientId], [epoch]) AND evaluate the floor in ONE transaction, returning the row only when
     * `epoch >= floor`. Resolving both reads atomically closes the read-after-read gap a separate
     * floor()-then-get() pair leaves open: a concurrent floor-raising upload committing between the two
     * reads could otherwise let a now-below-floor epoch authenticate. Also halves the per-auth DB work —
     * one scan of the client's rows instead of two.
     */
    suspend fun getIfAtOrAboveFloor(clientId: ClientId, epoch: Int): StoredEpoch? = db.tx {
        val rows = Epochs.selectAll().where { Epochs.clientId eq clientId.value }.toList()
        val floor = rows.maxOfOrNull { it[Epochs.minEpoch] } ?: 0
        if (epoch < floor) return@tx null
        rows.firstOrNull { it[Epochs.epoch] == epoch }?.let(::toStoredEpoch)
    }

    /**
     * Drop key-epochs whose validity ended more than [retentionGrace] ago (kept that long past [notAfter]
     * so a peer can still pull a retired key-epoch to verify an in-flight relayed envelope). NEVER drops a
     * client's highest epoch — that preserves the current operational key and the [floor]. Returns the
     * number removed. The cache is small (a personal mesh), so a full scan keyed by (clientId, epoch) is fine.
     */
    suspend fun purgeExpired(now: Long, retentionGrace: Long): Int = db.tx {
        val cutoff = now - retentionGrace
        val rows = Epochs.selectAll().toList()
        // Protect, per client, BOTH the highest-epoch row (current key) AND the highest-minEpoch row
        // (the one that defines floor()), so a GC of an expired non-latest row can never regress the floor.
        val protectedEpochs = rows.groupBy { it[Epochs.clientId] }.mapValues { (_, v) ->
            setOf(v.maxOf { it[Epochs.epoch] }, v.maxByOrNull { it[Epochs.minEpoch] }!![Epochs.epoch])
        }
        var removed = 0
        for (r in rows) {
            val cid = r[Epochs.clientId]
            val ep = r[Epochs.epoch]
            if (r[Epochs.notAfter] < cutoff && ep !in protectedEpochs.getValue(cid)) {
                removed += Epochs.deleteWhere { (Epochs.clientId eq cid) and (Epochs.epoch eq ep) }
            }
        }
        removed
    }

    private fun toStoredEpoch(it: org.jetbrains.exposed.v1.core.ResultRow) = StoredEpoch(
        clientId = ClientId(it[Epochs.clientId]),
        epoch = it[Epochs.epoch],
        minEpoch = it[Epochs.minEpoch],
        notBefore = it[Epochs.notBefore],
        notAfter = it[Epochs.notAfter],
        signedBlob = b64d.decode(it[Epochs.signedBlobB64]),
    )
}

data class StoredRoute(
    val clientId: ClientId,
    val transport: TransportType,
    val environment: RouteEnvironment,
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
            val blob = b64d.decode(it[Routes.signedBlobB64])
            StoredRoute(
                clientId = ClientId(it[Routes.clientId]),
                transport = TransportType.valueOf(it[Routes.transport]),
                environment = routeEnvironment(blob),
                routeRef = it[Routes.routeRef],
                epoch = it[Routes.epoch],
                signedBlob = blob,
            )
        }
    }

    suspend fun invalidate(clientId: ClientId, transport: TransportType) = db.tx {
        Routes.deleteWhere { (Routes.clientId eq clientId.value) and (Routes.transport eq transport.name) }
        Unit
    }

    private fun routeEnvironment(signedBlob: ByteArray): RouteEnvironment =
        runCatching {
            ProtocolCodec.decodeFromCbor<SignedBlob>(signedBlob).decode<RouteClaim>().environment
        }.getOrDefault(RouteEnvironment.PRODUCTION)
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

    /** Drop many of [recipientId]'s queued messages in ONE transaction — the batch-ack path. Chunked by
     *  [RelayAck.MAX_BATCH] so each `IN (...)` stays under SQLite's 999-variable limit no matter how many
     *  ids the (untrusted) client sends. */
    suspend fun ackManyByMessage(recipientId: ClientId, messageIds: Collection<String>): Int {
        if (messageIds.isEmpty()) return 0
        return db.tx {
            messageIds.chunked(RelayAck.MAX_BATCH).sumOf { chunk ->
                Relay.deleteWhere { (Relay.recipientId eq recipientId.value) and (Relay.messageId inList chunk) }
            }
        }
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
