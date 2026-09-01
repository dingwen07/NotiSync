package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Files
import net.extrawdw.notisync.desktop.DesktopProcessExecutableResolver
import net.extrawdw.notisync.desktop.DesktopProcessNameResolver
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCallerResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = LocalCallerResolver()

    @Test
    fun `current process context carries and refreshes the platform instance token`() {
        val original = resolver.resolve(
            ProcessHandle.current().pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        )

        assertNull(original.processContext.validationError())
        assertNotNull(original.processContext.leaf)
        val refreshed = resolver.refresh(original)
        assertEquals(original.processContext.bootId, refreshed.bootId)
        assertEquals(original.processContext.leaf, refreshed.leaf)
    }

    @Test
    fun `refresh rejects a changed local process-instance token`() {
        val original = resolver.resolve(
            ProcessHandle.current().pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        )
        val otherInstance = requireNotNull(original.leafInstance).copy(startToken = "reused-process")

        assertEquals(
            DesktopProcessContextSource.UNAVAILABLE,
            resolver.refresh(original.copy(leafInstance = otherInstance)).source,
        )
    }

    @Test
    fun `missing ancestor command keeps pid and does not stop parent traversal`() {
        val leaf = ProcessHandle.current()
        val parent = leaf.parent().orElse(null)
        val grandparent = parent?.parent()?.orElse(null)
        assumeNotNull(parent, grandparent)
        val hiddenPid = requireNotNull(parent).pid()
        val processExecutables = DesktopProcessExecutableResolver(
            osName = "Test OS",
            portableCommand = { pid ->
                if (pid == hiddenPid) null else ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
            },
        )
        val resolver = LocalCallerResolver(processExecutables = processExecutables)

        val resolved = resolver.resolve(
            leaf.pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        ).processContext

        assertEquals(
            listOf(leaf.pid(), hiddenPid, requireNotNull(grandparent).pid()),
            resolved.processLineage.take(3).map { it.pid },
        )
        assertNull(resolved.processLineage[1].executablePath)
    }

    @Test
    fun `Linux process name remains available when executable path is missing`() {
        val pid = ProcessHandle.current().pid()
        val procRoot = temporaryFolder.newFolder("proc").toPath()
        val processDirectory = Files.createDirectory(procRoot.resolve(pid.toString()))
        Files.writeString(processDirectory.resolve("comm"), "restricted-service\n")
        val resolver = LocalCallerResolver(
            processExecutables = DesktopProcessExecutableResolver(
                osName = "Linux",
                portableCommand = { null },
            ),
            processNames = DesktopProcessNameResolver(osName = "Linux", procRoot = procRoot),
        )

        val leaf = resolver.resolve(
            pid,
            DesktopProcessContextSource.CURRENT_PROCESS,
        ).processContext.leaf

        assertNotNull(leaf)
        assertNull(requireNotNull(leaf).executablePath)
        assertEquals("restricted-service", leaf.displayName)
    }

    @Test
    fun `macOS restricted login metadata does not hide its parent`() {
        assumeTrue(System.getProperty("os.name").contains("mac", ignoreCase = true))
        val processExecutables = DesktopProcessExecutableResolver()
        val login = ProcessHandle.allProcesses()
            .filter { it.info().command().isEmpty }
            .filter { process -> processExecutables.resolve(process.pid())?.endsWith("/login") == true }
            .findFirst()
            .orElse(null)
        val parent = login?.parent()?.orElse(null)
        assumeNotNull(login, parent)

        val resolved = LocalCallerResolver(processExecutables = processExecutables).resolve(
            requireNotNull(login).pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        ).processContext

        assertEquals(
            listOf(login.pid(), requireNotNull(parent).pid()),
            resolved.processLineage.take(2).map { it.pid },
        )
        assertEquals("/usr/bin/login", resolved.processLineage[0].executablePath)
        assertNotNull(resolved.processLineage[1].executablePath)
    }
}
