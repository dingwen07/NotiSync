package net.extrawdw.notisync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopProcessContextTest {
    @Test
    fun canonicalLineageRoundTripsAndDerivesConvenienceViews() {
        val leaf = process(30, "/usr/bin/ssh", "ssh")
        val parent = process(20, "/usr/bin/zsh", "zsh")
        val root = process(10, "/sbin/launchd", "launchd")
        val context = DesktopProcessContext(
            DesktopProcessContextSource.PEER_CREDENTIALS,
            listOf(leaf, parent, root),
            bootId = "01234567-89ab-cdef-0123-456789abcdef",
        )

        val decoded = ProtocolCodec.decodeFromCbor<DesktopProcessContext>(
            ProtocolCodec.encodeToCbor(context),
        )

        assertEquals(context, decoded)
        assertEquals(leaf, decoded.leaf)
        assertEquals(parent, decoded.processLineage[1])
        assertNull(decoded.validationError())
    }

    @Test
    fun availabilityAndLineageMustAgree() {
        val process = process(10, "C:\\Windows\\System32\\OpenSSH\\ssh.exe", "ssh.exe")

        assertNotNull(
            DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE, listOf(process)).validationError(),
        )
        assertNotNull(
            DesktopProcessContext(DesktopProcessContextSource.NAMED_PIPE_CLIENT_PID).validationError(),
        )
        assertNull(DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE).validationError())
        assertNotNull(
            DesktopProcessContext(
                DesktopProcessContextSource.UNAVAILABLE,
                bootId = "01234567-89ab-cdef-0123-456789abcdef",
            ).validationError(),
        )
    }

    @Test
    fun duplicateProcessIdsAreRejected() {
        val process = process(10, "/usr/bin/ssh", "ssh")

        assertNotNull(
            DesktopProcessContext(
                DesktopProcessContextSource.PEER_CREDENTIALS,
                listOf(process, process),
            ).validationError(),
        )
    }

    @Test
    fun pidOnlyIdentityIsValidAndRoundTrips() {
        val identity = DesktopProcessIdentity(pid = 42)
        val context = DesktopProcessContext(
            DesktopProcessContextSource.PEER_CREDENTIALS,
            listOf(identity),
        )

        val decoded = ProtocolCodec.decodeFromCbor<DesktopProcessContext>(
            ProtocolCodec.encodeToCbor(context),
        )

        assertEquals(context, decoded)
        assertNull(decoded.validationError())
    }

    @Test
    fun lineageAndIdentityBoundsAreEnforced() {
        val oversizedLineage = List(DesktopProcessContextLimits.MAX_LINEAGE + 1) { index ->
            process(index + 1L, "/usr/bin/process-$index", "process-$index")
        }

        assertNotNull(
            DesktopProcessContext(
                DesktopProcessContextSource.CURRENT_PROCESS,
                oversizedLineage,
            ).validationError(),
        )
        assertNotNull(process(10, "relative/path", "ssh").validationError())
        assertNotNull(process(10, "/usr/bin/ssh", "bad\nname").validationError())
        assertNull(process(10, "\\\\server\\share\\ssh.exe", "ssh.exe").validationError())
    }

    private fun process(pid: Long, path: String, name: String) = DesktopProcessIdentity(
        pid = pid,
        executablePath = path,
        displayName = name,
    )
}
