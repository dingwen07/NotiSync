package net.extrawdw.apps.notisync.messaging.core

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityDirection
import net.extrawdw.apps.notisync.data.activity.ActivityEventDraft
import net.extrawdw.apps.notisync.data.activity.ActivityEventId
import net.extrawdw.apps.notisync.data.activity.ActivityFeature
import net.extrawdw.apps.notisync.data.activity.ActivityOutcome
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.activity.ActivitySemanticCode
import net.extrawdw.apps.notisync.data.activity.ActivityStableIdentifier
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.CoreActivityProjection
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthority
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityApplyOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityReceiptResolution
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandBinding
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandDurableOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandIdentityPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandLimits
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandPreparationPort
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptEvidence
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptFinalizeOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptFinalizer
import net.extrawdw.apps.notisync.data.corecommand.CoreTrustCommandPreparationResult
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayStableCode

/**
 * Direct, lock-ordered Core delivery processor.
 *
 * The broker-retained authenticated envelope is the only retry journal. Every port call completes one database
 * transaction before the next call starts: exact decode/reduce is pure, Core owns mutation+marker+Activity, then
 * Operational owns handled evidence+Activity. This class never emits a network ACK or opens either database.
 */
internal class CoreCommandProcessor(
    private val preparation: CoreCommandPreparationPort,
    private val core: CoreCommandAuthority,
    private val finalizer: CoreCommandReceiptFinalizer,
) {
    suspend fun process(delivery: AuthenticatedCoreCommandDelivery): CoreCommandProcessingResult {
        val binding = when (val decoded = preparation.decodeIdentity(delivery)) {
            is CoreCommandIdentityPreparationResult.Ready -> try {
                CoreCommandBinding.bind(delivery, decoded.identity)
            } catch (_: IllegalArgumentException) {
                return security(delivery, CODE_DECODED_IDENTITY_MISMATCH)
            }
            is CoreCommandIdentityPreparationResult.Retryable ->
                return retry(delivery, decoded.errorCode)
            is CoreCommandIdentityPreparationResult.SecurityBlocked ->
                return security(delivery, decoded.errorCode)
        }

        when (val retained = core.resolve(binding.toReceiptIdentity())) {
            is CoreCommandAuthorityReceiptResolution.Found ->
                return finalizeReceipt(delivery, binding, retained.receipt)
            CoreCommandAuthorityReceiptResolution.Conflict ->
                return security(delivery, CODE_CORE_IDENTITY_CONFLICT)
            CoreCommandAuthorityReceiptResolution.Missing -> Unit
        }

        val prepared = when (val reduced = preparation.reduceAndSign(delivery, binding)) {
            is CoreTrustCommandPreparationResult.Ready -> {
                if (!reduced.command.binding.matches(binding)) {
                    return security(delivery, CODE_REDUCER_BINDING_MISMATCH)
                }
                reduced.command
            }
            is CoreTrustCommandPreparationResult.Retryable ->
                return retry(delivery, reduced.errorCode)
            is CoreTrustCommandPreparationResult.SecurityBlocked ->
                return security(delivery, reduced.errorCode)
        }

        val receipt = when (val applied = core.apply(prepared)) {
            is CoreCommandAuthorityApplyOutcome.Committed -> applied.receipt
            is CoreCommandAuthorityApplyOutcome.Duplicate -> applied.receipt
            CoreCommandAuthorityApplyOutcome.Conflict ->
                return security(delivery, CODE_CORE_IDENTITY_CONFLICT)
            is CoreCommandAuthorityApplyOutcome.Retryable ->
                return retry(delivery, RelayStableCode.of(applied.reason.stableCode))
        }
        return finalizeReceipt(delivery, binding, receipt)
    }

    private suspend fun finalizeReceipt(
        delivery: AuthenticatedCoreCommandDelivery,
        binding: CoreCommandBinding,
        receipt: CoreCommandReceiptEvidence,
    ): CoreCommandProcessingResult {
        if (!receipt.matches(binding)) return security(delivery, CODE_CORE_RECEIPT_CONFLICT)
        val disposition = when (receipt.outcome) {
            CoreCommandDurableOutcome.APPLIED -> RelayHandledDisposition.APPLIED
            CoreCommandDurableOutcome.SUPERSEDED -> RelayHandledDisposition.SUPERSEDED
            CoreCommandDurableOutcome.TERMINAL_REJECTED ->
                return security(delivery, CODE_CORE_MARKER_OUTCOME)
        }

        val pendingActivity = receipt.pendingActivity
        if (pendingActivity != null && !pendingActivity.matches(binding)) {
            return security(delivery, CODE_CORE_ACTIVITY_CONFLICT)
        }
        if (pendingActivity != null && pendingActivity.operationalGeneration > delivery.continuity.generation) {
            return security(delivery, CODE_CORE_ACTIVITY_FUTURE_GENERATION)
        }
        val activity = if (pendingActivity?.operationalGeneration == delivery.continuity.generation) {
            pendingActivity.toDraft(binding.commandType)
                ?: return security(delivery, CODE_CORE_ACTIVITY_CONFLICT)
        } else {
            null
        }
        val request = RelayFinalizeRequest(
            messageId = delivery.messageId,
            authenticatedToken = delivery.authenticatedToken,
            handledAt = receipt.appliedAt,
            activity = activity,
        )
        return when (finalizer.finalize(delivery.continuity, request)) {
            CoreCommandReceiptFinalizeOutcome.APPLIED,
            CoreCommandReceiptFinalizeOutcome.ALREADY_FINALIZED,
            -> {
                pendingActivity?.let { owed ->
                    core.acknowledgeCopiedActivity(owed.eventId, owed.operationalGeneration)
                }
                opportunisticMarkerPrune()
                CoreCommandProcessingResult.AcknowledgementReady(delivery.messageId, disposition)
            }
            CoreCommandReceiptFinalizeOutcome.CONFLICT ->
                security(delivery, CODE_OPERATIONAL_FINALIZE_CONFLICT)
            CoreCommandReceiptFinalizeOutcome.LEGACY_RETAINED_NO_ACK ->
                security(delivery, CODE_LEGACY_RETAINED_NO_ACK)
            CoreCommandReceiptFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH ->
                retry(delivery, CODE_OPERATIONAL_CONTINUITY_CHANGED)
        }
    }

    /** Marker cleanup cannot retroactively make an already committed message unsafe to ACK. */
    private suspend fun opportunisticMarkerPrune() {
        try {
            core.pruneRetainedMarkers(CoreCommandLimits.MAX_MARKER_PRUNE_BATCH)
        } catch (failure: SQLiteException) {
            when (failure) {
                is SQLiteFullException,
                is SQLiteDiskIOException,
                is SQLiteDatabaseLockedException,
                -> Unit // Bounded maintenance is retried opportunistically on a later delivery.
                is SQLiteDatabaseCorruptException -> throw failure
                else -> throw failure
            }
        }
    }

    private fun retry(
        delivery: AuthenticatedCoreCommandDelivery,
        code: RelayStableCode,
    ): CoreCommandProcessingResult = CoreCommandProcessingResult.RetryRequired(delivery.messageId, code)

    private fun security(
        delivery: AuthenticatedCoreCommandDelivery,
        code: RelayStableCode,
    ): CoreCommandProcessingResult = CoreCommandProcessingResult.SecurityBlocked(delivery.messageId, code)

    private companion object {
        val CODE_DECODED_IDENTITY_MISMATCH = RelayStableCode.of("core_decoded_identity_mismatch")
        val CODE_REDUCER_BINDING_MISMATCH = RelayStableCode.of("core_reducer_binding_mismatch")
        val CODE_CORE_IDENTITY_CONFLICT = RelayStableCode.of("core_command_identity_conflict")
        val CODE_CORE_RECEIPT_CONFLICT = RelayStableCode.of("core_receipt_conflict")
        val CODE_CORE_MARKER_OUTCOME = RelayStableCode.of("core_marker_outcome_invalid")
        val CODE_CORE_ACTIVITY_CONFLICT = RelayStableCode.of("core_activity_conflict")
        val CODE_CORE_ACTIVITY_FUTURE_GENERATION = RelayStableCode.of("core_activity_future_generation")
        val CODE_OPERATIONAL_FINALIZE_CONFLICT = RelayStableCode.of("operational_finalize_conflict")
        val CODE_LEGACY_RETAINED_NO_ACK = RelayStableCode.of("legacy_handled_evidence_no_ack")
        val CODE_OPERATIONAL_CONTINUITY_CHANGED = RelayStableCode.of("operational_continuity_changed")
    }
}

