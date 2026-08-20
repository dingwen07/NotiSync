package net.extrawdw.apps.notisync.data.storage.core

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs

internal enum class CoreCommandOutcome(val token: String) {
    APPLIED("APPLIED"),
    TERMINAL_REJECTED("TERMINAL_REJECTED"),
    SUPERSEDED("SUPERSEDED"),
    ;

    companion object {
        fun fromToken(token: String): CoreCommandOutcome = entries.firstOrNull { it.token == token }
            ?: error("Unknown core command outcome token")
    }
}

/**
 * Closed set of authenticated wire commands whose current reducer output is the signed trust aggregate.
 *
 * These stable tokens are persisted as raw text in [CoreCommandAppliedEntity]. The enum is deliberately not a
 * Room converter: a future token must first be handled by the message catalog/reducer rather than materializing as
 * a guessed command. Local broker/group/route control-plane changes do not belong in this inbound command set.
 */
internal enum class CoreTrustCommandType(val token: String) {
    DATA_SYNC_PROFILE("data_sync.profile"),
    DATA_SYNC_TRUST("data_sync.trust"),
    DATA_SYNC_CARD("data_sync.card"),
}

/**
 * Optional privacy-safe projection of an applied Core command.
 *
 * Event identity, feature, direction, outcome, correlation ID, recording time, and operational generation are
 * derived by the repository/transaction. Callers can provide only reviewed enum values, a non-secret peer ID,
 * bounded numeric render arguments, and the source occurrence time; arbitrary prose or payload bytes cannot enter
 * the Core outbox through this contract.
 */
internal data class CoreCommandActivity(
    val action: ActivityAction,
    val peerClientId: String? = null,
    val deliveryMode: ActivityDeliveryMode? = null,
    val renderArgs: ActivityRenderArgs.V1 = ActivityRenderArgs.V1(),
    val occurredAt: Long,
)

/**
 * Reducer output for one supported inbound Core command.
 *
 * [canonicalCommand] is the exact byte sequence staged by Operational storage. The repository copies and hashes it
 * with SHA-256; callers cannot supply the durable digest. [candidateSnapshot] must have been produced by the pure
 * Foundation reducer and identity signer outside SQL. The short Room transaction rechecks
 * [expectedSnapshotDigest] before replacing the authority.
 */
internal class CoreTrustCommand(
    val commandId: String,
    val authenticatedRequestId: String,
    canonicalCommand: ByteArray,
    val commandType: CoreTrustCommandType,
    val expectedOperationalGeneration: Long,
    val expectedOperationalIncarnationId: String,
    expectedSnapshotDigest: ByteArray?,
    val candidateSnapshot: TrustSnapshotInput,
    val activity: CoreCommandActivity? = null,
) {
    private val storedCanonicalCommand = canonicalCommand.copyOf()
    private val storedExpectedSnapshotDigest = expectedSnapshotDigest?.copyOf()

    internal fun canonicalCommandCopy(): ByteArray = storedCanonicalCommand.copyOf()
    internal fun expectedSnapshotDigestCopy(): ByteArray? = storedExpectedSnapshotDigest?.copyOf()
}

internal class CoreCommandSnapshot(
    val commandId: String,
    val authenticatedRequestId: String,
    commandDigest: ByteArray,
    val commandType: String,
    val outcome: CoreCommandOutcome,
    val coreRevision: Long,
    val appliedAt: Long,
) {
    private val storedCommandDigest = commandDigest.copyOf()
    val commandDigest: ByteArray get() = storedCommandDigest.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CoreCommandSnapshot &&
            commandId == other.commandId &&
            authenticatedRequestId == other.authenticatedRequestId &&
            MessageDigest.isEqual(storedCommandDigest, other.storedCommandDigest) &&
            commandType == other.commandType &&
            outcome == other.outcome &&
            coreRevision == other.coreRevision &&
            appliedAt == other.appliedAt

    override fun hashCode(): Int {
        var result = commandId.hashCode()
        result = 31 * result + authenticatedRequestId.hashCode()
        result = 31 * result + storedCommandDigest.contentHashCode()
        result = 31 * result + commandType.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + coreRevision.hashCode()
        return 31 * result + appliedAt.hashCode()
    }

    override fun toString(): String =
        "CoreCommandSnapshot(commandId=$commandId, commandType=$commandType, outcome=$outcome, " +
            "coreRevision=$coreRevision, appliedAt=$appliedAt, commandDigest=<${storedCommandDigest.size} bytes>)"
}

internal data class CoreCommandReceipt(
    val command: CoreCommandSnapshot,
    /** Present only while the owed projection still exists in the Core outbox. */
    val pendingActivity: CoreActivitySnapshot?,
)

/** Exact retained-marker identity decoded from an authenticated broker redelivery. */
internal class CoreCommandReceiptReference(
    val commandId: String,
    val authenticatedRequestId: String,
    commandDigest: ByteArray,
    val commandType: CoreTrustCommandType,
) {
    private val storedCommandDigest = commandDigest.copyOf()
    val commandDigest: ByteArray get() = storedCommandDigest.copyOf()

    init {
        requireCompactCoreIdentifier(commandId, "core command id")
        requireCompactCoreIdentifier(authenticatedRequestId, "authenticated core request id")
        require(storedCommandDigest.size == CORE_COMMAND_DIGEST_BYTES) {
            "Core command receipt digest must be SHA-256"
        }
    }
}

internal sealed interface CoreCommandReceiptResolution {
    data class Found(val receipt: CoreCommandReceipt) : CoreCommandReceiptResolution
    data object Missing : CoreCommandReceiptResolution
    data object Conflict : CoreCommandReceiptResolution
}

/**
 * Typed result of the Core half of the cross-database handoff.
 *
 * Programmer/validation failures throw before SQL. Integrity conflicts are explicit values. Cancellation and real
 * storage failures are never caught; Room rolls the whole transaction back and the coordinator retries later.
 */
internal sealed interface CoreCommandApplyResult {
    data class Applied(val receipt: CoreCommandReceipt) : CoreCommandApplyResult
    data class Superseded(val receipt: CoreCommandReceipt) : CoreCommandApplyResult
    data class Duplicate(val receipt: CoreCommandReceipt) : CoreCommandApplyResult
    data object Conflict : CoreCommandApplyResult
    data object StaleCoreState : CoreCommandApplyResult
    data object MissingIdentity : CoreCommandApplyResult
    data object CoreNotReady : CoreCommandApplyResult
}

internal class CoreActivitySnapshot(
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
    val renderArgs: ByteArray get() = storedRenderArgs.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CoreActivitySnapshot &&
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
        "CoreActivitySnapshot(commandId=$commandId, eventId=$eventId, generation=$operationalGeneration, " +
            "feature=$feature, action=$semanticAction, outcome=$outcome, " +
            "renderArgs=<${storedRenderArgs.size} bytes>)"
}

internal fun CoreCommandSnapshot.matchesIdentity(
    authenticatedRequestId: String,
    commandDigest: ByteArray,
    commandType: String,
): Boolean =
    this.authenticatedRequestId == authenticatedRequestId &&
        this.commandType == commandType &&
        MessageDigest.isEqual(this.commandDigest, commandDigest)

internal const val CORE_COMMAND_DIGEST_BYTES = 32
internal const val MAX_CORE_COMMAND_CANONICAL_BYTES = 4 * 1024 * 1024
