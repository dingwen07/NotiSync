package net.extrawdw.apps.notisync.assets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.domain.AssetResolver
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.crypto.AssetAead
import net.extrawdw.notisync.protocol.crypto.AssetHash
import java.io.File
import java.util.Base64

/** Provider-side upload bookkeeping for one asset: its opaque server id, per-asset key, last upload. */
@Serializable
data class AssetTicket(val assetId: String, val assetKeyB64: String, val lastUploadedAt: Long)

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
        val ref = PrivateAssetRef(role, assetHash, mimeType, plaintext.size, sourceClientId, assetId, assetKey)

        if (!freshlyUploaded) {
            val sealed = AssetAead.seal(ref, plaintext)
            if (!transport.uploadPrivateAsset(sourceClientId, assetId, sealed)) return null
            tickets.put(assetHash, AssetTicket(assetId, b64e.encodeToString(assetKey), now()))
        }
        return ref
    }

    override suspend fun ensureLocal(refs: List<PrivateAssetRef>): Boolean {
        var newlyAvailable = false
        for (ref in refs) {
            if (cache.has(ref.assetHash)) continue
            val sealed = transport.fetchPrivateAsset(ref.sourceClientId, ref.assetId) ?: continue
            val plaintext = runCatching { AssetAead.open(ref, sealed) }.getOrNull() ?: continue
            if (!AssetHash.matches(plaintext, ref.assetHash)) continue // wrong key / corrupt / substituted
            cache.write(ref.assetHash, plaintext)
            newlyAvailable = true
        }
        return newlyAvailable
    }

    private companion object {
        const val DEFAULT_ASSET_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
