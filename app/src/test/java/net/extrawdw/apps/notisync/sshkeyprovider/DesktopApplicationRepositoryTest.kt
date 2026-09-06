package net.extrawdw.apps.notisync.sshkeyprovider

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.DesktopApplicationDao
import net.extrawdw.apps.notisync.data.storage.operational.DesktopApplicationEntity
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.junit.Assert.*
import org.junit.Test

class DesktopApplicationRepositoryTest {
    @Test
    fun savedOverridesAndIconsSurviveReloadAndAffectRecognition() = runBlocking {
        val dao = MemoryDao()
        val repository = DesktopApplicationRepository(dao)
        val icon = byteArrayOf(1, 2, 3)
        repository.save(application("git"), icon, "git")
        icon[0] = 9

        val snapshot = DesktopApplicationRepository(dao).snapshot.value
        val custom = snapshot.registry.find(process("/opt/tools/git.exe"))!!
        assertEquals("自定义 Git", custom.displayName)
        assertEquals(Int.MIN_VALUE, custom.priority)
        assertEquals(DesktopApplicationLineageTraversal.SKIP, custom.traversal)
        assertEquals(setOf("git", "Git.app"), custom.acceptedNames)
        assertEquals(setOf("/opt/tools/", "/usr/bin/git"), custom.acceptedPaths)
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.icon("git"))
        assertNull(snapshot.registry.find(process("/usr/bin/git/other")))
        assertNull(DesktopApplicationAnchorSelector.select(listOf(process("/opt/tools/git.exe")), snapshot.registry).recommended)
        assertTrue(snapshot.isUserEntry("git"))
        assertEquals(1, dao.entries().size)
    }

    @Test
    fun renameMovesCustomIconAndRestoresBuiltinThenDeleteRemovesCustomEntry() = runBlocking {
        val dao = MemoryDao()
        val repository = DesktopApplicationRepository(dao)
        repository.save(application("git"), byteArrayOf(7), "git")
        repository.save(application("my-git"), repository.snapshot.value.icon("git"), "git")
        val renamed = repository.snapshot.value
        assertFalse(renamed.isUserEntry("git"))
        assertEquals("Git", renamed.registry.applications.single { it.id == "git" }.displayName)
        assertNull(renamed.icon("git"))
        assertArrayEquals(byteArrayOf(7), renamed.icon("my-git"))
        assertEquals(listOf("my-git"), dao.entries().map { it.id })

        repository.delete("my-git")
        assertTrue(dao.entries().isEmpty())
        assertFalse(repository.snapshot.value.registry.applications.any { it.id == "my-git" })
    }

    @Test
    fun removingIconAndResettingBuiltinPersistIndependently() = runBlocking {
        val dao = MemoryDao()
        val repository = DesktopApplicationRepository(dao)
        repository.save(application("git"), byteArrayOf(7), "git")
        repository.save(application("git"), null, "git")
        assertNull(DesktopApplicationRepository(dao).snapshot.value.icon("git"))
        assertTrue(repository.snapshot.value.isUserEntry("git"))
        repository.delete("git")
        val reset = DesktopApplicationRepository(dao).snapshot.value
        assertFalse(reset.isUserEntry("git"))
        assertEquals("Git", reset.registry.applications.single { it.id == "git" }.displayName)
    }

    @Test
    fun duplicateAndFailedWritesLeaveSnapshotUntouched() = runBlocking {
        val dao = MemoryDao()
        val repository = DesktopApplicationRepository(dao)
        val before = repository.snapshot.value
        try {
            repository.save(application("git"), null, null)
            fail("Duplicate built-in ID should be rejected")
        } catch (_: DuplicateDesktopApplicationIdException) { }
        assertSame(before, repository.snapshot.value)
        assertTrue(dao.entries().isEmpty())

        dao.failWrite = true
        try {
            repository.save(application("custom"), null, null)
            fail("Write should fail")
        } catch (_: IOException) { }
        assertSame(before, repository.snapshot.value)
        assertTrue(dao.entries().isEmpty())
    }

    @Test
    fun editorCancellationCannotLeaveCommittedOverrideOutOfTheSnapshot() = runBlocking {
        val dao = MemoryDao()
        val repository = DesktopApplicationRepository(dao)
        val committed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        dao.afterWrite = { committed.complete(Unit); resume.await() }
        val saving = async { repository.save(application("git"), null, "git") }
        committed.await()
        saving.cancel()
        resume.complete(Unit)
        saving.cancelAndJoin()
        assertEquals("自定义 Git", repository.snapshot.value.registry.applications.single { it.id == "git" }.displayName)
    }

    private fun application(id: String) = KnownDesktopApplication(
        id, "自定义 Git", Int.MIN_VALUE, DesktopApplicationLineageTraversal.SKIP,
        setOf("git", "Git.app"), setOf("/opt/tools/", "/usr/bin/git"),
    )
    private fun process(path: String) = DesktopProcessIdentity(pid = 1, executablePath = path)

    private class MemoryDao : DesktopApplicationDao {
        private val rows = linkedMapOf<String, DesktopApplicationEntity>()
        var failWrite = false
        var afterWrite: suspend () -> Unit = {}
        override suspend fun entries() = rows.values.toList()
        override suspend fun upsert(entry: DesktopApplicationEntity) { rows[entry.id] = entry }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun save(entry: DesktopApplicationEntity, previousId: String?) {
            if (failWrite) throw IOException("test write failure")
            super.save(entry, previousId)
            afterWrite()
        }
    }
}
