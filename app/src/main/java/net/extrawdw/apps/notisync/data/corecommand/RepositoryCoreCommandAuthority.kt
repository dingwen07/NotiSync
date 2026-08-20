package net.extrawdw.apps.notisync.data.corecommand

import android.content.Context
import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.storage.core.CoreActivitySnapshot
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandApplyResult
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandOutcome
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandReceipt
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandReceiptReference
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandReceiptResolution
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.coreCommandActivityEventId

/**
 * Narrow adapter around the Core foundation repository. Core persistence/repository types stop here; the
 * cross-database coordinator consumes only the storage-independent [CoreCommandAuthority] contract.
 */
internal class RepositoryCoreCommandAuthority(
    private val repository: CoreFoundationRepository,
) : CoreCommandAuthority {
    override suspend fun apply(command: BoundCoreTrustCommand): CoreCommandAuthorityApplyOutcome {
        val result = repository.applyCoreTrustCommand(command.commandForAuthority())
        return when (result) {
            is CoreCommandApplyResult.Applied -> result.receipt.toEvidence(command.binding)?.let { evidence ->
                if (evidence.outcome == CoreCommandDurableOutcome.APPLIED) {
                    CoreCommandAuthorityApplyOutcome.Committed(evidence)
                } else {
                    CoreCommandAuthorityApplyOutcome.Conflict
                }
            } ?: CoreCommandAuthorityApplyOutcome.Conflict
            is CoreCommandApplyResult.Superseded -> result.receipt.toEvidence(command.binding)?.let { evidence ->
                if (evidence.outcome == CoreCommandDurableOutcome.SUPERSEDED) {
                    CoreCommandAuthorityApplyOutcome.Committed(evidence)
                } else {
                    CoreCommandAuthorityApplyOutcome.Conflict
                }
            } ?: CoreCommandAuthorityApplyOutcome.Conflict
            is CoreCommandApplyResult.Duplicate -> result.receipt.toEvidence(command.binding)?.let { evidence ->
                CoreCommandAuthorityApplyOutcome.Duplicate(evidence)
            } ?: CoreCommandAuthorityApplyOutcome.Conflict
            CoreCommandApplyResult.Conflict -> CoreCommandAuthorityApplyOutcome.Conflict
            CoreCommandApplyResult.StaleCoreState -> CoreCommandAuthorityApplyOutcome.Retryable(
                CoreCommandAuthorityRetryReason.STALE_CORE_STATE,
            )
            CoreCommandApplyResult.MissingIdentity -> CoreCommandAuthorityApplyOutcome.Retryable(
                CoreCommandAuthorityRetryReason.MISSING_IDENTITY,
            )
            CoreCommandApplyResult.CoreNotReady -> CoreCommandAuthorityApplyOutcome.Retryable(
                CoreCommandAuthorityRetryReason.CORE_NOT_READY,
            )
        }
    }

    override suspend fun resolve(
        reference: CoreCommandReceiptIdentity,
    ): CoreCommandAuthorityReceiptResolution = when (
        val resolution = repository.resolveCoreCommandReceipt(
            CoreCommandReceiptReference(
                commandId = reference.commandId,
                authenticatedRequestId = reference.authenticatedRequestId,
                commandDigest = reference.commandDigestCopy(),
                commandType = reference.commandType.toCoreType(),
            ),
        )
    ) {
        is CoreCommandReceiptResolution.Found -> {
            val evidence = resolution.receipt.toEvidence(reference)
            if (evidence == null) {
                CoreCommandAuthorityReceiptResolution.Conflict
            } else {
                CoreCommandAuthorityReceiptResolution.Found(evidence)
            }
        }
        CoreCommandReceiptResolution.Missing -> CoreCommandAuthorityReceiptResolution.Missing
        CoreCommandReceiptResolution.Conflict -> CoreCommandAuthorityReceiptResolution.Conflict
    }

    override suspend fun acknowledgeCopiedActivity(
        eventId: String,
        operationalGeneration: Long,
    ): Boolean = repository.acknowledgeCopiedCoreActivity(eventId, operationalGeneration)

    override suspend fun pruneRetainedMarkers(limit: Int): Int =
        repository.pruneRetainedCoreCommandMarkers(limit)

    companion object {
        fun create(context: Context): RepositoryCoreCommandAuthority =
            RepositoryCoreCommandAuthority(CoreFoundationRepository.create(context))
    }
}

private fun CoreCommandReceipt.toEvidence(binding: CoreCommandBinding): CoreCommandReceiptEvidence? {
    val evidence = toEvidence() ?: return null
    if (
        evidence.commandId != binding.commandId ||
        evidence.authenticatedRequestId != binding.authenticatedRequestId ||
        evidence.commandType != binding.commandType ||
        !MessageDigest.isEqual(evidence.commandDigestCopy(), binding.commandDigestCopy())
    ) return null
    return evidence
}

private fun CoreCommandReceipt.toEvidence(reference: CoreCommandReceiptIdentity): CoreCommandReceiptEvidence? {
    val evidence = toEvidence() ?: return null
    return evidence.takeIf { it.matches(reference) }
}

private fun CoreCommandReceipt.toEvidence(): CoreCommandReceiptEvidence? {
    return try {
        val kind = CoreCommandKind.fromToken(command.commandType)
        val activity = pendingActivity?.toProjection()
        if (pendingActivity != null && activity == null) return null
        if (activity != null) {
            val expectedEventId = coreCommandActivityEventId(kind.toCoreType(), command.commandId)
            if (activity.eventId != expectedEventId || activity.correlationId != command.authenticatedRequestId) {
                return null
            }
        }
        CoreCommandReceiptEvidence(
            commandId = command.commandId,
            authenticatedRequestId = command.authenticatedRequestId,
            commandDigest = command.commandDigest,
            commandType = kind,
            outcome = when (command.outcome) {
                CoreCommandOutcome.APPLIED -> CoreCommandDurableOutcome.APPLIED
                CoreCommandOutcome.SUPERSEDED -> CoreCommandDurableOutcome.SUPERSEDED
                CoreCommandOutcome.TERMINAL_REJECTED -> CoreCommandDurableOutcome.TERMINAL_REJECTED
            },
            coreRevision = command.coreRevision,
            appliedAt = command.appliedAt,
            pendingActivity = activity,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun CoreActivitySnapshot.toProjection(): CoreActivityProjection? = try {
    CoreActivityProjection(
        commandId = commandId,
        eventId = eventId,
        operationalGeneration = operationalGeneration,
        feature = feature,
        semanticAction = semanticAction,
        direction = direction,
        outcome = outcome,
        peerClientId = peerClientId,
        correlationId = correlationId,
        deliveryMode = deliveryMode,
        argsVersion = argsVersion,
        renderArgs = renderArgs,
        occurredAt = occurredAt,
        createdAt = createdAt,
    )
} catch (_: IllegalArgumentException) {
    null
}
