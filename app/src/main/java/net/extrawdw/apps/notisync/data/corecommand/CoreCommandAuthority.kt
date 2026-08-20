package net.extrawdw.apps.notisync.data.corecommand

import java.security.MessageDigest

internal enum class CoreCommandDurableOutcome {
    APPLIED,
    SUPERSEDED,
    TERMINAL_REJECTED,
}

internal enum class CoreCommandAuthorityRetryReason(val stableCode: String) {
    STALE_CORE_STATE("core_stale_state"),
    MISSING_IDENTITY("core_identity_missing"),
    CORE_NOT_READY("core_not_ready"),
}

/** Storage-independent, defensively owned Core Activity outbox projection. */
internal class CoreActivityProjection(
    val commandId: String,
    val eventId: String,
    val operationalGeneration: Long,
    val feature: String,
    val semanticAction: String,
    val direction: String,
    val outcome: String,
    val peerClientId: String?,
    val correlationId: String?,
    val deliveryMode: String?,
    val argsVersion: Int,
    renderArgs: ByteArray,
    val occurredAt: Long,
    val createdAt: Long,
) {
    private val storedRenderArgs = renderArgs.copyOf()
    fun renderArgsCopy(): ByteArray = storedRenderArgs.copyOf()

    init {
        requireCompactIdentifier(commandId, "Core Activity command id")
        requireCompactIdentifier(eventId, "Core Activity event id")
        require(operationalGeneration > 0) { "Core Activity generation must be positive" }
        require(feature.isNotBlank() && semanticAction.isNotBlank() && direction.isNotBlank() && outcome.isNotBlank()) {
            "Core Activity semantic tokens must not be blank"
        }
        require(argsVersion > 0) { "Core Activity argument version must be positive" }
        require(storedRenderArgs.size <= 4 * 1024) { "Core Activity arguments exceed the privacy-reviewed bound" }
        require(occurredAt > 0 && createdAt > 0) {
            "Core Activity timestamps are invalid"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is CoreActivityProjection &&
            commandId == other.commandId &&
            eventId == other.eventId &&
            operationalGeneration == other.operationalGeneration &&
            feature == other.feature &&
            semanticAction == other.semanticAction &&
            direction == other.direction &&
            outcome == other.outcome &&
            peerClientId == other.peerClientId &&
            correlationId == other.correlationId &&
            deliveryMode == other.deliveryMode &&
            argsVersion == other.argsVersion &&
            MessageDigest.isEqual(storedRenderArgs, other.storedRenderArgs) &&
            occurredAt == other.occurredAt &&
            createdAt == other.createdAt

    override fun hashCode(): Int {
        var result = commandId.hashCode()
        result = 31 * result + eventId.hashCode()
        result = 31 * result + operationalGeneration.hashCode()
        result = 31 * result + feature.hashCode()
        result = 31 * result + semanticAction.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + (peerClientId?.hashCode() ?: 0)
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + (deliveryMode?.hashCode() ?: 0)
        result = 31 * result + argsVersion
        result = 31 * result + storedRenderArgs.contentHashCode()
        result = 31 * result + occurredAt.hashCode()
        return 31 * result + createdAt.hashCode()
    }

    override fun toString(): String =
        "CoreActivityProjection(commandId=$commandId, eventId=$eventId, generation=$operationalGeneration, " +
            "feature=$feature, action=$semanticAction, outcome=$outcome, " +
            "renderArgs=<${storedRenderArgs.size} bytes>)"
}

/** Exact immutable authority receipt retained by Core. */
internal class CoreCommandReceiptEvidence(
    val commandId: String,
    val authenticatedRequestId: String,
    commandDigest: ByteArray,
    val commandType: CoreCommandKind,
    val outcome: CoreCommandDurableOutcome,
    val coreRevision: Long,
    val appliedAt: Long,
    val pendingActivity: CoreActivityProjection?,
) {
    private val storedCommandDigest = commandDigest.copyOf()
    fun commandDigestCopy(): ByteArray = storedCommandDigest.copyOf()

    init {
        requireCompactIdentifier(commandId, "Core receipt command id")
        requireCompactIdentifier(authenticatedRequestId, "Core receipt authenticated request id")
        require(storedCommandDigest.size == CoreCommandLimits.SHA256_BYTES) {
            "Core receipt digest must be SHA-256"
        }
        require(coreRevision >= 0) { "Core receipt revision must not be negative" }
        require(appliedAt > 0) { "Core receipt application time must be positive" }
        require(outcome != CoreCommandDurableOutcome.SUPERSEDED || pendingActivity == null) {
            "A superseded Core command must not create an Activity obligation"
        }
    }

    internal fun matches(reference: CoreCommandReceiptIdentity): Boolean =
        commandId == reference.commandId &&
            authenticatedRequestId == reference.authenticatedRequestId &&
            commandType == reference.commandType &&
            MessageDigest.isEqual(storedCommandDigest, reference.commandDigestCopy())

    override fun toString(): String =
        "CoreCommandReceiptEvidence(commandId=$commandId, type=${commandType.token}, outcome=$outcome, " +
            "coreRevision=$coreRevision, appliedAt=$appliedAt, digest=<${storedCommandDigest.size} bytes>)"
}

/** Exact marker identity decoded from one authenticated broker delivery. */
internal class CoreCommandReceiptIdentity(
    val commandId: String,
    val authenticatedRequestId: String,
    commandDigest: ByteArray,
    val commandType: CoreCommandKind,
) {
    private val storedCommandDigest = commandDigest.copyOf()
    fun commandDigestCopy(): ByteArray = storedCommandDigest.copyOf()

    init {
        requireCompactIdentifier(commandId, "Core receipt-reference command id")
        requireCompactIdentifier(authenticatedRequestId, "Core receipt-reference authenticated request id")
        require(storedCommandDigest.size == CoreCommandLimits.SHA256_BYTES) {
            "Core receipt-reference digest must be SHA-256"
        }
    }
}

internal sealed interface CoreCommandAuthorityApplyOutcome {
    data class Committed(val receipt: CoreCommandReceiptEvidence) : CoreCommandAuthorityApplyOutcome
    data class Duplicate(val receipt: CoreCommandReceiptEvidence) : CoreCommandAuthorityApplyOutcome
    data object Conflict : CoreCommandAuthorityApplyOutcome
    data class Retryable(val reason: CoreCommandAuthorityRetryReason) : CoreCommandAuthorityApplyOutcome
}

internal sealed interface CoreCommandAuthorityReceiptResolution {
    data class Found(val receipt: CoreCommandReceiptEvidence) : CoreCommandAuthorityReceiptResolution
    data object Missing : CoreCommandAuthorityReceiptResolution
    data object Conflict : CoreCommandAuthorityReceiptResolution
}

/** Core side of the handoff. Each call opens and closes at most one Core transaction or read. */
internal interface CoreCommandAuthority {
    suspend fun apply(command: BoundCoreTrustCommand): CoreCommandAuthorityApplyOutcome

    suspend fun resolve(reference: CoreCommandReceiptIdentity): CoreCommandAuthorityReceiptResolution

    suspend fun acknowledgeCopiedActivity(eventId: String, operationalGeneration: Long): Boolean

    /** Bounded 72-hour retention maintenance; never deletes a marker with a pending Core Activity obligation. */
    suspend fun pruneRetainedMarkers(limit: Int = CoreCommandLimits.MAX_MARKER_PRUNE_BATCH): Int
}
