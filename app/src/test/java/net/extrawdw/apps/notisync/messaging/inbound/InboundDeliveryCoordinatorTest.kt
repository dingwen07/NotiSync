package net.extrawdw.apps.notisync.messaging.inbound

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeOutcome
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayHandledResolution
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.apps.notisync.data.relay.RelayRepository
import net.extrawdw.apps.notisync.messaging.DecodedInboundMessage
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundMessageRouter
import net.extrawdw.apps.notisync.messaging.InboundPayloadDecoder
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.ProtocolLeaf
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PerRecipientKey
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundDeliveryCoordinatorTest {
    @Test
    fun missingNotificationInvokesOnlyOperationalOwnerWithExactReceipt() = runTest {
        val fixture = fixture(decoded = notificationDecoded())

        val result = assertType<InboundCoordinatorResult.AcknowledgementPending>(
            fixture.coordinator.receive(fixture.arrival()),
        )

        assertEquals(1, fixture.operationalCalls)
        assertEquals(0, fixture.coreCalls)
        assertEquals(0, fixture.replayCalls)
        assertEquals(InboundAcknowledgementPrerequisite.NONE, result.candidate.prerequisite)
        assertEquals(RelayHandledDisposition.APPLIED, result.candidate.disposition)
        assertEquals(InboundPresentationPolicy.COMPLETE_BEFORE_ACK, fixture.lastPresentationPolicy)
        assertEquals(CONTINUITY, fixture.lastReceipt?.continuity)
        assertEquals("message-1", fixture.lastReceipt?.messageId)
        assertEquals(fixture.expectedToken, fixture.lastReceipt?.authenticatedToken)
        assertEquals(0, fixture.repository.finalizeCalls)
    }

    @Test
    fun coreDescriptorInvokesOnlyCoreOwner() = runTest {
        val fixture = fixture(
            messageType = MessageType.DATA_SYNC,
            decoded = profileDecoded(),
        )

        val result = fixture.coordinator.receive(fixture.arrival())

        assertTrue(result is InboundCoordinatorResult.AcknowledgementPending)
        assertEquals(0, fixture.operationalCalls)
        assertEquals(1, fixture.coreCalls)
        assertEquals(0, fixture.replayCalls)
    }

    @Test
    fun exactNotificationReplaysPresentationBeforeAckCandidate() = runTest {
        val fixture = fixture(
            decoded = notificationDecoded(),
            resolution = RelayHandledResolution.ExactAuthenticated,
            replayResult = InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE),
        )

        val result = assertType<InboundCoordinatorResult.AcknowledgementPending>(
            fixture.coordinator.receive(fixture.arrival()),
        )

        assertEquals(0, fixture.operationalCalls)
        assertEquals(0, fixture.coreCalls)
        assertEquals(1, fixture.replayCalls)
        assertEquals(InboundAcknowledgementPrerequisite.NONE, result.candidate.prerequisite)
    }

    @Test
    fun exactActionNeverReplaysExternalEffect() = runTest {
        val fixture = fixture(
            messageType = MessageType.ACTION,
            decoded = actionDecoded(),
            resolution = RelayHandledResolution.ExactAuthenticated,
        )

        val result = assertType<InboundCoordinatorResult.AcknowledgementPending>(
            fixture.coordinator.receive(fixture.arrival()),
        )

        assertEquals(RelayHandledDisposition.DUPLICATE, result.candidate.disposition)
        assertEquals(0, fixture.operationalCalls)
        assertEquals(0, fixture.replayCalls)
    }

    @Test
    fun finiteBatchDefersExactNotificationPresentationUntilValidatedEnd() = runTest {
        val fixture = fixture(
            decoded = notificationDecoded(),
            resolution = RelayHandledResolution.ExactAuthenticated,
        )
        var observed: InboundBatchObservation? = null

        val result = assertType<InboundCoordinatorResult.AcknowledgementPending>(
            fixture.coordinator.receiveForBatch(fixture.arrival()) {
                observed = it
                InboundBatchObservationResult.RECORDED
            },
        )

        assertEquals(0, fixture.replayCalls)
        assertEquals(InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION, observed?.prerequisite)
        assertEquals(InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION, result.candidate.prerequisite)
        assertEquals(null, fixture.lastPresentationPolicy)
    }

    @Test
    fun finiteBatchRejectsOwnerThatPresentsNotificationBeforeEnd() = runTest {
        val fixture = fixture(
            decoded = notificationDecoded(),
            operationalResult = InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED),
        )

        expectFailure<IllegalStateException> {
            fixture.coordinator.receiveForBatch(fixture.arrival()) {
                InboundBatchObservationResult.RECORDED
            }
        }

        assertEquals(1, fixture.operationalCalls)
        assertEquals(InboundPresentationPolicy.DEFER_UNTIL_BATCH_END, fixture.lastPresentationPolicy)
    }

    @Test
    fun finiteBatchConflictStopsBeforeOwnerMutation() = runTest {
        val fixture = fixture(decoded = notificationDecoded())

        val result = fixture.coordinator.receiveForBatch(fixture.arrival()) {
            InboundBatchObservationResult.CONFLICT
        }

        assertTrue(result is InboundCoordinatorResult.ConflictNoAck)
        assertEquals(0, fixture.operationalCalls)
    }

    @Test
    fun terminalNoFeatureDecisionUsesOnlyGenericReceiptTransaction() = runTest {
        val fixture = fixture(decoded = null)

        val result = assertType<InboundCoordinatorResult.AcknowledgementPending>(
            fixture.coordinator.receive(fixture.arrival()),
        )

        assertEquals(RelayHandledDisposition.TERMINAL_REJECTED, result.candidate.disposition)
        assertEquals(1, fixture.repository.finalizeCalls)
        assertEquals(0, fixture.operationalCalls)
        assertEquals(0, fixture.coreCalls)
    }

    @Test
    fun sameIdDifferentFingerprintConflictNeverAuthenticatesOrAcks() = runTest {
        val fixture = fixture(
            decoded = notificationDecoded(),
            resolution = RelayHandledResolution.Conflict,
        )

        val result = fixture.coordinator.receive(fixture.arrival())

        assertTrue(result is InboundCoordinatorResult.ConflictNoAck)
        assertEquals(0, fixture.openCalls)
        assertEquals(0, fixture.operationalCalls)
    }

    @Test
    fun actionCrashAfterExternalEffectBeforeReceiptPreservesAtLeastOnceBoundary() = runTest {
        var effects = 0
        val fixture = fixture(
            messageType = MessageType.ACTION,
            decoded = actionDecoded(),
            operationalBlock = { _, _ ->
                effects += 1
                if (effects == 1) throw IllegalStateException("crash after external effect")
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED)
            },
        )

        expectFailure<IllegalStateException> { fixture.coordinator.receive(fixture.arrival()) }
        val replay = fixture.coordinator.receive(fixture.arrival())

        assertTrue(replay is InboundCoordinatorResult.AcknowledgementPending)
        assertEquals(2, effects)
        assertEquals(2, fixture.operationalCalls)
    }

    @Test
    fun cancellationFromOwnerPropagatesWithoutReceiptOrAck() = runTest {
        val cancellation = CancellationException("cancelled")
        val fixture = fixture(
            decoded = notificationDecoded(),
            operationalBlock = { _, _ -> throw cancellation },
        )

        assertSame(cancellation, expectFailure<CancellationException> {
            fixture.coordinator.receive(fixture.arrival())
        })
        assertEquals(0, fixture.repository.finalizeCalls)
    }

    @Test
    fun finalAckRunsResolveAndNetworkInsideResetGateAndHonorsFalseReturn() = runTest {
        val events = mutableListOf<String>()
        val repository = FakeRelayRepository().apply {
            resolution = RelayHandledResolution.ExactAuthenticated
            onResolve = { events += "resolve" }
        }
        val gate = object : InboundResetExclusionGate {
            override suspend fun <T> withResetExcluded(block: suspend () -> T): T {
                events += "gate-enter"
                try {
                    return block()
                } finally {
                    events += "gate-exit"
                }
            }
        }
        val network = InboundNetworkAcknowledgementPort {
            events += "network"
            false
        }
        val result = InboundAcknowledgementCoordinator(repository, gate, network).acknowledge(candidate())

        assertEquals(InboundAcknowledgementResult.NotAcceptedNoAck, result)
        assertEquals(listOf("gate-enter", "resolve", "network", "gate-exit"), events)
    }

    @Test
    fun finalAckFailsClosedOnContinuityChangeAndPendingPresentation() = runTest {
        val repository = FakeRelayRepository().apply {
            resolution = RelayHandledResolution.StorageContinuityMismatch
        }
        var networkCalls = 0
        val coordinator = InboundAcknowledgementCoordinator(
            repository,
            object : InboundResetExclusionGate {
                override suspend fun <T> withResetExcluded(block: suspend () -> T): T = block()
            },
            InboundNetworkAcknowledgementPort { networkCalls += 1; true },
        )

        assertEquals(
            InboundAcknowledgementResult.StorageContinuityChanged,
            coordinator.acknowledge(candidate()),
        )
        assertEquals(
            InboundAcknowledgementResult.PrerequisitePendingNoAck,
            coordinator.acknowledge(
                candidate(InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION),
            ),
        )
        assertEquals(0, networkCalls)
    }

    @Test
    fun malformedEnvelopeIsRejectedBeforeFingerprintOrStorage() = runTest {
        val fixture = fixture(decoded = notificationDecoded())
        val malformed = InboundEnvelopeArrival(
            messageId = "message-1",
            encodedEnvelope = byteArrayOf(1),
            acceptedAt = 1,
            deliveryMode = RelayDeliveryMode.WEBSOCKET,
            continuity = CONTINUITY,
        )

        val result = fixture.coordinator.receive(malformed)

        assertTrue(result is InboundCoordinatorResult.RejectedBeforeAuthentication)
        assertEquals(0, fixture.repository.resolveCalls)
    }

    private fun fixture(
        messageType: MessageType = MessageType.NOTIFICATION,
        decoded: DecodedInboundMessage?,
        resolution: RelayHandledResolution = RelayHandledResolution.Missing,
        finalizeOutcome: RelayFinalizeOutcome = RelayFinalizeOutcome.APPLIED,
        operationalResult: InboundOwnerCommitResult =
            InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED),
        coreResult: InboundOwnerCommitResult =
            InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED),
        replayResult: InboundOwnerCommitResult =
            InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE),
        operationalBlock: (suspend (PlannedInboundCommand, InboundOwnerReceipt) -> InboundOwnerCommitResult)? = null,
    ): Fixture {
        val encodedEnvelope = encodedEnvelope(messageType)
        val repository = FakeRelayRepository().apply {
            this.resolution = resolution
            this.finalizeOutcome = finalizeOutcome
        }
        val fixture = Fixture(repository, encodedEnvelope)
        val decoder = InboundPayloadDecoder { _, _ -> decoded }
        val router = InboundMessageRouter(decoder)
        fixture.coordinator = InboundDeliveryCoordinator(
            relayRepository = repository,
            envelopeCodec = ProtocolInboundEnvelopeCodec,
            fingerprint = CanonicalInboundEnvelopeFingerprint,
            inboundEnvelopePort = InboundEnvelopePort { envelope ->
                fixture.openCalls += 1
                ready(envelope)
            },
            router = router,
            operationalDispatch = OperationalInboundDispatchPort { command, receipt, presentationPolicy ->
                fixture.operationalCalls += 1
                fixture.lastReceipt = receipt
                fixture.lastPresentationPolicy = presentationPolicy
                operationalBlock?.invoke(command, receipt) ?: operationalResult
            },
            coreDispatch = CoreInboundDispatchPort { _, receipt ->
                fixture.coreCalls += 1
                fixture.lastReceipt = receipt
                coreResult
            },
            handledReplay = HandledInboundReplayPort { _, receipt ->
                fixture.replayCalls += 1
                fixture.lastReceipt = receipt
                replayResult
            },
            clock = InboundCoordinatorClock { 10_000_000 },
        )
        fixture.expectedToken = CanonicalInboundEnvelopeFingerprint.derive(
            requireNotNull(ProtocolInboundEnvelopeCodec.decode(encodedEnvelope)),
        )
        return fixture
    }

    private class Fixture(
        val repository: FakeRelayRepository,
        private val encodedEnvelope: ByteArray,
    ) {
        lateinit var coordinator: InboundDeliveryCoordinator
        lateinit var expectedToken: AuthenticatedRelayToken
        var operationalCalls = 0
        var coreCalls = 0
        var replayCalls = 0
        var openCalls = 0
        var lastReceipt: InboundOwnerReceipt? = null
        var lastPresentationPolicy: InboundPresentationPolicy? = null

        fun arrival() = InboundEnvelopeArrival(
            messageId = "message-1",
            encodedEnvelope = encodedEnvelope,
            acceptedAt = 9_999_000,
            deliveryMode = RelayDeliveryMode.RELAY_DRAIN,
            continuity = CONTINUITY,
        )
    }

    private class FakeRelayRepository : RelayRepository {
        var resolution: RelayHandledResolution = RelayHandledResolution.Missing
        var finalizeOutcome: RelayFinalizeOutcome = RelayFinalizeOutcome.APPLIED
        var resolveCalls = 0
        var finalizeCalls = 0
        var onResolve: (() -> Unit)? = null

        override suspend fun resolveHandled(
            continuity: RelayOperationalContinuity,
            messageId: String,
            authenticatedToken: AuthenticatedRelayToken,
        ): RelayHandledResolution {
            resolveCalls += 1
            onResolve?.invoke()
            return resolution
        }

        override suspend fun finalize(
            continuity: RelayOperationalContinuity,
            request: RelayFinalizeRequest,
        ): RelayFinalizeOutcome {
            finalizeCalls += 1
            return finalizeOutcome
        }

        override suspend fun pruneHandled(now: Long): Int = 0
    }

    private fun notificationDecoded() = DecodedInboundMessage(
        ProtocolLeaf.Notification,
        DecodedInboundPayload.Notification(notification()),
    )

    private fun profileDecoded() = DecodedInboundMessage(
        ProtocolLeaf.Profile,
        DecodedInboundPayload.Data(
            DataSync(
                kind = DataSyncKind.PROFILE,
                profile = ProfileUpdate(
                    clientId = ClientId("sender"),
                    displayName = "Peer",
                    platform = "Android",
                    capabilities = listOf(Capability.BACKGROUND_WAKE),
                    updatedAt = 42,
                ),
            ),
        ),
    )

    private fun actionDecoded() = DecodedInboundMessage(
        ProtocolLeaf.Action(ActionKind.TAP),
        DecodedInboundPayload.Action(
            ActionEvent(
                sourceClientId = ClientId("source"),
                sourceKey = "key",
                kind = ActionKind.TAP,
                actedAt = 42,
            ),
        ),
    )

    private fun notification() = CapturedNotification(
        sourceClientId = ClientId("source"),
        sourceKey = "source-key",
        packageName = "example.app",
        appLabel = "Example",
        postTime = 42,
    )

    private fun encodedEnvelope(messageType: MessageType): ByteArray = ProtocolCodec.encodeToCbor(
        Envelope(
            typ = messageType,
            signerId = ClientId("sender"),
            signerEpoch = 7,
            messageId = "message-1",
            seq = 99,
            createdAt = 123_456,
            bodyCiphertext = byteArrayOf(1, 2, 3),
            recipients = listOf(PerRecipientKey(ClientId("recipient"), byteArrayOf(4, 5, 6), 2)),
            sig = byteArrayOf(7, 8, 9),
        ),
    )

    private fun ready(envelope: DecodedInboundEnvelope) = InboundSecureOpenResult.Ready(
        senderId = envelope.signerId,
        senderOwnDevice = true,
        messageType = envelope.messageType,
        signerEpoch = envelope.signerEpoch,
        messageId = envelope.messageId,
        signedSequence = envelope.signedSequence,
        signedCreatedAt = envelope.signedCreatedAt,
        recipientEpoch = envelope.recipients.single().recipientEpoch,
        encodedBody = byteArrayOf(11, 12, 13),
    )

    private fun candidate(
        prerequisite: InboundAcknowledgementPrerequisite = InboundAcknowledgementPrerequisite.NONE,
    ) = InboundAcknowledgementCandidate(
        messageId = "message-1",
        authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 1 }),
        continuity = CONTINUITY,
        disposition = RelayHandledDisposition.APPLIED,
        prerequisite = prerequisite,
    )

    private suspend inline fun <reified T : Throwable> expectFailure(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("expected ${T::class.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private companion object {
        val CONTINUITY = RelayOperationalContinuity(7, "incarnation-1")
    }
}
