package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.SshRememberScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshVolatileApplicationAuthorizationStoreTest {
    private val requester = ClientId("a".repeat(52))
    private var nextId = 0
    private val store = SshVolatileApplicationAuthorizationStore { "authorization-${++nextId}" }

    @Test
    fun grantMatchesOnlyItsKeyPeerNamespaceHostAndApplication() {
        remember(application = "/opt/codex/bin/codex", host = HOST)

        assertNotNull(match(application = "/opt/codex/bin/codex", host = HOST))
        assertNull(match(application = "/opt/codex/bin/codex", host = null))
        assertNull(match(application = "/opt/codex/bin/codex", host = OTHER_HOST))
        assertNull(match(application = "/opt/other/bin/other", host = HOST))
        assertNull(match(application = "/opt/codex/bin/codex", host = HOST, providerKeyId = "other-key"))
        assertNull(match(application = "/opt/codex/bin/codex", host = HOST, epoch = 8))
    }

    @Test
    fun applicationOnlyGrantMatchesWithAnyOrNoVerifiedHost() {
        remember(application = "/opt/codex/bin/codex", host = null)

        assertNotNull(match(application = "/opt/codex/bin/codex", host = null))
        assertNotNull(match(application = "/opt/codex/bin/codex", host = HOST))
        assertNotNull(match(application = "/opt/codex/bin/codex", host = OTHER_HOST))
        assertNull(match(application = "/opt/other/bin/other", host = HOST))
    }

    @Test
    fun matchingPrefersTheNarrowerHostBoundGrant() {
        val applicationOnly = remember(application = "/opt/codex/bin/codex", host = null)
        val applicationHost = remember(application = "/opt/codex/bin/codex", host = HOST)

        assertEquals(
            applicationHost.authorization.authorizationId,
            requireNotNull(match(application = "/opt/codex/bin/codex", host = HOST)).authorizationId,
        )
        assertEquals(
            applicationOnly.authorization.authorizationId,
            requireNotNull(match(application = "/opt/codex/bin/codex", host = OTHER_HOST)).authorizationId,
        )
    }

    @Test
    fun approvedApplicationMayRemainANonRecommendedCandidate() {
        remember(application = "/usr/bin/git", host = HOST)
        val current = selection("/usr/bin/ssh", "/usr/bin/git", "/opt/codex/bin/codex", "/usr/bin/zsh")

        assertEquals("Codex", requireNotNull(current.recommended).displayName)
        assertNotNull(
            store.matching("key", requester, GENERATION, 7, current, HOST),
        )
    }

    @Test
    fun preparingDuplicateGrantReusesItsAuthorizationId() {
        val first = prepare("/opt/codex/bin/codex", HOST)
        store.commit(first)
        val duplicate = prepare("/opt/codex/bin/codex", HOST)

        assertTrue(duplicate is PreparedVolatileApplicationAuthorization.Existing)
        assertEquals(first.authorization.authorizationId, duplicate.authorization.authorizationId)
        store.commit(duplicate)
        assertEquals(1, store.size())
    }

    @Test
    fun forgetInvalidatesOnlyCoveredRequesterEpochs() {
        remember(application = "/opt/codex/bin/codex", host = HOST, epoch = 7)
        remember(application = "/usr/bin/git", host = HOST, epoch = 8)

        assertTrue(store.forget(requester, GENERATION, 7))
        assertNull(match(application = "/opt/codex/bin/codex", host = HOST, epoch = 7))
        assertNotNull(match(application = "/usr/bin/git", host = HOST, epoch = 8))
    }

    @Test
    fun keyDeletionAndProcessResetClearGrants() {
        remember(application = "/opt/codex/bin/codex", host = HOST)
        assertTrue(store.forgetKey("key"))
        assertEquals(0, store.size())

        remember(application = "/opt/codex/bin/codex", host = HOST)
        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun snapshotListsLiveGrantsAndExplicitDeletionRemovesOnlyTheRequestedGrant() {
        val applicationOnly = remember(application = "/opt/codex/bin/codex", host = null)
        val applicationHost = remember(application = "/usr/bin/git", host = HOST)

        assertEquals(
            listOf(
                applicationOnly.authorization.authorizationId,
                applicationHost.authorization.authorizationId,
            ),
            store.snapshot().map(SshVolatileApplicationAuthorization::authorizationId),
        )
        val listed = applicationOnly.authorization.toRememberedAuthorizationSnapshot(hostname = null)
        assertEquals(SshRememberScope.APPLICATION_PROCESS, listed.scope)
        assertEquals("/opt/codex/bin/codex", listed.applicationExecutablePath)
        assertEquals("codex", listed.applicationId)
        assertEquals("Codex", listed.applicationDisplayName)
        assertNull(listed.hostKeySha256)
        assertTrue(listed.processMemoryOnly)
        assertTrue(store.delete(applicationOnly.authorization.authorizationId))
        assertEquals(
            listOf(applicationHost.authorization.authorizationId),
            store.snapshot().map(SshVolatileApplicationAuthorization::authorizationId),
        )
        assertFalse(store.delete(applicationOnly.authorization.authorizationId))
    }

    private fun remember(
        application: String,
        host: ByteArray?,
        providerKeyId: String = "key",
        epoch: Long = 7,
    ) = prepare(application, host, providerKeyId, epoch).also(store::commit)

    private fun prepare(
        application: String,
        host: ByteArray?,
        providerKeyId: String = "key",
        epoch: Long = 7,
    ) = requireNotNull(
        store.prepare(
            providerKeyId,
            requester,
            GENERATION,
            epoch,
            requireNotNull(selection(application).recommended),
            host,
            1_000,
        ),
    )

    private fun match(
        application: String,
        host: ByteArray?,
        providerKeyId: String = "key",
        epoch: Long = 7,
    ) = store.matching(
        providerKeyId,
        requester,
        GENERATION,
        epoch,
        selection(application),
        host,
    )

    private fun selection(vararg paths: String) = SshApplicationAnchorSelector.select(
        paths.mapIndexed { index, path ->
            DesktopProcessIdentity(
                pid = index + 1L,
                executablePath = path,
                displayName = path.substringAfterLast('/'),
            )
        },
    )

    private companion object {
        val GENERATION = "1".repeat(32)
        val HOST = ByteArray(32) { 1 }
        val OTHER_HOST = ByteArray(32) { 2 }
    }
}
