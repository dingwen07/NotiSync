package net.extrawdw.apps.notisync.composition.storage

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.composition.bootstrap.AndroidFreshIdentityCryptoPort
import net.extrawdw.apps.notisync.composition.bootstrap.ProductionCoreV51ActivationGate
import net.extrawdw.apps.notisync.composition.bootstrap.ProductionFreshLegacyInventorySource
import net.extrawdw.apps.notisync.composition.bootstrap.ProductionStorageBootstrapCoordinator
import net.extrawdw.apps.notisync.composition.bootstrap.ProductionV51LegacySourceInventorySource
import net.extrawdw.apps.notisync.composition.bootstrap.RepositoryFreshIdentityPersistencePort
import net.extrawdw.apps.notisync.composition.bootstrap.RoomCoreBootstrapTargetSnapshotSource
import net.extrawdw.apps.notisync.composition.bootstrap.RoomOperationalContinuityMarkerSource
import net.extrawdw.apps.notisync.composition.bootstrap.RoomOperationalRebuildIdentitySource
import net.extrawdw.apps.notisync.composition.runtime.CoreRoomRuntime
import net.extrawdw.apps.notisync.data.activity.ActivityRepository
import net.extrawdw.apps.notisync.data.activity.RoomActivityRepository
import net.extrawdw.apps.notisync.data.relay.RelayRepository
import net.extrawdw.apps.notisync.data.relay.RoomRelayRepository
import net.extrawdw.apps.notisync.data.run.RoomRunRepository
import net.extrawdw.apps.notisync.data.run.RunRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreRoomStore
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.ImportClock
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacyMessageLedgerSourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacyRunsStagingSourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacySealEnrollmentMapper
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.LegacySealHistorySourceAdapter
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.OperationalCutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.core.CoreV51CutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.AndroidLegacyKeystoreSnapshotPort
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreFileReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCoreKeystoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.core.LegacyCorePreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesAttemptReader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesDataStoreReader
import net.extrawdw.apps.notisync.data.storage.importer.target.RoomOperationalImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.SealImportPayloadMaterializer
import net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping.CoreV51MappingDefaults
import net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping.LegacyCoreV51Mapper
import net.extrawdw.apps.notisync.data.storage.importer.target.core.mapping.LegacyCoreV51PlanSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.room.RoomCoreV51ImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyDeviceProfileImportDefaults
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyOperationalPreferencesMapper
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.runtime.CoreOperationalContinuityValidator
import net.extrawdw.apps.notisync.data.storage.runtime.CoreOperationalGenerationSource
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyEnsurer
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate

/**
 * Application-scoped owner of both Room databases and the one migrator. Construction is side-effect free;
 * [initialize] is called only from the user-open application initializer on its I/O scope.
 */
internal class StorageContainer private constructor(
    val coreRepository: CoreFoundationRepository,
    val activityRepository: ActivityRepository,
    val relayRepository: RelayRepository,
    val runRepository: RunRepository,
    val operationalDatabase: OperationalDatabase,
    val protectedPayloadProtector: OperationalProtectedPayloadProtector,
    val maintenanceGate: OperationalStorageMaintenanceGate,
    val payloadKeyEnsurer: OperationalPayloadKeyEnsurer,
    private val bootstrap: ProductionStorageBootstrapCoordinator,
    private val onClose: suspend () -> Unit,
) {
    private val closeStarted = AtomicBoolean(false)

    suspend fun initialize(): CoreTransportSnapshot = bootstrap.initialize()

    suspend fun loadExistingAuthorityOrNull(): CoreTransportSnapshot? = bootstrap.loadExistingAuthorityOrNull()

    suspend fun loadCoreRuntime(): CoreRoomRuntime = CoreRoomRuntime.loadExisting(coreRepository)

    suspend fun close() {
        if (closeStarted.compareAndSet(false, true)) onClose()
    }

    companion object {
        internal fun create(
            coreRepository: CoreFoundationRepository,
            activityRepository: ActivityRepository,
            relayRepository: RelayRepository,
            runRepository: RunRepository,
            operationalDatabase: OperationalDatabase,
            protectedPayloadProtector: OperationalProtectedPayloadProtector,
            maintenanceGate: OperationalStorageMaintenanceGate,
            payloadKeyEnsurer: OperationalPayloadKeyEnsurer,
            bootstrap: ProductionStorageBootstrapCoordinator,
            onClose: suspend () -> Unit,
        ) = StorageContainer(
            coreRepository,
            activityRepository,
            relayRepository,
            runRepository,
            operationalDatabase,
            protectedPayloadProtector,
            maintenanceGate,
            payloadKeyEnsurer,
            bootstrap,
            onClose,
        )
    }
}

