package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.apps.notisync.data.storage.operational.DesktopApplicationDao
import net.extrawdw.apps.notisync.data.storage.operational.DesktopApplicationEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory

internal class DesktopApplicationSnapshot(entries: List<DesktopApplicationEntity>) {
    private val overrides = entries.associateBy { it.id }
    val registry = BUILT_IN_DESKTOP_APPLICATIONS.withUserApplications(entries.map { it.application() })

    fun isUserEntry(id: String): Boolean = id in overrides
    fun icon(id: String?): ByteArray? = overrides[id]?.iconData
}

/** Preloaded on AppGraph's I/O init thread, before SSH can process any request. */
internal class DesktopApplicationRepository(private val dao: DesktopApplicationDao) {
    constructor(context: Context) : this(OperationalDatabaseFactory.get(context).desktopApplications())

    private val mutex = Mutex()
    private val state = MutableStateFlow(runBlocking { DesktopApplicationSnapshot(dao.entries()) })
    val snapshot = state.asStateFlow()

    suspend fun save(application: KnownDesktopApplication, iconData: ByteArray?, previousId: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Renaming must never silently replace another application (including a built-in).
                if (application.id != previousId && state.value.registry.applications.any { it.id == application.id }) {
                    throw DuplicateDesktopApplicationIdException()
                }
                require(iconData == null || iconData.size <= MAX_DESKTOP_APPLICATION_ICON_BYTES)
                val entry = DesktopApplicationEntity(
                    id = application.id,
                    displayName = application.displayName,
                    priority = application.priority,
                    traversal = application.traversal.name,
                    acceptedNamesJson = Json.encodeToString(application.acceptedNames.toList()),
                    acceptedPathsJson = Json.encodeToString(application.acceptedPaths.toList()),
                    iconData = iconData?.copyOf(),
                )
                // Finish publishing a committed write even if its editor leaves composition.
                withContext(NonCancellable) {
                    dao.save(entry, previousId)
                    state.value = DesktopApplicationSnapshot(dao.entries())
                }
            }
        }

    /** Deleting an override exposes its built-in again; deleting a custom entry removes it. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            withContext(NonCancellable) {
                dao.delete(id)
                state.value = DesktopApplicationSnapshot(dao.entries())
            }
        }
    }
}

internal class DuplicateDesktopApplicationIdException : IllegalArgumentException()
internal const val MAX_DESKTOP_APPLICATION_ICON_BYTES = 4 * 1024 * 1024

private fun DesktopApplicationEntity.application() = KnownDesktopApplication(
    id = id,
    displayName = displayName,
    priority = priority,
    traversal = DesktopApplicationLineageTraversal.valueOf(traversal),
    acceptedNames = Json.decodeFromString<List<String>>(acceptedNamesJson).toSet(),
    acceptedPaths = Json.decodeFromString<List<String>>(acceptedPathsJson).toSet(),
)
