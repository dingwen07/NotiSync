package net.extrawdw.notisync.desktop.api

import java.io.Closeable
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.localapi.ApplicationEventAckRequest
import net.extrawdw.notisync.localapi.ApplicationEventCompletionRequest
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnixDaemonClientTest {
    @Test
    fun `status uses one bounded HTTP request over Unix socket`() {
        ScriptedServer(
            Response.json(
                LocalApiJson.encodeToString(
                    DaemonStatus("1", connectionState = DaemonConnectionState.CONNECTED),
                ),
            ),
        ).use { server ->
            val status = UnixDaemonClient(server.socket).status()

            assertEquals(DaemonConnectionState.CONNECTED, status.connectionState)
            assertEquals(
                "GET /v1/status HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: application/json\r\n" +
                    "Connection: close\r\n\r\n",
                server.awaitRequests().single().head,
            )
        }
    }

    @Test
    fun `application registration list and removal use generic endpoints`() {
        val view = ApplicationView(
            applicationId = "nsrun/app",
            displayName = "NotiSync Run",
            capabilities = listOf(Capability.CAPTURE, Capability.PUBLISH_RUNS),
            updatedAtEpochMillis = 9,
        )
        val listing = ApplicationListResponse(
            applications = listOf(view),
            effectiveCapabilities = listOf(Capability.FOREGROUND_CONNECTION, Capability.CAPTURE),
        )
        ScriptedServer(
            Response.json(LocalApiJson.encodeToString(view)),
            Response.json(LocalApiJson.encodeToString(listing)),
            Response.noContent(),
        ).use { server ->
            val client = UnixDaemonClient(server.socket)
            assertEquals(
                view,
                client.putApplication(
                    "nsrun/app",
                    ApplicationRegistrationRequest(
                        displayName = "NotiSync Run",
                        capabilities = setOf(Capability.PUBLISH_RUNS, Capability.CAPTURE),
                    ),
                ),
            )
            assertEquals(listing, client.listApplications())
            client.deleteApplication("nsrun/app")

            val requests = server.awaitRequests()
            assertEquals("PUT /v1/applications/nsrun%2Fapp HTTP/1.1", requests[0].requestLine)
            assertEquals("GET /v1/applications HTTP/1.1", requests[1].requestLine)
            assertEquals("DELETE /v1/applications/nsrun%2Fapp HTTP/1.1", requests[2].requestLine)
            val registration = LocalApiJson.decodeFromString<ApplicationRegistrationRequest>(requests[0].body)
            assertEquals(setOf(Capability.CAPTURE, Capability.PUBLISH_RUNS), registration.capabilities)
        }
    }

    @Test
    fun `single send uses JSON without a client supplied identity header`() {
        val accepted = SendAccepted("m-1", 123, "submission-1")
        val send = SendRequest(
            applicationId = "nsrun",
            messageType = MessageType.DATA_SYNC,
            body = "AQ==",
            submissionId = "submission-1",
        )
        ScriptedServer(Response.json(LocalApiJson.encodeToString(accepted), status = 202)).use { server ->
            assertEquals(accepted, UnixDaemonClient(server.socket).send(send))

            val request = server.awaitRequests().single()
            assertEquals("POST /v1/send HTTP/1.1", request.requestLine)
            assertEquals("application/json", request.headers["content-type"])
            assertEquals("application/json", request.headers["accept"])
            assertEquals(send, LocalApiJson.decodeFromString<SendRequest>(request.body))
            assertFalse(request.headers.keys.any { it.equals("x-notisync-client", ignoreCase = true) })
            assertFalse(request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `sendAll uses NDJSON and preserves acceptance order`() {
        val sends = listOf(
            SendRequest("nsrun", MessageType.DATA_SYNC, "AQ==", submissionId = "one"),
            SendRequest("nsrun", MessageType.NOTIFICATION, "Ag==", submissionId = "two"),
        )
        val acceptances = listOf(
            SendAccepted("m-2", 11, "one"),
            SendAccepted("m-1", 10, "two"),
        )
        val responseBody = acceptances.joinToString("\n", postfix = "\n") {
            LocalApiJson.encodeToString(it)
        }
        ScriptedServer(Response.ndjson(responseBody, status = 202)).use { server ->
            assertEquals(acceptances, UnixDaemonClient(server.socket).sendAll(sends))

            val request = server.awaitRequests().single()
            assertEquals("application/x-ndjson", request.headers["content-type"])
            assertEquals("application/x-ndjson", request.headers["accept"])
            assertEquals(
                sends,
                request.body.lineSequence().filter { it.isNotBlank() }
                    .map { LocalApiJson.decodeFromString<SendRequest>(it) }
                    .toList(),
            )
        }
    }

    @Test
    fun `sendAll rejects mixed applications before connecting`() {
        val connectorCalled = java.util.concurrent.atomic.AtomicBoolean()
        val client = UnixDaemonClient(
            socketPath = Files.createTempDirectory("unused").resolve("S.test"),
            connector = {
                connectorCalled.set(true)
                error("must not connect")
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            client.sendAll(
                listOf(
                    SendRequest("one", MessageType.DATA_SYNC, "AQ=="),
                    SendRequest("two", MessageType.DATA_SYNC, "Ag=="),
                ),
            )
        }
        assertFalse(connectorCalled.get())
    }

    @Test
    fun `receive is a POST NDJSON stream and closing wakes a blocked reader`() {
        val root = Files.createTempDirectory("notisync-receive-close")
        val socket = root.resolve("S.test")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        val releaseServer = CountDownLatch(1)
        val requestCaptured = java.util.concurrent.CompletableFuture<CapturedRequest>()
        val executor = Executors.newFixedThreadPool(2)
        executor.submit {
            server.accept().use { channel ->
                requestCaptured.complete(readRequest(channel))
                val first = LocalApiJson.encodeToString(
                    ReceiveRecord(
                        recordType = ReceiveRecordType.HEARTBEAT,
                        applicationId = "nsrun",
                        receivedAtEpochMillis = 22,
                    ),
                ) + "\n"
                writeResponse(
                    channel,
                    Response(
                        status = 200,
                        contentType = "application/x-ndjson",
                        body = first,
                        includeContentLength = false,
                    ),
                )
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val receive = ReceiveRequest("nsrun", messageTypes = listOf(MessageType.DATA_SYNC))
        val stream = UnixDaemonClient(socket).openReceive(receive)
        assertEquals(ReceiveRecordType.HEARTBEAT, stream.next()!!.recordType)
        val readStarted = CountDownLatch(1)
        val reading = executor.submit {
            readStarted.countDown()
            runCatching { stream.next() }
        }
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)

        val started = System.nanoTime()
        stream.close()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue("receive close took $elapsed", elapsed < Duration.ofSeconds(1))
        reading.get(1, TimeUnit.SECONDS)
        val captured = requestCaptured.get(1, TimeUnit.SECONDS)
        assertEquals("POST /v1/receive HTTP/1.1", captured.requestLine)
        assertEquals("application/json", captured.headers["content-type"])
        assertEquals("application/x-ndjson", captured.headers["accept"])
        assertEquals(receive, LocalApiJson.decodeFromString<ReceiveRequest>(captured.body))

        releaseServer.countDown()
        executor.shutdownNow()
        server.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(root)
    }

    @Test
    fun `unregister ack and complete carry application metadata in JSON`() {
        val receive = ReceiveRequest("nsrun", messageTypes = listOf(MessageType.ACTION))
        val response = SendRequest("nsrun", MessageType.DATA_SYNC, "AQ==")
        ScriptedServer(Response.noContent(), Response.noContent(), Response.noContent()).use { server ->
            val client = UnixDaemonClient(server.socket)
            client.unregisterReceive(receive)
            client.ack("nsrun", "event/1")
            client.complete("nsrun", "event/1", listOf(response))

            val requests = server.awaitRequests()
            assertEquals("DELETE /v1/receive HTTP/1.1", requests[0].requestLine)
            assertEquals(receive, LocalApiJson.decodeFromString<ReceiveRequest>(requests[0].body))
            assertEquals("POST /v1/events/event%2F1/ack HTTP/1.1", requests[1].requestLine)
            assertEquals(
                ApplicationEventAckRequest("nsrun"),
                LocalApiJson.decodeFromString<ApplicationEventAckRequest>(requests[1].body),
            )
            assertEquals("POST /v1/events/event%2F1/complete HTTP/1.1", requests[2].requestLine)
            assertEquals(
                ApplicationEventCompletionRequest("nsrun", listOf(response)),
                LocalApiJson.decodeFromString<ApplicationEventCompletionRequest>(requests[2].body),
            )
        }
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
        assertTrue(accepted.await(1, TimeUnit.SECONDS))

        executor.shutdownNow()
        server.close()
        Files.deleteIfExists(socket)
        Files.deleteIfExists(root)
    }

    private data class Response(
        val status: Int,
        val contentType: String?,
        val body: String,
        val includeContentLength: Boolean = true,
    ) {
        companion object {
            fun json(body: String, status: Int = 200) = Response(status, "application/json", body)
            fun ndjson(body: String, status: Int = 200) =
                Response(status, "application/x-ndjson", body)
            fun noContent() = Response(204, null, "")
        }
    }

    private data class CapturedRequest(
        val head: String,
        val body: String,
    ) {
        val requestLine: String get() = head.substringBefore("\r\n")
        val headers: Map<String, String> = head.substringAfter("\r\n")
            .split("\r\n")
            .filter { it.contains(':') }
            .associate { line ->
                line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
            }
    }

    private class ScriptedServer(vararg responses: Response) : Closeable {
        private val root = Files.createTempDirectory("notisync-local-client-test")
        val socket = root.resolve("S.test")
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socket))
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val future = executor.submit<List<CapturedRequest>> {
            responses.map { response ->
                server.accept().use { channel ->
                    val request = readRequest(channel)
                    writeResponse(channel, response)
                    request
                }
            }
        }

        fun awaitRequests(): List<CapturedRequest> = future.get(3, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            executor.shutdownNow()
            Files.deleteIfExists(socket)
            Files.deleteIfExists(root)
        }
    }

    private companion object {
        fun readRequest(channel: java.nio.channels.SocketChannel): CapturedRequest {
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
            val head = received.toString(StandardCharsets.US_ASCII)
            val contentLength = head.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
            val body = input.readNBytes(contentLength).toString(StandardCharsets.UTF_8)
            return CapturedRequest(head, body)
        }

        fun writeResponse(channel: java.nio.channels.SocketChannel, response: Response) {
            val body = response.body.encodeToByteArray()
            val reason = when (response.status) {
                200 -> "OK"
                202 -> "Accepted"
                204 -> "No Content"
                else -> "Response"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
                response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                if (response.includeContentLength) append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.encodeToByteArray()
            val output = ByteBuffer.wrap(head + body)
            while (output.hasRemaining()) channel.write(output)
        }
    }
}
