package net.extrawdw.apps.notisync.data.storage.core

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreDatabaseTest {
    @Test
    fun coreV1SecurityTablesHaveExactNormalFormColumns() = runBlocking {
        withDatabase { database ->
            assertEquals(
                setOf("singleton", "trust_cleanup_state", "trust_cleanup_completed_at", "updated_at"),
                database.tableColumns("core_maintenance_state"),
            )
            assertEquals(
                setOf(
                    "singleton",
                    "key_alias",
                    "key_alias_version",
                    "public_spki",
                    "client_id",
                    "security_level",
                    "lifecycle_state",
                    "created_at",
                    "updated_at",
                ),
                database.tableColumns("identity_metadata"),
            )
            assertEquals(
                setOf(
                    "epoch",
                    "operational_signer_alias",
                    "operational_signer_public_spki",
                    "hpke_public_keyset",
                    "hpke_private_keyset_wrapped",
                    "security_level",
                    "lifecycle_state",
                    "anti_rollback_floor",
                    "activation_at",
                    "retirement_at",
                    "created_at",
                    "updated_at",
                ),
                database.tableColumns("crypto_epoch"),
            )
            assertEquals(
                setOf(
                    "core_maintenance_state",
                    "identity_metadata",
                    "trust_snapshot",
                    "crypto_epoch",
                    "broker_auth_token",
                    "core_transport_state",
                    "keystore_operation",
                    "core_command_applied",
                    "core_activity_outbox",
                ),
                database.applicationTableNames(),
            )
            assertFalse("core_storage_import" in database.applicationTableNames())
            assertFalse("core_v51_cutover_attempt" in database.applicationTableNames())
        }
    }

    @Test
    fun authorityTablesPersistRepresentativeFoundationState() = runBlocking {
        withDatabase { database ->
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 100L }
            val signer = SoftwareIdentitySigner.generate()
            repository.saveMaintenanceState(
                CoreMaintenanceUpdate(
                    trustCleanupState = TrustCleanupState.NOT_STARTED,
                ),
            )
            repository.saveIdentityMetadata(
                IdentityMetadataInput(
                    keyAlias = "notisync.identity.v1",
                    keyAliasVersion = 1,
                    publicSpki = signer.publicKeySpki,
                    securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
                    lifecycleState = IdentityLifecycleState.ACTIVE,
                    createdAt = 1,
                ),
            )
            val entries = "[]"
            val cards = "{}"
            val overlays = "{}"
            val epochs = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
            assertEquals(
                TrustSnapshotWriteResult.APPLIED,
                repository.replaceTrustSnapshot(
                    TrustSnapshotInput.FourSection(
                        entriesUtf8 = entries.encodeToByteArray(),
                        cardsUtf8 = cards.encodeToByteArray(),
                        overlaysUtf8 = overlays.encodeToByteArray(),
                        epochsUtf8 = epochs.encodeToByteArray(),
                        signatureBase64UrlUtf8 = TrustStoreSigning.sign(
                            signer,
                            entries,
                            cards,
                            overlays,
                            epochs,
                        ).encodeToByteArray(),
                    ),
                ),
            )
            repository.saveCryptoEpoch(
                CryptoEpochInput(
                    epoch = 1,
                    operationalSignerAlias = "notisync.operational.v1.epoch1",
                    operationalSignerPublicSpki = byteArrayOf(12),
                    hpkePublicKeyset = byteArrayOf(13),
                    hpkePrivateKeysetWrapped = byteArrayOf(14),
                    securityLevel = CryptoEpochSecurityLevel.TRUSTED_ENVIRONMENT,
                    lifecycleState = CryptoEpochState.ACTIVE,
                    antiRollbackFloor = 1,
                    activationAt = 1,
                    createdAt = 1,
                ),
            )
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://broker.example.test",
                    groupId = "group",
                    fcmRouteRef = "route",
                    routeEpoch = 0,
                    updatedAt = 100,
                ),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(
                    BrokerAuthTokenInput(
                        wrappedToken = byteArrayOf(15),
                        encodingVersion = 1,
                        issuedAt = 1,
                        expiresAt = 2,
                        expectedBrokerEndpointRevision = 0,
                    ),
                ),
            )
            assertEquals(
                KeystoreOperationEnsureResult.INSERTED,
                repository.ensureKeystoreOperation(
                    KeystoreOperationIntent(
                        operationId = "operation",
                        targetType = KeystoreOperationTarget.CRYPTO_EPOCH,
                        targetId = "1",
                        operationKind = KeystoreOperationKind.CREATE,
                        createdAt = 1,
                    ),
                ),
            )
            val commandEntries = "[{\"command\":1}]"
            val commandCandidate = TrustSnapshotInput.FourSection(
                entriesUtf8 = commandEntries.encodeToByteArray(),
                cardsUtf8 = cards.encodeToByteArray(),
                overlaysUtf8 = overlays.encodeToByteArray(),
                epochsUtf8 = epochs.encodeToByteArray(),
                signatureBase64UrlUtf8 = TrustStoreSigning.sign(
                    signer,
                    commandEntries,
                    cards,
                    overlays,
                    epochs,
                ).encodeToByteArray(),
            )
            assertTrue(
                repository.applyCoreTrustCommand(
                    CoreTrustCommand(
                        commandId = "command",
                        authenticatedRequestId = "request",
                        canonicalCommand = byteArrayOf(16),
                        commandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
                        expectedOperationalGeneration = 1,
                        expectedOperationalIncarnationId = OPERATIONAL_INCARNATION_ID,
                        expectedSnapshotDigest = database.trustSnapshotDao().get()!!.snapshotDigest,
                        candidateSnapshot = commandCandidate,
                        activity = CoreCommandActivity(
                            action = ActivityAction.APPLIED,
                            peerClientId = "peer",
                            deliveryMode = ActivityDeliveryMode.WEBSOCKET,
                            renderArgs = ActivityRenderArgs.V1(revision = 1),
                            occurredAt = 1,
                        ),
                    ),
                ) is CoreCommandApplyResult.Applied,
            )

            // Core v1 has exactly these nine application tables; no generic import journal or cutover-attempt table
            // is part of the production schema.
            assertEquals(9, database.tableCountSnapshot())
            assertEquals(TrustCleanupState.NOT_STARTED, database.maintenanceStateDao().get()!!.trustCleanupState)
            assertEquals(
                signer.clientId.value,
                database.identityMetadataDao().get()!!.clientId,
            )
            assertArrayEquals(commandEntries.encodeToByteArray(), database.trustSnapshotDao().get()!!.entriesUtf8)
            assertEquals(1, database.cryptoEpochDao().find(1)!!.epoch)
            assertArrayEquals(byteArrayOf(15), database.brokerAuthTokenDao().get()!!.wrappedToken)
            val transport = requireNotNull(database.transportStateDao().get())
            assertEquals(ReplayFenceState.CONTINUITY_INTACT, transport.replayFenceState)
            assertEquals(OperationalContinuityOrigin.VERIFIED_V51_CUTOVER, transport.continuityOrigin)
            assertEquals(0, database.keystoreOperationDao().find("operation")!!.attempts)
            assertEquals("data_sync.profile", database.commandAppliedDao().find("command")!!.commandType)
            val activityEventId = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, "command")
            assertEquals(activityEventId, database.activityOutboxDao().find(activityEventId)!!.eventId)
        }
    }

    @Test
    fun freshAuthorityCommitsCryptoAndTransportAtomicallyWithExactOperationalBinding() = runBlocking {
        withDatabase { database ->
            var clock = 180L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            val initialization = FreshIdentityTransportInitialization("https://broker.example.test")
            val operationalStorage = operationalStorageBinding()
            repository.saveIdentityMetadata(freshIdentityInput())
            val cryptoEpoch = freshCryptoEpoch()

            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                repository.initializeFreshAuthority(initialization, operationalStorage, cryptoEpoch),
            )
            val initialized = requireNotNull(database.transportStateDao().get())
            assertEquals(INITIAL_OPERATIONAL_GENERATION, initialized.operationalGeneration)
            assertEquals(OPERATIONAL_INCARNATION_ID, initialized.operationalIncarnationId)
            assertEquals(ReplayFenceState.CONTINUITY_INTACT, initialized.replayFenceState)
            assertEquals(OperationalContinuityOrigin.FRESH_IDENTITY, initialized.continuityOrigin)
            assertEquals(CryptoEpochState.ACTIVE, database.cryptoEpochDao().find(1)?.lifecycleState)

            clock = 181L
            assertEquals(
                CoreTransportInitializationResult.ALREADY_INITIALIZED,
                repository.initializeFreshAuthority(initialization, operationalStorage, cryptoEpoch),
            )
            assertEquals(180L, database.transportStateDao().get()!!.updatedAt)

            assertEquals(
                CoreTransportInitializationResult.CONFLICT,
                repository.initializeFreshAuthority(
                    initialization,
                    operationalStorageBinding("different-incarnation"),
                    cryptoEpoch,
                ),
            )
            assertEquals(
                CoreTransportInitializationResult.CONFLICT,
                database.initializeVerifiedTransport(
                    brokerUrl = initialization.brokerUrl,
                    routeEpoch = 0,
                    updatedAt = 181,
                ),
            )
            assertEquals(OperationalContinuityOrigin.FRESH_IDENTITY, database.transportStateDao().get()!!.continuityOrigin)
        }

        withDatabase { database ->
            val failure = runCatching { OperationalStorageBinding(0, OPERATIONAL_INCARNATION_ID) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 190L }
            assertEquals(
                CoreTransportInitializationResult.CONFLICT,
                repository.initializeFreshAuthority(
                    FreshIdentityTransportInitialization("https://broker.example.test"),
                    operationalStorageBinding(),
                    freshCryptoEpoch(),
                ),
            )
            assertNull(database.transportStateDao().get())
            assertNull(database.cryptoEpochDao().find(1))
        }
    }

    @Test
    fun routeAndOperationalGenerationTransitionsAreMonotonicAndFenceClosed() = runBlocking {
        withDatabase { database ->
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 200L }
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://broker.example.test",
                    routeEpoch = 1,
                    updatedAt = 200,
                ),
            )
            assertEquals(
                RouteAdvanceResult.ADVANCED,
                repository.advanceRoute(
                    RouteUpdate(
                        brokerUrl = "https://broker.example.test",
                        fcmRouteRef = "route-2",
                        routeEpoch = 2,
                        expectedBrokerEndpointRevision = 0,
                        selfEpochActivatedAt = 20,
                    ),
                ),
            )
            assertEquals(
                RouteAdvanceResult.STALE,
                repository.advanceRoute(
                    RouteUpdate(
                        brokerUrl = "https://broker.example.test",
                        fcmRouteRef = "route-old",
                        routeEpoch = 1,
                        expectedBrokerEndpointRevision = 0,
                        selfEpochActivatedAt = null,
                    ),
                ),
            )
            assertEquals(
                RouteAdvanceResult.CONFLICT,
                repository.advanceRoute(
                    RouteUpdate(
                        brokerUrl = "https://other.example.test",
                        fcmRouteRef = "route-conflict",
                        routeEpoch = 2,
                        expectedBrokerEndpointRevision = 0,
                        selfEpochActivatedAt = 20,
                    ),
                ),
            )
            assertEquals(
                RouteAdvanceResult.CONFLICT,
                repository.advanceRoute(
                    RouteUpdate(
                        brokerUrl = "https://other.example.test",
                        fcmRouteRef = "route-newer-but-wrong-broker",
                        routeEpoch = 3,
                        expectedBrokerEndpointRevision = 0,
                        selfEpochActivatedAt = 30,
                    ),
                ),
            )
            assertNull(database.transportStateDao().get()!!.groupId)

            assertEquals(ReplayFenceResult.CONTINUITY_INTACT, repository.beginReplayFence(1))
            assertEquals(ReplayFenceResult.BLOCKED, repository.establishReplayFence(1, "not-a-reset-fence", 1))
            assertEquals(ReplayFenceState.CONTINUITY_INTACT, database.transportStateDao().get()!!.replayFenceState)
            assertEquals(OperationalGenerationResult.NON_SEQUENTIAL, repository.advanceOperationalGeneration(3))
            assertEquals(OperationalGenerationResult.ADVANCED, repository.advanceOperationalGeneration(2))
            assertEquals(ReplayFenceState.FENCE_REQUIRED, database.transportStateDao().get()!!.replayFenceState)
            assertNull(database.transportStateDao().get()!!.continuityOrigin)
            assertEquals(ReplayFenceResult.BLOCKED, repository.establishReplayFence(2, "fence-2", 2))
            assertEquals(ReplayFenceState.FENCE_REQUIRED, database.transportStateDao().get()!!.replayFenceState)
            assertEquals(ReplayFenceResult.ESTABLISHING, repository.beginReplayFence(2))
            assertEquals(ReplayFenceResult.ESTABLISHED, repository.establishReplayFence(2, "fence-2", 2))
            assertEquals(ReplayFenceResult.ALREADY_ESTABLISHED, repository.establishReplayFence(2, "fence-2", 2))
            assertEquals(ReplayFenceResult.BLOCKED, repository.establishReplayFence(2, "wrong", 3))
            assertEquals(ReplayFenceState.ESTABLISHED, database.transportStateDao().get()!!.replayFenceState)
            assertEquals(OperationalGenerationResult.STALE, repository.advanceOperationalGeneration(1))
        }
    }

    @Test
    fun operationalGenerationAdvanceIsCheckedAtLongBoundary() = runBlocking {
        withDatabase { database ->
            assertTrue(
                database.transportStateDao().insertIfAbsent(
                    CoreTransportStateEntity(
                        brokerUrl = "https://broker.example.test",
                        routeEpoch = 0,
                        operationalGeneration = Long.MAX_VALUE - 1,
                        operationalIncarnationId = OPERATIONAL_INCARNATION_ID,
                        replayFenceState = ReplayFenceState.FENCE_REQUIRED,
                        updatedAt = 1,
                    ),
                ) != -1L,
            )
            assertEquals(
                OperationalGenerationResult.ADVANCED,
                database.transportStateDao().advanceOperationalGeneration(Long.MAX_VALUE, 2),
            )
            assertEquals(Long.MAX_VALUE, database.transportStateDao().get()!!.operationalGeneration)
            assertEquals(
                OperationalGenerationResult.UNCHANGED,
                database.transportStateDao().advanceOperationalGeneration(Long.MAX_VALUE, 3),
            )
            assertEquals(2L, database.transportStateDao().get()!!.updatedAt)
        }
    }

    @Test
    fun groupIdentityHasDedicatedLifecycleAndRouteCallbacksRetainIt() = runBlocking {
        withDatabase { database ->
            var clock = 250L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://broker.example.test/base",
                    groupId = "trusted-group",
                    routeEpoch = 1,
                    updatedAt = 250,
                ),
            )

            clock = 251L
            assertEquals(
                RouteAdvanceResult.ADVANCED,
                repository.advanceRoute(
                    RouteUpdate(
                        brokerUrl = "https://broker.example.test/base/",
                        fcmRouteRef = "route-2",
                        routeEpoch = 2,
                        expectedBrokerEndpointRevision = 0,
                        selfEpochActivatedAt = null,
                    ),
                ),
            )
            assertEquals("trusted-group", database.transportStateDao().get()!!.groupId)

            clock = 252L
            assertEquals(GroupIdUpdateResult.UPDATED, repository.setGroupId("replacement-group"))
            assertEquals("replacement-group", database.transportStateDao().get()!!.groupId)
            assertEquals(252L, database.transportStateDao().get()!!.updatedAt)

            clock = 253L
            assertEquals(GroupIdUpdateResult.UNCHANGED, repository.setGroupId("replacement-group"))
            assertEquals(252L, database.transportStateDao().get()!!.updatedAt)

            clock = 254L
            assertEquals(GroupIdUpdateResult.UPDATED, repository.setGroupId(null))
            assertNull(database.transportStateDao().get()!!.groupId)
        }
    }

    @Test
    fun coreCommandAggregateAndActivityDrainAreIdempotent() = runBlocking {
        withDatabase { database ->
            var clock = 300L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            val signer = SoftwareIdentitySigner.generate()
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
            val entries = "[]"
            val cards = "{}"
            val overlays = "{}"
            val epochs = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
            fun snapshot(entriesValue: String) = TrustSnapshotInput.FourSection(
                entriesUtf8 = entriesValue.encodeToByteArray(),
                cardsUtf8 = cards.encodeToByteArray(),
                overlaysUtf8 = overlays.encodeToByteArray(),
                epochsUtf8 = epochs.encodeToByteArray(),
                signatureBase64UrlUtf8 = TrustStoreSigning.sign(
                    signer,
                    entriesValue,
                    cards,
                    overlays,
                    epochs,
                ).encodeToByteArray(),
            )
            assertEquals(TrustSnapshotWriteResult.APPLIED, repository.replaceTrustSnapshot(snapshot(entries)))
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://broker.example.test",
                    routeEpoch = 0,
                    updatedAt = 300,
                ),
            )
            val expectedDigest = database.trustSnapshotDao().get()!!.snapshotDigest
            val command = CoreTrustCommand(
                commandId = "command",
                authenticatedRequestId = "request",
                canonicalCommand = byteArrayOf(1, 2),
                commandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
                expectedOperationalGeneration = 1,
                expectedOperationalIncarnationId = OPERATIONAL_INCARNATION_ID,
                expectedSnapshotDigest = expectedDigest,
                candidateSnapshot = snapshot("[{\"updated\":1}]"),
                activity = CoreCommandActivity(
                    action = ActivityAction.APPLIED,
                    peerClientId = "peer",
                    renderArgs = ActivityRenderArgs.V1(revision = 1),
                    occurredAt = 1,
                ),
            )
            assertTrue(repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Applied)
            clock = 301L
            assertTrue(repository.applyCoreTrustCommand(command) is CoreCommandApplyResult.Duplicate)
            assertEquals(
                CoreCommandApplyResult.Conflict,
                repository.applyCoreTrustCommand(
                    CoreTrustCommand(
                        commandId = "command",
                        authenticatedRequestId = "request",
                        canonicalCommand = byteArrayOf(9),
                        commandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
                        expectedOperationalGeneration = 1,
                        expectedOperationalIncarnationId = OPERATIONAL_INCARNATION_ID,
                        expectedSnapshotDigest = expectedDigest,
                        candidateSnapshot = snapshot("[{\"updated\":1}]"),
                    ),
                ),
            )

            val eventId = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, "command")
            assertEquals("command", database.activityOutboxDao().find(eventId)?.commandId)
            assertTrue(repository.acknowledgeCopiedCoreActivity(eventId, 1))
            assertFalse(repository.acknowledgeCopiedCoreActivity(eventId, 1))
            assertNull(database.activityOutboxDao().find(eventId))
        }
    }

    @Test
    fun keystoreJournalUsesImmutableIntentIdentityAndVersionedCas() = runBlocking {
        withDatabase { database ->
            var clock = 310L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            val intent = KeystoreOperationIntent(
                operationId = "wrapping-key-create-7",
                targetType = KeystoreOperationTarget.WRAPPING_KEY,
                targetId = "notisync.core.wrapping.v1.g7",
                operationKind = KeystoreOperationKind.CREATE,
                createdAt = 300L,
            )

            assertEquals(KeystoreOperationEnsureResult.INSERTED, repository.ensureKeystoreOperation(intent))
            assertEquals(
                KeystoreOperationEnsureResult.EXISTING_PENDING,
                repository.ensureKeystoreOperation(intent.copy(createdAt = 999L)),
            )
            assertEquals(
                KeystoreOperationEnsureResult.CONFLICT,
                repository.ensureKeystoreOperation(intent.copy(targetId = "different-alias")),
            )

            clock = 311L
            assertEquals(
                KeystoreOperationTransitionResult.UPDATED,
                repository.transitionKeystoreOperation(
                    operationId = intent.operationId,
                    expectedState = KeystoreOperationState.PENDING,
                    expectedAttempts = 0,
                    targetState = KeystoreOperationState.RETRYABLE,
                    completedAt = null,
                    errorCode = "KEYSTORE_TRANSIENT",
                ),
            )
            assertEquals(
                KeystoreOperationTransitionResult.STALE,
                repository.transitionKeystoreOperation(
                    operationId = intent.operationId,
                    expectedState = KeystoreOperationState.PENDING,
                    expectedAttempts = 0,
                    targetState = KeystoreOperationState.BLOCKED,
                    completedAt = null,
                    errorCode = "STALE_RESULT",
                ),
            )

            clock = 312L
            assertEquals(
                KeystoreOperationTransitionResult.UPDATED,
                repository.transitionKeystoreOperation(
                    operationId = intent.operationId,
                    expectedState = KeystoreOperationState.RETRYABLE,
                    expectedAttempts = 1,
                    targetState = KeystoreOperationState.APPLIED,
                    completedAt = 312L,
                    errorCode = null,
                ),
            )
            val applied = requireNotNull(repository.getKeystoreOperation(intent.operationId))
            assertEquals(KeystoreOperationState.APPLIED, applied.state)
            assertEquals(2, applied.attempts)
            assertEquals(312L, applied.completedAt)
            assertEquals(
                KeystoreOperationEnsureResult.EXISTING_APPLIED,
                repository.ensureKeystoreOperation(intent),
            )
        }
    }

    @Test
    fun repositoryCopiesMutableBytesOnWriteAndProjection() = runBlocking {
        withDatabase { database ->
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 500L }
            repository.saveIdentityMetadata(freshIdentityInput())
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                repository.initializeFreshAuthority(
                    FreshIdentityTransportInitialization("https://broker.example.test"),
                    operationalStorageBinding(),
                    freshCryptoEpoch(),
                ),
            )
            val wrappedToken = byteArrayOf(1, 2, 3)
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(
                    BrokerAuthTokenInput(
                        wrappedToken = wrappedToken,
                        encodingVersion = 1,
                        issuedAt = 1,
                        expiresAt = 2,
                        expectedBrokerEndpointRevision = 0,
                    ),
                ),
            )

            wrappedToken[0] = 9
            assertArrayEquals(byteArrayOf(1, 2, 3), database.brokerAuthTokenDao().get()!!.wrappedToken)

            val firstProjection = repository.brokerAuthToken.first()!!
            firstProjection.wrappedToken[1] = 8
            assertArrayEquals(byteArrayOf(1, 2, 3), database.brokerAuthTokenDao().get()!!.wrappedToken)
            assertArrayEquals(byteArrayOf(1, 2, 3), repository.brokerAuthToken.first()!!.wrappedToken)
        }
    }

    @Test
    fun brokerEndpointTransitionIsAtomicAndRejectsStaleAbaTokens() = runBlocking {
        withDatabase { database ->
            var clock = 600L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://A.EXAMPLE.test:443/",
                    groupId = "trusted-group",
                    fcmRouteRef = "route-a",
                    routeEpoch = 7,
                    selfEpochActivatedAt = 44,
                    updatedAt = 600,
                ),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(tokenInput(byte = 1, expectedRevision = 0)),
            )

            clock = 700L
            assertEquals(
                BrokerEndpointChangeResult.UNCHANGED,
                repository.changeBrokerEndpoint("wss://a.example.test"),
            )
            assertEquals(600L, database.transportStateDao().get()!!.updatedAt)
            assertArrayEquals(byteArrayOf(1), database.brokerAuthTokenDao().get()!!.wrappedToken)

            assertEquals(
                BrokerEndpointChangeResult.CHANGED,
                repository.changeBrokerEndpoint("https://b.example.test/"),
            )
            val atB = requireNotNull(database.transportStateDao().get())
            assertEquals("https://b.example.test", atB.brokerUrl)
            assertEquals(1L, atB.brokerEndpointRevision)
            assertEquals("trusted-group", atB.groupId)
            assertEquals(7L, atB.routeEpoch)
            assertEquals(44L, atB.selfEpochActivatedAt)
            assertEquals(1L, atB.operationalGeneration)
            assertEquals(ReplayFenceState.CONTINUITY_INTACT, atB.replayFenceState)
            assertEquals(OperationalContinuityOrigin.VERIFIED_V51_CUTOVER, atB.continuityOrigin)
            assertNull(atB.fcmRouteRef)
            assertNull(database.brokerAuthTokenDao().get())

            assertEquals(
                BrokerAuthTokenSaveResult.STALE_ENDPOINT,
                repository.saveBrokerAuthToken(tokenInput(byte = 2, expectedRevision = 0)),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(tokenInput(byte = 3, expectedRevision = 1)),
            )

            clock = 800L
            assertEquals(
                BrokerEndpointChangeResult.CHANGED,
                repository.changeBrokerEndpoint("https://a.example.test"),
            )
            assertEquals(2L, database.transportStateDao().get()!!.brokerEndpointRevision)
            assertNull(database.brokerAuthTokenDao().get())
            assertEquals(
                BrokerAuthTokenSaveResult.STALE_ENDPOINT,
                repository.saveBrokerAuthToken(tokenInput(byte = 4, expectedRevision = 0)),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.STALE_ENDPOINT,
                repository.saveBrokerAuthToken(tokenInput(byte = 5, expectedRevision = 1)),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(tokenInput(byte = 6, expectedRevision = 2)),
            )
            assertArrayEquals(byteArrayOf(6), database.brokerAuthTokenDao().get()!!.wrappedToken)
        }
    }

    @Test
    fun brokerBasePathTransitionDistinguishesPathsButNotTrailingSlashes() = runBlocking {
        withDatabase { database ->
            var clock = 850L
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { clock }
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://BROKER.example.test/Api/%2Ftenant/",
                    groupId = "trusted-group",
                    fcmRouteRef = "route-a",
                    routeEpoch = 5,
                    updatedAt = 850,
                ),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(tokenInput(byte = 9, expectedRevision = 0)),
            )

            clock = 851L
            assertEquals(
                BrokerEndpointChangeResult.UNCHANGED,
                repository.changeBrokerEndpoint("wss://broker.example.test:443/Api/%2Ftenant///"),
            )
            assertEquals(850L, database.transportStateDao().get()!!.updatedAt)
            assertArrayEquals(byteArrayOf(9), database.brokerAuthTokenDao().get()!!.wrappedToken)

            clock = 852L
            assertEquals(
                BrokerEndpointChangeResult.CHANGED,
                repository.changeBrokerEndpoint("https://broker.example.test/Api/%2Fother"),
            )
            val changed = requireNotNull(database.transportStateDao().get())
            assertEquals("https://broker.example.test/Api/%2Fother", changed.brokerUrl)
            assertEquals(1L, changed.brokerEndpointRevision)
            assertEquals("trusted-group", changed.groupId)
            assertEquals(5L, changed.routeEpoch)
            assertNull(changed.fcmRouteRef)
            assertNull(database.brokerAuthTokenDao().get())
        }
    }

    @Test
    fun brokerEndpointFailureRollsBackTokenInvalidation() = runBlocking {
        withDatabase { database ->
            val repository = CoreFoundationRepository(CoreRoomStore.forDatabase(database)) { 900L }
            assertEquals(
                CoreTransportInitializationResult.INITIALIZED,
                database.initializeVerifiedTransport(
                    brokerUrl = "https://a.example.test",
                    fcmRouteRef = "route-a",
                    routeEpoch = 1,
                    updatedAt = 900,
                ),
            )
            assertEquals(
                BrokerAuthTokenSaveResult.SAVED,
                repository.saveBrokerAuthToken(tokenInput(byte = 7, expectedRevision = 0)),
            )
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "CREATE TEMP TRIGGER fail_broker_transition " +
                        "BEFORE UPDATE OF broker_url ON core_transport_state " +
                        "BEGIN SELECT RAISE(ABORT, 'forced test rollback'); END",
                )
            }

            var failed = false
            try {
                repository.changeBrokerEndpoint("https://b.example.test")
            } catch (_: Throwable) {
                failed = true
            }
            assertTrue("The forced transport update failure must escape", failed)
            assertEquals("https://a.example.test", database.transportStateDao().get()!!.brokerUrl)
            assertEquals(0L, database.transportStateDao().get()!!.brokerEndpointRevision)
            assertEquals("route-a", database.transportStateDao().get()!!.fcmRouteRef)
            assertArrayEquals(byteArrayOf(7), database.brokerAuthTokenDao().get()!!.wrappedToken)
        }
    }

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

    private suspend fun CoreDatabase.tableCountSnapshot(): Int {
        var count = 0
        if (maintenanceStateDao().get() != null) count++
        if (identityMetadataDao().get() != null) count++
        if (trustSnapshotDao().get() != null) count++
        if (cryptoEpochDao().find(1) != null) count++
        if (brokerAuthTokenDao().get() != null) count++
        if (transportStateDao().get() != null) count++
        if (keystoreOperationDao().find("operation") != null) count++
        if (commandAppliedDao().find("command") != null) count++
        if (
            activityOutboxDao().find(
                coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, "command"),
            ) != null
        ) count++
        return count
    }

    private suspend fun CoreDatabase.tableColumns(tableName: String): Set<String> =
        useReaderConnection { connection ->
            connection.usePrepared("PRAGMA table_info($tableName)") { statement ->
                buildSet { while (statement.step()) add(statement.getText(1)) }
            }
        }

    private suspend fun CoreDatabase.applicationTableNames(): Set<String> =
        useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT IN ('room_master_table', 'android_metadata') AND name NOT LIKE 'sqlite_%'",
            ) { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private fun tokenInput(byte: Byte, expectedRevision: Long): BrokerAuthTokenInput =
        BrokerAuthTokenInput(
            wrappedToken = byteArrayOf(byte),
            encodingVersion = 1,
            issuedAt = 1,
            expiresAt = 2,
            expectedBrokerEndpointRevision = expectedRevision,
        )

    private suspend fun CoreDatabase.initializeVerifiedTransport(
        brokerUrl: String,
        groupId: String? = null,
        fcmRouteRef: String? = null,
        routeEpoch: Long,
        selfEpochActivatedAt: Long? = null,
        updatedAt: Long,
    ): CoreTransportInitializationResult = transportStateDao().initialize(
        CoreTransportStateEntity(
            brokerUrl = canonicalizeBrokerEndpoint(brokerUrl),
            groupId = groupId,
            fcmRouteRef = fcmRouteRef,
            routeEpoch = routeEpoch,
            brokerEndpointRevision = 0,
            selfEpochActivatedAt = selfEpochActivatedAt,
            operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
            operationalIncarnationId = OPERATIONAL_INCARNATION_ID,
            replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
            continuityOrigin = OperationalContinuityOrigin.VERIFIED_V51_CUTOVER,
            replayFenceId = null,
            replayFenceEpoch = null,
            updatedAt = updatedAt,
        ),
    )

    private fun operationalStorageBinding(
        storageIncarnationId: String = OPERATIONAL_INCARNATION_ID,
    ): OperationalStorageBinding = OperationalStorageBinding(
        operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
        storageIncarnationId = storageIncarnationId,
    )

    private fun freshIdentityInput(): IdentityMetadataInput = IdentityMetadataInput(
        keyAlias = "fresh-identity",
        keyAliasVersion = 1,
        publicSpki = SoftwareIdentitySigner.generate().publicKeySpki,
        securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
        lifecycleState = IdentityLifecycleState.ACTIVE,
        createdAt = 1L,
    )

    private fun freshCryptoEpoch(): CryptoEpochInput = CryptoEpochInput(
        epoch = 1,
        operationalSignerAlias = "fresh-operational",
        operationalSignerPublicSpki = byteArrayOf(1, 2, 3),
        hpkePublicKeyset = byteArrayOf(4, 5, 6),
        hpkePrivateKeysetWrapped = byteArrayOf(7, 8, 9),
        securityLevel = CryptoEpochSecurityLevel.TRUSTED_ENVIRONMENT,
        lifecycleState = CryptoEpochState.ACTIVE,
        antiRollbackFloor = 1,
        activationAt = 1L,
        createdAt = 1L,
    )

    private companion object {
        const val OPERATIONAL_INCARNATION_ID = "test-operational-incarnation-1"
    }
}
