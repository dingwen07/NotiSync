package net.extrawdw.notisync.gpg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSigningInvocationParserTest {
    @Test
    fun acceptsOnlyExactGitCommitSigningShape() {
        val invocation = GitSigningInvocationParser.parse(
            listOf("--status-fd=2", "-bsau", "89ABCDEF01234567")
        )

        assertEquals(GitSigningInvocation.Remote("89ABCDEF01234567"), invocation)
    }

    @Test
    fun acceptsLongFingerprintAndOptionalHexPrefix() {
        val selector = "0x0123456789ABCDEF0123456789ABCDEF01234567"

        assertEquals(
            GitSigningInvocation.Remote(selector),
            GitSigningInvocationParser.parse(listOf("--status-fd=2", "-bsau", selector)),
        )
    }

    @Test
    fun delegatesExactSubkeyAndNonSigningInvocations() {
        val delegated = listOf(
            listOf("--status-fd=2", "-bsau", "89ABCDEF01234567!"),
            listOf("--status-fd", "2", "-bsau", "89ABCDEF01234567"),
            listOf("--status-fd=2", "--detach-sign", "89ABCDEF01234567"),
            listOf("--version"),
            emptyList(),
        )

        delegated.forEach { arguments ->
            assertTrue("should delegate $arguments", GitSigningInvocationParser.parse(arguments) is GitSigningInvocation.Delegate)
        }
    }
}
