package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.ssh.core.AgentMessageCodec
import net.extrawdw.notisync.ssh.core.AgentNumbers
import net.extrawdw.notisync.ssh.core.SshAgentFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

class UnixAgentEndpointTest {
    @Test
    fun `filesystem endpoint serves SSH framing and omits unavailable Windows process details`() {
        val files = SecureFileSystem()
        val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        val directory = files.ensurePrivateDirectory(Files.createTempDirectory(temporaryRoot, "nssa-"))
        val socketPath = directory.resolve("S")
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val processContext = AtomicReference<DesktopProcessContext?>()
        val response = AgentMessageCodec.identitiesAnswer(emptyList())
        val endpoint = UnixAgentEndpoint(
            socketPath,
            { socket ->
                processContext.set(LocalCallerResolver().resolve(socket).processContext)
                assertArrayEquals(
                    byteArrayOf(AgentNumbers.SSH_AGENTC_REQUEST_IDENTITIES.toByte()),
                    SshAgentFrameCodec.read(socket.getInputStream()),
                )
                SshAgentFrameCodec.write(socket.getOutputStream(), response)
            },
            maximumConnections = 2,
        )
        val server = Thread.ofPlatform().name("unix-agent-endpoint-test").start {
            runCatching { endpoint.run(ready::countDown) }.exceptionOrNull()?.let(failure::set)
        }
        try {
            assertTrue("endpoint did not become ready", ready.await(5, TimeUnit.SECONDS))
            AFUNIXSocket.newInstance().use { socket ->
                socket.soTimeout = 5_000
                socket.connect(AFUNIXSocketAddress.of(socketPath.toFile()))
                SshAgentFrameCodec.write(
                    socket.getOutputStream(),
                    byteArrayOf(AgentNumbers.SSH_AGENTC_REQUEST_IDENTITIES.toByte()),
                )
                assertArrayEquals(response, SshAgentFrameCodec.read(socket.getInputStream()))
            }
            if (isWindows()) {
                val context = requireNotNull(processContext.get())
                assertEquals(DesktopProcessContextSource.UNAVAILABLE, context.source)
                assertNull(context.leaf)
                assertTrue(context.processLineage.isEmpty())
            }
        } finally {
            endpoint.close()
            server.join(5_000)
            assertFalse("endpoint thread did not stop", server.isAlive)
            failure.get()?.let { throw AssertionError("endpoint failed", it) }
            assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
            Files.deleteIfExists(directory)
        }
    }
}
