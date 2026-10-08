package net.extrawdw.apps.notisync.seal

import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationLineageTraversal
import net.extrawdw.apps.notisync.sshkeyprovider.KnownDesktopApplication
import net.extrawdw.apps.notisync.sshkeyprovider.KnownDesktopApplicationRegistry
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealProcessPresentationTest {
    private val lineage = listOf(
        DesktopProcessIdentity(30, "/opt/notisync/bin/notisync-gpg"),
        DesktopProcessIdentity(20, "/usr/bin/git", username = "alice", uid = 501),
        DesktopProcessIdentity(10, "/bin/zsh"),
    )

    @Test
    fun sealUsesTheSameGitRecognitionAndRootFirstTreeAsSsh() {
        val stored = stored(DesktopProcessContext(DesktopProcessContextSource.CURRENT_PROCESS, lineage))
        assertEquals("Git", stored.processLabel())
        assertEquals("git", stored.applicationAnchor()?.applicationId)
        assertEquals(lineage.asReversed(), stored.processLineageForDisplay())
    }

    @Test
    fun customNamesPathsPrioritiesAndTraversalApplyToSeal() {
        val stored = stored(DesktopProcessContext(DesktopProcessContextSource.CURRENT_PROCESS, lineage))
        val registry = KnownDesktopApplicationRegistry(listOf(
            KnownDesktopApplication("gpg", "Seal wrapper", 1000, DesktopApplicationLineageTraversal.SKIP, setOf("notisync-gpg")),
            KnownDesktopApplication("git", "Work Git", 10, acceptedNames = setOf("git"), acceptedPaths = setOf("/usr/bin/git")),
            KnownDesktopApplication("shell", "Shell boundary", 999, DesktopApplicationLineageTraversal.STOP, setOf("zsh")),
        ))
        assertEquals("Work Git", stored.processLabel(registry))
        assertEquals("/usr/bin/git", stored.applicationAnchor(registry)?.identity?.executablePath)
    }

    @Test
    fun legacyAndUnavailableRequestsHaveNoInventedProcessDetails() {
        listOf(null, DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE)).forEach { context ->
            val stored = stored(context)
            assertNull(stored.processLabel())
            assertNull(stored.applicationAnchor())
            assertTrue(stored.processLineageForDisplay().isEmpty())
        }
        val restricted = stored(DesktopProcessContext(DesktopProcessContextSource.CURRENT_PROCESS, listOf(DesktopProcessIdentity(42))))
        assertEquals("PID 42", restricted.processLabel())
    }

    private fun stored(context: DesktopProcessContext?) = StoredOpenPgpRequest(
        OpenPgpSignSync(
            OpenPgpSignAction.REQUEST, requestId = "1".repeat(32), requesterClientId = ClientId("desktop"),
            issuedAt = 1, expiresAt = 2, primaryKeyId = "1".repeat(16), payloadSha256 = ByteArray(32),
            objectKind = OpenPgpObjectKind.GIT_COMMIT, processContext = context,
        ),
        senderClientId = ClientId("desktop"), state = OpenPgpRequestState.SENT, updatedAt = 3,
    )
}
