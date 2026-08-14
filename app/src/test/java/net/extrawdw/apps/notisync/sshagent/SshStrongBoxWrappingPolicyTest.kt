package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshStrongBoxWrappingPolicyTest {
    @Test
    fun perUseAuthenticationUsesTeeWithoutRunningStrongBoxProbe() {
        var probed = false

        val requested = shouldRequestStrongBoxAesWrapping(
            preferStrongBox = true,
            strongBoxAvailable = true,
            userVerificationPolicy = SshUserVerificationPolicy.PER_USE,
        ) {
            probed = true
            true
        }

        assertFalse(requested)
        assertFalse(probed)
    }

    @Test
    fun unauthenticatedWrappingUsesStrongBoxOnlyAfterExactProbePasses() {
        assertTrue(
            shouldRequestStrongBoxAesWrapping(
                preferStrongBox = true,
                strongBoxAvailable = true,
                userVerificationPolicy = SshUserVerificationPolicy.NONE,
            ) { true },
        )
        assertFalse(
            shouldRequestStrongBoxAesWrapping(
                preferStrongBox = true,
                strongBoxAvailable = true,
                userVerificationPolicy = SshUserVerificationPolicy.NONE,
            ) { false },
        )
    }
}
