package net.extrawdw.apps.notisync.data.incomingfilter

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow

/** Bounds owned by the canonical filter representation and mirrored by the target DAO. */
object IncomingFilterLimits {
    const val MAX_REQUESTER_ID_CHARS = 256
    const val MAX_APP_ID_CHARS = 512
    const val MAX_APP_ID_BYTES = 2_048
    const val MAX_CHANNEL_ID_CHARS = 256
    const val MAX_CHANNEL_ID_BYTES = 1_024
    const val MAX_RULES = 512
    const val DIGEST_BYTES = IncomingFilterCanonicalizer.DIGEST_BYTES
}

/** The capture origin targeted by an incoming suppression rule. */
enum class IncomingFilterOrigin {
    ANDROID_LOCAL,
    IOS_ANCS,
}

/** A fixed-width SHA-256 identity. The mutable byte array never crosses this boundary. */
class IncomingFilterDigest private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == IncomingFilterLimits.DIGEST_BYTES) { "filter digest must be SHA-256" }
    }

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is IncomingFilterDigest && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "IncomingFilterDigest(SHA-256)"

    companion object {
        fun of(bytes: ByteArray): IncomingFilterDigest = IncomingFilterDigest(bytes)
    }
}

/** One semantic filter tuple, before canonical set ordering and digest assignment. */
class IncomingFilterRuleSpec(
    val origin: IncomingFilterOrigin,
    val appId: String? = null,
    val channelId: String? = null,
) {
    init {
        toCanonicalValue().requireCanonicalFilterValue()
    }

    internal fun toCanonicalValue(): IncomingFilterRuleValue = IncomingFilterRuleValue(
        origin = when (origin) {
            IncomingFilterOrigin.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
            IncomingFilterOrigin.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
        },
        appId = appId,
        channelId = channelId,
    )

    override fun equals(other: Any?): Boolean =
        other is IncomingFilterRuleSpec &&
            origin == other.origin && appId == other.appId && channelId == other.channelId

    override fun hashCode(): Int = 31 * (31 * origin.hashCode() + (appId?.hashCode() ?: 0)) +
        (channelId?.hashCode() ?: 0)

    override fun toString(): String =
        "IncomingFilterRuleSpec(origin=$origin, appId=$appId, channelId=$channelId)"
}

/** A complete authenticated replacement command. The list is copied at construction. */
class IncomingFilterUpdate(
    val requesterClientId: String,
    val updatedAt: Long,
    val receivedAt: Long,
    rules: List<IncomingFilterRuleSpec>,
) {
    val rules: List<IncomingFilterRuleSpec> = rules.toList()

    init {
        requireRequesterId(requesterClientId)
        require(updatedAt > 0) { "filter update time must be positive" }
        require(receivedAt > 0) { "filter receive time must be positive" }
        require(this.rules.size <= IncomingFilterLimits.MAX_RULES) { "incoming filter has too many rules" }
    }

    fun copy(
        requesterClientId: String = this.requesterClientId,
        updatedAt: Long = this.updatedAt,
        receivedAt: Long = this.receivedAt,
        rules: List<IncomingFilterRuleSpec> = this.rules,
    ): IncomingFilterUpdate = IncomingFilterUpdate(requesterClientId, updatedAt, receivedAt, rules)
}

