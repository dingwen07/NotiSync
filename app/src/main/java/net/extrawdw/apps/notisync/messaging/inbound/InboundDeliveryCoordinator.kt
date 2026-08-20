package net.extrawdw.apps.notisync.messaging.inbound

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeOutcome
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayHandledResolution
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.apps.notisync.data.relay.RelayRepository
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate
import net.extrawdw.apps.notisync.messaging.AuthenticatedInboundDelivery
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundDeliveryMode
import net.extrawdw.apps.notisync.messaging.InboundCommitBoundary
import net.extrawdw.apps.notisync.messaging.InboundMessageRouter
import net.extrawdw.apps.notisync.messaging.InboundPlanningResult
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.ProtocolMessageDescriptor

internal fun interface InboundCoordinatorClock {
    fun now(): Long
}

/** Receiver-local metadata policy run after the router classifies the authenticated body once. */
internal fun interface InboundLocalDeliveryPolicy {
    fun forceSilent(
        descriptor: ProtocolMessageDescriptor,
        acceptedAt: Long,
        processingAt: Long,
    ): Boolean
}

/** Preserves the shipped two-hour silent-replay behavior for normal and quiet notifications. */
internal object StaleNotificationLocalDeliveryPolicy : InboundLocalDeliveryPolicy {
    override fun forceSilent(
        descriptor: ProtocolMessageDescriptor,
        acceptedAt: Long,
        processingAt: Long,
    ): Boolean =
        (descriptor.leaf == net.extrawdw.apps.notisync.messaging.ProtocolLeaf.Notification ||
            descriptor.leaf == net.extrawdw.apps.notisync.messaging.ProtocolLeaf.QuietNotification) &&
            processingAt > STALE_RELAY_AGE_MILLIS &&
            acceptedAt <= processingAt - STALE_RELAY_AGE_MILLIS

    private const val STALE_RELAY_AGE_MILLIS = 2L * 60 * 60 * 1_000
}

/** Exact evidence an owning handler must include in its mutation+Activity+handled transaction. */
internal data class InboundOwnerReceipt(
    val messageId: String,
    val authenticatedToken: AuthenticatedRelayToken,
    val continuity: RelayOperationalContinuity,
    val handledAt: Long,
) {
    init {
        require(messageId.isNotBlank()) { "inbound receipt message id must not be blank" }
        require(handledAt > 0) { "inbound receipt time must be positive" }
    }
}

/**
 * Result from an owning Core or Operational handler.
 *
 * [AcknowledgementReady] is legal only after the owner atomically committed its mutation (or explicit terminal
 * no-op), deterministic Activity, and exact modern handled evidence. The coordinator never performs a second
 * generic receipt transaction for such a result.
 */
internal sealed interface InboundOwnerCommitResult {
    data class AcknowledgementReady(
        val disposition: RelayHandledDisposition,
        val prerequisite: InboundAcknowledgementPrerequisite = InboundAcknowledgementPrerequisite.NONE,
    ) : InboundOwnerCommitResult

    data class RetryRequired(val errorCode: RelayStableCode) : InboundOwnerCommitResult
    data class SecurityBlocked(val errorCode: RelayStableCode) : InboundOwnerCommitResult
    data object LegacyRetainedNoAck : InboundOwnerCommitResult
    data object ConflictNoAck : InboundOwnerCommitResult
    data object StorageContinuityMismatch : InboundOwnerCommitResult
}

/** A post-commit external effect that must complete before the broker copy can be acknowledged. */
internal enum class InboundAcknowledgementPrerequisite {
    NONE,
    NOTIFICATION_PRESENTATION,
    DISMISSAL_PRESENTATION,
}

/** Explicit side-effect policy for an Operational owner; never inferred from transport delivery mode. */
internal enum class InboundPresentationPolicy {
    /** Complete any idempotent presentation after commit before reporting an ACK-ready result. */
    COMPLETE_BEFORE_ACK,

    /** Commit mutation, Activity, and handled evidence only; presentation is replayed after validated batch END. */
    DEFER_UNTIL_BATCH_END,
}

/** Operational handlers must own their feature mutation and receipt in one Operational transaction. */
internal fun interface OperationalInboundDispatchPort {
    suspend fun dispatch(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        presentationPolicy: InboundPresentationPolicy,
    ): InboundOwnerCommitResult
}

