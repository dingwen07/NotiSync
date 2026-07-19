package net.extrawdw.notisync.daemon

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.cli.NotisyncCli
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.desktop.api.LocalApiException
import net.extrawdw.notisync.desktop.api.UnixDaemonClient
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.peer.storage.InMemoryGenericSendOutbox
import net.extrawdw.notisync.daemon.peer.storage.PersistentApplicationBridgeStore
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnixHttpServerIntegrationTest {
    @Test
    fun `notisync config changes the daemon device name over the local API`() =
        ServerFixture().use { fixture ->
            PrivateFiles.atomicWrite(
                fixture.paths.nsrunConfig,
                "# NotiSync Run configuration\nupdate-interval-seconds 45\n".encodeToByteArray(),
            )
            val originalRunConfig = Files.readAllBytes(fixture.paths.nsrunConfig)
            fixture.start()

            val output = StringBuilder()
            val error = StringBuilder()
            val cli = NotisyncCli(fixture.paths, output, error)
            assertEquals(0, cli.run(arrayOf("config", "set", "device-name", "Build Host")))
            assertEquals("", error.toString())
            assertEquals("Build Host", NotisyncdConfigStore(fixture.paths.daemonConfig).load().deviceName)
            assertArrayEquals(originalRunConfig, Files.readAllBytes(fixture.paths.nsrunConfig))

            output.clear()
            assertEquals(0, cli.run(arrayOf("config", "get")))
            val view = LocalApiJson.decodeFromString<DaemonConfigView>(output.toString().trim())
            assertEquals("Build Host", view.deviceName)
            val status = fixture.raw("GET /v1/status?view=full HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(200, status.status)

            val accessLines = fixture.logs.lines()
            val patchAccess = accessLines.single { it.contains("\"PATCH /v1/config HTTP/1.1\"") }
            assertTrue(patchAccess.contains("INFO  [notisyncd-http-"))
            assertTrue(
                patchAccess.contains(
                    "pid=${ProcessHandle.current().pid()} process=",
                ),
            )
            assertTrue(patchAccess.contains(" executable="))
            assertTrue(Regex(".* \\\"PATCH /v1/config HTTP/1\\.1\\\" 200 \\d+ms$").matches(patchAccess))
            assertFalse(patchAccess.contains("->"))

            assertTrue(accessLines.any { it.contains("\"GET /v1/config HTTP/1.1\" 200 ") })
            assertTrue(accessLines.any { it.contains("\"GET /v1/status?view=full HTTP/1.1\" 200 ") })
        }

    @Test
    fun `only the explicit daemon readiness probe is reduced to debug`() =
        ServerFixture().use { fixture ->
            fixture.start()

            assertEquals(200, fixture.raw("GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n").status)
            assertTrue(fixture.logs.contains("\"GET /v1/status HTTP/1.1\" 200 "))

            assertEquals(
                200,
                fixture.raw("GET /v1/status?probe=ready HTTP/1.1\r\nHost: localhost\r\n\r\n").status,
            )
            assertFalse(fixture.logs.contains("\"GET /v1/status?probe=ready HTTP/1.1\""))
        }

    @Test
    fun `real socket status and config patch remain strictly separated from nsrun config`() =
        ServerFixture().use { fixture ->
            val secret = "local-run-key-that-must-not-leave-nsrun-conf"
            PrivateFiles.atomicWrite(
                fixture.paths.nsrunConfig,
                (
                    "# NotiSync Run configuration\n" +
                        "llm-base-url \"https://llm.invalid/v1\"\n" +
                        "llm-model \"test-model\"\n" +
                        "llm-api-key \"$secret\"\n"
                    ).encodeToByteArray(),
            )
            val originalRunConfig = Files.readAllBytes(fixture.paths.nsrunConfig)

            fixture.start()
            val statusResponse = fixture.raw("GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(200, statusResponse.status)
            assertEquals(
                setOf("FOREGROUND_CONNECTION", "CAPABILITY_ROUTING_V1"),
                LocalApiJson.decodeFromString<net.extrawdw.notisync.localapi.DaemonStatus>(statusResponse.body)
                    .capabilities,
            )

            val patch = LocalApiJson.encodeToString(
                DaemonConfigPatch(
                    deviceName = "Socket Test Desktop",
                    automaticallyApplyTrustedDeviceTables = true,
                ),
            )
            val configResponse = fixture.jsonRequest("PATCH", "/v1/config", patch)
            assertEquals(200, configResponse.status)
            val view = LocalApiJson.decodeFromString<DaemonConfigView>(configResponse.body)
            assertEquals("Socket Test Desktop", view.deviceName)
            assertEquals("desktop", view.platformName)
            assertTrue(view.automaticallyApplyTrustedDeviceTables)

            assertArrayEquals(originalRunConfig, Files.readAllBytes(fixture.paths.nsrunConfig))
            assertFalse(Files.readString(fixture.paths.daemonConfig).contains(secret))
            assertFalse(statusResponse.body.contains(secret))
            assertFalse(configResponse.body.contains(secret))
        }

    @Test
    fun `bounded HTTP rejects unsupported framing and closes after one request`() =
        ServerFixture(maxHeaderBytes = 128, maxBodyBytes = 128).use { fixture ->
            fixture.start()

            val missingLength = fixture.raw(
                "POST /v1/send HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n\r\n",
            )
            assertError(missingLength, 411, "length_required")

            val chunked = fixture.raw(
                "POST /v1/send HTTP/1.1\r\nHost: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n0\r\n\r\n",
            )
            assertError(chunked, 400, "transfer_encoding")

            val oversized = fixture.raw(
                "POST /v1/send HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: application/json\r\nContent-Length: 129\r\n\r\n",
            )
            assertError(oversized, 413, "body_too_large")

            val duplicateLength = fixture.raw(
                "POST /v1/send HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Length: 0\r\nContent-Length: 0\r\n\r\n",
            )
            assertError(duplicateLength, 400, "duplicate_header")

            val oversizedHeaders = fixture.raw(
                "GET /v1/status HTTP/1.1\r\nHost: localhost\r\nX-Fill: ${"a".repeat(200)}\r\n\r\n",
            )
            assertError(oversizedHeaders, 431, "headers_too_large")

            val pipelined = fixture.raw(
                "GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                    "GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n",
            )
            assertEquals(200, pipelined.status)
            assertEquals(1, "HTTP/1.1".toRegex().findAll(pipelined.raw).count())
        }

    @Test
    fun `applications and generic JSON plus NDJSON sends use one atomic bridge`() =
        ServerFixture().use { fixture ->
            fixture.start()
            val client = UnixDaemonClient(fixture.paths.socket)
            val beforeRegistration = assertThrows(LocalApiException::class.java) {
                client.send(send("nsrun", byteArrayOf(1)))
            }
            assertEquals(409, beforeRegistration.status)

            val registered = client.putApplication(
                "nsrun",
                ApplicationRegistrationRequest(
                    displayName = "NotiSync Run",
                    version = "1.0",
                    capabilities = setOf(Capability.PUBLISH_RUNS, Capability.CAPTURE),
                ),
            )
            assertEquals("nsrun", registered.applicationId)
            assertEquals(
                listOf(Capability.CAPTURE, Capability.PUBLISH_RUNS),
                registered.capabilities,
            )
            val applications = client.listApplications()
            assertEquals(listOf("nsrun"), applications.applications.map { it.applicationId })
            assertEquals(
                listOf(
                    Capability.CAPTURE,
                    Capability.FOREGROUND_CONNECTION,
                    Capability.CAPABILITY_ROUTING_V1,
                    Capability.PUBLISH_RUNS,
                ),
                applications.effectiveCapabilities,
            )

            val jsonAccepted = client.send(send("nsrun", byteArrayOf(2), submissionId = "single"))
            assertEquals("single", jsonAccepted.submissionId)
            val firstPending = fixture.outbox.peekConsecutive().single()
            assertEquals(jsonAccepted.messageId, firstPending.messageId)
            assertEquals(Recipients.OwnMesh, firstPending.scope)
            assertEquals(Urgency.NORMAL, firstPending.urgency)
            assertEquals(SignerSelection.OPERATIONAL, firstPending.signWith)

            val ndjsonAccepted = client.sendAll(
                listOf(
                    send("nsrun", byteArrayOf(3), submissionId = "batch-1"),
                    send(
                        "nsrun",
                        byteArrayOf(4),
                        messageType = MessageType.NOTIFICATION,
                        submissionId = "batch-2",
                    ),
                ),
            )
            assertEquals(listOf("batch-1", "batch-2"), ndjsonAccepted.map { it.submissionId })
            assertEquals(3, fixture.outbox.pendingCount())

            val validLine = LocalApiJson.encodeToString(send("nsrun", byteArrayOf(5)))
            val malformed = "$validLine\n{\"applicationId\":\"nsrun\",\"messageType\":\"DATA_SYNC\"}\n"
            val rejected = fixture.request(
                method = "POST",
                target = "/v1/send",
                body = malformed,
                contentType = "application/x-ndjson",
            )
            assertError(rejected, 400, "invalid_request")
            assertEquals(3, fixture.outbox.pendingCount())

            client.deleteApplication("nsrun")
            assertTrue(client.listApplications().applications.isEmpty())
            assertEquals(
                listOf(Capability.FOREGROUND_CONNECTION, Capability.CAPABILITY_ROUTING_V1),
                client.listApplications().effectiveCapabilities,
            )
            assertEquals(0, fixture.outbox.pendingCount())
        }

    @Test
    fun `send rejects unsupported content type record overflow and mixed applications atomically`() =
        ServerFixture().use { fixture ->
            fixture.start()
            val client = UnixDaemonClient(fixture.paths.socket)
            client.putApplication("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
            client.putApplication("other", ApplicationRegistrationRequest("Other"))

            val oneRecord = LocalApiJson.encodeToString(send("nsrun", byteArrayOf(1)))
            val unsupported = fixture.request(
                method = "POST",
                target = "/v1/send",
                body = oneRecord,
                contentType = "text/plain",
            )
            assertError(unsupported, 415, "content_type")
            assertEquals(0, fixture.outbox.pendingCount())

            val tooManyRecords = List(1_025) { oneRecord }.joinToString(separator = "\n", postfix = "\n")
            val overflow = fixture.request(
                method = "POST",
                target = "/v1/send",
                body = tooManyRecords,
                contentType = "application/x-ndjson",
            )
            assertError(overflow, 413, "too_many_records")
            assertEquals(0, fixture.outbox.pendingCount())

            val mixedApplications = listOf(
                LocalApiJson.encodeToString(send("nsrun", byteArrayOf(2))),
                LocalApiJson.encodeToString(send("other", byteArrayOf(3))),
            ).joinToString(separator = "\n", postfix = "\n")
            val mixed = fixture.request(
                method = "POST",
                target = "/v1/send",
                body = mixedApplications,
                contentType = "application/x-ndjson",
            )
            assertError(mixed, 400, "invalid_request")
            assertEquals(0, fixture.outbox.pendingCount())
        }

    @Test
    fun `receive sockets fan out while ack completion and interest deletion are application scoped`() =
        ServerFixture().use { fixture ->
            fixture.start()
            val client = UnixDaemonClient(fixture.paths.socket)
            client.putApplication("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
            val interest = ReceiveRequest("nsrun", messageTypes = listOf(MessageType.NOTIFICATION))

            client.openReceive(interest).use { first ->
                client.openReceive(interest).use { second ->
                    fixture.awaitAccessCount("\"POST /v1/receive HTTP/1.1\" 200 ", 2)
                    assertEquals(1, fixture.receiver.interestCount("nsrun"))
                    assertTrue(fixture.receiver.accept(fixture.inbound("fanout", byteArrayOf(7, 8))))
                    assertEquals("fanout", first.next()?.envelopeId)
                    assertEquals("fanout", second.next()?.envelopeId)
                    assertEquals(1, fixture.receiver.pendingCount("nsrun"))

                    client.ack("nsrun", "fanout")
                    client.ack("nsrun", "fanout")
                    assertEquals(0, fixture.receiver.pendingCount("nsrun"))

                    assertTrue(fixture.receiver.accept(fixture.inbound("complete", byteArrayOf(9))))
                    assertEquals("complete", first.next()?.envelopeId)
                    assertEquals("complete", second.next()?.envelopeId)
                    client.complete(
                        applicationId = "nsrun",
                        envelopeId = "complete",
                        sends = listOf(send("nsrun", byteArrayOf(10), submissionId = "response")),
                    )
                    client.complete(
                        applicationId = "nsrun",
                        envelopeId = "complete",
                        sends = listOf(send("nsrun", byteArrayOf(10), submissionId = "response")),
                    )
                    assertEquals(0, fixture.receiver.pendingCount("nsrun"))
                    assertEquals(1, fixture.outbox.pendingCount())

                    client.unregisterReceive(interest)
                    assertEquals(0, fixture.receiver.interestCount("nsrun"))
                    assertFalse(fixture.receiver.accept(fixture.inbound("ignored", byteArrayOf(11))))
                    assertEquals(0, fixture.receiver.pendingCount("nsrun"))
                }
            }
        }

    @Test
    fun `shutdown response closes listener without waiting on its own request worker`() =
        ServerFixture().use { fixture ->
            fixture.start()
            val response = fixture.raw(
                "POST /v1/shutdown HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
            )
            assertEquals(204, response.status)
            fixture.awaitStopped()
            assertFalse(Files.exists(fixture.paths.socket))
        }

    private fun send(
        applicationId: String,
        body: ByteArray,
        messageType: MessageType = MessageType.DATA_SYNC,
        submissionId: String? = null,
    ) = SendRequest(
        applicationId = applicationId,
        messageType = messageType,
        body = Base64.getEncoder().encodeToString(body),
        submissionId = submissionId,
    )

    private fun assertError(response: RawResponse, status: Int, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, LocalApiJson.decodeFromString<ApiError>(response.body).code)
    }

    private class ServerFixture(
        // macOS limits sockaddr_un paths to 104 bytes; the Gradle temp root is much longer.
        val root: Path = Files.createTempDirectory(Path.of("/private/tmp"), "nsuds-").toRealPath(),
        private val maxHeaderBytes: Int = 64 * 1024,
        private val maxBodyBytes: Int = 1024 * 1024,
    ) : AutoCloseable {
        val paths = DesktopPaths(root)
        private val identityResolver = ProcessIdentityResolver()
        val logs = StringBuilder()
        val applications: PersistentApplicationBridgeStore
        val outbox: InMemoryGenericSendOutbox
        val receiver: ApplicationReceiveRouter
        private val dispatcher: GenericSendDispatcher
        private val service: DaemonService
        private val server: UnixHttpServer
        private val executor = Executors.newSingleThreadExecutor()
        private var running: Future<*>? = null

        init {
            PrivateFiles.ensureDirectory(root)
            applications = PersistentApplicationBridgeStore(
                DaemonDatabaseRepository(DaemonStorageLayout(root)),
            )
            outbox = InMemoryGenericSendOutbox(applications)
            receiver = ApplicationReceiveRouter(
                applications = RegisteredApplicationLookup { applications.find(it) != null },
                identityResolver = identityResolver,
            )
            dispatcher = GenericSendDispatcher(
                outbox = outbox,
                sender = GenericBatchSender { _, _ -> 0 },
            )
            service = DaemonService(
                configStore = NotisyncdConfigStore(paths.daemonConfig),
                applications = applications,
                receiver = receiver,
                sendResolver = GenericSendResolver(applications, ActionOriginPolicy { true }),
                sendDispatcher = dispatcher,
            )
            server = UnixHttpServer(
                socketPath = paths.socket,
                service = service,
                identityResolver = identityResolver,
                maxHeaderBytes = maxHeaderBytes,
                maxBodyBytes = maxBodyBytes,
                logger = DaemonLogger("INFO", logs),
            )
        }

        fun start() {
            check(running == null)
            running = executor.submit(server::run)
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (!canConnect()) {
                running?.let { if (it.isDone) it.get() }
                check(System.nanoTime() < deadline) { "Unix socket was not created" }
                Thread.onSpinWait()
            }
        }

        private fun canConnect(): Boolean {
            if (!Files.exists(paths.socket)) return false
            return runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(paths.socket))
                }
            }.isSuccess
        }

        fun jsonRequest(method: String, target: String, body: String): RawResponse {
            return request(method, target, body, "application/json")
        }

        fun request(method: String, target: String, body: String, contentType: String): RawResponse {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            return raw(
                "$method $target HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n\r\n$body",
            )
        }

        fun raw(request: String): RawResponse {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(paths.socket))
                val output = ByteBuffer.wrap(request.toByteArray(StandardCharsets.UTF_8))
                while (output.hasRemaining()) channel.write(output)
                channel.shutdownOutput()
                val bytes = ByteArrayOutputStream()
                Channels.newInputStream(channel).copyTo(bytes)
                return RawResponse.parse(bytes.toByteArray())
            }
        }

        fun inbound(id: String, body: ByteArray) = InboundMessage(
            senderId = ClientId("trusted-phone"),
            senderOwnDevice = true,
            typ = MessageType.NOTIFICATION,
            body = body,
            signerEpoch = 4,
            messageId = id,
            deliveryMode = DeliveryMode.WEBSOCKET,
        )

        fun awaitAccessCount(marker: String, expected: Int) {
            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (logs.lines().count { marker in it } < expected) {
                check(System.nanoTime() < deadline) {
                    "expected $expected access records containing '$marker'; logs were:\n$logs"
                }
                Thread.onSpinWait()
            }
        }

        fun awaitStopped() {
            running?.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            server.close()
            dispatcher.close()
            running?.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private data class RawResponse(val status: Int, val body: String, val raw: String) {
        companion object {
            fun parse(bytes: ByteArray): RawResponse {
                val raw = bytes.toString(StandardCharsets.UTF_8)
                val separator = raw.indexOf("\r\n\r\n")
                require(separator >= 0) { "missing HTTP response head: $raw" }
                val status = raw.substringBefore("\r\n").split(' ')[1].toInt()
                return RawResponse(status, raw.substring(separator + 4), raw)
            }
        }
    }
}
