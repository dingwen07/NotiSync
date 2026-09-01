package net.extrawdw.apps.notisync.sshkeyprovider

import java.security.MessageDigest
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRememberAuthorizationPolicyTest {
    @Test
    fun alwaysAskDisablesApplyingRememberedAuthorizations() {
        assertFalse(
            SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                SshApprovalPolicy.ALWAYS_ASK,
                SshUserVerificationPolicy.NONE,
            ),
        )
        assertTrue(
            SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                SshApprovalPolicy.ALLOW_REMEMBER,
                SshUserVerificationPolicy.NONE,
            ),
        )
        assertFalse(
            SshRememberAuthorizationPolicy.keyAllowsRememberedAuthorization(
                SshApprovalPolicy.ALLOW_REMEMBER,
                SshUserVerificationPolicy.PER_USE,
            ),
        )
    }

    @Test
    fun peerScopeIsAlwaysAvailableButHostScopeRequiresVerifiedSessionBind() {
        val unknown = destination(SshDestinationProvenance.UNKNOWN)
        val signedUserAuth = destination(SshDestinationProvenance.SIGNED_USERAUTH, HOST_KEY)
        val verified = destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, HOST_KEY)

        assertEquals(setOf(SshRememberScope.PEER), SshRememberAuthorizationPolicy.availableDiskScopes(unknown))
        assertEquals(
            setOf(SshRememberScope.PEER),
            SshRememberAuthorizationPolicy.availableDiskScopes(signedUserAuth),
        )
        assertEquals(
            setOf(SshRememberScope.PEER, SshRememberScope.PEER_HOST_KEY),
            SshRememberAuthorizationPolicy.availableDiskScopes(verified),
        )
    }

    @Test
    fun hostScopeRecomputesAndChecksTheClaimedFingerprint() {
        val forged = SshDestinationContext(
            provenance = SshDestinationProvenance.VERIFIED_SESSION_BIND,
            connectionDirection = SshConnectionDirection.DIRECT,
            serverHostKeyBlob = HOST_KEY,
            serverHostKeyBlobSha256 = ByteArray(32) { 7 },
        )

        assertNull(SshRememberAuthorizationPolicy.verifiedHostKeySha256(forged))
        assertEquals(
            setOf(SshRememberScope.PEER),
            SshRememberAuthorizationPolicy.availableDiskScopes(forged),
        )
    }

    @Test
    fun hostScopedRuleOnlyMatchesTheSameVerifiedHostKey() {
        val approved = destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, HOST_KEY)
        val changed = destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, OTHER_HOST_KEY)
        val stored = requireNotNull(SshRememberAuthorizationPolicy.verifiedHostKeySha256(approved))

        assertTrue(
            SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                SshRememberScope.PEER_HOST_KEY,
                stored,
                approved,
            ),
        )
        assertFalse(
            SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                SshRememberScope.PEER_HOST_KEY,
                stored,
                changed,
            ),
        )
        assertFalse(
            SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                SshRememberScope.PEER_HOST_KEY,
                stored,
                destination(SshDestinationProvenance.SIGNED_USERAUTH, HOST_KEY),
            ),
        )
    }

    @Test
    fun applicationProcessScopeCannotEnterTheDiskRulePath() {
        assertEquals(
            SshRememberAuthorizationStorage.PROCESS_MEMORY,
            SshRememberScope.APPLICATION_PROCESS.authorizationStorage,
        )
        assertFalse(
            SshRememberAuthorizationPolicy.persistentAuthorizationMatches(
                SshRememberScope.APPLICATION_PROCESS,
                null,
                destination(SshDestinationProvenance.UNKNOWN),
            ),
        )
    }

    @Test
    fun applicationChoicesRequireAFullPathAnchorAndOfferAnOptionalVerifiedHostConstraint() {
        val withApplication = DesktopProcessContext(
            DesktopProcessContextSource.CURRENT_PROCESS,
            listOf(
                DesktopProcessIdentity(2, "/usr/bin/ssh", "ssh"),
                DesktopProcessIdentity(1, "/opt/codex/bin/codex", "codex"),
            ),
        )
        val withoutApplicationPath = DesktopProcessContext(
            DesktopProcessContextSource.CURRENT_PROCESS,
            listOf(DesktopProcessIdentity(2, displayName = "ssh")),
        )

        val available = SshRememberAuthorizationPolicy.availableOptions(
            destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, HOST_KEY),
            withApplication,
        )
        assertTrue(SshRememberAuthorizationChoice.APPLICATION in available.choices)
        assertTrue(SshRememberAuthorizationChoice.APPLICATION_HOST in available.choices)
        assertEquals("Codex", requireNotNull(available.applicationAnchor).displayName)
        val withoutVerifiedHost = SshRememberAuthorizationPolicy.availableOptions(
            destination(SshDestinationProvenance.UNKNOWN, HOST_KEY),
            withApplication,
        )
        assertTrue(SshRememberAuthorizationChoice.APPLICATION in withoutVerifiedHost.choices)
        assertFalse(SshRememberAuthorizationChoice.APPLICATION_HOST in withoutVerifiedHost.choices)
        assertFalse(
            SshRememberAuthorizationChoice.APPLICATION in SshRememberAuthorizationPolicy.availableOptions(
                destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, HOST_KEY),
                withoutApplicationPath,
            ).choices,
        )
        assertFalse(
            SshRememberAuthorizationChoice.APPLICATION_HOST in SshRememberAuthorizationPolicy.availableOptions(
                destination(SshDestinationProvenance.VERIFIED_SESSION_BIND, HOST_KEY),
                withoutApplicationPath,
            ).choices,
        )
    }

    @Test
    fun hostRegistryFingerprintFormatsTheDigestWithoutRehashingIt() {
        assertEquals(
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            ByteArray(32).toSshHostKeyFingerprint(),
        )
    }

    private fun destination(provenance: SshDestinationProvenance, hostKey: ByteArray? = null) =
        SshDestinationContext(
            provenance = provenance,
            connectionDirection = SshConnectionDirection.DIRECT,
            serverHostKeyBlob = hostKey,
            serverHostKeyBlobSha256 = hostKey?.let(::sha256),
        )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        val HOST_KEY = "first-host-key".encodeToByteArray()
        val OTHER_HOST_KEY = "second-host-key".encodeToByteArray()
    }
}
