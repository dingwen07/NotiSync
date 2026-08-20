package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
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
class CoreCommandAtomicHandoffAndroidTest {
    @Test
    fun newApplyCommitsMutationMarkerAndPrivacySafeActivityTogether() = runBlocking {
        withDatabase { database ->
            val fixture = fixture(database)
            val canonical = byteArrayOf(1, 2, 3)
            val expectedCanonical = canonical.copyOf()
            val command = fixture.command(
                canonical = canonical,
                activity = activity(revision = 7),
            )
            canonical[0] = 99

            val result = fixture.repository.applyCoreTrustCommand(command)
            assertTrue(result is CoreCommandApplyResult.Applied)
            val receipt = (result as CoreCommandApplyResult.Applied).receipt
            val marker = requireNotNull(database.commandAppliedDao().find(COMMAND_ID))
            val outbox = requireNotNull(receipt.pendingActivity)
            val storedOutbox = requireNotNull(database.activityOutboxDao().find(outbox.eventId))

            assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(expectedCanonical),
                marker.commandDigest,
            )
            assertEquals(CoreTrustCommandType.DATA_SYNC_PROFILE.token, marker.commandType)
            assertEquals(CoreCommandOutcome.APPLIED, marker.outcome)
            assertEquals(fixture.clock.value, marker.coreRevision)
            assertEquals(fixture.clock.value, marker.appliedAt)
            assertArrayEquals(fixture.candidate.exactBytes().entriesUtf8, database.trustSnapshotDao().get()!!.entriesUtf8)

            assertEquals(INITIAL_OPERATIONAL_GENERATION, storedOutbox.operationalGeneration)
            assertEquals(COMMAND_ID, storedOutbox.commandId)
            assertEquals("profile", storedOutbox.feature)
            assertEquals("applied", storedOutbox.semanticAction)
            assertEquals("inbound", storedOutbox.direction)
            assertEquals("success", storedOutbox.outcome)
            assertEquals(PEER_ID, storedOutbox.peerClientId)
            assertEquals(REQUEST_ID, storedOutbox.correlationId)
            assertEquals("fcm_relay_fetch", storedOutbox.deliveryMode)
            assertEquals(1, storedOutbox.argsVersion)
            assertArrayEquals(
                ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1(revision = 7)),
                storedOutbox.renderArgs,
            )

            val receiptDigest = receipt.command.commandDigest
            val expectedDigest = marker.commandDigest.copyOf()
            receiptDigest[0] = (receiptDigest[0].toInt() xor 0x7f).toByte()
            assertArrayEquals(expectedDigest, receipt.command.commandDigest)
            assertArrayEquals(expectedDigest, database.commandAppliedDao().find(COMMAND_ID)!!.commandDigest)

            val receiptArgs = outbox.renderArgs
            val expectedArgs = storedOutbox.renderArgs.copyOf()
            receiptArgs[0] = (receiptArgs[0].toInt() xor 0x7f).toByte()
            assertArrayEquals(expectedArgs, outbox.renderArgs)
            assertArrayEquals(expectedArgs, database.activityOutboxDao().find(outbox.eventId)!!.renderArgs)