/** One canonical, persisted rule. Its digest is the canonical rule identity, not payload data. */
class IncomingFilterRule internal constructor(
    val position: Int,
    val origin: IncomingFilterOrigin,
    val appId: String?,
    val channelId: String?,
    digest: IncomingFilterDigest,
) {
    val digest: IncomingFilterDigest = digest

    init {
        require(position >= 0) { "filter rule position must not be negative" }
        IncomingFilterRuleSpec(origin, appId, channelId)
    }

    override fun equals(other: Any?): Boolean =
        other is IncomingFilterRule &&
            position == other.position && origin == other.origin && appId == other.appId &&
            channelId == other.channelId && digest == other.digest

    override fun hashCode(): Int {
        var result = position
        result = 31 * result + origin.hashCode()
        result = 31 * result + (appId?.hashCode() ?: 0)
        result = 31 * result + (channelId?.hashCode() ?: 0)
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String =
        "IncomingFilterRule(position=$position, origin=$origin, appId=$appId, channelId=$channelId, digest=$digest)"
}

/** A transactionally consistent header plus canonical child rules. */
class IncomingFilterSnapshot internal constructor(
    val requesterClientId: String,
    val canonicalizationVersion: Int,
    val updatedAt: Long,
    val receivedAt: Long,
    val ruleSetDigest: IncomingFilterDigest,
    rules: List<IncomingFilterRule>,
) {
    val rules: List<IncomingFilterRule> = rules.toList()

    init {
        requireRequesterId(requesterClientId)
        require(canonicalizationVersion == IncomingFilterCanonicalizer.VERSION) {
            "unsupported incoming-filter canonicalization version"
        }
        require(updatedAt > 0) { "filter update time must be positive" }
        require(receivedAt > 0) { "filter receive time must be positive" }
        require(this.rules.size <= IncomingFilterLimits.MAX_RULES) { "incoming filter has too many rules" }
        require(this.rules.indices.all { index -> this.rules[index].position == index }) {
            "filter rules are not in deterministic order"
        }
        val canonical = IncomingFilterCanonicalizer.canonicalize(
            this.rules.map { rule ->
                IncomingFilterRuleValue(
                    origin = rule.origin.toCanonicalOrigin(),
                    appId = rule.appId,
                    channelId = rule.channelId,
                )
            },
        )
        require(canonical.rules.size == this.rules.size) { "filter rules are not canonically deduplicated" }
        canonical.rules.forEachIndexed { index, canonicalRule ->
            val actual = this.rules[index]
            require(canonicalRule.position == actual.position) { "filter rule position is not canonical" }
            require(canonicalRule.value.appId == actual.appId && canonicalRule.value.channelId == actual.channelId) {
                "filter rule canonical projection mismatch"
            }
            require(canonicalRule.value.origin == actual.origin.toCanonicalOrigin()) {
                "filter rule origin projection mismatch"
            }
            require(canonicalRule.digestCopy().contentEquals(actual.digest.copyBytes())) {
                "filter rule digest mismatch"
            }
        }
        require(canonical.digestCopy().contentEquals(ruleSetDigest.copyBytes())) {
            "filter set digest mismatch"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is IncomingFilterSnapshot &&
            requesterClientId == other.requesterClientId &&
            canonicalizationVersion == other.canonicalizationVersion &&
            updatedAt == other.updatedAt && receivedAt == other.receivedAt &&
            ruleSetDigest == other.ruleSetDigest && rules == other.rules

    override fun hashCode(): Int {
        var result = requesterClientId.hashCode()
        result = 31 * result + canonicalizationVersion
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + ruleSetDigest.hashCode()
        result = 31 * result + rules.hashCode()
        return result
    }

    override fun toString(): String =
        "IncomingFilterSnapshot(requesterClientId=$requesterClientId, canonicalizationVersion=$canonicalizationVersion, " +
            "updatedAt=$updatedAt, receivedAt=$receivedAt, ruleSetDigest=$ruleSetDigest, ruleCount=${rules.size})"
}

/** Exact result of one Room-owned full-snapshot replacement. */
enum class IncomingFilterReplaceResult {
    INSERTED,
    REPLACED,
    UNCHANGED,
    STALE,
    CONFLICT,
}

/** Result of applying a Room emission to the in-memory projection. */
enum class IncomingFilterProjectionResult {
    APPLIED,
    UNCHANGED,
    STALE,
    CONFLICT,
}

/**
 * Immutable hot-path view of all filters. Every state transition is fenced by `(updatedAt, digest)`;
 * delayed Flow emissions cannot replace a newer snapshot or re-add a removed one.
 */
class IncomingFilterProjection internal constructor() {
    private data class Fence(val updatedAt: Long, val digest: IncomingFilterDigest)

    private data class State(
        val snapshots: Map<String, IncomingFilterSnapshot> = emptyMap(),
        val fences: Map<String, Fence> = emptyMap(),
    )

    private val state = AtomicReference(State())

    fun filterFor(requesterClientId: String): IncomingFilterSnapshot? {
        requireRequesterId(requesterClientId)
        return state.get().snapshots[requesterClientId]
    }

    /** Returns a detached immutable list in stable requester order. */
    fun snapshots(): List<IncomingFilterSnapshot> = state.get().snapshots
        .toSortedMap()
        .values
        .toList()

    /**
     * Computes the requester ids to exclude for a capture. [appId] and [channelId] are primitive
     * projections supplied by the protocol/domain coordinator; no protocol type is imported here.
     */
    fun recipientsToExclude(
        origin: IncomingFilterOrigin,
        appId: String?,
        channelId: String?,
    ): Set<String> {
        val normalizedAppId = appId.normalizeMatchValue()
        val normalizedChannelId = channelId.normalizeMatchValue()
        val current = state.get().snapshots
        if (current.isEmpty()) return emptySet()
        return current.entries
            .asSequence()
            .filter { (_, snapshot) ->
                snapshot.rules.any { rule ->
                    rule.origin == origin && when {
                        rule.appId == null -> true
                        rule.appId != normalizedAppId -> false
                        rule.channelId == null -> true
                        else -> rule.channelId == normalizedChannelId
                    }
                }
            }
            .mapTo(linkedSetOf()) { (requesterClientId, _) -> requesterClientId }
    }

    /** The old store preferred a nonblank iOS bundle id, then a nonblank Android package name. */
    fun recipientsToExclude(
        origin: IncomingFilterOrigin,
        packageName: String?,
        iosBundleId: String?,
        channelId: String?,
    ): Set<String> = recipientsToExclude(
        origin = origin,
        appId = applicationIdentifier(packageName, iosBundleId),
        channelId = channelId,
    )

    internal fun accept(snapshot: IncomingFilterSnapshot): IncomingFilterProjectionResult =
        acceptInternal(snapshot, restoreTombstone = false)

    /**
     * Applies a snapshot known to have been committed by this repository's owner write.
     *
     * A delete intentionally leaves a same-version tombstone so a delayed Flow emission cannot
     * resurrect the row.  A later owner write may legitimately insert that exact identity again;
     * only this post-commit path is allowed to clear the tombstone.  Ordinary Flow/application
     * emissions continue to use [accept] and therefore cannot perform that resurrection.
     */
    internal fun acceptOwnerWrite(snapshot: IncomingFilterSnapshot): IncomingFilterProjectionResult =
        acceptInternal(snapshot, restoreTombstone = true)

    private fun acceptInternal(
        snapshot: IncomingFilterSnapshot,
        restoreTombstone: Boolean,
    ): IncomingFilterProjectionResult {
        while (true) {
            val current = state.get()
            val fence = current.fences[snapshot.requesterClientId]
            if (fence != null) {
                when {
                    snapshot.updatedAt < fence.updatedAt -> return IncomingFilterProjectionResult.STALE
                    snapshot.updatedAt == fence.updatedAt && snapshot.ruleSetDigest != fence.digest ->
                        return IncomingFilterProjectionResult.CONFLICT
                    snapshot.updatedAt == fence.updatedAt && snapshot.ruleSetDigest == fence.digest -> {
                        if (current.snapshots.containsKey(snapshot.requesterClientId)) {
                            // Same version/digest with a different local-receipt timestamp is still the
                            // same authenticated filter identity. Keep the existing state and fence.
                            return IncomingFilterProjectionResult.UNCHANGED
                        }
                        if (!restoreTombstone) return IncomingFilterProjectionResult.UNCHANGED
                        val next = State(
                            snapshots = current.snapshots + (snapshot.requesterClientId to snapshot),
                            fences = current.fences,
                        )
                        if (state.compareAndSet(current, next)) return IncomingFilterProjectionResult.APPLIED
                    }
                }
            }
            val next = State(
                snapshots = current.snapshots + (snapshot.requesterClientId to snapshot),
                fences = current.fences +
                    (snapshot.requesterClientId to Fence(snapshot.updatedAt, snapshot.ruleSetDigest)),
            )
            if (state.compareAndSet(current, next)) return IncomingFilterProjectionResult.APPLIED
        }
    }

    /**
     * Removes a row and leaves a tombstone fence. [removedSnapshot] is the exact row observed immediately
     * before the Room delete; it prevents a delayed pre-delete emission from resurrecting the projection.
     */
    internal fun remove(
        requesterClientId: String,
        removedSnapshot: IncomingFilterSnapshot?,
    ): IncomingFilterProjectionResult {
        requireRequesterId(requesterClientId)
        while (true) {
            val current = state.get()
            val existingFence = current.fences[requesterClientId]
            val candidateFence = removedSnapshot?.let { Fence(it.updatedAt, it.ruleSetDigest) }
            val nextFence = when {
                existingFence == null -> candidateFence
                candidateFence == null -> existingFence
                existingFence.updatedAt > candidateFence.updatedAt -> existingFence
                existingFence.updatedAt < candidateFence.updatedAt -> candidateFence
                existingFence.digest == candidateFence.digest -> existingFence
                else -> existingFence
            }
            val next = State(
                snapshots = current.snapshots - requesterClientId,
                fences = if (nextFence == null) current.fences - requesterClientId
                else current.fences + (requesterClientId to nextFence),
            )
            if (state.compareAndSet(current, next)) {
                return if (current.snapshots.containsKey(requesterClientId)) {
                    IncomingFilterProjectionResult.APPLIED
                } else {
                    IncomingFilterProjectionResult.UNCHANGED
                }
            }
        }
    }

    companion object {
        fun applicationIdentifier(packageName: String?, iosBundleId: String?): String? =
            iosBundleId.normalizeMatchValue() ?: packageName.normalizeMatchValue()
    }
}

/** Storage-independent owner of the inbound filter aggregate. */
interface IncomingFilterRepository {
    val projection: IncomingFilterProjection

    fun observe(requesterClientId: String): Flow<IncomingFilterSnapshot?>

    fun observeAll(): Flow<List<IncomingFilterSnapshot>>

    suspend fun read(requesterClientId: String): IncomingFilterSnapshot?

    suspend fun replace(update: IncomingFilterUpdate): IncomingFilterReplaceResult

    suspend fun remove(requesterClientId: String): Boolean

    /** Suspends until the first complete aggregate emission has populated the hot-path projection. */
    suspend fun awaitProjectionHydrated()
}

private fun IncomingFilterOrigin.toCanonicalOrigin(): CanonicalIncomingFilterOrigin = when (this) {
    IncomingFilterOrigin.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
    IncomingFilterOrigin.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
}

private fun IncomingFilterRuleValue.requireCanonicalFilterValue() {
    // Calling the shared canonicalizer keeps this model on the one reviewed v1 validation path.
    IncomingFilterCanonicalizer.canonicalize(listOf(this))
}

private fun requireRequesterId(value: String) {
    require(value.isNotBlank()) { "filter requester id must not be blank" }
    require(value.length <= IncomingFilterLimits.MAX_REQUESTER_ID_CHARS) {
        "filter requester id is too long"
    }
    require(value.none(Char::isISOControl)) { "filter requester id contains a control character" }
}

private fun String?.normalizeMatchValue(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
