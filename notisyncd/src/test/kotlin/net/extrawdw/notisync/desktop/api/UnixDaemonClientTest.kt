package net.extrawdw.notisync.desktop.api

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.SessionResponse

class UnixDaemonClientTest {
    @Test
    fun `status uses one bounded HTTP request over Unix socket`() {
        val root = Files.createTempDirectory("notisync-uds-client")
        val socket = root.resolve("S.test")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        val executor = Executors.newSingleThreadExecutor()
        val request = executor.submit<String> {
            server.accept().use { channel ->
                val input = Channels.newInputStream(channel)
                val received = java.io.ByteArrayOutputStream()
                var matched = 0
                val delimiter = "\r\n\r\n".encodeToByteArray()
                while (matched < delimiter.size) {
                    val byte = input.read()
                    check(byte >= 0)
                    received.write(byte)
                    matched = if (byte.toByte() == delimiter[matched]) matched + 1 else 0
                }
                val body = LocalApiJson.encodeToString(
                    DaemonStatus("1", connectionState = DaemonConnectionState.CONNECTED),
                ).encodeToByteArray()
                val response = (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).encodeToByteArray() + body
                var output = ByteBuffer.wrap(response)
                while (output.hasRemaining()) channel.write(output)
                received.toString(StandardCharsets.US_ASCII)
            }
        }

        val status = UnixDaemonClient(socket).status()
        assertEquals(DaemonConnectionState.CONNECTED, status.connectionState)
        assertTrue(request.get().startsWith("GET /v1/status HTTP/1.1\r\n"))
        executor.shutdownNow()
        server.close()
        Files.deleteIfExists(socket)
    }

    @Test
    fun `wedged same uid daemon has a hard client deadline`() {
        val root = Files.createTempDirectory("notisync-uds-deadline")
        val socket = root.resolve("S.test")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        val accepted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            server.accept().use {
                accepted.countDown()
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        assertThrows(SocketTimeoutException::class.java) {
            UnixDaemonClient(socket, requestTimeout = Duration.ofMillis(100)).status()
        }
        assertTrue(accepted.await(1, java.util.concurrent.TimeUnit.SECONDS))

        executor.shutdownNow()
        server.close()
        Files.deleteIfExists(socket)
    }

    @Test
    fun `closing event stream wakes a blocked reader immediately`() {
        val root = Files.createTempDirectory("notisync-event-close")
        val socket = root.resolve("S.test")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        val releaseServer = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        executor.submit {
            server.accept().use { channel ->
                val input = Channels.newInputStream(channel)
                var matched = 0
                val delimiter = "\r\n\r\n".encodeToByteArray()
                while (matched < delimiter.size) {
                    val byte = input.read()
                    check(byte >= 0)
                    matched = if (byte.toByte() == delimiter[matched]) matched + 1 else 0
                }
                channel.write(ByteBuffer.wrap(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\n" +
                        "Connection: close\r\n\r\n").encodeToByteArray(),
                ))
                releaseServer.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val stream = UnixDaemonClient(socket).openEvents(SessionResponse("s", "k", "bearer", true))
        val readStarted = CountDownLatch(1)
        val reading = executor.submit {
            readStarted.countDown()
            runCatching { stream.next() }
        }
        assertTrue(readStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(50)

        val started = System.nanoTime()
        stream.close()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue("event close took $elapsed", elapsed < Duration.ofSeconds(1))
        reading.get(1, java.util.concurrent.TimeUnit.SECONDS)
        releaseServer.countDown()
        executor.shutdownNow()
        server.close()
        Files.deleteIfExists(socket)
    }
}
