package net.extrawdw.apps.notisync.messaging.core

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.BoundCoreTrustCommand
import net.extrawdw.apps.notisync.data.corecommand.CoreActivityProjection
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthority
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityApplyOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityReceiptResolution
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityRetryReason
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandBinding
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandDurableOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandIdentityPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandPreparationPort
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptEvidence
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptFinalizeOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptFinalizer
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandReceiptIdentity
import net.extrawdw.apps.notisync.data.corecommand.CoreTrustCommandPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.DecodedCoreCommandIdentity
import net.extrawdw.apps.notisync.data.corecommand.OperationalStorageContinuity
import net.extrawdw.apps.notisync.data.corecommand.toCoreType
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayFinalizeRequest
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.apps.notisync.data.storage.core.coreCommandActivityEventId
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCommandProcessorTest {
    @Test
    fun newCommitFinalizesActivityCleansOutboxAndRunsBoundedPrune() = runTest {
        val fixture = fixture(includeActivity = true)

        val result = fixture.processor.process(fixture.delivery)

        assertEquals(
            RelayHandledDisposition.APPLIED,
            (result as CoreCommandProcessingResult.AcknowledgementReady).disposition,
        )
        assertEquals(1, fixture.core.applyCalls)
        assertEquals(1, fixture.finalizer.calls)
        assertNotNull(fixture.finalizer.committed?.activity)
        assertNull(fixture.core.marker?.pendingActivity)
        assertEquals(1, fixture.core.pruneCalls)
        assertEquals(64, fixture.core.lastPruneLimit)
    }

    @Test
    fun retainedMarkerReplaySkipsReducerAndPreservesMarkerOutcome() = runTest {
        listOf(
            CoreCommandDurableOutcome.APPLIED to RelayHandledDisposition.APPLIED,
            CoreCommandDurableOutcome.SUPERSEDED to RelayHandledDisposition.SUPERSEDED,
        ).forEach { (markerOutcome, expectedDisposition) ->
            val fixture = fixture()
            fixture.core.marker = fixture.receipt(markerOutcome)

            val result = fixture.processor.process(fixture.delivery)

            assertEquals(
                expectedDisposition,
                (result as CoreCommandProcessingResult.AcknowledgementReady).disposition,
            )
            assertEquals(1, fixture.preparation.decodeCalls)
            assertEquals(0, fixture.preparation.reduceCalls)
            assertEquals(0, fixture.core.applyCalls)
        }
    }

    @Test
    fun duplicateApplyWrapperUsesImmutableMarkerOutcome() = runTest {
        val fixture = fixture()
        fixture.core.forcedApply = CoreCommandAuthorityApplyOutcome.Duplicate(
            fixture.receipt(CoreCommandDurableOutcome.SUPERSEDED),
        )

        val result = fixture.processor.process(fixture.delivery)

        assertEquals(
            RelayHandledDisposition.SUPERSEDED,
            (result as CoreCommandProcessingResult.AcknowledgementReady).disposition,
        )
    }

    @Test
    fun reusedCommandIdWithDifferentDigestOrRequestFailsClosed() = runTest {
        val differentDigest = fixture()
        differentDigest.core.marker = differentDigest.receipt(CoreCommandDurableOutcome.APPLIED).copyWithDigest(
            sha256(byteArrayOf(9)),
        )
        assertTrue(
            differentDigest.processor.process(differentDigest.delivery) is
                CoreCommandProcessingResult.SecurityBlocked,
        )
        assertEquals(0, differentDigest.preparation.reduceCalls)
        assertEquals(0, differentDigest.finalizer.calls)

        val differentRequest = fixture()
        differentRequest.core.marker = differentRequest.receipt(
            CoreCommandDurableOutcome.APPLIED,
            requestId = "different-request",
        )
        assertTrue(
            differentRequest.processor.process(differentRequest.delivery) is
                CoreCommandProcessingResult.SecurityBlocked,
        )
        assertEquals(0, differentRequest.finalizer.calls)
    }

    @Test
    fun reducerBindingMismatchAndCoreReadinessRemainNonAck() = runTest {
        val wrongBinding = fixture()
        wrongBinding.preparation.returnWrongBinding = true
        assertTrue(
            wrongBinding.processor.process(wrongBinding.delivery) is CoreCommandProcessingResult.SecurityBlocked,
        )
        assertEquals(0, wrongBinding.core.applyCalls)
        assertEquals(0, wrongBinding.finalizer.calls)

        val notReady = fixture()
        notReady.core.forcedApply = CoreCommandAuthorityApplyOutcome.Retryable(
            CoreCommandAuthorityRetryReason.CORE_NOT_READY,
        )
        assertTrue(notReady.processor.process(notReady.delivery) is CoreCommandProcessingResult.RetryRequired)
        assertEquals(0, notReady.finalizer.calls)
    }

    @Test
    fun crashAfterCoreCommitRecoversFromMarkerWithoutRerunningReducer() = runTest {
        val fixture = fixture(includeActivity = true)
        val failure = IllegalStateException("process stopped after Core commit")
        fixture.core.throwAfterNextCommit = failure

        assertSame(
            failure,
            assertSuspendFailsWith<IllegalStateException> { fixture.processor.process(fixture.delivery) },
        )
        assertNotNull(fixture.core.marker)
        assertNull(fixture.finalizer.committed)
        assertEquals(1, fixture.preparation.reduceCalls)

        assertTrue(
            fixture.processor.process(fixture.delivery) is CoreCommandProcessingResult.AcknowledgementReady,
        )
        assertEquals(1, fixture.preparation.reduceCalls)
        assertEquals(1, fixture.core.applyCalls)
        assertNotNull(fixture.finalizer.committed)
    }

    @Test
    fun crashAfterOperationalCommitReplaysHandledEvidenceThenCleansActivity() = runTest {
        val fixture = fixture(includeActivity = true)
        val failure = IllegalStateException("process stopped after Operational commit")
        fixture.finalizer.throwAfterNextCommit = failure

        assertSame(
            failure,
            assertSuspendFailsWith<IllegalStateException> { fixture.processor.process(fixture.delivery) },
        )
        assertNotNull(fixture.finalizer.committed)
        assertNotNull(fixture.core.marker?.pendingActivity)

        assertTrue(
            fixture.processor.process(fixture.delivery) is CoreCommandProcessingResult.AcknowledgementReady,
        )
        assertEquals(1, fixture.preparation.reduceCalls)
        assertNull(fixture.core.marker?.pendingActivity)
    }

    @Test
    fun operationalResetUsesRetainedMarkerAndInvalidatesOldGenerationActivity() = runTest {
        val initial = fixture(includeActivity = true)
        initial.core.marker = initial.receipt(
            CoreCommandDurableOutcome.APPLIED,
            generation = 1,
            includeActivity = true,
        )
        val resetDelivery = delivery(OperationalStorageContinuity(2, "incarnation-2"))
        val resetFinalizer = FakeFinalizer(resetDelivery.continuity)
        val processor = CoreCommandProcessor(initial.preparation, initial.core, resetFinalizer)

        val result = processor.process(resetDelivery)

        assertTrue(result is CoreCommandProcessingResult.AcknowledgementReady)
        assertEquals(0, initial.preparation.reduceCalls)
        assertNull(resetFinalizer.committed?.activity)
        assertNull(initial.core.marker?.pendingActivity)
    }

    @Test
    fun sameGenerationNewIncarnationCannotMutateCore() = runTest {
        val delivery = delivery(OperationalStorageContinuity(1, "new-incarnation"))
        val fixture = fixture(delivery = delivery)
        fixture.core.acceptedContinuity = OperationalStorageContinuity(1, "old-incarnation")

        val result = fixture.processor.process(delivery)

        assertTrue(result is CoreCommandProcessingResult.RetryRequired)
        assertNull(fixture.core.marker)
        assertEquals(0, fixture.finalizer.calls)
    }

    @Test
    fun finalizationConflictAndContinuityChangeNeverAckOrCleanCoreActivity() = runTest {
        listOf(
            CoreCommandReceiptFinalizeOutcome.CONFLICT to CoreCommandProcessingResult.SecurityBlocked::class.java,
            CoreCommandReceiptFinalizeOutcome.LEGACY_RETAINED_NO_ACK to
                CoreCommandProcessingResult.SecurityBlocked::class.java,
            CoreCommandReceiptFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH to
                CoreCommandProcessingResult.RetryRequired::class.java,
        ).forEach { (outcome, resultClass) ->
            val fixture = fixture(includeActivity = true)
            fixture.finalizer.forced = outcome

            val result = fixture.processor.process(fixture.delivery)

            assertTrue(resultClass.isInstance(result))
            assertNotNull(fixture.core.marker?.pendingActivity)
            assertEquals(0, fixture.core.pruneCalls)
        }
    }

    @Test
    fun futureGenerationActivityFailsClosed() = runTest {
        val fixture = fixture()
        fixture.core.marker = fixture.receipt(
            CoreCommandDurableOutcome.APPLIED,
            generation = 2,
            includeActivity = true,
        )

        assertTrue(fixture.processor.process(fixture.delivery) is CoreCommandProcessingResult.SecurityBlocked)
        assertEquals(0, fixture.finalizer.calls)
    }

    @Test
    fun ordinaryPruneFailureDoesNotRevokeAckButCancellationAndCorruptionPropagate() = runTest {
        val ordinary = fixture()
        ordinary.core.pruneFailure = SQLiteFullException("full")
        assertTrue(ordinary.processor.process(ordinary.delivery) is CoreCommandProcessingResult.AcknowledgementReady)

        val unexpected = fixture()
        val unexpectedFailure = SQLException("unexpected")
        unexpected.core.pruneFailure = unexpectedFailure
        assertSame(
            unexpectedFailure,
            assertSuspendFailsWith<SQLException> { unexpected.processor.process(unexpected.delivery) },
        )
        assertNotNull(unexpected.finalizer.committed)

        val cancelled = fixture()
        val cancellation = CancellationException("cancelled")
        cancelled.core.pruneFailure = cancellation
        assertSame(
            cancellation,
            assertSuspendFailsWith<CancellationException> { cancelled.processor.process(cancelled.delivery) },
        )
        assertNotNull(cancelled.finalizer.committed)

        val corrupt = fixture()
        val corruption = SQLiteDatabaseCorruptException("corrupt")
        corrupt.core.pruneFailure = corruption
        assertSame(
            corruption,
            assertSuspendFailsWith<SQLiteDatabaseCorruptException> { corrupt.processor.process(corrupt.delivery) },
        )
        assertNotNull(corrupt.finalizer.committed)
    }

    @Test
    fun genericPreparationFailureAndCancellationPropagateUnchanged() = runTest {
        val generic = fixture()
        val failure = IllegalStateException("decode failed")
        generic.preparation.decodeFailure = failure
        assertSame(
            failure,
            assertSuspendFailsWith<IllegalStateException> { generic.processor.process(generic.delivery) },
        )

        val cancelled = fixture()
        val cancellation = CancellationException("cancelled")
        cancelled.preparation.reduceFailure = cancellation
        assertSame(
            cancellation,
            assertSuspendFailsWith<CancellationException> { cancelled.processor.process(cancelled.delivery) },
        )
        assertEquals(0, cancelled.finalizer.calls)
    }

    private fun fixture(
        includeActivity: Boolean = false,
        delivery: AuthenticatedCoreCommandDelivery = delivery(),
    ): Fixture {
        val preparation = FakePreparation()
        val core = FakeCore(includeActivity)
        val finalizer = FakeFinalizer(delivery.continuity)
        return Fixture(
            CoreCommandProcessor(preparation, core, finalizer),
            preparation,
            core,
            finalizer,
            delivery,
        )
    }

    private data class Fixture(
        val processor: CoreCommandProcessor,
        val preparation: FakePreparation,
        val core: FakeCore,
        val finalizer: FakeFinalizer,
        val delivery: AuthenticatedCoreCommandDelivery,
    ) {
        fun receipt(
            outcome: CoreCommandDurableOutcome,
            requestId: String = REQUEST_ID,
            generation: Long = delivery.continuity.generation,
            includeActivity: Boolean = false,
        ): CoreCommandReceiptEvidence {
            val binding = CoreCommandBinding.bind(
                delivery,
                DecodedCoreCommandIdentity(
                    delivery.commandId,
                    delivery.authenticatedRequestId,
                    CoreCommandKind.DATA_SYNC_PROFILE,
                    delivery.decodedCommand,
                ),
            )
            return core.receipt(binding, outcome, requestId, generation, includeActivity)
        }
    }

    private class FakePreparation : CoreCommandPreparationPort {
        var decodeCalls = 0
        var reduceCalls = 0
        var returnWrongBinding = false
        var decodeFailure: Throwable? = null
        var reduceFailure: Throwable? = null

        override suspend fun decodeIdentity(
            delivery: AuthenticatedCoreCommandDelivery,
        ): CoreCommandIdentityPreparationResult {
            decodeCalls++
            decodeFailure?.let { throw it }
            return CoreCommandIdentityPreparationResult.Ready(
                DecodedCoreCommandIdentity(
                    delivery.commandId,
                    delivery.authenticatedRequestId,
                    delivery.commandType,
                    delivery.decodedCommand,
                ),
            )
        }

        override suspend fun reduceAndSign(
            delivery: AuthenticatedCoreCommandDelivery,
            binding: CoreCommandBinding,
        ): CoreTrustCommandPreparationResult {
            reduceCalls++
            reduceFailure?.let { throw it }
            val outputBinding = if (returnWrongBinding) {
                val other = AuthenticatedCoreCommandDelivery(
                    messageId = delivery.messageId,
                    commandId = delivery.commandId,
                    authenticatedRequestId = delivery.authenticatedRequestId,
                    commandType = delivery.commandType,
                    senderId = delivery.senderId,
                    senderOwnDevice = delivery.senderOwnDevice,
                    signerEpoch = delivery.signerEpoch,
                    signedCreatedAt = delivery.signedCreatedAt,
                    deliveryMode = delivery.deliveryMode,
                    decodedCommand = delivery.decodedCommand,
                    canonicalCommand = delivery.canonicalCommandCopy(),
                    authenticatedToken = delivery.authenticatedToken,
                    continuity = OperationalStorageContinuity(
                        delivery.continuity.generation,
                        "different-incarnation",
                    ),
                )
                CoreCommandBinding.bind(
                    other,
                    DecodedCoreCommandIdentity(
                        other.commandId,
                        other.authenticatedRequestId,
                        other.commandType,
                        other.decodedCommand,
                    ),
                )
            } else {
                binding
            }
            return CoreTrustCommandPreparationResult.Ready(
                BoundCoreTrustCommand.bind(outputBinding, command(outputBinding)),
            )
        }
    }

    private class FakeCore(private val includeActivityOnCommit: Boolean) : CoreCommandAuthority {
        var marker: CoreCommandReceiptEvidence? = null
        var applyCalls = 0
        var resolveCalls = 0
        var pruneCalls = 0
        var lastPruneLimit: Int? = null
        var forcedApply: CoreCommandAuthorityApplyOutcome? = null
        var acceptedContinuity: OperationalStorageContinuity? = null
        var throwAfterNextCommit: Throwable? = null
        var pruneFailure: Throwable? = null

        override suspend fun resolve(
            reference: CoreCommandReceiptIdentity,
        ): CoreCommandAuthorityReceiptResolution {
            resolveCalls++
            val current = marker ?: return CoreCommandAuthorityReceiptResolution.Missing
            return if (current.matches(reference)) {
                CoreCommandAuthorityReceiptResolution.Found(current)
            } else {
                CoreCommandAuthorityReceiptResolution.Conflict
            }
        }

        override suspend fun apply(command: BoundCoreTrustCommand): CoreCommandAuthorityApplyOutcome {
            applyCalls++
            forcedApply?.let { return it }
            acceptedContinuity?.let { expected ->
                if (
                    command.binding.expectedOperationalGeneration != expected.generation ||
                    command.binding.expectedOperationalIncarnationId != expected.storageIncarnationId
                ) {
                    return CoreCommandAuthorityApplyOutcome.Retryable(
                        CoreCommandAuthorityRetryReason.STALE_CORE_STATE,
                    )
                }
            }
            marker?.let { existing ->
                return if (existing.matches(command.binding.toReceiptIdentity())) {
                    CoreCommandAuthorityApplyOutcome.Duplicate(existing)
                } else {
                    CoreCommandAuthorityApplyOutcome.Conflict
                }
            }
            val committed = receipt(
                command.binding,
                CoreCommandDurableOutcome.APPLIED,
                command.binding.authenticatedRequestId,
                command.binding.expectedOperationalGeneration,
                includeActivityOnCommit,
            )
            marker = committed
            throwAfterNextCommit?.let { failure ->
                throwAfterNextCommit = null
                throw failure
            }
            return CoreCommandAuthorityApplyOutcome.Committed(committed)
        }

        override suspend fun acknowledgeCopiedActivity(
            eventId: String,
            operationalGeneration: Long,
        ): Boolean {
            val current = marker ?: return false
            val activity = current.pendingActivity ?: return false
            if (activity.eventId != eventId || activity.operationalGeneration != operationalGeneration) return false
            marker = current.withoutActivity()
            return true
        }

        override suspend fun pruneRetainedMarkers(limit: Int): Int {
            pruneCalls++
            lastPruneLimit = limit
            pruneFailure?.let { throw it }
            return 0
        }

        fun receipt(
            binding: CoreCommandBinding,
            outcome: CoreCommandDurableOutcome,
            requestId: String,
            generation: Long,
            includeActivity: Boolean,
        ) = CoreCommandReceiptEvidence(
            commandId = binding.commandId,
            authenticatedRequestId = requestId,
            commandDigest = binding.commandDigestCopy(),
            commandType = binding.commandType,
            outcome = outcome,
            coreRevision = REVISION,
            appliedAt = 100,
            pendingActivity = if (includeActivity) activity(binding, generation) else null,
        )
    }

    private class FakeFinalizer(
        var continuity: OperationalStorageContinuity,
    ) : CoreCommandReceiptFinalizer {
        var calls = 0
        var committed: RelayFinalizeRequest? = null
        var forced: CoreCommandReceiptFinalizeOutcome? = null
        var throwAfterNextCommit: Throwable? = null

        override suspend fun finalize(
            continuity: OperationalStorageContinuity,
            request: RelayFinalizeRequest,
        ): CoreCommandReceiptFinalizeOutcome {
            calls++
            forced?.let { return it }
            if (continuity != this.continuity) {
                return CoreCommandReceiptFinalizeOutcome.STORAGE_CONTINUITY_MISMATCH
            }
            committed?.let { current ->
                return if (current == request) {
                    CoreCommandReceiptFinalizeOutcome.ALREADY_FINALIZED
                } else {
                    CoreCommandReceiptFinalizeOutcome.CONFLICT
                }
            }
            committed = request
            throwAfterNextCommit?.let { failure ->
                throwAfterNextCommit = null
                throw failure
            }
            return CoreCommandReceiptFinalizeOutcome.APPLIED
        }
    }

    private companion object {
        const val MESSAGE_ID = "message-1"
        const val COMMAND_ID = MESSAGE_ID
        const val REQUEST_ID = MESSAGE_ID
        const val REVISION = 7L

        fun delivery(
            continuity: OperationalStorageContinuity = OperationalStorageContinuity(1, "incarnation-1"),
        ) = AuthenticatedCoreCommandDelivery(
            messageId = MESSAGE_ID,
            commandId = COMMAND_ID,
            authenticatedRequestId = REQUEST_ID,
            commandType = CoreCommandKind.DATA_SYNC_PROFILE,
            senderId = "sender-1",
            senderOwnDevice = true,
            signerEpoch = 1,
            signedCreatedAt = 10,
            deliveryMode = ActivityDeliveryMode.RELAY_DRAIN,
            decodedCommand = decodedProfileCommand(),
            canonicalCommand = byteArrayOf(1, 2, 3),
            authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 4 }),
            continuity = continuity,
        )

        fun decodedProfileCommand(): FoundationTrustCommand =
            (FoundationTrustCommand.decode(
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        kind = DataSyncKind.PROFILE,
                        profile = ProfileUpdate(ClientId("sender-1"), "name", "android", emptyList(), 10),
                    ),
                ),
            ) as FoundationTrustCommandDecodeResult.Ready).command

        fun command(binding: CoreCommandBinding) = CoreTrustCommand(
            commandId = binding.commandId,
            authenticatedRequestId = binding.authenticatedRequestId,
            canonicalCommand = binding.canonicalCommandCopy(),
            commandType = binding.commandType.toCoreType(),
            expectedOperationalGeneration = binding.expectedOperationalGeneration,
            expectedOperationalIncarnationId = binding.expectedOperationalIncarnationId,
            expectedSnapshotDigest = null,
            candidateSnapshot = TrustSnapshotInput.ThreeSection(
                entriesUtf8 = "[]".encodeToByteArray(),
                cardsUtf8 = "{}".encodeToByteArray(),
                overlaysUtf8 = "{}".encodeToByteArray(),
                signatureBase64UrlUtf8 = "signature".encodeToByteArray(),
            ),
        )

        fun activity(binding: CoreCommandBinding, generation: Long) = CoreActivityProjection(
            commandId = binding.commandId,
            eventId = coreCommandActivityEventId(binding.commandType.toCoreType(), binding.commandId),
            operationalGeneration = generation,
            feature = "profile",
            semanticAction = "applied",
            direction = "inbound",
            outcome = "success",
            peerClientId = "peer-1",
            correlationId = binding.authenticatedRequestId,
            deliveryMode = "websocket",
            argsVersion = 1,
            renderArgs = ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1(revision = REVISION)),
            occurredAt = 90,
            createdAt = 100,
        )

        fun CoreCommandReceiptEvidence.withoutActivity() = CoreCommandReceiptEvidence(
            commandId,
            authenticatedRequestId,
            commandDigestCopy(),
            commandType,
            outcome,
            coreRevision,
            appliedAt,
            null,
        )

        fun CoreCommandReceiptEvidence.copyWithDigest(digest: ByteArray) = CoreCommandReceiptEvidence(
            commandId,
            authenticatedRequestId,
            digest,
            commandType,
            outcome,
            coreRevision,
            appliedAt,
            pendingActivity,
        )

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
            crossinline block: suspend () -> Unit,
        ): T = try {
            block()
            throw AssertionError("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
            failure
        }
    }
}
