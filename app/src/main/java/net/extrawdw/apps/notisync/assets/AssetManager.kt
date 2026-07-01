package net.extrawdw.apps.notisync.assets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.analytics.perfTrace
import net.extrawdw.apps.notisync.domain.AssetResolver
import net.extrawdw.apps.notisync.domain.AssetTrigger
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

    /** Returns a ref to embed in the notification body, or null if the blob couldn't be uploaded. Traced as
     *  `asset_upload`: `seal_ms`/`upload_ms` are recorded only on a real upload — a within-TTL dedup skips
     *  both and reports `result=deduped`, so the network cost isn't diluted by the common re-reference path. */
    suspend fun ensureUploaded(
        plaintext: ByteArray,
        role: AssetRole,
        mimeType: String,
        sourceClientId: ClientId,
    ): PrivateAssetRef? = perfTrace("asset_upload") { span ->
        span.attr("role", role.name.lowercase())
        span.metric("bytes", plaintext.size.toLong())
        val assetHash = AssetHash.of(plaintext)
        val cacheStartNanos = System.nanoTime()
        cache.write(assetHash, plaintext) // keep locally so we can answer a future repair request
        span.metric("cache_write_ms", (System.nanoTime() - cacheStartNanos) / 1_000_000)
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

        if (freshlyUploaded) {
            span.attr("result", "deduped") // still within TTL — the broker already holds it; skip the network
            return@perfTrace ref
        }
        val sealStartNanos = System.nanoTime()
        val sealed = AssetAead.seal(ref, plaintext)
        span.metric("seal_ms", (System.nanoTime() - sealStartNanos) / 1_000_000)
        val uploadStartNanos = System.nanoTime()
        val uploaded = transport.uploadPrivateAsset(sourceClientId, assetId, sealed)
        span.metric("upload_ms", (System.nanoTime() - uploadStartNanos) / 1_000_000)
        if (!uploaded) {
            span.attr("result", "failed")
            return@perfTrace null
        }
        tickets.put(
            assetHash,
            AssetTicket(assetId, b64e.encodeToString(assetKey), role, mimeType, now())
        )
        span.attr("result", "uploaded")
        ref
    }

    override suspend fun ensureLocal(
        refs: List<PrivateAssetRef>,
        trigger: AssetTrigger,
    ): ResolveResult =
        // Trace EVERY resolve (incl. all-cached) so cache-hit rate is visible via `cached_count`. `fetched_ms`
        // is set ONLY when a real fetch ran — Firebase aggregates a metric only over the samples that report
        // it, so cache-hit no-ops don't dilute the fetch-latency average, and a mixed batch times only its
        // fetched refs. `trigger` separates initial delivery from a post-repair fetch.
        perfTrace("asset_resolve") { span ->
            span.attr("trigger", trigger.name.lowercase())
            var newlyAvailable = false
            var fetchedBytes = 0L
            var cachedCount = 0
            var fetchedCount = 0
            var fetchedNanos = 0L
            val stillMissing = ArrayList<PrivateAssetRef>()
            for (ref in refs) {
                if (cache.has(ref.assetHash)) {
                    cachedCount++
                    continue
                }
                fetchedCount++
                val startNanos = System.nanoTime()
                val plaintext = transport.fetchPrivateAsset(ref.sourceClientId, ref.assetId)
                    ?.let { runCatching { AssetAead.open(ref, it) }.getOrNull() }
                    ?.takeIf { AssetHash.matches(it, ref.assetHash) }
                fetchedNanos += System.nanoTime() - startNanos
                if (plaintext == null) { // missing / wrong key / corrupt / substituted
                    stillMissing.add(ref)
                    continue
                }
                cache.write(ref.assetHash, plaintext)
                newlyAvailable = true
                fetchedBytes += plaintext.size
            }
            span.metric("asset_count", refs.size.toLong())
            span.metric("cached_count", cachedCount.toLong())
            span.metric("fetched_count", fetchedCount.toLong())
            if (fetchedCount > 0) span.metric("fetched_ms", fetchedNanos / 1_000_000)
            span.metric("missing_count", stillMissing.size.toLong())
            span.metric("bytes", fetchedBytes)
            span.attr(
                "result",
                when {
                    fetchedCount == 0 -> "cache_hit"
                    stillMissing.isEmpty() -> "fetched"
                    newlyAvailable -> "partial"
                    else -> "miss"
                }
            )
            ResolveResult(newlyAvailable, stillMissing)
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
