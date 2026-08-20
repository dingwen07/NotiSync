package net.extrawdw.apps.notisync.messaging.core

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.BoundCoreTrustCommand
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthority
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandAuthorityApplyOutcome
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandBinding
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandIdentityPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandPreparationPort
import net.extrawdw.apps.notisync.data.corecommand.CoreTrustCommandPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.DecodedCoreCommandIdentity
import net.extrawdw.apps.notisync.data.corecommand.OperationalStorageContinuity
import net.extrawdw.apps.notisync.data.corecommand.RepositoryCoreCommandAuthority
import net.extrawdw.apps.notisync.data.corecommand.RoomCoreCommandReceiptFinalizer
import net.extrawdw.apps.notisync.data.corecommand.toCoreType
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.storage.core.CoreActivityOutboxEntity
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandActivity
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandApplyResult
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreRoomStore
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportStateEntity
import net.extrawdw.apps.notisync.data.storage.core.IdentityLifecycleState
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataInput
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataSaveResult
import net.extrawdw.apps.notisync.data.storage.core.IdentitySecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.OperationalContinuityOrigin
import net.extrawdw.apps.notisync.data.storage.core.ReplayFenceState
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotWriteResult
import net.extrawdw.apps.notisync.data.storage.core.coreCommandActivityEventId
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreCommandProcessorAndroidTest {
    @Test
    fun realDatabasesApplyFinalizeActivityAndHaveNoInboxOrAckTables() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            val delivery = delivery(OperationalStorageContinuity(1, CORE_INCARNATION))
            val preparation = preparation(core, includeActivity = true)

            val result = processor(core, operational, preparation).process(delivery)

            assertTrue(result is CoreCommandProcessingResult.AcknowledgementReady)
            assertEquals(200L, operational.relayDao().findHandled(MESSAGE_ID)?.handledAt)
            assertNotNull(operational.activityDao().find(activityEventId()))
            assertNull(core.database.activityOutboxDao().find(activityEventId()))
            assertEquals(
                emptySet<String>(),
                tableNames(operational).intersect(
                    setOf("relay_inbox", "relay_ack_outbox", "core_command_inbox"),
                ),
            )
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun sameGenerationNewIncarnationCannotMutateCoreOrFinalizeOperational() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, "recreated-incarnation")
        try {
            val initialTrust = requireNotNull(core.database.trustSnapshotDao().get()).snapshotDigest.copyOf()
            val result = processor(core, operational, preparation(core)).process(
                delivery(OperationalStorageContinuity(1, "recreated-incarnation")),
            )

            assertTrue(result is CoreCommandProcessingResult.RetryRequired)
            assertArrayEquals(initialTrust, core.database.trustSnapshotDao().get()!!.snapshotDigest)
            assertNull(core.database.commandAppliedDao().find(COMMAND_ID))
            assertNull(operational.relayDao().findHandled(MESSAGE_ID))
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun forcedOperationalActivityFailureRollsBackReceiptAndReplayUsesMarkerOnly() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            operational.useWriterConnection { connection ->
                connection.executeSQL(
                    "CREATE TEMP TRIGGER fail_core_activity BEFORE INSERT ON activity_event " +
                        "BEGIN SELECT RAISE(ABORT, 'forced core activity failure'); END",
                )
            }
            val preparation = preparation(core, includeActivity = true)
            val processor = processor(core, operational, preparation)
            val delivery = delivery(OperationalStorageContinuity(1, CORE_INCARNATION))

            assertSuspendFailsWith<SQLiteException> { processor.process(delivery) }
            assertNull(operational.relayDao().findHandled(MESSAGE_ID))
            assertNull(operational.activityDao().find(activityEventId()))
            assertNotNull(core.database.commandAppliedDao().find(COMMAND_ID))
            assertNotNull(core.database.activityOutboxDao().find(activityEventId()))
            val reductions = preparation.reduceCalls

            operational.useWriterConnection { connection -> connection.executeSQL("DROP TRIGGER fail_core_activity") }
            assertTrue(processor.process(delivery) is CoreCommandProcessingResult.AcknowledgementReady)
            assertEquals(reductions, preparation.reduceCalls)
            assertNotNull(operational.relayDao().findHandled(MESSAGE_ID))
            assertNull(core.database.activityOutboxDao().find(activityEventId()))
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun operationalResetAfterCoreCommitUsesMarkerAndDropsOldGenerationActivity() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val firstOperational = operationalDatabase(1, CORE_INCARNATION)
        val preparation = preparation(core, includeActivity = true)
        val oldDelivery = delivery(OperationalStorageContinuity(1, CORE_INCARNATION))
        try {
            val crashing = CrashAfterApplyAuthority(RepositoryCoreCommandAuthority(core.repository))
            val first = CoreCommandProcessor(
                preparation,
                crashing,
                RoomCoreCommandReceiptFinalizer.forDatabase(firstOperational),
            )
            assertSuspendFailsWith<CancellationException> { first.process(oldDelivery) }
            assertNotNull(core.database.commandAppliedDao().find(COMMAND_ID))
            assertNotNull(core.database.activityOutboxDao().find(activityEventId()))
            assertNull(firstOperational.relayDao().findHandled(MESSAGE_ID))
        } finally {
            firstOperational.close()
        }

        val recreated = operationalDatabase(2, "reset-incarnation")
        try {
            val result = processor(core, recreated, preparation).process(
                delivery(OperationalStorageContinuity(2, "reset-incarnation")),
            )

            assertTrue(result is CoreCommandProcessingResult.AcknowledgementReady)
            assertEquals(1, preparation.reduceCalls)
            assertNotNull(recreated.relayDao().findHandled(MESSAGE_ID))
            assertNull(recreated.activityDao().find(activityEventId()))
            assertNull(core.database.activityOutboxDao().find(activityEventId()))
        } finally {
            recreated.close()
            core.database.close()
        }
    }

    @Test
    fun crashAfterOperationalCommitReplaysHandledThenCleansCoreActivity() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            val authority = CrashBeforeActivityCleanupAuthority(RepositoryCoreCommandAuthority(core.repository))
            val preparation = preparation(core, includeActivity = true)
            val processor = CoreCommandProcessor(
                preparation,
                authority,
                RoomCoreCommandReceiptFinalizer.forDatabase(operational),
            )
            val delivery = delivery(OperationalStorageContinuity(1, CORE_INCARNATION))

            assertSuspendFailsWith<CancellationException> { processor.process(delivery) }
            assertNotNull(operational.relayDao().findHandled(MESSAGE_ID))
            assertNotNull(operational.activityDao().find(activityEventId()))
            assertNotNull(core.database.activityOutboxDao().find(activityEventId()))

            assertTrue(processor.process(delivery) is CoreCommandProcessingResult.AcknowledgementReady)
            assertEquals(1, preparation.reduceCalls)
            assertNull(core.database.activityOutboxDao().find(activityEventId()))
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun sameAuthenticatedMessageIdDifferentDigestNeverOverwritesHandledEvidence() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            val continuity = OperationalStorageContinuity(1, CORE_INCARNATION)
            assertTrue(
                processor(core, operational, preparation(core)).process(delivery(continuity)) is
                    CoreCommandProcessingResult.AcknowledgementReady,
            )
            val firstFingerprint = requireNotNull(
                operational.relayDao().findHandled(MESSAGE_ID)?.authenticatedFingerprint,
            ).copyOf()
            val conflict = AuthenticatedCoreCommandDelivery(
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
                canonicalCommand = byteArrayOf(9),
                authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 5 }),
                continuity = continuity,
            )

            assertTrue(
                processor(core, operational, preparation(core)).process(conflict) is
                    CoreCommandProcessingResult.SecurityBlocked,
            )
            assertArrayEquals(
                firstFingerprint,
                operational.relayDao().findHandled(MESSAGE_ID)?.authenticatedFingerprint,
            )
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun malformedActivityChildForExactMarkerFailsClosedInsteadOfBeingHiddenByEventLookup() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            val continuity = OperationalStorageContinuity(1, CORE_INCARNATION)
            val delivery = delivery(continuity)
            val binding = CoreCommandBinding.bind(
                delivery,
                DecodedCoreCommandIdentity(
                    COMMAND_ID,
                    REQUEST_ID,
                    CoreCommandKind.DATA_SYNC_PROFILE,
                    delivery.decodedCommand,
                ),
            )
            val command = net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand(
                commandId = binding.commandId,
                authenticatedRequestId = binding.authenticatedRequestId,
                canonicalCommand = binding.canonicalCommandCopy(),
                commandType = binding.commandType.toCoreType(),
                expectedOperationalGeneration = binding.expectedOperationalGeneration,
                expectedOperationalIncarnationId = binding.expectedOperationalIncarnationId,
                expectedSnapshotDigest = core.expectedDigest,
                candidateSnapshot = core.candidate,
            )
            assertTrue(core.repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Applied)
            core.database.activityOutboxDao().insertRequired(
                CoreActivityOutboxEntity(
                    commandId = COMMAND_ID,
                    eventId = "malformed-event",
                    operationalGeneration = 1,
                    feature = "profile",
                    semanticAction = "applied",
                    direction = "inbound",
                    outcome = "success",
                    correlationId = REQUEST_ID,
                    argsVersion = 1,
                    renderArgs = byteArrayOf(1),
                    occurredAt = 180,
                    createdAt = 200,
                ),
            )

            val result = processor(core, operational, preparation(core)).process(delivery)

            assertTrue(result is CoreCommandProcessingResult.SecurityBlocked)
            assertNull(operational.relayDao().findHandled(MESSAGE_ID))
        } finally {
            operational.close()
            core.database.close()
        }
    }

    @Test
    fun twoProcessorsRacingConvergeToOneMarkerAndHandledReceipt() = runBlocking {
        val core = coreFixture(CORE_INCARNATION)
        val operational = operationalDatabase(1, CORE_INCARNATION)
        try {
            val preparation = BarrierPreparation(preparation(core))
            val delivery = delivery(OperationalStorageContinuity(1, CORE_INCARNATION))
            val first = processor(core, operational, preparation)
            val second = processor(core, operational, preparation)

            val results = coroutineScope {
                listOf(async { first.process(delivery) }, async { second.process(delivery) }).awaitAll()
            }

            assertTrue(results.all { it is CoreCommandProcessingResult.AcknowledgementReady })
            assertNotNull(core.database.commandAppliedDao().find(COMMAND_ID))
            assertNotNull(operational.relayDao().findHandled(MESSAGE_ID))
        } finally {
            operational.close()
            core.database.close()
        }
    }

    private fun processor(
        core: CoreFixture,
        operational: OperationalDatabase,
        preparation: CoreCommandPreparationPort,
    ) = CoreCommandProcessor(
        preparation,
        RepositoryCoreCommandAuthority(core.repository),
        RoomCoreCommandReceiptFinalizer.forDatabase(operational),
    )

    private suspend fun coreFixture(incarnation: String): CoreFixture {
        val database = Room.inMemoryDatabaseBuilder<CoreDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        val signer = SoftwareIdentitySigner.generate()
        var clock = 100L
        val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
        assertEquals(
            IdentityMetadataSaveResult.SAVED,
            repository.saveIdentityMetadata(
                IdentityMetadataInput(
                    keyAlias = "notisync.identity.v1",
                    keyAliasVersion = 1,
                    publicSpki = signer.publicKeySpki,
                    securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
                    lifecycleState = IdentityLifecycleState.ACTIVE,
                    createdAt = 1,
                ),
            ),
        )
        val initial = snapshot(signer, "[]")
        assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(initial))
        assertTrue(
            database.transportStateDao().insertIfAbsent(
                CoreTransportStateEntity(
                    brokerUrl = "https://broker.example.test",
                    routeEpoch = 0,
                    operationalGeneration = 1,
                    operationalIncarnationId = incarnation,
                    replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
                    continuityOrigin = OperationalContinuityOrigin.FRESH_IDENTITY,
                    updatedAt = 150,
                ),
            ) != -1L,
        )
        val expectedDigest = database.trustSnapshotDao().get()!!.snapshotDigest.copyOf()
        val candidate = snapshot(signer, "[{\"changed\":1}]")
        clock = 200
        return CoreFixture(database, repository, expectedDigest, candidate)
    }

    private suspend fun operationalDatabase(generation: Long, incarnation: String): OperationalDatabase {
        val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        )
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        database.profileDao().initializeMaintenance(
            MaintenanceStateEntity(
                operationalGeneration = generation,
                storageIncarnationId = incarnation,
                postCutoverWriteAt = null,
                lastIntegrityCheckAt = null,
                updatedAt = 1,
            ),
        )
        return database
    }

    private fun delivery(continuity: OperationalStorageContinuity) = AuthenticatedCoreCommandDelivery(
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

    private fun preparation(core: CoreFixture, includeActivity: Boolean = false) = TestPreparation(
        expectedDigest = core.expectedDigest,
        candidate = core.candidate,
        includeActivity = includeActivity,
    )

    private class TestPreparation(
        expectedDigest: ByteArray,
        private val candidate: TrustSnapshotInput,
        private val includeActivity: Boolean,
    ) : CoreCommandPreparationPort {
        private val expectedDigest = expectedDigest.copyOf()
        var reduceCalls = 0

        override suspend fun decodeIdentity(
            delivery: AuthenticatedCoreCommandDelivery,
        ): CoreCommandIdentityPreparationResult = CoreCommandIdentityPreparationResult.Ready(
            DecodedCoreCommandIdentity(
                delivery.commandId,
                delivery.authenticatedRequestId,
                delivery.commandType,
                delivery.decodedCommand,
            ),
        )

        override suspend fun reduceAndSign(
            delivery: AuthenticatedCoreCommandDelivery,
            binding: CoreCommandBinding,
        ): CoreTrustCommandPreparationResult {
            reduceCalls++
            return CoreTrustCommandPreparationResult.Ready(
                BoundCoreTrustCommand.bind(
                    binding,
                    net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand(
                        commandId = binding.commandId,
                        authenticatedRequestId = binding.authenticatedRequestId,
                        canonicalCommand = binding.canonicalCommandCopy(),
                        commandType = binding.commandType.toCoreType(),
                        expectedOperationalGeneration = binding.expectedOperationalGeneration,
                        expectedOperationalIncarnationId = binding.expectedOperationalIncarnationId,
                        expectedSnapshotDigest = expectedDigest,
                        candidateSnapshot = candidate,
                        activity = if (includeActivity) {
                            CoreCommandActivity(
                                action = ActivityAction.APPLIED,
                                peerClientId = "peer-1",
                                deliveryMode = ActivityDeliveryMode.WEBSOCKET,
                                renderArgs = ActivityRenderArgs.V1(revision = 7),
                                occurredAt = 180,
                            )
                        } else {
                            null
                        },
                    ),
                ),
            )
        }
    }

    private class BarrierPreparation(
        private val delegate: CoreCommandPreparationPort,
    ) : CoreCommandPreparationPort {
        private val arrivals = AtomicInteger()
        private val release = CompletableDeferred<Unit>()

        override suspend fun decodeIdentity(
            delivery: AuthenticatedCoreCommandDelivery,
        ): CoreCommandIdentityPreparationResult = delegate.decodeIdentity(delivery)

        override suspend fun reduceAndSign(
            delivery: AuthenticatedCoreCommandDelivery,
            binding: CoreCommandBinding,
        ): CoreTrustCommandPreparationResult {
            if (arrivals.incrementAndGet() == 2) release.complete(Unit)
            release.await()
            return delegate.reduceAndSign(delivery, binding)
        }
    }

    private class CrashAfterApplyAuthority(
        private val delegate: CoreCommandAuthority,
    ) : CoreCommandAuthority by delegate {
        override suspend fun apply(command: BoundCoreTrustCommand): CoreCommandAuthorityApplyOutcome {
            delegate.apply(command)
            throw CancellationException("simulated death after Core commit")
        }
    }

    private class CrashBeforeActivityCleanupAuthority(
        private val delegate: CoreCommandAuthority,
    ) : CoreCommandAuthority by delegate {
        private var crash = true

        override suspend fun acknowledgeCopiedActivity(eventId: String, operationalGeneration: Long): Boolean {
            if (crash) {
                crash = false
                throw CancellationException("simulated death after Operational commit")
            }
            return delegate.acknowledgeCopiedActivity(eventId, operationalGeneration)
        }
    }

    private fun snapshot(signer: SoftwareIdentitySigner, entries: String): TrustSnapshotInput.FourSection =
        TrustSnapshotInput.FourSection(
            entriesUtf8 = entries.encodeToByteArray(),
            cardsUtf8 = CARDS.encodeToByteArray(),
            overlaysUtf8 = OVERLAYS.encodeToByteArray(),
            epochsUtf8 = EPOCHS.encodeToByteArray(),
            signatureBase64UrlUtf8 = TrustStoreSigning.sign(
                signer,
                entries,
                CARDS,
                OVERLAYS,
                EPOCHS,
            ).encodeToByteArray(),
        )

    private fun activityEventId(): String =
        coreCommandActivityEventId(
            net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommandType.DATA_SYNC_PROFILE,
            COMMAND_ID,
        )

    private suspend fun tableNames(database: OperationalDatabase): Set<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private data class CoreFixture(
        val database: CoreDatabase,
        val repository: CoreFoundationRepository,
        val expectedDigest: ByteArray,
        val candidate: TrustSnapshotInput,
    )

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        crossinline block: suspend () -> Unit,
    ): T = try {
        block()
        throw AssertionError("Expected ${T::class.java.name}")
    } catch (failure: Throwable) {
        if (failure !is T) throw failure
        failure
    }

    private companion object {
        const val MESSAGE_ID = "message-1"
        const val COMMAND_ID = MESSAGE_ID
        const val REQUEST_ID = MESSAGE_ID
        const val CORE_INCARNATION = "core-incarnation-1"
        const val CARDS = "{}"
        const val OVERLAYS = "{}"
        const val EPOCHS = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"

        fun decodedProfileCommand(): FoundationTrustCommand =
            (FoundationTrustCommand.decode(
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        kind = DataSyncKind.PROFILE,
                        profile = ProfileUpdate(ClientId("sender-1"), "name", "android", emptyList(), 10),
                    ),
                ),
            ) as FoundationTrustCommandDecodeResult.Ready).command
    }
}
