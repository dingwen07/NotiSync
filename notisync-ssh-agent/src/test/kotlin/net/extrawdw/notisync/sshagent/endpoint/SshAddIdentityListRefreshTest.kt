package net.extrawdw.notisync.sshagent.endpoint

import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class SshAddIdentityListRefreshTest {
    @Test
    fun `ssh-add identity listing submits a remote refresh`() {
        var refreshes = 0
        val refresh = SshAddIdentityListRefresh(refresh = { refreshes++ })

        refresh.request(caller("/usr/bin/ssh-add", "ssh-add"))
        refresh.request(caller("C:\\Windows\\System32\\OpenSSH\\ssh-add.exe", "ssh-add.exe"))

        assertEquals(2, refreshes)
    }

    @Test
    fun `other identity-list callers do not submit a remote refresh`() {
        var refreshes = 0
        val refresh = SshAddIdentityListRefresh(refresh = { refreshes++ })

        refresh.request(caller("/usr/bin/ssh", "ssh"))
        refresh.request(LocalCallerSnapshot(DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE), null))

        assertEquals(0, refreshes)
    }

    @Test
    fun `refresh submission and execution failures do not break identity listing`() {
        SshAddIdentityListRefresh(refresh = { error("offline") })
            .request(caller("/usr/bin/ssh-add", "ssh-add"))
        SshAddIdentityListRefresh(
            refresh = {},
            execute = { error("shutting down") },
        ).request(caller("/usr/bin/ssh-add", "ssh-add"))
    }

    private fun caller(path: String, displayName: String) = LocalCallerSnapshot(
        DesktopProcessContext(
            DesktopProcessContextSource.PEER_CREDENTIALS,
            listOf(DesktopProcessIdentity(123, path, displayName)),
        ),
        null,
    )
}
