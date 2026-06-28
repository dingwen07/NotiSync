package net.extrawdw.apps.notisync.assets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.domain.AssetResolver
import net.extrawdw.apps.notisync.domain.ResolveResult
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.crypto.AssetAead
import net.extrawdw.notisync.protocol.crypto.AssetHash
import java.io.File
import java.util.Base64

/** Provider-side upload bookkeeping for one asset: opaque server id, per-asset key, role/mime, last upload. */
@Serializable
data class AssetTicket(
    val assetId: String,
    val assetKeyB64: String,
    val role: AssetRole,
    val mimeType: String,
    val lastUploadedAt: Long,
)

/** Persists the assetHash -> [AssetTicket] map as JSON under [baseDir], guarded by a [Mutex]. */
class TicketStore(baseDir: File) {
    private val file = File(baseDir.apply { mkdirs() }, "tickets.json")
    private val mutex = Mutex()
    private val map: MutableMap<String, AssetTicket> = runCatching {
        ProtocolCodec.decodeFromJson<Map<String, AssetTicket>>(file.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    suspend fun get(assetHash: String): AssetTicket? = mutex.withLock { map[assetHash] }

    suspend fun put(assetHash: String, ticket: AssetTicket): Unit = mutex.withLock {
        map[assetHash] = ticket
        runCatching { file.writeText(ProtocolCodec.encodeToJson(map)) }
        Unit
    }
}

/**
 * Orchestrates private-asset transfer for both roles of the same app:
 *  - provider: [ensureUploaded] caches the plaintext, (re)uploads an AEAD blob under an opaque
 *    `(sourceClientId, assetId)`, and returns the body-only [PrivateAssetRef] to embed in the body;
 *  - consumer: [ensureLocal] fetches/decrypts/verifies any missing refs into the local cache.
 *
 * An `assetKey` is bound 1:1 to a plaintext (its `assetHash`) via the ticket, so a key is never
 * reused for different bytes. Reuse of the same `assetId` across messages is the common path; a
 * blob is re-uploaded only once its server TTL has likely lapsed (or on repair — a later phase).
 */
class AssetManager(
    private val transport: Transport,
    private val cache: AssetCache,
    private val tickets: TicketStore,
    private val assetTtlMillis: Long = DEFAULT_ASSET_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) : AssetResolver {

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** Returns a ref to embed in the notification body, or null if the blob couldn't be uploaded. */
    suspend fun ensureUploaded(
        plaintext: ByteArray,
        role: AssetRole,
        mimeType: String,
        sourceClientId: ClientId,
    ): PrivateAssetRef? {
        val assetHash = AssetHash.of(plaintext)
        cache.write(assetHash, plaintext) // keep locally so we can answer a future repair request
        val ticket = tickets.get(assetHash)
        val freshlyUploaded = ticket != null && now() - ticket.lastUploadedAt < assetTtlMillis
        val assetId = ticket?.assetId ?: AssetAead.generateAssetId()
        val assetKey = ticket?.let { b64d.decode(it.assetKeyB64) } ?: AssetAead.generateAssetKey()
        val ref = PrivateAssetRef(
            role,
            assetHash,
            mimeType,
            plaintext.size,
            sourceClientId,
            assetId,
            assetKey
        )

        if (!freshlyUploaded) {
            val sealed = AssetAead.seal(ref, plaintext)
            if (!transport.uploadPrivateAsset(sourceClientId, assetId, sealed)) return null
            tickets.put(
                assetHash,
                AssetTicket(assetId, b64e.encodeToString(assetKey), role, mimeType, now())
            )
        }
        return ref
    }

    override suspend fun ensureLocal(refs: List<PrivateAssetRef>): ResolveResult {
        var newlyAvailable = false
        val stillMissing = ArrayList<PrivateAssetRef>()
        for (ref in refs) {
            if (cache.has(ref.assetHash)) continue
            val sealed = transport.fetchPrivateAsset(ref.sourceClientId, ref.assetId)
            val plaintext = sealed?.let { runCatching { AssetAead.open(ref, it) }.getOrNull() }
            if (plaintext == null || !AssetHash.matches(
                    plaintext,
                    ref.assetHash
                )
            ) { // missing / wrong key / corrupt / substituted
                stillMissing.add(ref)
                continue
            }
            cache.write(ref.assetHash, plaintext)
            newlyAvailable = true
        }
        return ResolveResult(newlyAvailable, stillMissing)
    }

    /** Provider repair: re-seal the cached plaintext under its existing id and re-upload it. */
    override suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef? {
        val plaintext = cache.read(assetHash) ?: return null
        val ticket = tickets.get(assetHash) ?: return null
        val ref = PrivateAssetRef(
            ticket.role,
            assetHash,
            ticket.mimeType,
            plaintext.size,
            sourceClientId,
            ticket.assetId,
            b64d.decode(ticket.assetKeyB64)
        )
        val sealed = AssetAead.seal(ref, plaintext)
        if (!transport.uploadPrivateAsset(sourceClientId, ticket.assetId, sealed)) return null
        tickets.put(assetHash, ticket.copy(lastUploadedAt = now()))
        return ref
    }

    private companion object {
        const val DEFAULT_ASSET_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
