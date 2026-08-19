package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshProcessIdentity
import net.extrawdw.notisync.protocol.SshRememberScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshProcessLineageUiTest {
    @Test
    fun sshLeafUsesItsDirectParentAsMainCaller() {
        val ssh = process(30, "C:\\Windows\\System32\\OpenSSH\\ssh.exe", "ssh.exe")
        val terminal = process(20, "C:\\Program Files\\WindowsApps\\wt.exe", "Windows Terminal")
        val shell = process(10, "C:\\Program Files\\PowerShell\\pwsh.exe", "PowerShell")

        assertEquals("Windows Terminal", listOf(ssh, terminal, shell).mainCallerLabel())
    }

    @Test
    fun nonSshLeafUsesItselfAsMainCaller() {
        val git = process(30, "/usr/bin/git", "git")
        val shell = process(20, "/usr/bin/zsh", "zsh")

        assertEquals("git", listOf(git, shell).mainCallerLabel())
    }

    @Test
    fun sshWithoutAnAvailableParentUsesItself() {
        val ssh = process(30, "/usr/bin/ssh", "ssh")

        assertEquals("ssh", listOf(ssh).mainCallerLabel())
    }

    @Test
    fun storedLeafFirstLineageIsDisplayedRootFirst() {
        val leaf = process(30, "/usr/bin/ssh", "ssh")
        val parent = process(20, "/usr/bin/zsh", "zsh")
        val root = process(10, "/usr/lib/systemd/systemd", "systemd")
        val request = StoredSshProviderRequest(
            requestId = "1".repeat(32),
            kind = SshProviderRequestKind.SIGN,
            requesterClientId = ClientId("a".repeat(52)),
            requestFingerprint = ByteArray(32),
            history = SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                processLineage = listOf(leaf, parent, root),
                payloadSize = 16,
            ),
            state = SshProviderRequestState.SENT,
            updatedAt = 2_000,
        )

        assertEquals(listOf(root, parent, leaf), request.processLineageForDisplay())
    }

    @Test
    fun destinationCombinesSignedUsernameWithAvailableHost() {
        val request = storedRequest(
            SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                destinationUsername = "git",
                destinationHost = "code.example",
                payloadSize = 16,
            ),
        )

        assertEquals("git@code.example", request.destinationLabel())
    }

    @Test
    fun userAssignedHostnameTakesPrecedenceOverRequesterAlias() {
        val request = storedRequest(
            SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                destinationUsername = "git",
                destinationHost = "requester-alias.example",
                destinationHostKeyFingerprint = "SHA256:host-key",
                payloadSize = 16,
            ),
        )

        assertEquals("git@Production Git", request.destinationLabel("Production Git"))
    }

    @Test
    fun fingerprintIsNeverUsedAsTheCombinedDestinationFallback() {
        val request = storedRequest(
            SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                destinationUsername = "git",
                destinationHostKeyFingerprint = "SHA256:host-key",
                payloadSize = 16,
            ),
        )

        assertNull(request.destinationLabel())
    }

    @Test
    fun approvalDestinationIsUnknownUntilTheFingerprintHasASavedHostname() {
        val request = storedRequest(
            SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                destinationUsername = "git",
                destinationHost = "requester-alias.example",
                destinationHostKeyFingerprint = "SHA256:host-key",
                payloadSize = 16,
            ),
        )

        assertNull(request.approvalDestinationLabel(null))
        assertEquals("Production Git", request.approvalDestinationLabel("Production Git"))
    }

    @Test
    fun ordinaryDestinationKeepsTheRequesterAliasForUnseenHosts() {
        val request = storedRequest(
            SshRequestHistorySnapshot(
                requestedAt = 1_000,
                expiresAt = 2_000,
                destinationUsername = "git",
                destinationHost = "requester-alias.example",
                destinationHostKeyFingerprint = "SHA256:host-key",
                payloadSize = 16,
            ),
        )

        assertEquals("git@requester-alias.example", request.destinationLabel())
    }

    @Test
    fun historyAuditDistinguishesRememberedAuthorizationFromManualApproval() {
        val remembered = SshRequestHistorySnapshot(
            requestedAt = 1_000,
            expiresAt = 2_000,
            payloadSize = 16,
            approvalKind = SshRequestApprovalKind.REMEMBERED_AUTHORIZATION,
            rememberedAuthorizationId = "authorization-id",
            rememberedScope = SshRememberScope.PEER_HOST_KEY,
        )

        val decoded = ProtocolCodec.decodeFromCbor<SshRequestHistorySnapshot>(
            ProtocolCodec.encodeToCbor(remembered),
        )

        assertEquals(SshRequestApprovalKind.REMEMBERED_AUTHORIZATION, decoded.approvalKind)
        assertEquals("authorization-id", decoded.rememberedAuthorizationId)
        assertEquals(SshRememberScope.PEER_HOST_KEY, decoded.rememberedScope)
        assertEquals(SshRequestApprovalKind.MANUAL, remembered.copy(approvalKind = SshRequestApprovalKind.MANUAL).approvalKind)
    }

    @Test
    fun processTreeShowsEveryExecutableFromRootToLeaf() {
        val root = process(10, "/usr/lib/systemd/systemd", "systemd")
        val shell = process(20, "/usr/bin/zsh", "zsh")
        val ssh = process(30, "/usr/bin/ssh", "ssh")

        assertEquals(
            "systemd (10)\n" +
                "└─ zsh (20)\n" +
                "  └─ ssh (30)",
            listOf(root, shell, ssh).toProcessTreeText(),
        )
    }

    @Test
    fun tappingToExpandCanShowFullPathsWithoutPidLabels() {
        val root = process(10, "/usr/lib/systemd/systemd", "systemd")
        val ssh = process(30, "/usr/bin/ssh", "ssh")

        assertEquals(
            "/usr/lib/systemd/systemd (10)\n└─ /usr/bin/ssh (30)",
            listOf(root, ssh).toProcessTreeText(showFullPaths = true),
        )
    }

    private fun process(pid: Long, path: String, name: String) = SshProcessIdentity(
        pid = pid,
        startEpochMillis = pid * 1_000,
        executablePath = path,
        displayName = name,
    )

    private fun storedRequest(history: SshRequestHistorySnapshot) = StoredSshProviderRequest(
        requestId = "1".repeat(32),
        kind = SshProviderRequestKind.SIGN,
        requesterClientId = ClientId("a".repeat(52)),
        requestFingerprint = ByteArray(32),
        history = history,
        state = SshProviderRequestState.SENT,
        updatedAt = 2_000,
    )
}