            val observedCommand = fixture.repository.observeCoreCommand(COMMAND_ID).first()!!
            val observedDigest = observedCommand.commandDigest
            observedDigest[0] = (observedDigest[0].toInt() xor 0x3f).toByte()
            assertArrayEquals(expectedDigest, observedCommand.commandDigest)
            val observedActivity = fixture.repository.observeActivity(outbox.eventId).first()!!
            val observedArgs = observedActivity.renderArgs
            observedArgs[0] = (observedArgs[0].toInt() xor 0x3f).toByte()
            assertArrayEquals(expectedArgs, observedActivity.renderArgs)
        }
    }

    @Test
    fun exactReplaySucceedsButDigestRequestAndTypeReuseFailClosed() = runBlocking {
        withDatabase { database ->
            val fixture = fixture(database)
            val command = fixture.command(activity = activity())
            assertTrue(fixture.repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Applied)
            val committed = requireNotNull(database.commandAppliedDao().find(COMMAND_ID))
            val committedTrust = requireNotNull(database.trustSnapshotDao().get())

            fixture.clock.value += 50
            val replay = fixture.repository.applyCoreTrustCommand(command)
            assertTrue(replay is CoreCommandApplyResult.Duplicate)
            val replayReceipt = (replay as CoreCommandApplyResult.Duplicate).receipt
            val replayDigest = replayReceipt.command.commandDigest
            replayDigest[0] = (replayDigest[0].toInt() xor 0x7f).toByte()
            assertArrayEquals(committed.commandDigest, replayReceipt.command.commandDigest)
            val replayActivity = requireNotNull(replayReceipt.pendingActivity)
            val replayArgs = replayActivity.renderArgs
            val committedArgs = replayArgs.copyOf()
            replayArgs[0] = (replayArgs[0].toInt() xor 0x7f).toByte()
            assertArrayEquals(committedArgs, replayActivity.renderArgs)
            assertEquals(committed.appliedAt, database.commandAppliedDao().find(COMMAND_ID)!!.appliedAt)
            assertEquals(committedTrust.updatedAt, database.trustSnapshotDao().get()!!.updatedAt)

            assertEquals(
                CoreCommandApplyResult.Conflict,
                fixture.repository.applyCoreTrustCommand(fixture.command(canonical = byteArrayOf(9))),
            )
            assertEquals(
                CoreCommandApplyResult.Conflict,
                fixture.repository.applyCoreTrustCommand(fixture.command(authenticatedRequestId = "different-request")),
            )
            assertEquals(
                CoreCommandApplyResult.Conflict,
                fixture.repository.applyCoreTrustCommand(
                    fixture.command(commandType = CoreTrustCommandType.DATA_SYNC_TRUST),
                ),
            )
            assertEquals(CoreCommandOutcome.APPLIED, database.commandAppliedDao().find(COMMAND_ID)!!.outcome)
            assertArrayEquals(committedTrust.snapshotDigest, database.trustSnapshotDao().get()!!.snapshotDigest)
        }
    }

    @Test
    fun unchangedCandidatePersistsSupersededMarkerAndSuppressesActivity() = runBlocking {
        withDatabase { database ->
            val fixture = fixture(database)
            val initial = requireNotNull(database.trustSnapshotDao().get())
            val command = fixture.command(
                candidate = fixture.initial,
                activity = activity(),
            )

            val result = fixture.repository.applyCoreTrustCommand(command)
            assertTrue(result is CoreCommandApplyResult.Superseded)
            val receipt = (result as CoreCommandApplyResult.Superseded).receipt
            assertEquals(CoreCommandOutcome.SUPERSEDED, receipt.command.outcome)
            assertEquals(initial.updatedAt, receipt.command.coreRevision)
            assertNull(receipt.pendingActivity)
            assertNull(
                database.activityOutboxDao().find(
                    coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, COMMAND_ID),
                ),
            )
            assertEquals(initial.updatedAt, database.trustSnapshotDao().get()!!.updatedAt)
        }
    }

    @Test
    fun forcedActivityInsertFailureRollsBackMutationAndMarkerThenRetryCommits() = runBlocking {
        withDatabase { database ->
            val fixture = fixture(database)
            val initial = requireNotNull(database.trustSnapshotDao().get())
            val command = fixture.command(activity = activity())
            val eventId = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, COMMAND_ID)
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "CREATE TEMP TRIGGER fail_core_activity " +
                        "BEFORE INSERT ON core_activity_outbox " +
                        "BEGIN SELECT RAISE(ABORT, 'forced activity rollback'); END",
                )
            }

            val failure = runCatching { fixture.repository.applyCoreTrustCommand(command) }.exceptionOrNull()
            assertNotNull("The injected Activity failure must escape as a storage failure", failure)
            assertArrayEquals(initial.snapshotDigest, database.trustSnapshotDao().get()!!.snapshotDigest)
            assertEquals(initial.updatedAt, database.trustSnapshotDao().get()!!.updatedAt)
            assertNull(database.commandAppliedDao().find(COMMAND_ID))
            assertNull(database.activityOutboxDao().find(eventId))

            database.useWriterConnection { connection ->
                connection.executeSQL("DROP TRIGGER fail_core_activity")
            }
            fixture.clock.value += 1
            assertTrue(fixture.repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Applied)
            assertNotNull(database.commandAppliedDao().find(COMMAND_ID))
            assertNotNull(database.activityOutboxDao().find(eventId))
        }
    }

    @Test
    fun activityOutboxForeignKeyRejectsAnOrphanRow() = runBlocking {
        withDatabase { database ->
            val eventId = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, COMMAND_ID)
            val failure = runCatching {
                database.activityOutboxDao().insertRequired(
                    CoreActivityOutboxEntity(
                        commandId = COMMAND_ID,
                        eventId = eventId,
                        operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
                        feature = "profile",
                        semanticAction = "applied",
                        direction = "inbound",
                        outcome = "success",
                        argsVersion = 1,
                        renderArgs = ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1()),
                        occurredAt = 1,
                        createdAt = 1,
                    ),
                )
            }.exceptionOrNull()

            assertNotNull("Core Activity must be a child of a retained command marker", failure)
            assertNull(database.activityOutboxDao().find(eventId))
        }
    }

    @Test
    fun boundedMarkerPruneHonorsCutoffAndProtectsPendingActivity() = runBlocking {
        withDatabase { database ->
            val now = CORE_COMMAND_MARKER_RETENTION_MILLIS + 100
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { now }
            fun marker(commandId: String, appliedAt: Long) = CoreCommandAppliedEntity(
                commandId = commandId,
                authenticatedRequestId = "request-$commandId",
                commandDigest = MessageDigest.getInstance("SHA-256").digest(commandId.encodeToByteArray()),
                commandType = CoreTrustCommandType.DATA_SYNC_PROFILE.token,
                outcome = CoreCommandOutcome.APPLIED,
                coreRevision = appliedAt,
                appliedAt = appliedAt,
            )
            val protected = marker("protected", 1)
            val oldOne = marker("old-1", 2)
            val oldTwo = marker("old-2", 3)
            val boundary = marker("boundary", 100)
            for (candidate in listOf(protected, oldOne, oldTwo, boundary)) {
                database.commandAppliedDao().insertRequired(candidate)
            }
            val protectedEventId = coreCommandActivityEventId(
                CoreTrustCommandType.DATA_SYNC_PROFILE,
                protected.commandId,
            )
            database.activityOutboxDao().insertRequired(
                CoreActivityOutboxEntity(
                    commandId = protected.commandId,
                    eventId = protectedEventId,
                    operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
                    feature = "profile",
                    semanticAction = "applied",
                    direction = "inbound",
                    outcome = "success",
                    argsVersion = 1,
                    renderArgs = ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1()),
                    occurredAt = 1,
                    createdAt = 1,
                ),
            )

            assertEquals(1, repository.pruneRetainedCoreCommandMarkers(limit = 1))
            assertNull(database.commandAppliedDao().find(oldOne.commandId))
            assertNotNull(database.commandAppliedDao().find(oldTwo.commandId))
            assertNotNull(database.commandAppliedDao().find(protected.commandId))
            assertNotNull(database.commandAppliedDao().find(boundary.commandId))

            assertEquals(1, repository.pruneRetainedCoreCommandMarkers(limit = 64))
            assertNull(database.commandAppliedDao().find(oldTwo.commandId))
            assertNotNull(database.commandAppliedDao().find(protected.commandId))
            assertNotNull(database.commandAppliedDao().find(boundary.commandId))

            assertTrue(repository.acknowledgeCopiedCoreActivity(protectedEventId, INITIAL_OPERATIONAL_GENERATION))
            assertEquals(1, repository.pruneRetainedCoreCommandMarkers(limit = 64))
            assertNull(database.commandAppliedDao().find(protected.commandId))
            assertNotNull(database.commandAppliedDao().find(boundary.commandId))
        }
    }

    @Test
    fun staleGenerationIncarnationDigestAndClosedReplayFencePerformNoWrites() = runBlocking {
        withDatabase { database ->
            val fixture = fixture(database)
            assertEquals(
                CoreCommandApplyResult.StaleCoreState,
                fixture.repository.applyCoreTrustCommand(
                    fixture.command(expectedDigest = ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES) { 9 }),
                ),
            )
            assertEquals(
                CoreCommandApplyResult.StaleCoreState,
                fixture.repository.applyCoreTrustCommand(fixture.command(expectedGeneration = 2)),
            )
            assertEquals(
                CoreCommandApplyResult.StaleCoreState,
                fixture.repository.applyCoreTrustCommand(
                    fixture.command(expectedIncarnationId = "recreated-same-generation"),
                ),
            )
            val transport = requireNotNull(database.transportStateDao().get())
            assertEquals(
                1,
                database.transportStateDao().update(
                    transport.copy(
                        replayFenceState = ReplayFenceState.FENCE_REQUIRED,
                        continuityOrigin = null,
                        updatedAt = transport.updatedAt + 1,
                    ),
                ),
            )
            assertEquals(
                CoreCommandApplyResult.CoreNotReady,
                fixture.repository.applyCoreTrustCommand(fixture.command()),
            )
            assertNull(database.commandAppliedDao().find(COMMAND_ID))
            assertArrayEquals(fixture.initialDigest, database.trustSnapshotDao().get()!!.snapshotDigest)
        }
    }

    @Test
    fun processReopenReplaysFromAppliedMarkerWithoutRepeatingMutation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "core-command-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        var database = open(context, name)
        try {
            val fixture = fixture(database)
            val command = fixture.command(activity = activity())
            assertTrue(fixture.repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Applied)
            val firstMarker = requireNotNull(database.commandAppliedDao().find(COMMAND_ID))
            val firstTrust = requireNotNull(database.trustSnapshotDao().get())
            val eventId = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, COMMAND_ID)
            assertNotNull(database.activityOutboxDao().find(eventId))

            database.close()
            database = open(context, name)
            fixture.clock.value += 100
            val reopenedRepository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) {
                fixture.clock.value
            }
            val replay = reopenedRepository.applyCoreTrustCommand(command)
            assertTrue(replay is CoreCommandApplyResult.Duplicate)
            assertEquals(firstMarker.appliedAt, database.commandAppliedDao().find(COMMAND_ID)!!.appliedAt)
            assertEquals(firstTrust.updatedAt, database.trustSnapshotDao().get()!!.updatedAt)
            assertArrayEquals(firstTrust.snapshotDigest, database.trustSnapshotDao().get()!!.snapshotDigest)
            assertNotNull(database.activityOutboxDao().find(eventId))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun fixture(database: CoreDatabase): Fixture {
        val signer = SoftwareIdentitySigner.generate()
        val clock = MutableClock(100)
        val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock.value }
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
                    operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
                    operationalIncarnationId = "core-command-test-incarnation",
                    replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
                    continuityOrigin = OperationalContinuityOrigin.FRESH_IDENTITY,
                    updatedAt = clock.value,
                ),
            ) != -1L,
        )
        val initialDigest = database.trustSnapshotDao().get()!!.snapshotDigest.copyOf()
        val candidate = snapshot(signer, "[{\"changed\":1}]")
        clock.value = 200
        return Fixture(repository, signer, clock, initial, initialDigest, candidate)
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

    private fun activity(revision: Long = 1): CoreCommandActivity = CoreCommandActivity(
        action = ActivityAction.APPLIED,
        peerClientId = PEER_ID,
        deliveryMode = ActivityDeliveryMode.FCM_RELAY_FETCH,
        renderArgs = ActivityRenderArgs.V1(revision = revision),
        occurredAt = 10,
    )

    private suspend fun withDatabase(block: suspend (CoreDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<CoreDatabase>(
            context = ApplicationProvider.getApplicationContext(),
        )
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun open(context: Context, name: String): CoreDatabase =
        Room.databaseBuilder<CoreDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private data class MutableClock(var value: Long)

    private inner class Fixture(
        val repository: CoreFoundationRepository,
        val signer: SoftwareIdentitySigner,
        val clock: MutableClock,
        val initial: TrustSnapshotInput,
        val initialDigest: ByteArray,
        val candidate: TrustSnapshotInput,
    ) {
        fun command(
            canonical: ByteArray = byteArrayOf(1, 2, 3),
            authenticatedRequestId: String = REQUEST_ID,
            commandType: CoreTrustCommandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
            expectedGeneration: Long = INITIAL_OPERATIONAL_GENERATION,
            expectedIncarnationId: String = "core-command-test-incarnation",
            expectedDigest: ByteArray? = initialDigest,
            candidate: TrustSnapshotInput = this.candidate,
            activity: CoreCommandActivity? = null,
        ): CoreTrustCommand = CoreTrustCommand(
            commandId = COMMAND_ID,
            authenticatedRequestId = authenticatedRequestId,
            canonicalCommand = canonical,
            commandType = commandType,
            expectedOperationalGeneration = expectedGeneration,
            expectedOperationalIncarnationId = expectedIncarnationId,
            expectedSnapshotDigest = expectedDigest,
            candidateSnapshot = candidate,
            activity = activity,
        )
    }

    private companion object {
        const val COMMAND_ID = "command-1"
        const val REQUEST_ID = "request-1"
        const val PEER_ID = "peer-1"
        const val CARDS = "{}"
        const val OVERLAYS = "{}"
        const val EPOCHS = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
    }
}
