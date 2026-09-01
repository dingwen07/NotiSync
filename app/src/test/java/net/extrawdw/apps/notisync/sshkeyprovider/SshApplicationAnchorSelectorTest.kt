package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshApplicationAnchorSelectorTest {
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
    fun wslHostRepresentsTheAvailableSideOfAPipeRelay() {
        assertSelected(
            "WSL Host",
            listOf(
                process("C:\\Tools\\npiperelay.exe"),
                process("C:\\Windows\\System32\\wslhost.exe"),
            ),
        )
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

        assertEquals("Terminal", requireNotNull(SshApplicationAnchorSelector.select(lineage).recommended).displayName)
    }

    @Test
    fun boundaryPreventsSelectingAnUnrelatedAncestor() {
        val lineage = listOf(
            process("/usr/bin/ssh"),
            process("/usr/bin/bash"),
            process("/usr/lib/systemd/systemd"),
            process("/opt/unrelated/desktop-app"),
        )

        assertEquals("Bash", requireNotNull(SshApplicationAnchorSelector.select(lineage).recommended).displayName)
    }

    @Test
    fun noFullPathMeansNoAuthorizationAnchor() {
        val context = DesktopProcessContext(
            DesktopProcessContextSource.CURRENT_PROCESS,
            listOf(DesktopProcessIdentity(pid = 1, displayName = "ssh")),
        )

        assertNull(SshApplicationAnchorSelector.select(context).recommended)
    }

    @Test
    fun windowsApplicationIdentityMatchesPathCaseAndSeparatorVariants() {
        val first = SshApplicationIdentity("C:\\Program Files\\Codex\\codex.exe")
        val second = SshApplicationIdentity("c:/program files/codex/CODEX.EXE")

        assertTrue(first.matches(second))
    }

    @Test
    fun selectionRetainsLowerRankedCandidatesForGrantMatching() {
        val selection = SshApplicationAnchorSelector.select(
            listOf(
                process("/usr/bin/ssh"),
                process("/usr/bin/git"),
                process("/opt/codex/bin/codex"),
                process("/usr/bin/zsh"),
                process("/sbin/init"),
            ),
        )

        assertEquals("Codex", requireNotNull(selection.recommended).displayName)
        assertTrue(selection.contains(SshApplicationIdentity("/usr/bin/git")))
    }

    @Test
    fun aiAgentOutranksUserApplicationAndCarriesStableRegistryPresentation() {
        val selected = requireNotNull(
            SshApplicationAnchorSelector.select(
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
        assertEquals(SshApplicationProcessRole.AI_AGENT, selected.role)
    }

    @Test
    fun semanticRolePrioritiesAreTunableSevenHundredToOneHundredHeuristics() {
        assertEquals(700, SshApplicationProcessRole.AI_AGENT.selectionPriority)
        assertEquals(600, SshApplicationProcessRole.USER_APPLICATION.selectionPriority)
        assertEquals(500, SshApplicationProcessRole.OPERATION_CLIENT.selectionPriority)
        assertEquals(400, SshApplicationProcessRole.TERMINAL_HOST.selectionPriority)
        assertEquals(300, SshApplicationProcessRole.UNKNOWN.selectionPriority)
        assertEquals(200, SshApplicationProcessRole.INTERACTIVE_SHELL.selectionPriority)
        assertEquals(100, SshApplicationProcessRole.ENVIRONMENT_HOST.selectionPriority)
        assertNull(SshApplicationProcessRole.TRANSPARENT_HELPER.selectionPriority)
        assertNull(SshApplicationProcessRole.SESSION_BOUNDARY.selectionPriority)
    }

    @Test
    fun knownApplicationAlwaysRequiresANameAndOnlyNonEmptyPathsRestrictIt() {
        val restricted = KnownApplication(
            "tool",
            "Tool",
            "tool",
            role = SshApplicationProcessRole.USER_APPLICATION,
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

        val emptyPathsAcceptAll = KnownApplication(
            "systemd-test",
            "systemd",
            "systemd",
            role = SshApplicationProcessRole.SESSION_BOUNDARY,
            acceptedPaths = emptySet(),
        )
        val nullPathsAcceptAll = KnownApplication(
            "init-test",
            "init",
            "init",
            role = SshApplicationProcessRole.SESSION_BOUNDARY,
            acceptedPaths = null,
        )
        assertTrue(emptyPathsAcceptAll.matches(DesktopProcessIdentity(nextPid++, displayName = "systemd")))
        assertTrue(nullPathsAcceptAll.matches(DesktopProcessIdentity(nextPid++, displayName = "init")))
    }

    private fun assertSelected(expected: String, lineage: List<DesktopProcessIdentity>) {
        assertEquals(expected, requireNotNull(SshApplicationAnchorSelector.select(lineage).recommended).displayName)
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
