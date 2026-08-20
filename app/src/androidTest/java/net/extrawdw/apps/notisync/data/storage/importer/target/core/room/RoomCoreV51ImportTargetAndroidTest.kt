package net.extrawdw.apps.notisync.data.storage.importer.target.core.room

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.core.CoreActivityOutboxEntity
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandAppliedEntity
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandOutcome
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreRoomStore
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochState
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationEntity
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationKind
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationState
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationTarget
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.core.CoreV51CutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.core.CoreV51CutoverResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReadResult
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreSourceContract
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesSourceContract
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreSourceDigests
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyIdentityKeySource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyKeystoreSecurityLevel
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyOperationalSignerSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyWrappingKeySource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping.CoreV51MappingDefaults
import net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping.LegacyCoreV51Mapper
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationGate
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51Digest
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalRebuildStep
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalStorageBinding
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PlanSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PrepareResult
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventorySource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventory
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCoreV51ImportTargetAndroidTest {
    @Test
    fun transportIsPublishedLastAndCompletedRestartNeverReadsLegacy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("complete")
        context.deleteDatabase(name)
        var database = open(context, name)
        try {
            val fixture = planFixture(context, fourSection = true)
            var sourceCalls = 0
            var inventoryCalls = 0
            var operationalCalls = 0
            var gateCalls = 0
            val coordinator = coordinator(
                database = database,
                planSource = {
                    sourceCalls += 1
                    assertNull(database.transportStateDao().get())
                    fixture.plan
                },
                inventorySource = {
                    inventoryCalls += 1
                    READY_INVENTORY
                },
                operationalStep = {
                    operationalCalls += 1
                    OPERATIONAL_IDENTITY
                },
                activation = { snapshot ->
                    gateCalls += 1
                    assertNull(database.transportStateDao().get())
                    activationFor(snapshot)
                },
            )

            assertEquals(CoreV51CutoverResult.IMPORTED, coordinator())
            assertPlanPersisted(database, fixture)
            val transport = requireNotNull(database.transportStateDao().get())
            assertEquals(OPERATIONAL_IDENTITY.operationalGeneration, transport.operationalGeneration)
            assertEquals(OPERATIONAL_IDENTITY.storageIncarnationId, transport.operationalIncarnationId)
            assertEquals(1, sourceCalls)
            assertEquals(1, inventoryCalls)
            assertEquals(1, operationalCalls)
            assertEquals(1, gateCalls)

            database.close()
            database = open(context, name)
            val replay = coordinator(
                database = database,
                planSource = { error("completed Core must not read legacy") },
                inventorySource = { error("completed Core must not recapture legacy inventory") },
                operationalStep = { error("completed Core must not rebuild Operational") },
                activation = { error("completed Core must not activate keys") },
            )
            assertEquals(CoreV51CutoverResult.ALREADY_COMPLETE, replay())
            assertPlanPersisted(database, fixture)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun processDeathAtEveryCoreBoundaryRestartsFromOrdinalZero() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (failurePoint in FailurePoint.entries) {
            val name = databaseName("restart-${failurePoint.name.lowercase()}")
            context.deleteDatabase(name)
            var database = open(context, name)
            try {
                val fixture = planFixture(context, fourSection = true)
                var sourceCalls = 0
                val cancellation = CancellationException("simulated process death")
                val first = coordinator(
                    database = database,
                    planSource = {
                        sourceCalls += 1
                        if (failurePoint == FailurePoint.CORE_SOURCE) throw cancellation
                        fixture.plan
                    },
                    inventorySource = { READY_INVENTORY },
                    operationalStep = {
                        if (failurePoint == FailurePoint.OPERATIONAL) throw cancellation
                        OPERATIONAL_IDENTITY
                    },
                    activation = { snapshot ->
                        if (failurePoint == FailurePoint.ACTIVATION) throw cancellation
                        activationFor(snapshot)
                    },
                )
                try {
                    first()
                    fail("CancellationException expected at $failurePoint")
                } catch (actual: CancellationException) {
                    assertEquals(cancellation, actual)
                }
                assertNull(database.transportStateDao().get())
                assertNull(database.brokerAuthTokenDao().get())

                database.close()
                database = open(context, name)
                val retry = coordinator(
                    database = database,
                    planSource = { sourceCalls += 1; fixture.plan },
                    inventorySource = { READY_INVENTORY },
                    operationalStep = { OPERATIONAL_IDENTITY },
                    activation = { activationFor(it) },
                )
                assertEquals(CoreV51CutoverResult.IMPORTED, retry())
                assertPlanPersisted(database, fixture)
                assertTrue(sourceCalls >= 1)
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test
    fun restartPurgeCoversEveryPreAuthorityProjectionAndRebuildsExactBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("purge")
        context.deleteDatabase(name)
        var database = open(context, name)
        try {
            val fixture = planFixture(context, fourSection = true)
            var clock = 100L
            var target = target(database) { clock++ }
            assertEquals(CoreV51PrepareResult.READY, target.prepareForRebuild())
            target.stage(fixture.plan)
            val firstIdentity = requireNotNull(database.identityMetadataDao().get()).publicSpki.copyOf()
            val firstTrust = requireNotNull(database.trustSnapshotDao().get()).snapshotDigest.copyOf()
            val firstEpoch = requireNotNull(database.cryptoEpochDao().find(1)).hpkePrivateKeysetWrapped!!.copyOf()
            seedDeliveryProjections(database)
            assertEquals(1, database.commandAppliedDao().countAll())
            assertEquals(1, database.activityOutboxDao().countAll())
            assertNull(database.transportStateDao().get())

            database.close()
            database = open(context, name)
            target = target(database) { clock++ }
            assertEquals(CoreV51PrepareResult.READY, target.prepareForRebuild())
            assertNull(database.identityMetadataDao().get())
            assertNull(database.trustSnapshotDao().get())
            assertTrue(database.cryptoEpochDao().getAll().isEmpty())
            assertNull(database.maintenanceStateDao().get())
            assertEquals(0, database.commandAppliedDao().countAll())
            assertEquals(0, database.activityOutboxDao().countAll())
            assertNull(database.brokerAuthTokenDao().get())
            assertNull(database.transportStateDao().get())

            target.stage(fixture.plan)
            assertArrayEquals(firstIdentity, database.identityMetadataDao().get()!!.publicSpki)
            assertArrayEquals(firstTrust, database.trustSnapshotDao().get()!!.snapshotDigest)
            assertArrayEquals(firstEpoch, database.cryptoEpochDao().find(1)!!.hpkePrivateKeysetWrapped)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun purgePreservesKeystoreRecoveryJournalAndRefusesToProceed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("keystore")
        context.deleteDatabase(name)
        val database = open(context, name)
        try {
            val fixture = planFixture(context, fourSection = true)
            val target = target(database) { 200L }
            assertEquals(CoreV51PrepareResult.READY, target.prepareForRebuild())
            target.stage(fixture.plan)
            seedDeliveryProjections(database)
            val operation = KeystoreOperationEntity(
                operationId = "fresh.identity.create",
                targetType = KeystoreOperationTarget.IDENTITY,
                targetId = "notisync.identity.v1",
                operationKind = KeystoreOperationKind.CREATE,
                state = KeystoreOperationState.PENDING,
                attempts = 0,
                createdAt = 10,
                updatedAt = 10,
            )
            assertTrue(database.keystoreOperationDao().insertIfAbsent(operation) != -1L)

            assertEquals(CoreV51PrepareResult.KEYSTORE_RECOVERY_REQUIRED, target.prepareForRebuild())
            assertNotNull(database.keystoreOperationDao().find(operation.operationId))
            assertNull(database.identityMetadataDao().get())
            assertNull(database.trustSnapshotDao().get())
            assertTrue(database.cryptoEpochDao().getAll().isEmpty())
            assertNull(database.maintenanceStateDao().get())
            assertEquals(0, database.commandAppliedDao().countAll())
            assertEquals(0, database.activityOutboxDao().countAll())
            assertNull(database.transportStateDao().get())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedFinalTransactionNeverPublishesTransportAndValidRetryCommitsAtomically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("final")
        context.deleteDatabase(name)
        val database = open(context, name)
        try {
            val fixture = planFixture(context, fourSection = true)
            val target = target(database) { 300L }
            assertEquals(CoreV51PrepareResult.READY, target.prepareForRebuild())
            target.stage(fixture.plan)
            val snapshot = target.readActivationSnapshot(fixture.plan)
            val wrong = activationFor(snapshot, candidateDigest = CoreV51Digest.sha256(ByteArray(32) { 8 }))

            val failure = runCatching {
                target.finalize(fixture.plan, wrong, OPERATIONAL_IDENTITY)
            }.exceptionOrNull() as CoreV51ImportFailure
            assertEquals("activation_evidence_mismatch", failure.errorCode)
            assertNull(database.transportStateDao().get())
            assertNull(database.brokerAuthTokenDao().get())
            assertEquals(CryptoEpochState.PROVISIONING, database.cryptoEpochDao().find(1)!!.lifecycleState)

            target.finalize(fixture.plan, activationFor(snapshot), OPERATIONAL_IDENTITY)
            assertNotNull(database.transportStateDao().get())
            assertArrayEquals(fixture.wrappedToken, database.brokerAuthTokenDao().get()!!.wrappedToken)
            assertEquals(CryptoEpochState.ACTIVE, database.cryptoEpochDao().find(1)!!.lifecycleState)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun allAbsentOriginNeverCreatesAuthorityOrOpensLegacyCore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("absent")
        context.deleteDatabase(name)
        val database = open(context, name)
        try {
            var sourceCalls = 0
            val absent = CoreV51ImportPlan.absent()
            val invoke = coordinator(
                database = database,
                planSource = { sourceCalls += 1; absent },
                inventorySource = { V51LegacySourceInventory.ALL_ABSENT },
                operationalStep = { OPERATIONAL_IDENTITY },
                activation = { error("absent Core must not activate") },
            )
            assertEquals(CoreV51CutoverResult.SOURCE_ABSENT, invoke())
            assertEquals(CoreV51CutoverResult.SOURCE_ABSENT, invoke())
            assertEquals(0, sourceCalls)
            assertNull(database.transportStateDao().get())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun readerMappedThreeSectionKeepsEpochSectionPhysicallyAbsentAcrossRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = databaseName("three")
        context.deleteDatabase(name)
        var database = open(context, name)
        try {
            val fixture = planFixture(context, fourSection = false)
            val invoke = coordinator(
                database,
                { fixture.plan },
                { READY_INVENTORY },
                { OPERATIONAL_IDENTITY },
                { activationFor(it) },
            )
            assertEquals(CoreV51CutoverResult.IMPORTED, invoke())
            val raw = requireNotNull(database.trustSnapshotDao().get())
            assertEquals("TRUSTSTORE_V1_THREE_SECTION", raw.signatureFormat)
            assertNull(raw.epochsUtf8)
            val digest = raw.snapshotDigest.copyOf()

            database.close()
            database = open(context, name)
            val validated = requireNotNull(
                CoreFoundationRepository(CoreRoomStore.forDatabase(database)).loadValidatedTrustSnapshot(),
            )
            assertNull(validated.epochsUtf8)
            assertArrayEquals(digest, validated.snapshotDigest)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun coordinator(
        database: CoreDatabase,
        planSource: suspend () -> CoreV51ImportPlan,
        inventorySource: suspend () -> V51LegacySourceInventory,
        operationalStep: suspend () -> CoreV51OperationalStorageBinding,
        activation: suspend (CoreV51ActivationSnapshot) -> CoreV51ActivationEvidence,
    ): suspend () -> CoreV51CutoverResult {
        val coordinator = CoreV51CutoverCoordinator(
            CoreV51PlanSource { planSource() },
            target(database),
            CoreV51ActivationGate { snapshot -> activation(snapshot) },
        )
        return {
            coordinator.run(
                V51LegacySourceInventorySource { inventorySource() },
                CoreV51OperationalRebuildStep { operationalStep() },
            )
        }
    }

    private fun target(database: CoreDatabase, clock: () -> Long = { 100L }): RoomCoreV51ImportTarget =
        RoomCoreV51ImportTarget(
            database = database,
            repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database), clock),
            now = clock,
        )

    private fun open(context: Context, name: String): CoreDatabase =
        Room.databaseBuilder<CoreDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private suspend fun assertPlanPersisted(database: CoreDatabase, fixture: CoreV51RoomFixture) {
        val foundation = requireNotNull(fixture.plan.foundation)
        assertArrayEquals(foundation.identity.publicSpkiCopy(), database.identityMetadataDao().get()!!.publicSpki)
        assertArrayEquals(fixture.wrappedPrivate, database.cryptoEpochDao().find(1)!!.hpkePrivateKeysetWrapped)
        assertArrayEquals(fixture.wrappedToken, database.brokerAuthTokenDao().get()!!.wrappedToken)
        assertArrayEquals(
            fixture.trustSignature.encodeToByteArray(),
            database.trustSnapshotDao().get()!!.signatureBase64UrlUtf8,
        )
        assertEquals(CryptoEpochState.ACTIVE, database.cryptoEpochDao().find(1)!!.lifecycleState)
    }

    private suspend fun seedDeliveryProjections(database: CoreDatabase) {
        database.commandAppliedDao().insertRequired(
            CoreCommandAppliedEntity(
                commandId = "command-1",
                authenticatedRequestId = "request-1",
                commandDigest = ByteArray(32) { 4 },
                commandType = "data_sync.trust",
                outcome = CoreCommandOutcome.APPLIED,
                coreRevision = 1,
                appliedAt = 50,
            ),
        )
        database.activityOutboxDao().insertRequired(
            CoreActivityOutboxEntity(
                commandId = "command-1",
                eventId = "event-1",
                operationalGeneration = 1,
                feature = "TRUST",
                semanticAction = "UPDATED",
                direction = "INCOMING",
                outcome = "SUCCEEDED",
                argsVersion = 1,
                renderArgs = byteArrayOf(1),
                occurredAt = 50,
                createdAt = 50,
            ),
        )
    }

    private enum class FailurePoint { OPERATIONAL, CORE_SOURCE, ACTIVATION }

    private companion object {
        val READY_INVENTORY = V51LegacySourceInventory.CORE_FOUNDATION_PRESENT
        val OPERATIONAL_IDENTITY = CoreV51OperationalStorageBinding(1, "core-v51-target-incarnation")
        fun databaseName(suffix: String): String = "core-v51-$suffix-${System.nanoTime()}.db"
    }
}

private class CoreV51RoomFixture(
    val plan: CoreV51ImportPlan,
    val trustSignature: String,
    val wrappedPrivate: ByteArray,
    val wrappedToken: ByteArray,
)

private suspend fun planFixture(context: Context, fourSection: Boolean): CoreV51RoomFixture {
    val identity = SoftwareIdentitySigner.generate()
    val entries = "[]"
    val cards = "{}"
    val overlays = "{}"
    val epochs = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
    val signature = if (fourSection) {
        TrustStoreSigning.sign(identity, entries, cards, overlays, epochs)
    } else {
        signLegacyThree(identity, entries, cards, overlays)
    }
    val preferences = mutablePreferencesOf().apply {
        this[stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_ENTRIES_KEY)] = entries
        this[stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_CARDS_KEY)] = cards
        this[stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_OVERLAYS_KEY)] = overlays
        if (fourSection) this[stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_EPOCHS_KEY)] = epochs
        this[stringPreferencesKey(LegacyCorePreferencesSourceContract.TRUST_SIGNATURE_KEY)] = signature
    }
    val preferencesResult = LegacyCorePreferencesDataStoreReader().read(preferences)

    val pair = Hpke.generateKeyPair()
    val wrappedPrivate = wrapFixture(pair.privateKeyset)
    val wrappedToken = wrapFixture(byteArrayOf(9, 8, 7))
    val directory = File(context.cacheDir, "core-v51-reader-${System.nanoTime()}")
    check(directory.mkdir())
    val publicFile = File(directory, "hpke_public.epoch1.bin")
    val privateFile = File(directory, "hpke_private.epoch1.wrapped")
    val tokenFile = File(directory, "auth_token.wrapped")
    val filesResult = try {
        publicFile.writeBytes(pair.publicKeyset)
        privateFile.writeBytes(wrappedPrivate)
        tokenFile.writeBytes(wrappedToken)
        LegacyCoreFileReader(directory.toPath()).read()
    } finally {
        publicFile.delete()
        privateFile.delete()
        tokenFile.delete()
        directory.delete()
    }
    val digests = LegacyCoreSourceDigests(ByteArray(32) { 2 }, ByteArray(32) { 3 })
    val keystoreSnapshot = LegacyCoreKeystoreSnapshot(
        identity = LegacyIdentityKeySource(
            LegacyCoreKeystoreSourceContract.IDENTITY_ALIAS,
            1,
            identity.publicKeySpki,
            LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
            1,
        ),
        operationalSigners = listOf(
            LegacyOperationalSignerSource(
                1,
                LegacyCoreKeystoreSourceContract.OPERATIONAL_ALIAS_PREFIX + "1",
                1,
                SoftwareIdentitySigner.generate().publicKeySpki,
                LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                1,
            ),
        ),
        wrappingKey = LegacyWrappingKeySource(
            LegacyCoreKeystoreSourceContract.WRAPPING_ALIAS,
            1,
            LegacyKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
            1,
        ),
        digests = digests,
    )
    val keystoreResult = LegacyCoreKeystoreReadResult(
        LegacyCoreReadStatus.READY,
        keystoreSnapshot,
        emptySet(),
        relevantAliasCount = 3,
        digests = digests,
    )
    val plan = LegacyCoreV51Mapper(CoreV51MappingDefaults("https://broker.example.test"))
        .map(preferencesResult, keystoreResult, filesResult)
    return CoreV51RoomFixture(plan, signature, wrappedPrivate, wrappedToken)
}

private fun wrapFixture(value: ByteArray): ByteArray =
    byteArrayOf(12) + ByteArray(12) { 1 } + value + ByteArray(16) { 2 }

private fun signLegacyThree(
    signer: SoftwareIdentitySigner,
    entries: String,
    cards: String,
    overlays: String,
): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun digest(value: String): String = encoder.encodeToString(sha256(value.encodeToByteArray()))
    val canonical = buildString {
        append(TrustStoreSigning.VERSION).append('\n')
        append(signer.clientId.value).append('\n')
        append(digest(entries)).append('\n')
        append(digest(cards)).append('\n')
        append(digest(overlays))
    }.encodeToByteArray()
    return encoder.encodeToString(signer.sign(canonical))
}

private fun activationFor(
    snapshot: CoreV51ActivationSnapshot,
    candidateDigest: CoreV51Digest = snapshot.candidateDigest,
): CoreV51ActivationEvidence = CoreV51ActivationEvidence(
    planDigest = snapshot.planDigest,
    candidateDigest = candidateDigest,
    identityClientId = ClientIds.derive(snapshot.identity.publicSpkiCopy()).value,
    identitySelfTestedAt = 20,
    wrappingKeySelfTestedAt = 21,
    epochEvidence = snapshot.epochs.map { epoch ->
        CoreV51EpochActivationEvidence(
            epoch = epoch.epoch,
            hpkePublicKeysetFingerprint = sha256(epoch.hpkePublicKeysetCopy()),
            operationalSignerSelfTestedAt = 22,
            hpkePairSelfTestedAt = 23,
        )
    },
    authTokenSelfTestedAt = snapshot.authToken?.let { 24 },
    validatedAt = 25,
)

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
