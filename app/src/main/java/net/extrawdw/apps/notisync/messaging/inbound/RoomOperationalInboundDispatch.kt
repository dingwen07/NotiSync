package net.extrawdw.apps.notisync.messaging.inbound

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.extrawdw.apps.notisync.data.activity.ActivityEventId
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.activity.ActivitySemanticCode
import net.extrawdw.apps.notisync.data.activity.ActivityStableIdentifier
import net.extrawdw.apps.notisync.data.incomingfilter.CanonicalIncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterCanonicalizer
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleValue
import net.extrawdw.apps.notisync.data.storage.operational.ActivityAction
import net.extrawdw.apps.notisync.data.storage.operational.ActivityDirection
import net.extrawdw.apps.notisync.data.storage.operational.ActivityEventEntity
import net.extrawdw.apps.notisync.data.storage.operational.ActivityFeature
import net.extrawdw.apps.notisync.data.storage.operational.ActivityOutcome
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterDao
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterEntity
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterRuleEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.MirrorLifecycleDao
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDeliveryMode
import net.extrawdw.apps.notisync.data.storage.operational.OperationalFeatureCommitResult
import net.extrawdw.apps.notisync.data.storage.operational.PreparedOperationalReceipt
import net.extrawdw.apps.notisync.data.storage.operational.RelayDao
import net.extrawdw.apps.notisync.data.storage.operational.RelayFinalizeResult
import net.extrawdw.apps.notisync.data.storage.operational.RunDao
import net.extrawdw.apps.notisync.data.storage.operational.RunPhaseToken
import net.extrawdw.apps.notisync.data.storage.operational.RunStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenDao
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundDeliveryMode
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.ProtocolLeaf
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind

/** Result of a non-durable Android effect performed after its owner transaction commits. */
internal enum class InboundEffectResult {
    COMPLETED,
    NO_OP,
    RETRY_REQUIRED,
    SECURITY_BLOCKED,
}

/** External effects whose bytes/state cannot be made part of Room's SQLite transaction. */
internal interface OperationalInboundEffects {
    suspend fun presentNotification(
        notification: CapturedNotification,
        forceSilent: Boolean,
    ): InboundEffectResult

    suspend fun dismissNotification(event: DismissEvent): InboundEffectResult

    /** The action is deliberately called before its handled receipt is committed. */
    suspend fun performAction(event: ActionEvent): InboundEffectResult

    companion object {
        /** Useful only for tests and for feature builds that intentionally do not present notifications. */
        val NO_OP: OperationalInboundEffects = object : OperationalInboundEffects {
            override suspend fun presentNotification(
                notification: CapturedNotification,
                forceSilent: Boolean,
            ): InboundEffectResult = InboundEffectResult.NO_OP

            override suspend fun dismissNotification(event: DismissEvent): InboundEffectResult =
                InboundEffectResult.NO_OP

            override suspend fun performAction(event: ActionEvent): InboundEffectResult =
                InboundEffectResult.NO_OP
        }
    }
}

/**
 * Compatibility bridge for authenticated leaves whose feature handlers predate the Room owner APIs.
 * The implementation must complete the decoded feature effect before returning success; this dispatch then
 * records the exact handled evidence as the final step.
 */
internal fun interface OperationalInboundCompatibilityEffects {
    suspend fun handle(command: PlannedInboundCommand): InboundEffectResult

    companion object {
        /** Safe default while a feature owner is unavailable: it never ACKs the item. */
        val UNAVAILABLE: OperationalInboundCompatibilityEffects = OperationalInboundCompatibilityEffects {
            InboundEffectResult.RETRY_REQUIRED
        }
    }
}

/**
 * Protected Seal storage is intentionally prepared outside this adapter. The request and its wrapped projection
 * must be produced by the Seal owner, which knows the active payload-key generation. That owner still commits its
 * feature rows and [PreparedOperationalReceipt] in one Room transaction.
 */
