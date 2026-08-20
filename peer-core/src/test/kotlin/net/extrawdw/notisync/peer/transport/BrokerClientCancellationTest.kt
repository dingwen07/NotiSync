package net.extrawdw.notisync.peer.transport

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrokerClientCancellationTest {
    @Test
    fun suspendCatchingPropagatesTheOriginalCancellation() = runBlocking {
        val expected = CancellationException("cancelled response read")

        try {
            runSuspendCatching<Nothing> {
                yield()
                throw expected
            }
            fail("cooperative cancellation must not become a Result failure")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun suspendCatchingDoesNotConvertFatalErrorsToOrdinaryFailures() = runBlocking {
        val expected = LinkageError("fatal runtime linkage failure")

        try {
            runSuspendCatching<Nothing> { throw expected }
            fail("fatal errors must escape the typed failure boundary")
        } catch (actual: LinkageError) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun ordinaryResponseParseFailureRetainsTypedFallback() = runBlocking {
        val result = runSuspendCatching {
            ProtocolCodec.decodeFromJson<SendResult>("not a provider response")
        }.getOrDefault(SendResult(accepted = false))

        assertFalse(result.accepted)
    }

    @Test
    fun brokerSuspendResponseReadsUseCancellationPreservingBoundary() {
        val source = Files.readString(findBrokerClientSource())
        assertTrue(
            "send response decoding must use the cancellation-preserving helper",
            "return runSuspendCatching { ProtocolCodec.decodeFromJson<SendResult>(resp.bodyAsText()) }" in source,
        )
        assertFalse(
            "suspended body reads must never be enclosed by ordinary runCatching",
            Regex("runCatching\\s*\\{[\\s\\S]{0,160}?bodyAsText\\(\\)").containsMatchIn(source),
        )
        assertFalse(
            "broker/provider response bodies must not enter exception messages",
            Regex("IntegrityException\\([\\s\\S]{0,200}?bodyAsText\\(\\)").containsMatchIn(source),
        )
    }

    private fun findBrokerClientSource(): Path {
        val repositoryRelative = Path.of(
            "peer-core/src/main/kotlin/net/extrawdw/notisync/peer/transport/BrokerClient.kt",
        )
        val moduleRelative = Path.of(
            "src/main/kotlin/net/extrawdw/notisync/peer/transport/BrokerClient.kt",
        )
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current != null) {
            current.resolve(repositoryRelative).takeIf(Files::isRegularFile)?.let { return it }
            current.resolve(moduleRelative).takeIf(Files::isRegularFile)?.let { return it }
            current = current.parent
        }
        error("could not locate BrokerClient.kt")
    }
}
