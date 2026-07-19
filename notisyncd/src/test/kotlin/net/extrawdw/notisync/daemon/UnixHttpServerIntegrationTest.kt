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
import net.extrawdw.notisync.desktop.config.LlmConfig
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.desktop.config.NSRunConfigStore
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnixHttpServerIntegrationTest {
    @Test
    fun `notisync config changes the daemon device name over the local API`() =
        ServerFixture().use { fixture ->
            val runStore = NSRunConfigStore(fixture.paths.nsrunConfig)
            runStore.save(NSRunConfig(updateIntervalSeconds = 45))
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
            assertTrue(fixture.logs.toString().contains("HTTP PATCH /v1/config -> 200"))
            assertTrue(fixture.logs.toString().contains("HTTP GET /v1/config -> 200"))
            assertTrue(
                fixture.logs.toString().contains(
                    "pid=${ProcessHandle.current().pid()}",
                ),
            )
            assertTrue(fixture.logs.toString().contains("process="))
            assertTrue(fixture.logs.toString().contains("executable="))
            assertTrue(fixture.logs.toString().contains("[notisyncd-http-"))
        }

    @Test
    fun `real socket status and config patch remain strictly separated from nsrun config`() =
        ServerFixture().use { fixture ->
            val secret = "local-run-key-that-must-not-leave-nsrun-conf"
            val runStore = NSRunConfigStore(fixture.paths.nsrunConfig)
            runStore.save(
                NSRunConfig(
                    llm = LlmConfig(
                        baseUrl = "https://llm.invalid/v1",
                        model = "test-model",
                        apiKey = secret,
                    ),
                ),
            )
            val originalRunConfig = Files.readAllBytes(fixture.paths.nsrunConfig)

            fixture.start()
            val statusResponse = fixture.raw("GET /v1/status HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(200, statusResponse.status)
            assertEquals(
                setOf("CAPTURE", "FOREGROUND_CONNECTION", "CAPABILITY_ROUTING_V1"),
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
                "POST /v1/sessions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n\r\n",
            )
            assertError(missingLength, 411, "length_required")

            val chunked = fixture.raw(
                "POST /v1/sessions HTTP/1.1\r\nHost: localhost\r\n" +
                    "Transfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n0\r\n\r\n",
            )
            assertError(chunked, 400, "transfer_encoding")

            val oversized = fixture.raw(
                "POST /v1/sessions HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: application/json\r\nContent-Length: 129\r\n\r\n",
            )
            assertError(oversized, 413, "body_too_large")

            val duplicateLength = fixture.raw(
                "POST /v1/sessions HTTP/1.1\r\nHost: localhost\r\n" +
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
    fun `session bearer isolates socket requests and NDJSON events require explicit acknowledgement`() =
        ServerFixture().use { fixture ->
            fixture.start()
            val client = UnixDaemonClient(fixture.paths.socket)
            val first = client.createSession(CreateSessionRequest("first-client"))
            val second = client.createSession(CreateSessionRequest("second-client"))
            assertTrue(first.sourceKey.startsWith("local:"))
            assertFalse(first.sourceKey == second.sourceKey)

            val generation = 7L
            val notification = NotificationRequest(
                sessionId = first.sessionId,
                generation = generation,
                phase = NotificationPhase.BLOCKED,
                title = "Input required",
                text = "Continue?",
                silent = false,
                ongoing = true,
                clearable = false,
                actions = listOf(
                    LocalNotificationAction(
                        id = "input",
                        title = "Input",
                        kind = NotificationActionKind.REMOTE_INPUT,
                        generation = generation,
                        remoteInputLabel = "Reply",
                    ),
                ),
            )
            assertTrue(client.postNotification(first, notification).id.isNotBlank())

            val crossSession = assertThrows(LocalApiException::class.java) {
                client.postNotification(
                    second.copy(bearerToken = first.bearerToken),
                    notification.copy(sessionId = second.sessionId),
                )
            }
            assertEquals(401, crossSession.status)
            assertEquals("unauthorized", crossSession.apiError?.code)

            val wireAction = fixture.sessions.registeredActions(first.sessionId).single()
            assertTrue(
                fixture.sessions.deliverWireAction(
                    sourceKey = first.sourceKey,
                    actionIndex = wireAction.index,
                    actionTitle = wireAction.title,
                    inputText = "continue",
                    senderClientId = "trusted-phone",
                    senderIsTrustedOwnDevice = true,
                    actionGeneration = wireAction.generation,
                    actionToken = wireAction.actionToken,
                    relayMessageId = "socket-integration-action",
                ),
            )

            client.openEvents(first).use { stream ->
                val event = stream.next()
                requireNotNull(event)
                assertEquals(LocalEventType.ACTION, event.type)
                assertEquals(first.sessionId, event.sessionId)
                assertEquals(generation, event.generation)
                assertEquals("input", event.actionId)
                assertEquals("continue", event.inputText)
                client.acknowledgeEvent(first, event.id)
            }

            val peer = fixture.localPeer(first.peerIdentityVerified)
            assertNull(fixture.sessions.awaitEvent(first.sessionId, first.bearerToken, peer, 0))

            val badBearer = assertThrows(LocalApiException::class.java) {
                client.openEvents(first.copy(bearerToken = "not-the-session-bearer"))
            }
            assertEquals(401, badBearer.status)
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
        val sessions: LocalSessionRegistry
        private val dispatcher: NotificationDispatcher
        private val service: DaemonService
        private val server: UnixHttpServer
        private val executor = Executors.newSingleThreadExecutor()
        private var running: Future<*>? = null

        init {
            PrivateFiles.ensureDirectory(root)
            sessions = LocalSessionRegistry(identityResolver)
            dispatcher = NotificationDispatcher(
                sessions = sessions,
                outbox = InMemoryNotificationOutbox(),
                sender = object : NotificationMeshSender {
                    override val clientId = ClientId("local-test-daemon")
                    override suspend fun send(item: PendingNotification) = Unit
                },
            )
            service = DaemonService(NotisyncdConfigStore(paths.daemonConfig), sessions, dispatcher)
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
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            return raw(
                "$method $target HTTP/1.1\r\nHost: localhost\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n$body",
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

        fun localPeer(verified: Boolean): LocalPeer {
            val uid = identityResolver.currentUid(root)
            if (!verified) return LocalPeer(uid, null, null)
            val pid = ProcessHandle.current().pid()
            return LocalPeer(uid, pid, requireNotNull(identityResolver.startTime(pid)))
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