private fun CoreCommandReceiptEvidence.matches(binding: CoreCommandBinding): Boolean =
    commandId == binding.commandId &&
        authenticatedRequestId == binding.authenticatedRequestId &&
        commandType == binding.commandType &&
        MessageDigest.isEqual(commandDigestCopy(), binding.commandDigestCopy()) &&
        pendingActivity?.matches(binding) != false

private fun CoreActivityProjection.matches(binding: CoreCommandBinding): Boolean =
    commandId == binding.commandId &&
        eventId == coreActivityEventId(binding.commandType, binding.commandId) &&
        correlationId == binding.authenticatedRequestId

private fun CoreActivityProjection.toDraft(commandType: CoreCommandKind): ActivityEventDraft? {
    return try {
        val decodedArgs = ActivityRenderArgsCodec.decode(argsVersion, renderArgsCopy())
        if (decodedArgs !is ActivityRenderArgs.V1) return null
        val featureValue = ActivityFeature.entries.firstOrNull { it.token == feature } ?: return null
        val expectedFeature = when (commandType) {
            CoreCommandKind.DATA_SYNC_PROFILE -> ActivityFeature.PROFILE
            CoreCommandKind.DATA_SYNC_TRUST,
            CoreCommandKind.DATA_SYNC_CARD,
            -> ActivityFeature.TRUST
        }
        if (featureValue != expectedFeature) return null
        val actionValue = ActivityAction.entries.firstOrNull { it.token == semanticAction } ?: return null
        val directionValue = ActivityDirection.entries.firstOrNull { it.token == direction } ?: return null
        val outcomeValue = ActivityOutcome.entries.firstOrNull { it.token == outcome } ?: return null
        if (directionValue != ActivityDirection.INBOUND || outcomeValue != ActivityOutcome.SUCCESS) return null
        val deliveryModeValue = deliveryMode?.let { token ->
            ActivityDeliveryMode.entries.firstOrNull { it.token == token } ?: return null
        }
        ActivityEventDraft(
            eventId = eventId,
            occurredAt = occurredAt,
            recordedAt = createdAt,
            feature = featureValue,
            semanticAction = actionValue,
            direction = directionValue,
            outcome = outcomeValue,
            peerClientId = peerClientId,
            correlationId = correlationId,
            deliveryMode = deliveryModeValue,
            renderArgs = decodedArgs,
            coalescingKeyToken = null,
            coalescedCount = 1,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun coreActivityEventId(commandType: CoreCommandKind, commandId: String): String =
    ActivityEventId.derive(
        semanticCode = ActivitySemanticCode.of("core.${commandType.token}.transition"),
        identifiers = listOf(ActivityStableIdentifier.of(commandId)),
    )
