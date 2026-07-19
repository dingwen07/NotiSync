package net.extrawdw.notisync.daemon.peer.runtime

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.extrawdw.notisync.daemon.ActionOriginPolicy
import net.extrawdw.notisync.daemon.ApplicationReceiveRouter
import net.extrawdw.notisync.daemon.GenericBatchSender
import net.extrawdw.notisync.daemon.PeerAdministration
import net.extrawdw.notisync.daemon.ProcessIdentityResolver
import net.extrawdw.notisync.daemon.RegisteredApplicationLookup
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.peer.storage.FileAuthTokenRepository
import net.extrawdw.notisync.daemon.peer.storage.FileKeyMaterialProvider
import net.extrawdw.notisync.daemon.peer.storage.FileTrustPersistence
import net.extrawdw.notisync.daemon.peer.storage.PersistentApplicationBridgeStore
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore

/** One file-backed generic desktop peer graph. */
data class FileBackedDesktopPeer(
    val runtime: DesktopPeerRuntime,
    val database: DaemonDatabaseRepository,
    val applications: PersistentApplicationBridgeStore,
    val receiver: ApplicationReceiveRouter,
) {
    val administration: PeerAdministration get() = runtime
    val sender: GenericBatchSender get() = runtime
    val actionOrigins: ActionOriginPolicy get() = runtime
}

fun createFileBackedDesktopPeer(
    layout: DaemonStorageLayout,
    configStore: NotisyncdConfigStore,
    identityResolver: ProcessIdentityResolver,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    clock: Clock = Clock.systemUTC(),
    fileSystem: SecureFileSystem = SecureFileSystem(),
    logger: DaemonLogger = DaemonLogger(configStore.load().logLevel),
): FileBackedDesktopPeer {
    layout.prepare(fileSystem)
    val keys = FileKeyMaterialProvider(layout, fileSystem)
    val trust = FileTrustPersistence(layout, fileSystem)
    val auth = FileAuthTokenRepository(layout, fileSystem)
    val database = DaemonDatabaseRepository(layout, clock = clock, fileSystem = fileSystem)
    val applications = PersistentApplicationBridgeStore(database, clock)
    val receiver = ApplicationReceiveRouter(
        applications = RegisteredApplicationLookup { applications.find(it) != null },
        identityResolver = identityResolver,
        clock = clock,
    )
    val runtime = DesktopPeerRuntime(
        configProvider = configStore::load,
        keyMaterial = keys,
        trustPersistence = trust,
        authTokens = auth,
        deduplication = database,
        receiveRouter = receiver,
        capabilitiesProvider = applications::effectiveCapabilities,
        profileState = applications,
        parentScope = parentScope,
        clock = clock,
        logger = logger,
    )
    return FileBackedDesktopPeer(runtime, database, applications, receiver)
}
