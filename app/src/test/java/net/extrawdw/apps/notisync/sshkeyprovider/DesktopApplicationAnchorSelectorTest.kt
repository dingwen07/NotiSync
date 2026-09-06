package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopApplicationAnchorSelectorTest {
    @Test
    fun directSshFallsBackToInteractiveShell() {
        assertSelected(
            "Bash",
            listOf(process("/usr/bin/ssh"), process("/usr/bin/bash"), process("/sbin/init")),
        )
    }

    @Test
    fun gitOutranksItsInteractiveShell() {
        assertSelected(
            "Git",
            listOf(
                process("/usr/bin/ssh"),
                process("/usr/bin/git"),
                process("/usr/bin/bash"),
                process("/sbin/init"),
            ),
        )
    }

    @Test
    fun windowsTerminalOutranksPowerShellAndStopsBeforeExplorer() {
        assertSelected(
            "Windows Terminal",
            listOf(
                process("C:\\Windows\\System32\\OpenSSH\\ssh.exe"),
                process("C:\\Program Files\\PowerShell\\7\\pwsh.exe"),
                process("C:\\Program Files\\WindowsApps\\WindowsTerminal.exe"),
                process("C:\\Windows\\explorer.exe"),
            ),
        )
    }

    @Test
    fun pipeRelayStopsBeforeTheUnrelatedWindowsHost() {
        val selection = DesktopApplicationAnchorSelector.select(
            listOf(
                process("C:\\Tools\\npiperelay.exe"),
                process("C:\\Windows\\System32\\wslhost.exe"),
            ),
        )

        assertNull(selection.recommended)
        assertTrue(selection.candidates.isEmpty())
    }

    @Test
    fun codexOutranksGitShellAndSessionInfrastructure() {
        assertSelected(
            "Codex",
            listOf(
                process("/usr/bin/ssh"),
                process("/usr/bin/git"),
                process("/opt/codex/bin/codex"),
                process("/usr/bin/zsh"),
                process("/opt/notisync/Relay"),
                process("/opt/notisync/SessionLeader"),
                process("/usr/lib/systemd/init-systemd"),
                process("/usr/lib/systemd/systemd"),
            ),
        )
    }

    @Test
    fun missingExecutablePathIsSkippedWithoutTruncatingLineage() {
        val lineage = listOf(
            process("/usr/bin/ssh"),
            process("/bin/zsh"),
            DesktopProcessIdentity(pid = 3, displayName = "login"),
            process("/Applications/Utilities/Terminal.app/Contents/MacOS/Terminal"),
            process("/sbin/launchd"),
        )

        assertEquals("Apple Terminal", requireNotNull(DesktopApplicationAnchorSelector.select(lineage).recommended).displayName)
    }

    @Test
    fun boundaryPreventsSelectingAnUnrelatedAncestor() {
        val lineage = listOf(
            process("/usr/bin/ssh"),
            process("/usr/bin/bash"),
            process("/usr/lib/systemd/systemd"),
            process("/opt/unrelated/desktop-app"),
        )

        assertEquals("Bash", requireNotNull(DesktopApplicationAnchorSelector.select(lineage).recommended).displayName)
    }

    @Test
    fun noFullPathMeansNoAuthorizationAnchor() {
        val context = DesktopProcessContext(
            DesktopProcessContextSource.CURRENT_PROCESS,
            listOf(DesktopProcessIdentity(pid = 1, displayName = "ssh")),
        )

        assertNull(DesktopApplicationAnchorSelector.select(context).recommended)
    }

    @Test
    fun windowsApplicationIdentityMatchesPathCaseAndSeparatorVariants() {
        val first = DesktopApplicationIdentity("C:\\Program Files\\Codex\\codex.exe")
        val second = DesktopApplicationIdentity("c:/program files/codex/CODEX.EXE")

        assertTrue(first.matches(second))
    }

    @Test
    fun selectionRetainsLowerRankedCandidatesForGrantMatching() {
        val selection = DesktopApplicationAnchorSelector.select(
            listOf(
                process("/usr/bin/ssh"),
                process("/usr/bin/git"),
                process("/opt/codex/bin/codex"),
                process("/usr/bin/zsh"),
                process("/sbin/init"),
            ),
        )

        assertEquals("Codex", requireNotNull(selection.recommended).displayName)
        assertTrue(selection.contains(DesktopApplicationIdentity("/usr/bin/git")))
    }

    @Test
    fun aiAgentOutranksUserApplicationAndCarriesStableRegistryPresentation() {
        val selected = requireNotNull(
            DesktopApplicationAnchorSelector.select(
                listOf(
                    process("/usr/bin/ssh"),
                    process("/opt/opencode/bin/opencode"),
                    process("/opt/vscode/bin/code"),
                    process("/usr/bin/zsh"),
                ),
            ).recommended,
        )

        assertEquals("opencode", selected.applicationId)
        assertEquals("OpenCode", selected.displayName)
        assertEquals(700, selected.priority)
    }

    @Test
    fun userRegistryExtendsAndReplacesBuiltInsWithNumericPriority() {
        val custom = KnownDesktopApplication("custom-tool", "My Tool", 725, acceptedNames = setOf("tool"))
        val codex = KnownDesktopApplication("codex", "My Codex", 650, acceptedNames = setOf("codex"))
        val registry = BUILT_IN_DESKTOP_APPLICATIONS.withUserApplications(listOf(custom, codex))
        val lineage = listOf(process("/opt/codex"), process("/opt/tool"), process("/usr/bin/git"))
        val selection = DesktopApplicationAnchorSelector.select(lineage, registry)

        assertEquals(listOf("My Tool", "My Codex", "Git"), selection.candidates.map { it.displayName })
        assertEquals(725, requireNotNull(selection.recommended).priority)
        assertEquals("Codex", BUILT_IN_DESKTOP_APPLICATIONS.find(lineage.first())?.displayName)
        assertTrue(selection.contains(DesktopApplicationIdentity("/opt/codex")))
    }

    @Test
    fun traversalIsPerEntryAndIndependentOfPriority() {
        val registry = KnownDesktopApplicationRegistry(
            listOf(
                KnownDesktopApplication("helper", "Helper", Int.MAX_VALUE, traversal = DesktopApplicationLineageTraversal.SKIP, acceptedNames = setOf("helper")),
                KnownDesktopApplication("tool", "Tool", Int.MIN_VALUE, acceptedNames = setOf("tool")),
                KnownDesktopApplication("boundary", "Boundary", 900, traversal = DesktopApplicationLineageTraversal.STOP, acceptedNames = setOf("boundary")),
            ),
        )
        val selection = DesktopApplicationAnchorSelector.select(
            listOf(
                process("/opt/helper"),
                process("/opt/tool"),
                DesktopProcessIdentity(pid = nextPid++, displayName = "boundary"),
                process("/opt/unrelated"),
            ),
            registry,
        )

        assertEquals(listOf("Tool"), selection.candidates.map { it.displayName })
        assertEquals(Int.MIN_VALUE, requireNotNull(selection.recommended).priority)
    }

    @Test
    fun matchingPriorityTiesAreStableAndCandidateTiesPreferTheLeaf() {
        val first = KnownDesktopApplication("a", "First", 615, acceptedNames = setOf("tool"))
        val second = KnownDesktopApplication("b", "Second", 615, acceptedNames = setOf("tool"))
        val registry = KnownDesktopApplicationRegistry(listOf(second, first))
        val lineage = listOf(process("/opt/leaf/tool"), process("/opt/parent/tool"))

        assertEquals("a", registry.find(lineage.first())?.id)
        assertEquals("a", KnownDesktopApplicationRegistry(listOf(first, second)).find(lineage.first())?.id)
        assertEquals(
            "/opt/leaf/tool",
            DesktopApplicationAnchorSelector.select(lineage, registry).recommended?.identity?.executablePath,
        )
    }

    @Test
    fun overlappingNamesUseTheHighestMatchingPriorityAndEnforcePaths() {
        val generic = KnownDesktopApplication("tool", "Tool", 500, acceptedNames = setOf("tool"))
        val specific = KnownDesktopApplication("custom-tool", "Custom Tool", 575, acceptedNames = setOf("tool"), acceptedPaths = setOf("/opt/custom/tool"))
        val registry = KnownDesktopApplicationRegistry(listOf(generic, specific))

        assertEquals("custom-tool", registry.find(process("/opt/custom/tool"))?.id)
        assertEquals("tool", registry.find(process("/tmp/tool"))?.id)
    }

    @Test
    fun userOverrideCanChangeTraversalWithoutChangingPriority() {
        val registry = BUILT_IN_DESKTOP_APPLICATIONS.withUserApplications(
            listOf(KnownDesktopApplication("ssh", "OpenSSH", 0, acceptedNames = setOf("ssh"))),
        )

        assertEquals("OpenSSH", DesktopApplicationAnchorSelector.select(listOf(process("/usr/bin/ssh")), registry).recommended?.displayName)
        assertNull(DesktopApplicationAnchorSelector.select(listOf(process("/usr/bin/ssh"))).recommended)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateUserIdsAreRejectedBeforeMerging() {
        val tool = KnownDesktopApplication("tool", "Tool", 500, acceptedNames = setOf("tool"))
        BUILT_IN_DESKTOP_APPLICATIONS.withUserApplications(listOf(tool, tool))
    }

    @Test
    fun registryCopiesCallerOwnedCollections() {
        val names = mutableSetOf("tool")
        val paths = mutableSetOf("/opt/tool")
        val tool = KnownDesktopApplication("tool", "Tool", 500, acceptedNames = names, acceptedPaths = paths)
        val entries = mutableListOf(tool)
        val registry = KnownDesktopApplicationRegistry(entries)
        names.clear()
        paths.clear()
        entries.clear()

        assertEquals(setOf("tool"), tool.acceptedNames)
        assertEquals(setOf("/opt/tool"), tool.acceptedPaths)
        assertEquals("tool", registry.find(process("/opt/tool"))?.id)
        assertNull(registry.find(process("/tmp/tool")))
    }

    @Test
    fun knownDesktopApplicationAlwaysRequiresANameAndOnlyNonEmptyPathsRestrictIt() {
        val restricted = KnownDesktopApplication(
            "tool",
            "Tool",
            priority = 600,
            acceptedNames = setOf("tool"),
            acceptedPaths = setOf("/opt/trusted/tool"),
        )
        assertTrue(restricted.matches(process("/opt/trusted/tool")))
        assertFalse(restricted.matches(process("/tmp/tool")))
        assertFalse(
            restricted.matches(
                DesktopProcessIdentity(
                    pid = nextPid++,
                    executablePath = "/opt/trusted/other",
                    displayName = "tool",
                ),
            ),
        )

        val emptyPathsAcceptAll = KnownDesktopApplication(
            "systemd-test",
            "systemd",
            priority = 0,
            traversal = DesktopApplicationLineageTraversal.STOP,
            acceptedNames = setOf("systemd"),
            acceptedPaths = emptySet(),
        )
        val nullPathsAcceptAll = KnownDesktopApplication(
            "init-test",
            "init",
            priority = 0,
            traversal = DesktopApplicationLineageTraversal.STOP,
            acceptedNames = setOf("init"),
            acceptedPaths = null,
        )
        assertTrue(emptyPathsAcceptAll.matches(DesktopProcessIdentity(nextPid++, displayName = "systemd")))
        assertTrue(nullPathsAcceptAll.matches(DesktopProcessIdentity(nextPid++, displayName = "init")))
    }

    @Test
    fun automaticExeAliasesPreserveExplicitSuffixesAndPathConstraints() {
        val application = KnownDesktopApplication(
            "tools", "Tools", 600,
            acceptedNames = setOf(" Tool ", "explicit.EXE", "Bundle.APP"),
            acceptedPaths = setOf("C:/Apps/"),
        )

        assertTrue(application.matches(process("C:\\Apps\\tool")))
        assertTrue(application.matches(process("C:\\Apps\\TOOL.EXE")))
        assertTrue(application.matches(process("C:\\Apps\\explicit.exe")))
        assertTrue(application.matches(process("C:\\Apps\\Bundle.app")))
        assertFalse(application.matches(process("C:\\Apps\\tool.exe.exe")))
        assertFalse(application.matches(process("C:\\Apps\\explicit")))
        assertFalse(application.matches(process("C:\\Apps\\explicit.exe.exe")))
        assertFalse(application.matches(process("C:\\Apps\\Bundle.app.exe")))
        assertFalse(application.matches(process("C:\\Elsewhere\\tool.exe")))
    }

    @Test
    fun macOsApplicationsMatchEitherBundleOrExecutableName() {
        val executable = process("/Applications/My App.app/Contents/Resources/bin/worker")
        for (name in listOf("MY APP.APP", "WORKER")) {
            val application = KnownDesktopApplication("my-app", "My App", 600, acceptedNames = setOf(name))
            assertTrue(name, application.matches(executable))
        }
        val unrelated = KnownDesktopApplication("other-app", "Other App", 600, acceptedNames = setOf("Other.app"))
        assertFalse(unrelated.matches(executable.copy(displayName = "Other.app")))
    }

    @Test
    fun chatGptMacApplicationResolvesToCodex() {
        val path = "/Applications/ChatGPT.app/Contents/MacOS/ChatGPT"
        val selected = requireNotNull(
            DesktopApplicationAnchorSelector.select(listOf(process("/usr/bin/ssh"), process(path))).recommended,
        )

        assertEquals("codex", selected.applicationId)
        assertEquals("Codex", selected.displayName)
        assertEquals(path, selected.identity.executablePath)
    }

    @Test
    fun bundleNameMatchingStillEnforcesAcceptedExecutablePaths() {
        val path = "/System/Applications/Utilities/Terminal.app/Contents/Resources/bin/worker"
        val application = KnownDesktopApplication(
            "com.apple.Terminal", "Apple Terminal", 400,
            acceptedNames = setOf("Terminal.app"),
            acceptedPaths = setOf(path),
        )

        assertTrue(application.matches(process(path)))
        assertFalse(application.matches(process("/tmp/Terminal.app/Contents/Resources/bin/worker")))
        assertFalse(application.matches(process("/System/Applications/Utilities/Terminal.app/Contents/Resources/bin/other")))
    }

    @Test
    fun bundleNamesRecognizeExecutablesAnywhereUnderContents() {
        val application = KnownDesktopApplication("my-app", "My App", 600, acceptedNames = setOf("My App.app"))

        for (relativePath in listOf("worker", "MacOS/worker", "MacOS/nested/worker", "Resources/bin/worker", "Helpers/worker", "Resources/Contents/worker")) {
            assertTrue(relativePath, application.matches(process("/Applications/My App.app/Contents/$relativePath")))
        }
        assertFalse(application.matches(process("/Applications/My App.app/Contents/Resources/")))
        assertFalse(application.matches(process("/Applications/My App.app/Contents")))
        assertFalse(application.matches(process("/Applications/My App.app/ContentsOther/worker")))
        assertFalse(application.matches(process("/Applications/My App.app/Resources/worker")))
        assertFalse(application.matches(process("/Applications/My App.app.backup/Contents/MacOS/worker")))
    }

    @Test
    fun nestedAppsUseTheNearestBundleContainingContents() {
        val outer = KnownDesktopApplication("outer-app", "Outer App", 600, acceptedNames = setOf("My App.app"))
        val inner = KnownDesktopApplication("inner-app", "Inner App", 600, acceptedNames = setOf("Inner.app"))
        val executable = process("/Applications/My App.app/Contents/Helpers/Inner.app/Contents/Resources/bin/worker")

        assertFalse(outer.matches(executable))
        assertTrue(inner.matches(executable))
    }

    @Test
    fun trailingSlashAcceptsDirectoryDescendantsAndStillRequiresANameMatch() {
        val application = KnownDesktopApplication(
            "tool", "Tool", 600,
            acceptedNames = setOf("tool"),
            acceptedPaths = setOf("/usr/bin/"),
        )

        assertTrue(application.matches(process("/usr/bin/tool")))
        assertTrue(application.matches(process("/usr/bin/nested/tool")))
        assertFalse(application.matches(process("/usr/binary/tool")))
        assertFalse(application.matches(process("/usr/BIN/tool")))
        assertFalse(application.matches(process("/usr/bin/other")))
        assertFalse(application.matches(process("usr/bin/tool")))
        assertFalse(application.matches(DesktopProcessIdentity(nextPid++, displayName = "tool")))
    }

    @Test
    fun pathsWithoutTrailingSlashRemainExactAlongsideDirectoryPrefixes() {
        val application = KnownDesktopApplication(
            "tool", "Tool", 600,
            acceptedNames = setOf("myapp", "myexe"),
            acceptedPaths = setOf("/opt/myapp", "/usr/bin/"),
        )

        assertTrue(application.matches(process("/opt/myapp")))
        assertTrue(application.matches(process("/usr/bin/myexe")))
        assertFalse(application.matches(process("/opt/myapp/myexe")))
        assertFalse(application.matches(process("/opt/myapp-other/myexe")))
    }

    @Test
    fun windowsDirectoryPrefixesKeepCaseAndSeparatorNormalization() {
        val application = KnownDesktopApplication(
            "tool", "Tool", 600,
            acceptedNames = setOf("tool.exe"),
            acceptedPaths = setOf("C:/Program Files/MyApp/", "\\\\server\\share\\tools/"),
        )

        assertTrue(application.matches(process("c:\\PROGRAM FILES\\myapp\\bin\\TOOL.EXE")))
        assertTrue(application.matches(process("\\\\SERVER\\SHARE\\TOOLS\\tool.exe")))
        assertFalse(application.matches(process("C:\\Program Files\\MyAppOther\\tool.exe")))
        assertFalse(application.matches(process("\\\\server\\share\\tools-other\\tool.exe")))
    }

    @Test
    fun prefixRecognitionStillAnchorsAuthorizationToTheExactExecutable() {
        val registry = KnownDesktopApplicationRegistry(
            listOf(KnownDesktopApplication("tool", "Tool", 600, acceptedNames = setOf("tool"), acceptedPaths = setOf("/usr/bin/"))),
        )
        val selection = DesktopApplicationAnchorSelector.select(listOf(process("/usr/bin/tool")), registry)

        assertEquals("/usr/bin/tool", selection.recommended?.identity?.executablePath)
        assertTrue(selection.contains(DesktopApplicationIdentity("/usr/bin/tool")))
        assertFalse(selection.contains(DesktopApplicationIdentity("/usr/bin/nested/tool")))
        assertFalse(selection.contains(DesktopApplicationIdentity("/usr/bin/")))
    }

    private fun assertSelected(expected: String, lineage: List<DesktopProcessIdentity>) {
        assertEquals(expected, requireNotNull(DesktopApplicationAnchorSelector.select(lineage).recommended).displayName)
    }

    private fun process(path: String) = DesktopProcessIdentity(
        pid = nextPid++,
        executablePath = path,
        displayName = path.substringAfterLast('/').substringAfterLast('\\'),
    )

    private companion object {
        var nextPid = 1L
    }
}