/** Core adapter boundary; implementations map the planned command to the closed Core processor outside SQL. */
internal fun interface CoreInboundDispatchPort {
    suspend fun dispatch(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult
}

/**
 * Reconciles only owner-specific post-commit prerequisites for exact handled redelivery.
 *
 * It must never reapply a feature mutation or a non-idempotent ACTION. Notification/dismissal owners use this hook
 * to replay idempotent NotificationManager presentation from durable state before returning ACK-ready.
 */
internal fun interface HandledInboundReplayPort {
    suspend fun reconcile(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult
}

/** Reviewed, authenticated metadata exposed just before an owning mutation starts. */
internal class InboundBatchObservation(
    val messageId: String,
    authenticatedToken: AuthenticatedRelayToken,
    val prerequisite: InboundAcknowledgementPrerequisite,
) {
    private val tokenSnapshot = AuthenticatedRelayToken.of(authenticatedToken.copyBytes())

    val authenticatedToken: AuthenticatedRelayToken
        get() = AuthenticatedRelayToken.of(tokenSnapshot.copyBytes())

    override fun toString(): String =
        "InboundBatchObservation(messageId=$messageId, prerequisite=$prerequisite, fingerprint=<32 bytes>)"
}

internal enum class InboundBatchObservationResult {
    RECORDED,
    CONFLICT,
}

internal fun interface InboundBatchObservationPort {
    suspend fun observe(observation: InboundBatchObservation): InboundBatchObservationResult
}

internal interface InboundBatchDeliveryPort {
    suspend fun receiveForBatch(
        arrival: InboundEnvelopeArrival,
        observation: InboundBatchObservationPort,
    ): InboundCoordinatorResult

    suspend fun replayBatchPresentation(
        arrival: InboundEnvelopeArrival,
        expected: InboundBatchObservation,
    ): InboundCoordinatorResult
}

internal fun interface InboundDirectDeliveryPort {
    suspend fun receive(arrival: InboundEnvelopeArrival): InboundCoordinatorResult
}

/** One application-scoped serialization gate shared by live, exact-fetch, and finite-drain entry points. */
internal interface InboundProcessGate {
    suspend fun <T> withInboundExclusive(block: suspend () -> T): T
}

internal class MutexInboundProcessGate : InboundProcessGate {
    private val mutex = Mutex()

    override suspend fun <T> withInboundExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

internal enum class InboundPreAuthenticationFailure(val token: String) {
    MALFORMED_ENVELOPE("malformed_envelope"),
    LOCATOR_MESSAGE_ID_MISMATCH("locator_message_id_mismatch"),
}

internal enum class InboundConflictReason(val token: String) {
    HANDLED_FINGERPRINT_CONFLICT("handled_fingerprint_conflict"),
    OWNER_COMMIT_CONFLICT("owner_commit_conflict"),
    GENERIC_FINALIZATION_CONFLICT("generic_finalization_conflict"),
}

/** Opaque candidate. The network caller must pass it through [InboundAcknowledgementCoordinator]. */
internal class InboundAcknowledgementCandidate(
    val messageId: String,
    val authenticatedToken: AuthenticatedRelayToken,
    val continuity: RelayOperationalContinuity,
    val disposition: RelayHandledDisposition,
    val prerequisite: InboundAcknowledgementPrerequisite,
) {
    override fun toString(): String =
        "InboundAcknowledgementCandidate(messageId=$messageId, generation=${continuity.generation}, " +
            "disposition=$disposition, prerequisite=$prerequisite, " +
            "fingerprint=<${authenticatedToken.copyBytes().size} bytes>)"
}

/** Processing never sends a network ACK and never creates local retry state. */
internal sealed interface InboundCoordinatorResult {
    data class AcknowledgementPending(
        val candidate: InboundAcknowledgementCandidate,
    ) : InboundCoordinatorResult

    data class RejectedBeforeAuthentication(
        val messageId: String,
        val failure: InboundPreAuthenticationFailure,
    ) : InboundCoordinatorResult

    data class RetryRequired(
        val messageId: String,
        val errorCode: RelayStableCode,
    ) : InboundCoordinatorResult

    data class SecurityBlocked(
        val messageId: String,
        val errorCode: RelayStableCode,
    ) : InboundCoordinatorResult

    data class LegacyRetainedNoAck(val messageId: String) : InboundCoordinatorResult

    data class ConflictNoAck(
        val messageId: String,
        val reason: InboundConflictReason,
    ) : InboundCoordinatorResult

    data class StorageContinuityChanged(val messageId: String) : InboundCoordinatorResult
}

/**
 * Direct broker-custody receive path for one live envelope.
 *
 * The broker copy is the only retry journal. This coordinator decodes, authenticates, and plans once, then invokes
 * exactly one owning command port. Generic receipt finalization is restricted to catalog-level terminal/no-feature
 * decisions; it is never appended after a successful feature mutation.
 */
internal class InboundDeliveryCoordinator(
    private val relayRepository: RelayRepository,
    private val envelopeCodec: InboundEnvelopeCodec,
    private val fingerprint: InboundEnvelopeFingerprint,
    private val inboundEnvelopePort: InboundEnvelopePort,
    private val router: InboundMessageRouter,
    private val operationalDispatch: OperationalInboundDispatchPort,
    private val coreDispatch: CoreInboundDispatchPort,
    private val handledReplay: HandledInboundReplayPort,
    private val clock: InboundCoordinatorClock,
    private val localDeliveryPolicy: InboundLocalDeliveryPolicy = StaleNotificationLocalDeliveryPolicy,
) : InboundBatchDeliveryPort, InboundDirectDeliveryPort {
    override suspend fun receive(arrival: InboundEnvelopeArrival): InboundCoordinatorResult = receiveInternal(
        arrival = arrival,
        batchObservation = null,
        deferPresentation = false,
    )

    /**
     * Finite-batch path. Metadata is recorded before an owning mutation and presentation is always deferred until
     * the batch source has validated END and its advertised item count.
     */
    override suspend fun receiveForBatch(
        arrival: InboundEnvelopeArrival,
        observation: InboundBatchObservationPort,
    ): InboundCoordinatorResult = receiveInternal(
        arrival = arrival,
        batchObservation = observation,
        deferPresentation = true,
    )

    /** Refetch path used only after validated END; exact metadata is rechecked before idempotent presentation. */
    override suspend fun replayBatchPresentation(
        arrival: InboundEnvelopeArrival,
        expected: InboundBatchObservation,
    ): InboundCoordinatorResult = receiveInternal(
        arrival = arrival,
        batchObservation = InboundBatchObservationPort { actual ->
            if (
                actual.messageId == expected.messageId &&
                actual.authenticatedToken == expected.authenticatedToken &&
                actual.prerequisite == expected.prerequisite
            ) {
                InboundBatchObservationResult.RECORDED
            } else {
                InboundBatchObservationResult.CONFLICT
            }
        },
        deferPresentation = false,
    )

    private suspend fun receiveInternal(
        arrival: InboundEnvelopeArrival,
        batchObservation: InboundBatchObservationPort?,
        deferPresentation: Boolean,
    ): InboundCoordinatorResult {
        currentCoroutineContext().ensureActive()
        val encodedEnvelope = arrival.copyEncodedEnvelope()
        try {
            val decoded = envelopeCodec.decode(encodedEnvelope)
                ?: return InboundCoordinatorResult.RejectedBeforeAuthentication(
                    arrival.messageId,
                    InboundPreAuthenticationFailure.MALFORMED_ENVELOPE,
                )
            if (decoded.messageId != arrival.messageId) {
                return InboundCoordinatorResult.RejectedBeforeAuthentication(
                    arrival.messageId,
                    InboundPreAuthenticationFailure.LOCATOR_MESSAGE_ID_MISMATCH,
                )
            }
            val token = fingerprint.derive(decoded)
            currentCoroutineContext().ensureActive()
            val alreadyHandled = when (
                relayRepository.resolveHandled(
                    continuity = arrival.continuity,
                    messageId = arrival.messageId,
                    authenticatedToken = token,
                )
            ) {
                RelayHandledResolution.ExactAuthenticated -> true
                RelayHandledResolution.LegacyRetainedNoAck ->
                    return InboundCoordinatorResult.LegacyRetainedNoAck(arrival.messageId)
                RelayHandledResolution.Conflict -> {
                    batchObservation?.observe(
                        InboundBatchObservation(
                            arrival.messageId,
                            token,
                            InboundAcknowledgementPrerequisite.NONE,
                        ),
                    )
                    return InboundCoordinatorResult.ConflictNoAck(
                        arrival.messageId,
                        InboundConflictReason.HANDLED_FINGERPRINT_CONFLICT,
                    )
                }
                RelayHandledResolution.StorageContinuityMismatch ->
                    return InboundCoordinatorResult.StorageContinuityChanged(arrival.messageId)
                RelayHandledResolution.Missing -> false
            }

            currentCoroutineContext().ensureActive()
            return when (val opened = inboundEnvelopePort.authenticateAndOpen(decoded)) {
                is InboundSecureOpenResult.UnresolvedSender -> retry(arrival, "unresolved_sender")
                is InboundSecureOpenResult.BadSignature -> security(arrival, "bad_signature")
                is InboundSecureOpenResult.RecipientUnavailable -> when (opened.reason) {
                    InboundRecipientUnavailableReason.MISSING_PRIVATE_KEY ->
                        retry(arrival, "recipient_private_key_unavailable")
                    InboundRecipientUnavailableReason.NOT_ADDRESSED_TO_THIS_DEVICE ->
                        security(arrival, "recipient_not_addressed")
                    InboundRecipientUnavailableReason.AMBIGUOUS_RECIPIENT ->
                        security(arrival, "recipient_ambiguous")
                }
                is InboundSecureOpenResult.DecryptFailed -> security(arrival, "decrypt_failed")
                is InboundSecureOpenResult.Ready -> processOpened(
                    arrival,
                    token,
                    decoded,
                    opened,
                    alreadyHandled,
                    batchObservation,
                    deferPresentation,
                )
            }
        } finally {
            encodedEnvelope.fill(0)
        }
    }

    private suspend fun processOpened(
        arrival: InboundEnvelopeArrival,
        token: AuthenticatedRelayToken,
        decoded: DecodedInboundEnvelope,
        opened: InboundSecureOpenResult.Ready,
        alreadyHandled: Boolean,
        batchObservation: InboundBatchObservationPort?,
        deferPresentation: Boolean,
    ): InboundCoordinatorResult {
        currentCoroutineContext().ensureActive()
        if (!opened.matchesSignedEnvelope(decoded)) return security(arrival, "opened_metadata_mismatch")

        val body = opened.copyEncodedBody()
        val delivery = try {
            AuthenticatedInboundDelivery(
                messageId = opened.messageId,
                messageType = opened.messageType,
                senderId = opened.senderId,
                senderOwnDevice = opened.senderOwnDevice,
                signerEpoch = opened.signerEpoch,
                signedSequence = opened.signedSequence,
                signedCreatedAt = opened.signedCreatedAt,
                recipientEpoch = opened.recipientEpoch,
                encodedBody = body,
                acceptedAt = arrival.acceptedAt,
                deliveryMode = arrival.deliveryMode.toInboundDeliveryMode(),
                forceSilent = false,
            )
        } finally {
            body.fill(0)
        }

        return when (val planning = router.plan(delivery)) {
            is InboundPlanningResult.TerminalRejected -> {
                if (
                    batchObservation?.observe(
                        InboundBatchObservation(
                            arrival.messageId,
                            token,
                            InboundAcknowledgementPrerequisite.NONE,
                        ),
                    ) == InboundBatchObservationResult.CONFLICT
                ) {
                    InboundCoordinatorResult.ConflictNoAck(
                        arrival.messageId,
                        InboundConflictReason.HANDLED_FINGERPRINT_CONFLICT,
                    )
                } else if (alreadyHandled) {
                    acknowledgementPending(
                        arrival,
                        token,
                        RelayHandledDisposition.DUPLICATE,
                        InboundAcknowledgementPrerequisite.NONE,
                    )
                } else {
                    finalizeGenericTerminal(arrival, token, RelayHandledDisposition.TERMINAL_REJECTED)
                }
            }
            is InboundPlanningResult.SecurityBlocked -> security(arrival, planning.reasonCode)
            is InboundPlanningResult.Planned -> processPlanned(
                arrival,
                token,
                planning.command.withLocalPolicy(checkedNow()),
                alreadyHandled,
                batchObservation,
                deferPresentation,
            )
        }
    }

    private suspend fun processPlanned(
        arrival: InboundEnvelopeArrival,
        token: AuthenticatedRelayToken,
        command: PlannedInboundCommand,
        alreadyHandled: Boolean,
        batchObservation: InboundBatchObservationPort?,
        deferPresentation: Boolean,
    ): InboundCoordinatorResult {
        currentCoroutineContext().ensureActive()
        val prerequisite = command.acknowledgementPrerequisite()
        if (
            batchObservation?.observe(
                InboundBatchObservation(arrival.messageId, token, prerequisite),
            ) == InboundBatchObservationResult.CONFLICT
        ) {
            return InboundCoordinatorResult.ConflictNoAck(
                arrival.messageId,
                InboundConflictReason.HANDLED_FINGERPRINT_CONFLICT,
            )
        }
        val receipt = InboundOwnerReceipt(
            messageId = arrival.messageId,
            authenticatedToken = token,
            continuity = arrival.continuity,
            handledAt = checkedNow(),
        )
        val result = if (alreadyHandled) {
            when (prerequisite) {
                InboundAcknowledgementPrerequisite.NONE ->
                    InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE)
                InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION,
                InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION,
                -> if (deferPresentation) {
                    InboundOwnerCommitResult.AcknowledgementReady(
                        RelayHandledDisposition.DUPLICATE,
                        prerequisite,
                    )
                } else {
                    handledReplay.reconcile(command, receipt)
                }
            }
        } else if (
            command.descriptor.commitBoundary == InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT
        ) {
            coreDispatch.dispatch(command, receipt)
        } else {
            operationalDispatch.dispatch(
                command,
                receipt,
                if (deferPresentation) {
                    InboundPresentationPolicy.DEFER_UNTIL_BATCH_END
                } else {
                    InboundPresentationPolicy.COMPLETE_BEFORE_ACK
                },
            )
        }
        currentCoroutineContext().ensureActive()
        if (
            deferPresentation &&
            prerequisite != InboundAcknowledgementPrerequisite.NONE &&
            result is InboundOwnerCommitResult.AcknowledgementReady
        ) {
            check(result.prerequisite == prerequisite) {
                "finite-batch presentation was completed before validated END"
            }
        }
        return result.toCoordinatorResult(arrival, token, command)
    }

    private suspend fun finalizeGenericTerminal(
        arrival: InboundEnvelopeArrival,
        token: AuthenticatedRelayToken,
        disposition: RelayHandledDisposition,
    ): InboundCoordinatorResult = when (
        relayRepository.finalize(
            continuity = arrival.continuity,
            request = RelayFinalizeRequest(
                messageId = arrival.messageId,
                authenticatedToken = token,
                handledAt = checkedNow(),
            ),
        )
    ) {
        RelayFinalizeOutcome.APPLIED,
        RelayFinalizeOutcome.ALREADY_FINALIZED,
        -> acknowledgementPending(arrival, token, disposition)
        RelayFinalizeOutcome.LEGACY_RETAINED_NO_ACK ->
            InboundCoordinatorResult.LegacyRetainedNoAck(arrival.messageId)
        RelayFinalizeOutcome.CONFLICT -> InboundCoordinatorResult.ConflictNoAck(
            arrival.messageId,
            InboundConflictReason.GENERIC_FINALIZATION_CONFLICT,
        )
        RelayFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH ->
            InboundCoordinatorResult.StorageContinuityChanged(arrival.messageId)
    }

    private fun InboundOwnerCommitResult.toCoordinatorResult(
        arrival: InboundEnvelopeArrival,
        token: AuthenticatedRelayToken,
        command: PlannedInboundCommand,
    ): InboundCoordinatorResult = when (this) {
        is InboundOwnerCommitResult.AcknowledgementReady -> {
            val expected = command.acknowledgementPrerequisite()
            check(prerequisite == InboundAcknowledgementPrerequisite.NONE || prerequisite == expected) {
                "inbound owner returned an ACK prerequisite incompatible with the reviewed descriptor"
            }
            acknowledgementPending(arrival, token, disposition, prerequisite)
        }
        is InboundOwnerCommitResult.RetryRequired ->
            InboundCoordinatorResult.RetryRequired(arrival.messageId, errorCode)
        is InboundOwnerCommitResult.SecurityBlocked ->
            InboundCoordinatorResult.SecurityBlocked(arrival.messageId, errorCode)
        InboundOwnerCommitResult.LegacyRetainedNoAck ->
            InboundCoordinatorResult.LegacyRetainedNoAck(arrival.messageId)
        InboundOwnerCommitResult.ConflictNoAck -> InboundCoordinatorResult.ConflictNoAck(
            arrival.messageId,
            InboundConflictReason.OWNER_COMMIT_CONFLICT,
        )
        InboundOwnerCommitResult.StorageContinuityMismatch ->
            InboundCoordinatorResult.StorageContinuityChanged(arrival.messageId)
    }

    private fun acknowledgementPending(
        arrival: InboundEnvelopeArrival,
        token: AuthenticatedRelayToken,
        disposition: RelayHandledDisposition,
        prerequisite: InboundAcknowledgementPrerequisite = InboundAcknowledgementPrerequisite.NONE,
    ): InboundCoordinatorResult.AcknowledgementPending = InboundCoordinatorResult.AcknowledgementPending(
        InboundAcknowledgementCandidate(
            messageId = arrival.messageId,
            authenticatedToken = token,
            continuity = arrival.continuity,
            disposition = disposition,
            prerequisite = prerequisite,
        ),
    )

    private fun retry(arrival: InboundEnvelopeArrival, code: String): InboundCoordinatorResult =
        InboundCoordinatorResult.RetryRequired(arrival.messageId, RelayStableCode.of(code))

    private fun security(arrival: InboundEnvelopeArrival, code: String): InboundCoordinatorResult =
        InboundCoordinatorResult.SecurityBlocked(arrival.messageId, RelayStableCode.of(code))

    private fun PlannedInboundCommand.withLocalPolicy(processingAt: Long): PlannedInboundCommand {
        if (!localDeliveryPolicy.forceSilent(descriptor, delivery.acceptedAt, processingAt)) return this
        val body = delivery.encodedBody
        return try {
            copy(
                delivery = AuthenticatedInboundDelivery(
                    messageId = delivery.messageId,
                    messageType = delivery.messageType,
                    senderId = delivery.senderId,
                    senderOwnDevice = delivery.senderOwnDevice,
                    signerEpoch = delivery.signerEpoch,
                    signedSequence = delivery.signedSequence,
                    signedCreatedAt = delivery.signedCreatedAt,
                    recipientEpoch = delivery.recipientEpoch,
                    encodedBody = body,
                    acceptedAt = delivery.acceptedAt,
                    deliveryMode = delivery.deliveryMode,
                    forceSilent = true,
                ),
            )
        } finally {
            body.fill(0)
        }
    }

    private fun checkedNow(): Long = clock.now().also {
        require(it in 1 until Long.MAX_VALUE) { "inbound clock must return a positive schedulable time" }
    }
}

/** Process-wide reset exclusion; unlike a Room transaction, it may safely cover the following network ACK. */
internal interface InboundResetExclusionGate {
    suspend fun <T> withResetExcluded(block: suspend () -> T): T
}

internal class OperationalMaintenanceInboundResetGate(
    private val gate: OperationalStorageMaintenanceGate,
) : InboundResetExclusionGate {
    override suspend fun <T> withResetExcluded(block: suspend () -> T): T = gate.withExclusiveAccess(block)
}

/** Existing wire-level ACK implementation is injected here; this port does not alter envelope/ACK semantics. */
internal fun interface InboundNetworkAcknowledgementPort {
    /** True only when the unchanged broker ACK operation was accepted. */
    suspend fun acknowledge(messageId: String): Boolean
}

internal sealed interface InboundAcknowledgementResult {
    data object Acknowledged : InboundAcknowledgementResult
    data object NotAcceptedNoAck : InboundAcknowledgementResult
    data object PrerequisitePendingNoAck : InboundAcknowledgementResult
    data object LegacyRetainedNoAck : InboundAcknowledgementResult
    data object MissingNoAck : InboundAcknowledgementResult
    data object ConflictNoAck : InboundAcknowledgementResult
    data object StorageContinuityChanged : InboundAcknowledgementResult
}

internal fun interface InboundAcknowledgementPort {
    suspend fun acknowledge(candidate: InboundAcknowledgementCandidate): InboundAcknowledgementResult
}

internal data class DirectInboundExecutionResult(
    val processing: InboundCoordinatorResult,
    val acknowledgement: InboundAcknowledgementResult?,
)

/**
 * Serializes one direct broker delivery through every required owner/presentation effect.
 *
 * The process gate is released before the network ACK; the ACK coordinator independently performs its final
 * continuity read and network call under the reset-exclusion gate, with no Room transaction held open.
 */
internal class SerializedDirectInboundProcessor(
    private val processGate: InboundProcessGate,
    private val delivery: InboundDirectDeliveryPort,
    private val acknowledgements: InboundAcknowledgementPort,
) {
    suspend fun process(arrival: InboundEnvelopeArrival): DirectInboundExecutionResult {
        val processing = processGate.withInboundExclusive { delivery.receive(arrival) }
        currentCoroutineContext().ensureActive()
        val acknowledgement = (processing as? InboundCoordinatorResult.AcknowledgementPending)?.let {
            acknowledgements.acknowledge(it.candidate)
        }
        return DirectInboundExecutionResult(processing, acknowledgement)
    }
}

/**
 * Final ACK authorization. The resolver's Room transaction closes before the network call, while the process-wide
 * reset gate remains held so reset cannot erase the evidence between authorization and ACK acceptance.
 */
internal class InboundAcknowledgementCoordinator(
    private val relayRepository: RelayRepository,
    private val resetGate: InboundResetExclusionGate,
    private val network: InboundNetworkAcknowledgementPort,
) : InboundAcknowledgementPort {
    override suspend fun acknowledge(candidate: InboundAcknowledgementCandidate): InboundAcknowledgementResult =
        resetGate.withResetExcluded {
            if (candidate.prerequisite != InboundAcknowledgementPrerequisite.NONE) {
                return@withResetExcluded InboundAcknowledgementResult.PrerequisitePendingNoAck
            }
            when (
                relayRepository.resolveHandled(
                    continuity = candidate.continuity,
                    messageId = candidate.messageId,
                    authenticatedToken = candidate.authenticatedToken,
                )
            ) {
                RelayHandledResolution.ExactAuthenticated -> {
                    // No Room transaction remains open here; reset exclusion alone spans the network operation.
                    if (network.acknowledge(candidate.messageId)) {
                        InboundAcknowledgementResult.Acknowledged
                    } else {
                        InboundAcknowledgementResult.NotAcceptedNoAck
                    }
                }
                RelayHandledResolution.LegacyRetainedNoAck ->
                    InboundAcknowledgementResult.LegacyRetainedNoAck
                RelayHandledResolution.Missing -> InboundAcknowledgementResult.MissingNoAck
                RelayHandledResolution.Conflict -> InboundAcknowledgementResult.ConflictNoAck
                RelayHandledResolution.StorageContinuityMismatch ->
                    InboundAcknowledgementResult.StorageContinuityChanged
            }
        }
}

private fun PlannedInboundCommand.acknowledgementPrerequisite(): InboundAcknowledgementPrerequisite =
    when (payload) {
        is DecodedInboundPayload.Notification -> InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION
        is DecodedInboundPayload.Dismissal -> InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION
        is DecodedInboundPayload.Action -> InboundAcknowledgementPrerequisite.NONE
        is DecodedInboundPayload.Data -> when (payload.value.kind) {
            net.extrawdw.notisync.protocol.DataSyncKind.NOTIFICATION ->
                InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION
            else -> InboundAcknowledgementPrerequisite.NONE
        }
    }

private fun InboundSecureOpenResult.Ready.matchesSignedEnvelope(envelope: DecodedInboundEnvelope): Boolean =
    senderId == envelope.signerId &&
        messageType == envelope.messageType &&
        signerEpoch == envelope.signerEpoch &&
        messageId == envelope.messageId &&
        signedSequence == envelope.signedSequence &&
        signedCreatedAt == envelope.signedCreatedAt &&
        envelope.recipients.any { it.recipientEpoch == recipientEpoch }

private fun RelayDeliveryMode.toInboundDeliveryMode(): InboundDeliveryMode = when (this) {
    RelayDeliveryMode.UNKNOWN -> InboundDeliveryMode.UNKNOWN
    RelayDeliveryMode.WEBSOCKET -> InboundDeliveryMode.WEBSOCKET
    RelayDeliveryMode.FCM_INLINE -> InboundDeliveryMode.FCM_INLINE
    RelayDeliveryMode.FCM_RELAY_FETCH -> InboundDeliveryMode.FCM_RELAY_FETCH
    RelayDeliveryMode.RELAY_DRAIN -> InboundDeliveryMode.RELAY_DRAIN
}
