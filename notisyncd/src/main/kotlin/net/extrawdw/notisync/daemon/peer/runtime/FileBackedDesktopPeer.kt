package net.extrawdw.notisync.daemon.peer.runtime

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.extrawdw.notisync.daemon.GenericMeshControl
import net.extrawdw.notisync.daemon.LocalSessionRegistry
import net.extrawdw.notisync.daemon.NotificationMeshSender
import net.extrawdw.notisync.daemon.NotificationOutbox
import net.extrawdw.notisync.daemon.PeerAdministration
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.peer.storage.FileAuthTokenRepository
import net.extrawdw.notisync.daemon.peer.storage.FileKeyMaterialProvider
import net.extrawdw.notisync.daemon.peer.storage.FileTrustPersistence
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.SecureFileSystem
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore

/**
 * File-backed production components for one desktop peer.
 *
 * The database instance is returned because it must be shared: it is the channel's durable message
 * deduplication repository, the notification dispatcher's durable outbox, and the local-session store.
 * [DaemonDatabaseRepository]'s transaction lock is per instance, so constructing a second repository for
 * the same file could lose a concurrent read-modify-write update.
 */
data class FileBackedDesktopPeer(
    val runtime: DesktopPeerRuntime,
    val database: DaemonDatabaseRepository,
    /** The exact process/session registry wired into [runtime]; share it with the UDS service/dispatcher. */
    val sessions: LocalSessionRegistry,
) {
    val administration: PeerAdministration get() = runtime
    val meshControl: GenericMeshControl get() = runtime
    val notificationSender: NotificationMeshSender get() = runtime.notificationMeshSender
    val notificationOutbox: NotificationOutbox get() = database
}

/** Build the initial unencrypted-private-key provider and every durable peer repository. */
fun createFileBackedDesktopPeer(
    layout: DaemonStorageLayout,
    configStore: NotisyncdConfigStore,
    sessionFactory: (DaemonDatabaseRepository) -> LocalSessionRegistry,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    clock: Clock = Clock.systemUTC(),
    fileSystem: SecureFileSystem = SecureFileSystem(),
): FileBackedDesktopPeer {
    layout.prepare(fileSystem)
    val keys = FileKeyMaterialProvider(layout, fileSystem)
    val trust = FileTrustPersistence(layout, fileSystem)
    val auth = FileAuthTokenRepository(layout, fileSystem)
    val database = DaemonDatabaseRepository(layout, clock = clock, fileSystem = fileSystem)
    val sessions = sessionFactory(database)
    val runtime = DesktopPeerRuntime(
        configProvider = configStore::load,
        keyMaterial = keys,
        trustPersistence = trust,
        authTokens = auth,
        deduplication = database,
        sessions = sessions,
        parentScope = parentScope,
        clock = clock,
    )
    return FileBackedDesktopPeer(runtime, database, sessions)
}