internal object StorageContainerFactory {
    private val registry = ProcessSingletonRegistry<StorageContainer>()

    fun get(context: Context, dependencies: StorageContainerDependencies): StorageContainer =
        registry.getOrCreate { assemble(context.applicationContext, dependencies) }

    suspend fun resetForTests(ioDispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        registry.peek()?.close() ?: withContext(ioDispatcher) {
            CoreDatabaseFactory.closeForTests()
            OperationalDatabaseFactory.closeForTests()
        }
    }

    private fun assemble(context: Context, dependencies: StorageContainerDependencies): StorageContainer {
        val coreDatabase = CoreDatabaseFactory.get(context)
        val operationalDatabase = OperationalDatabaseFactory.get(context)
        val coreRepository = CoreFoundationRepository(
            store = CoreRoomStore.processSingleton(context),
            now = dependencies.clock::nowMillis,
        )
        val activityRepository: ActivityRepository = RoomActivityRepository(operationalDatabase.activityDao())
        val relayRepository: RelayRepository = RoomRelayRepository(operationalDatabase.relayDao())
        val runRepository: RunRepository = RoomRunRepository(
            dao = operationalDatabase.runDao(),
            nowMillis = dependencies.clock::nowMillis,
        )
        val maintenanceGate = OperationalStorageMaintenanceGate()

        val protector = OperationalProtectedPayloadProtector(dependencies.payloadVault)
        val sealMaterializer = SealImportPayloadMaterializer(
            protector = protector,
            payloadKeyVault = dependencies.payloadVault,
            maintenanceGate = maintenanceGate,
            ioDispatcher = dependencies.ioDispatcher,
        )
        val operationalTarget = RoomOperationalImportTarget(
            database = operationalDatabase,
            maintenanceGate = maintenanceGate,
            sealPayloadVerifier = sealMaterializer,
        )
        val sources = dependencies.legacyV51Sources
        val preferencesReader = LegacyOperationalPreferencesDataStoreReader()
        val operationalCutover = OperationalCutoverCoordinator(
            adapters = listOf(
                LegacyMessageLedgerSourceAdapter(
                    sourceFile = sources.messageLedgerFile,
                    noBackupDirectory = sources.noBackupStagingDirectory,
                    ioDispatcher = dependencies.ioDispatcher,
                ),
                LegacyRunsStagingSourceAdapter(
                    sourceFile = sources.runsFile,
                    noBackupDirectory = sources.noBackupStagingDirectory,
                    ioDispatcher = dependencies.ioDispatcher,
                ),
                LegacySealHistorySourceAdapter(
                    sourceFile = sources.sealHistoryFile,
                    payloadMaterializer = sealMaterializer,
                    ioDispatcher = dependencies.ioDispatcher,
                ),
            ),
            preferencesReader = LegacyOperationalPreferencesAttemptReader(
                dataStore = sources.preferencesDataStore,
                operationalReader = preferencesReader,
            ),
            preferencesMapper = LegacyOperationalPreferencesMapper(
                LegacyDeviceProfileImportDefaults(
                    dependencies.preferencesCutoverDefaults.legacyDeviceName,
                ),
            ),
            sealEnrollmentMapper = LegacySealEnrollmentMapper(sealMaterializer),
            target = operationalTarget,
            clock = ImportClock(dependencies.clock::nowMillis),
        )

        val corePreferencesReader = LegacyCorePreferencesDataStoreReader()
        val coreKeystoreReader = LegacyCoreKeystoreReader(
            AndroidLegacyKeystoreSnapshotPort(dependencies.ioDispatcher),
        )
        val coreFileReader = LegacyCoreFileReader(
            filesDirectory = sources.coreFilesDirectory,
            ioDispatcher = dependencies.ioDispatcher,
        )
        val coreSource = LegacyCoreV51PlanSource(
            preferencesDataStore = sources.preferencesDataStore,
            preferencesReader = corePreferencesReader,
            keystoreReader = coreKeystoreReader,
            fileReader = coreFileReader,
            mapper = LegacyCoreV51Mapper(CoreV51MappingDefaults(dependencies.bootstrap.defaultBrokerUrl)),
        )
        val coreTarget = RoomCoreV51ImportTarget(
            database = coreDatabase,
            repository = coreRepository,
            now = dependencies.clock::nowMillis,
        )
        val inventorySource = ProductionV51LegacySourceInventorySource(
            coreSource = coreSource,
            allAbsentSource = ProductionFreshLegacyInventorySource(
                sources = sources,
                preferencesReader = preferencesReader,
                corePreferencesReader = corePreferencesReader,
                coreKeystoreReader = coreKeystoreReader,
                coreFileReader = coreFileReader,
                ioDispatcher = dependencies.ioDispatcher,
            ),
        )
        val targetSource = RoomCoreBootstrapTargetSnapshotSource(coreDatabase, dependencies.clock)
        val continuityValidator = CoreOperationalContinuityValidator(
            transportSource = { targetSource.read().transport },
            markerSource = RoomOperationalContinuityMarkerSource(operationalDatabase),
        )
        val bootstrap = ProductionStorageBootstrapCoordinator(
            targetSource = targetSource,
            inventorySource = inventorySource,
            coreCutover = CoreV51CutoverCoordinator(
                source = coreSource,
                target = coreTarget,
                activationGate = ProductionCoreV51ActivationGate(
                    dependencies.ioDispatcher,
                    dependencies.clock,
                ),
            ),
            coreTarget = coreTarget,
            operationalCutover = operationalCutover,
            operationalIdentitySource = RoomOperationalRebuildIdentitySource(
                operationalDatabase,
                dependencies.clock,
            ),
            freshPersistence = RepositoryFreshIdentityPersistencePort(coreRepository),
            freshCrypto = AndroidFreshIdentityCryptoPort(dependencies.ioDispatcher),
            continuityValidator = continuityValidator,
            clock = dependencies.clock,
            defaultBrokerUrl = dependencies.bootstrap.defaultBrokerUrl,
        )
        val payloadKeyEnsurer = OperationalPayloadKeyEnsurer(
            generationSource = CoreOperationalGenerationSource(coreRepository),
            vault = dependencies.payloadVault,
            maintenanceGate = maintenanceGate,
        )

        lateinit var container: StorageContainer
        container = StorageContainer.create(
            coreRepository = coreRepository,
            activityRepository = activityRepository,
            relayRepository = relayRepository,
            runRepository = runRepository,
            operationalDatabase = operationalDatabase,
            protectedPayloadProtector = protector,
            maintenanceGate = maintenanceGate,
            payloadKeyEnsurer = payloadKeyEnsurer,
            bootstrap = bootstrap,
            onClose = {
                try {
                    withContext(dependencies.ioDispatcher) {
                        CoreDatabaseFactory.closeForTests()
                        OperationalDatabaseFactory.closeForTests()
                    }
                } finally {
                    registry.clearIfSame(container)
                }
            },
        )
        return container
    }
}

internal class ProcessSingletonRegistry<T : Any> {
    private val lock = Any()
    private var value: T? = null

    fun getOrCreate(factory: () -> T): T = synchronized(lock) {
        value ?: factory().also { value = it }
    }

    fun peek(): T? = synchronized(lock) { value }

    fun clearIfSame(expected: T): Boolean = synchronized(lock) {
        if (value !== expected) return@synchronized false
        value = null
        true
    }
}