internal fun interface SealInboundReceiptPort {
    suspend fun accept(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        now: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult
}

/** Same boundary for SSH provider history/custody, whose protected payload contract is SSH-owned. */
internal fun interface SshInboundReceiptPort {
    suspend fun accept(
        request: SshAgentSync,
        senderClientId: ClientId,
        now: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult
}

/**
 * Production Operational owner for the already-decoded inbound command.
 *
 * This class is deliberately the only place where the messaging boundary knows which Room DAO owns a leaf. Each
 * supported mutation calls a named `*WithReceipt` DAO method; no generic handled finalization is appended after a
 * successful feature write. Compatibility leaves finalize only after their injected feature effect succeeds, while
 * wrong-database owners remain typed no-ACK outcomes.
 */
internal class RoomOperationalInboundDispatch(
    private val mirrorDao: MirrorLifecycleDao,
    private val relayDao: RelayDao,
    private val runDao: RunDao,
    private val filterDao: IncomingFilterDao,
    private val screenDao: ScreenDao,
    private val effects: OperationalInboundEffects,
    private val compatibilityEffects: OperationalInboundCompatibilityEffects =
        OperationalInboundCompatibilityEffects.UNAVAILABLE,
    private val sealPort: SealInboundReceiptPort? = null,
    private val sshPort: SshInboundReceiptPort? = null,
) : OperationalInboundDispatchPort, HandledInboundReplayPort {
    internal constructor(
        database: OperationalDatabase,
        effects: OperationalInboundEffects,
        compatibilityEffects: OperationalInboundCompatibilityEffects =
            OperationalInboundCompatibilityEffects.UNAVAILABLE,
        sealPort: SealInboundReceiptPort? = null,
        sshPort: SshInboundReceiptPort? = null,
    ) : this(
        mirrorDao = database.mirrorLifecycleDao(),
        relayDao = database.relayDao(),
        runDao = database.runDao(),
        filterDao = database.incomingFilterDao(),
        screenDao = database.screenDao(),
        effects = effects,
        compatibilityEffects = compatibilityEffects,
        sealPort = sealPort,
        sshPort = sshPort,
    )

    override suspend fun dispatch(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        presentationPolicy: InboundPresentationPolicy,
    ): InboundOwnerCommitResult {
        currentCoroutineContext().ensureActive()
        return when (val leaf = command.descriptor.leaf) {
            ProtocolLeaf.Notification -> dispatchNotification(command, receipt, presentationPolicy)
            ProtocolLeaf.Dismissal -> dispatchDismissal(command, receipt, presentationPolicy)
            is ProtocolLeaf.Action -> dispatchAction(command, receipt)
            ProtocolLeaf.QuietNotification -> dispatchQuietNotification(command, receipt, presentationPolicy)
            ProtocolLeaf.Filter -> dispatchFilter(command, receipt)
            is ProtocolLeaf.Run -> dispatchRun(command, receipt)
            is ProtocolLeaf.Screen -> dispatchScreen(command, receipt)
            is ProtocolLeaf.Seal -> dispatchSeal(command, receipt)
            is ProtocolLeaf.Ssh -> dispatchSsh(command, receipt)
            is ProtocolLeaf.Asset -> dispatchCompatibility(command, receipt)
            ProtocolLeaf.Profile,
            ProtocolLeaf.Trust,
            ProtocolLeaf.Card,
            -> InboundOwnerCommitResult.SecurityBlocked(
                net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("wrong_database_owner"),
            )
        }
    }

    override suspend fun reconcile(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        currentCoroutineContext().ensureActive()
        return when (val payload = command.payload) {
            is DecodedInboundPayload.Notification -> effects.presentNotification(
                payload.value,
                command.delivery.forceSilent,
            ).toOwnerResult()
            is DecodedInboundPayload.Dismissal -> effects.dismissNotification(payload.value)
                .toOwnerResult()
            is DecodedInboundPayload.Data -> when (payload.value.kind) {
                DataSyncKind.NOTIFICATION -> {
                    val notification = payload.value.notification ?: return malformedPayload()
                    effects.presentNotification(notification, command.delivery.forceSilent).toOwnerResult()
                }
                else -> InboundOwnerCommitResult.AcknowledgementReady(
                    net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.DUPLICATE,
                )
            }
            is DecodedInboundPayload.Action ->
                InboundOwnerCommitResult.AcknowledgementReady(
                    net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.DUPLICATE,
                )
        }
    }

    private suspend fun dispatchNotification(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        presentationPolicy: InboundPresentationPolicy,
    ): InboundOwnerCommitResult {
        val notification = (command.payload as? DecodedInboundPayload.Notification)?.value
            ?: return malformedPayload()
        if (notification.postTime <= 0) return security("invalid_notification")
        val prepared = preparedReceipt(command, receipt, ActivityAction.RECEIVED, ActivityFeature.NOTIFICATION)
        val result = mirrorDao.acceptPostWithReceipt(
            sourceClientId = notification.sourceClientId.value,
            sourceKey = notification.sourceKey,
            postTime = notification.postTime,
            updatedAt = receipt.handledAt,
            receipt = prepared,
        )
        return result.afterNotificationPresentation(
            presentationPolicy = presentationPolicy,
            present = { effects.presentNotification(notification, command.delivery.forceSilent) },
        )
    }

    private suspend fun dispatchQuietNotification(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        presentationPolicy: InboundPresentationPolicy,
    ): InboundOwnerCommitResult {
        val data = (command.payload as? DecodedInboundPayload.Data)?.value
            ?: return malformedPayload()
        val notification = data.notification ?: return malformedPayload()
        if (notification.postTime <= 0) return security("invalid_notification")
        val prepared = preparedReceipt(command, receipt, ActivityAction.RECEIVED, ActivityFeature.NOTIFICATION)
        val result = mirrorDao.acceptPostWithReceipt(
            sourceClientId = notification.sourceClientId.value,
            sourceKey = notification.sourceKey,
            postTime = notification.postTime,
            updatedAt = receipt.handledAt,
            receipt = prepared,
        )
        return result.afterNotificationPresentation(
            presentationPolicy = presentationPolicy,
            present = { effects.presentNotification(notification, true) },
        )
    }

    private suspend fun dispatchDismissal(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        presentationPolicy: InboundPresentationPolicy,
    ): InboundOwnerCommitResult {
        val event = (command.payload as? DecodedInboundPayload.Dismissal)?.value
            ?: return malformedPayload()
        if (event.dismissedAt <= 0) return security("invalid_dismissal")
        val prepared = preparedReceipt(command, receipt, ActivityAction.DISMISSED, ActivityFeature.NOTIFICATION)
        val result = mirrorDao.recordDismissalWithReceipt(
            sourceClientId = event.sourceClientId.value,
            sourceKey = event.sourceKey,
            dismissedAt = event.dismissedAt,
            updatedAt = receipt.handledAt,
            receipt = prepared,
        )
        return result.afterDismissalPresentation(
            presentationPolicy = presentationPolicy,
            dismiss = { effects.dismissNotification(event) },
        )
    }

    private suspend fun dispatchAction(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val event = (command.payload as? DecodedInboundPayload.Action)?.value
            ?: return malformedPayload()
        when (val effect = effects.performAction(event)) {
            InboundEffectResult.RETRY_REQUIRED -> return retry("action_effect_retry")
            InboundEffectResult.SECURITY_BLOCKED -> return security("action_effect_blocked")
            InboundEffectResult.COMPLETED,
            InboundEffectResult.NO_OP,
            -> Unit
        }
        val prepared = preparedReceipt(command, receipt, ActivityAction.CONTROLLED, ActivityFeature.NOTIFICATION)
        return mirrorDao.finalizeNotificationActionReceipt(prepared).toOwnerResult()
    }

    private suspend fun dispatchFilter(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val data = (command.payload as? DecodedInboundPayload.Data)?.value
            ?: return malformedPayload()
        val filter = data.filter ?: return malformedPayload()
        if (filter.updatedAt <= 0) return security("invalid_filter")
        val canonical = try {
            IncomingFilterCanonicalizer.canonicalize(
                filter.rules.map { rule ->
                    IncomingFilterRuleValue(
                        origin = when (rule.originPlatform) {
                            net.extrawdw.notisync.protocol.OriginPlatform.ANDROID_LOCAL ->
                                CanonicalIncomingFilterOrigin.ANDROID_LOCAL
                            net.extrawdw.notisync.protocol.OriginPlatform.IOS_ANCS ->
                                CanonicalIncomingFilterOrigin.IOS_ANCS
                        },
                        appId = rule.appId,
                        channelId = rule.channelId,
                    )
                },
            )
        } catch (_: IllegalArgumentException) {
            return security("invalid_filter")
        }
        val header = IncomingFilterEntity(
            requesterClientId = command.delivery.senderId.value,
            canonicalizationVersion = IncomingFilterCanonicalizer.VERSION,
            updatedAt = filter.updatedAt,
            receivedAt = receipt.handledAt,
            ruleSetDigest = canonical.digestCopy(),
        )
        val rules = canonical.rules.map { rule ->
            IncomingFilterRuleEntity(
                requesterClientId = command.delivery.senderId.value,
                ruleDigest = rule.digestCopy(),
                position = rule.position,
                originPlatform = when (rule.value.origin) {
                    CanonicalIncomingFilterOrigin.ANDROID_LOCAL ->
                        net.extrawdw.apps.notisync.data.storage.operational.NotificationOriginPlatform.ANDROID_LOCAL
                    CanonicalIncomingFilterOrigin.IOS_ANCS ->
                        net.extrawdw.apps.notisync.data.storage.operational.NotificationOriginPlatform.IOS_ANCS
                },
                appId = rule.value.appId,
                channelId = rule.value.channelId,
            )
        }
        return try {
            filterDao.replaceWithReceipt(
                header = header,
                rules = rules,
                receipt = preparedReceipt(command, receipt, ActivityAction.APPLIED, ActivityFeature.NOTIFICATION),
            ).toOwnerResult()
        } catch (_: IllegalArgumentException) {
            security("invalid_filter")
        }
    }

    private suspend fun dispatchRun(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val data = (command.payload as? DecodedInboundPayload.Data)?.value
            ?: return malformedPayload()
        val run = data.run ?: return malformedPayload()
        val state = run.state
        if (run.kind != net.extrawdw.notisync.protocol.RunSyncKind.STATE || state == null) {
            return dispatchCompatibility(command, receipt)
        }
        if (state.hostClientId != command.delivery.senderId) return security("run_sender_mismatch")
        val payload = ProtocolCodec.encodeToCbor(state)
        return try {
            val candidate = RunStateEntity(
                hostClientId = state.hostClientId.value,
                runId = state.runId,
                revision = state.revision,
                phase = state.phase.toStorageToken(),
                presentedRevision = -1L,
                active = state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED,
                updatedAt = state.updatedAt,
                endedAt = state.endedAt,
                receivedAt = receipt.handledAt,
                payload = payload.copyOf(),
                payloadDigest = sha256(payload),
            )
            runDao.compareAndUpsertWithReceipt(
                candidate = candidate,
                receipt = preparedReceipt(command, receipt, ActivityAction.APPLIED, ActivityFeature.RUN),
            ).toOwnerResult()
        } finally {
            payload.fill(0)
        }
    }

    private suspend fun dispatchScreen(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val data = (command.payload as? DecodedInboundPayload.Data)?.value
            ?: return malformedPayload()
        val request = data.screenMirror ?: return malformedPayload()
        if (request.action != ScreenMirrorAction.REQUEST) return dispatchCompatibility(command, receipt)
        if (request.requesterPeerId != command.delivery.senderId) return security("screen_sender_mismatch")
        val routingToken = request.routingToken ?: return security("screen_request_missing_token")
        val expiresAt = request.expiresAt ?: return security("screen_request_missing_expiry")
        if (
            request.sessionId.isBlank() || request.sessionId.length > 128 ||
            routingToken.size != SCREEN_ROUTING_TOKEN_BYTES || expiresAt <= receipt.handledAt
        ) return security("invalid_screen_request")
        val prepared = preparedReceipt(command, receipt, ActivityAction.REQUESTED, ActivityFeature.SCREEN_MIRRORING)
        return screenDao.consumeReplayWithReceipt(
            sessionDigest = replayDigest(SESSION_REPLAY_DOMAIN, request.sessionId.encodeToByteArray()),
            routingTokenDigest = replayDigest(TOKEN_REPLAY_DOMAIN, routingToken),
            expiresAt = expiresAt,
            consumedAt = receipt.handledAt,
            receipt = prepared,
        ).toOwnerResult().afterCompatibilityEffect(command)
    }

    private suspend fun dispatchSeal(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val request = ((command.payload as? DecodedInboundPayload.Data)?.value?.openPgpSign)
            ?: return malformedPayload()
        if (request.action != OpenPgpSignAction.REQUEST) return dispatchCompatibility(command, receipt)
        val prepared = preparedReceipt(command, receipt, ActivityAction.REQUESTED, ActivityFeature.SEAL)
        return ownerResult(
            invalidCode = "invalid_seal_request",
            retryCode = "seal_owner_unavailable",
        ) {
            sealPort?.accept(
                request = request,
                senderClientId = command.delivery.senderId,
                now = receipt.handledAt,
                receipt = prepared,
            )
        }.afterCompatibilityEffect(command)
    }

    private suspend fun dispatchSsh(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val request = ((command.payload as? DecodedInboundPayload.Data)?.value?.sshAgent)
            ?: return malformedPayload()
        if (request.kind !in setOf(SshAgentSyncKind.SIGN_REQUEST, SshAgentSyncKind.IMPORT_REQUEST)) {
            return dispatchCompatibility(command, receipt)
        }
        val prepared = preparedReceipt(command, receipt, ActivityAction.REQUESTED, ActivityFeature.SSH_AGENT)
        return ownerResult(
            invalidCode = "invalid_ssh_request",
            retryCode = "ssh_owner_unavailable",
        ) {
            sshPort?.accept(
                request = request,
                senderClientId = command.delivery.senderId,
                now = receipt.handledAt,
                receipt = prepared,
            )
        }.afterCompatibilityEffect(command)
    }

    private suspend fun dispatchCompatibility(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
    ): InboundOwnerCommitResult {
        val effect = try {
            compatibilityEffects.handle(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retry("compatibility_effect_retry")
        }
        return when (effect) {
            InboundEffectResult.RETRY_REQUIRED -> retry("compatibility_effect_retry")
            InboundEffectResult.SECURITY_BLOCKED -> security("compatibility_effect_blocked")
            InboundEffectResult.COMPLETED,
            InboundEffectResult.NO_OP,
            -> {
                currentCoroutineContext().ensureActive()
                relayDao.finalizeHandled(
                    handled = receipt.toHandledEntity(),
                    expectedOperationalGeneration = receipt.continuity.generation,
                    expectedStorageIncarnationId = receipt.continuity.storageIncarnationId,
                    activity = null,
                ).toCompatibilityOwnerResult()
            }
        }
    }

    /**
     * Runs the feature's idempotent post-commit work before exposing an owner ACK.
     *
     * The owner transaction may already have persisted handled evidence. A failed effect therefore returns
     * retry/no-ACK and relies on exact duplicate redelivery to enter this method again; the effect must be
     * idempotent for that reason.
     */
    private suspend fun InboundOwnerCommitResult.afterCompatibilityEffect(
        command: PlannedInboundCommand,
    ): InboundOwnerCommitResult {
        if (this !is InboundOwnerCommitResult.AcknowledgementReady) return this
        val effect = try {
            compatibilityEffects.handle(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retry("compatibility_effect_retry")
        }
        return when (effect) {
            InboundEffectResult.COMPLETED,
            InboundEffectResult.NO_OP,
            -> this
            InboundEffectResult.RETRY_REQUIRED -> retry("compatibility_effect_retry")
            InboundEffectResult.SECURITY_BLOCKED -> security("compatibility_effect_blocked")
        }
    }

    private suspend fun ownerResult(
        invalidCode: String,
        retryCode: String,
        accept: suspend () -> OperationalFeatureCommitResult?,
    ): InboundOwnerCommitResult = try {
        accept()?.toOwnerResult() ?: retry(retryCode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        security(invalidCode)
    } catch (_: Exception) {
        retry(retryCode)
    }

    private fun preparedReceipt(
        command: PlannedInboundCommand,
        receipt: InboundOwnerReceipt,
        action: ActivityAction,
        feature: ActivityFeature,
    ): PreparedOperationalReceipt = PreparedOperationalReceipt.prepare(
        handled = receipt.toHandledEntity(),
        expectedOperationalGeneration = receipt.continuity.generation,
        expectedStorageIncarnationId = receipt.continuity.storageIncarnationId,
        activity = ActivityEventEntity(
            eventId = activityId(command, action, feature),
            occurredAt = command.delivery.signedCreatedAt,
            recordedAt = receipt.handledAt,
            feature = feature,
            semanticAction = action,
            direction = ActivityDirection.INBOUND,
            outcome = ActivityOutcome.SUCCESS,
            peerClientId = command.delivery.senderId.value,
            correlationId = command.delivery.messageId,
            deliveryMode = command.delivery.deliveryMode.toStorageMode(),
            renderArgsVersion = ActivityRenderArgsCodec.CURRENT_VERSION,
            renderArgs = ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1()),
            coalescingKeyToken = null,
            coalescedCount = 1,
        ),
    )

    private fun activityId(
        command: PlannedInboundCommand,
        action: ActivityAction,
        feature: ActivityFeature,
    ): String = ActivityEventId.derive(
        semanticCode = ActivitySemanticCode.of("inbound.${feature.token}.${action.token}"),
        identifiers = listOf(ActivityStableIdentifier.of(command.delivery.messageId)),
    )

    private fun InboundOwnerReceipt.toHandledEntity(): MessageDedupEntity = MessageDedupEntity(
        messageId = messageId,
        authenticatedFingerprint = authenticatedToken.copyBytes(),
        evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
        handledAt = handledAt,
    )

    private fun InboundEffectResult.toOwnerResult(): InboundOwnerCommitResult = when (this) {
        InboundEffectResult.COMPLETED,
        InboundEffectResult.NO_OP,
        -> InboundOwnerCommitResult.AcknowledgementReady(
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.DUPLICATE,
        )
        InboundEffectResult.RETRY_REQUIRED -> retry("presentation_retry")
        InboundEffectResult.SECURITY_BLOCKED -> security("presentation_blocked")
    }

    private fun OperationalFeatureCommitResult.toOwnerResult(): InboundOwnerCommitResult = when (this) {
        is OperationalFeatureCommitResult.AcknowledgementReady ->
            InboundOwnerCommitResult.AcknowledgementReady(disposition.toRelayDisposition())
        is OperationalFeatureCommitResult.RetryRequired -> retry(errorCode)
        is OperationalFeatureCommitResult.SecurityBlocked -> security(errorCode)
        OperationalFeatureCommitResult.LegacyRetainedNoAck -> InboundOwnerCommitResult.LegacyRetainedNoAck
        OperationalFeatureCommitResult.ConflictNoAck -> InboundOwnerCommitResult.ConflictNoAck
        OperationalFeatureCommitResult.StorageContinuityMismatch ->
            InboundOwnerCommitResult.StorageContinuityMismatch
    }

    private fun RelayFinalizeResult.toCompatibilityOwnerResult(): InboundOwnerCommitResult = when (this) {
        RelayFinalizeResult.APPLIED -> InboundOwnerCommitResult.AcknowledgementReady(
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.APPLIED,
        )
        RelayFinalizeResult.ALREADY_FINALIZED -> InboundOwnerCommitResult.AcknowledgementReady(
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.DUPLICATE,
        )
        RelayFinalizeResult.LEGACY_RETAINED_NO_ACK -> InboundOwnerCommitResult.LegacyRetainedNoAck
        RelayFinalizeResult.CONFLICT -> InboundOwnerCommitResult.ConflictNoAck
        RelayFinalizeResult.STORAGE_CONTINUITY_MISMATCH -> InboundOwnerCommitResult.StorageContinuityMismatch
    }

    private fun net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition
        .toRelayDisposition(): net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition = when (this) {
        net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition.APPLIED ->
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.APPLIED
        net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition.DUPLICATE ->
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.DUPLICATE
        net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition.SUPERSEDED ->
            net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition.SUPERSEDED
    }

    private fun malformedPayload(): InboundOwnerCommitResult = security("inbound_payload_mismatch")

    private fun retry(code: String): InboundOwnerCommitResult =
        InboundOwnerCommitResult.RetryRequired(
            net.extrawdw.apps.notisync.data.relay.RelayStableCode.of(code),
        )

    private fun security(code: String): InboundOwnerCommitResult =
        InboundOwnerCommitResult.SecurityBlocked(
            net.extrawdw.apps.notisync.data.relay.RelayStableCode.of(code),
        )

    private suspend fun OperationalFeatureCommitResult.afterDismissalPresentation(
        presentationPolicy: InboundPresentationPolicy,
        dismiss: suspend () -> InboundEffectResult,
    ): InboundOwnerCommitResult {
        if (this !is OperationalFeatureCommitResult.AcknowledgementReady) return toOwnerResult()
        if (disposition != net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition.APPLIED) {
            return toOwnerResult()
        }
        if (presentationPolicy == InboundPresentationPolicy.DEFER_UNTIL_BATCH_END) {
            return InboundOwnerCommitResult.AcknowledgementReady(
                disposition.toRelayDisposition(),
                InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION,
            )
        }
        return when (val effect = dismiss()) {
            InboundEffectResult.COMPLETED,
            InboundEffectResult.NO_OP,
            -> InboundOwnerCommitResult.AcknowledgementReady(disposition.toRelayDisposition())
            InboundEffectResult.RETRY_REQUIRED -> retry("dismissal_presentation_retry")
            InboundEffectResult.SECURITY_BLOCKED -> security("dismissal_presentation_blocked")
        }
    }

    private suspend fun OperationalFeatureCommitResult.afterNotificationPresentation(
        presentationPolicy: InboundPresentationPolicy,
        present: suspend () -> InboundEffectResult,
    ): InboundOwnerCommitResult {
        if (this !is OperationalFeatureCommitResult.AcknowledgementReady) return toOwnerResult()
        if (disposition != net.extrawdw.apps.notisync.data.storage.operational.OperationalReceiptDisposition.APPLIED) {
            return toOwnerResult()
        }
        if (presentationPolicy == InboundPresentationPolicy.DEFER_UNTIL_BATCH_END) {
            return InboundOwnerCommitResult.AcknowledgementReady(
                disposition.toRelayDisposition(),
                InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION,
            )
        }
        return when (val effect = present()) {
            InboundEffectResult.COMPLETED,
            InboundEffectResult.NO_OP,
            -> InboundOwnerCommitResult.AcknowledgementReady(disposition.toRelayDisposition())
            InboundEffectResult.RETRY_REQUIRED -> retry("notification_presentation_retry")
            InboundEffectResult.SECURITY_BLOCKED -> security("notification_presentation_blocked")
        }
    }

    private fun InboundDeliveryMode.toStorageMode(): OperationalDeliveryMode = when (this) {
        InboundDeliveryMode.UNKNOWN -> OperationalDeliveryMode.UNKNOWN
        InboundDeliveryMode.WEBSOCKET -> OperationalDeliveryMode.WEBSOCKET
        InboundDeliveryMode.FCM_INLINE -> OperationalDeliveryMode.FCM_INLINE
        InboundDeliveryMode.FCM_RELAY_FETCH -> OperationalDeliveryMode.FCM_RELAY_FETCH
        InboundDeliveryMode.RELAY_DRAIN -> OperationalDeliveryMode.RELAY_DRAIN
    }

    private fun RunPhase.toStorageToken(): RunPhaseToken = when (this) {
        RunPhase.RUNNING -> RunPhaseToken.RUNNING
        RunPhase.BLOCKED -> RunPhaseToken.BLOCKED
        RunPhase.COMPLETED -> RunPhaseToken.COMPLETED
        RunPhase.FAILED_TO_START -> RunPhaseToken.FAILED_TO_START
    }

    private fun replayDigest(domain: ByteArray, value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(domain + value)

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private companion object {
        val SESSION_REPLAY_DOMAIN = "notisync-screen/replay/v1/session\u0000".encodeToByteArray()
        val TOKEN_REPLAY_DOMAIN = "notisync-screen/replay/v1/token\u0000".encodeToByteArray()
        const val SCREEN_ROUTING_TOKEN_BYTES = 16
    }
}
