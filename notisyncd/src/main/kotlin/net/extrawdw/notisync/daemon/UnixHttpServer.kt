package net.extrawdw.notisync.daemon

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.localapi.ActionSendRequest
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.CloseSessionRequest
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DismissalRequest
import net.extrawdw.notisync.localapi.EventAckRequest
import net.extrawdw.notisync.localapi.EventCompletionRequest
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

class UnixHttpServer(
    private val socketPath: Path,
    private val service: DaemonService,
    private val identityResolver: ProcessIdentityResolver = ProcessIdentityResolver(),
    private val maxHeaderBytes: Int = 64 * 1024,
    private val maxBodyBytes: Int = 1024 * 1024,
    private val requestReadTimeoutMillis: Int = 15_000,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listenerClosed = AtomicBoolean(false)
    private val clients = Executors.newVirtualThreadPerTaskExecutor()
    private val activeSockets = ConcurrentHashMap.newKeySet<AFUNIXSocket>()
    private lateinit var server: AFUNIXServerSocket
    private var daemonUid: Long = -1

    init {
        require(maxHeaderBytes > 0) { "maxHeaderBytes must be positive" }
        require(maxBodyBytes >= 0) { "maxBodyBytes must not be negative" }
        require(requestReadTimeoutMillis > 0) { "requestReadTimeoutMillis must be positive" }
    }

    fun run() {
        check(!closed.get()) { "Unix HTTP server is already closed" }
        require(!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
            "socket path already exists: $socketPath"
        }
        daemonUid = identityResolver.currentUid(requireNotNull(socketPath.parent))
        server = AFUNIXServerSocket.newInstance()
        try {
            server.bind(AFUNIXSocketAddress.of(socketPath.toFile()))
            setSocketPermissions()
            while (!closed.get() && !service.isStopping()) {
                val socket = try {
                    server.accept()
                } catch (error: java.net.SocketException) {
                    if (closed.get() || service.isStopping()) break else throw error
                }
                activeSockets += socket
                try {
                    clients.submit { handle(socket) }
                } catch (error: Throwable) {
                    activeSockets -= socket
                    runCatching { socket.close() }
                    throw error
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeListener()
        activeSockets.forEach { runCatching { it.close() } }
        clients.shutdown()
        if (!clients.awaitTermination(CLIENT_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
            clients.shutdownNow()
        }
        deleteOwnedSocket()
    }

    /** May run on a request worker: unlike [close], this never waits for that worker to exit. */
    private fun closeListener() {
        if (!listenerClosed.compareAndSet(false, true)) return
        if (::server.isInitialized) runCatching { server.close() }
    }

    private fun handle(socket: AFUNIXSocket) {
        socket.use {
            it.soTimeout = requestReadTimeoutMillis
            val output = BufferedOutputStream(it.getOutputStream())
            try {
                val peer = identityResolver.resolve(it)
                if (peer.uid != daemonUid) {
                    writeJson(output, 403, ApiError("wrong_uid", "local API access is restricted to the daemon uid"))
                    return
                }
                val request = readRequest(BufferedInputStream(it.getInputStream()))
                if (request.path == "/v1/events" && request.method == "GET") {
                    streamEvents(output, request, peer)
                } else {
                    val response = route(request, peer)
                    writeResponse(output, response)
                }
            } catch (error: Throwable) {
                if (error is java.net.SocketTimeoutException) {
                    runCatching { writeError(output, error) }
                    return
                }
                if (error is java.net.SocketException || error is java.io.IOException && closed.get()) return
                runCatching { writeError(output, error) }
            } finally {
                runCatching { output.flush() }
                activeSockets -= it
                if (service.isStopping()) closeListener()
            }
        }
    }

    private fun route(request: HttpRequest, peer: LocalPeer): HttpResponse = when {
        request.method == "GET" && request.path == "/v1/status" -> json(200, service.status())
        request.method == "GET" && request.path == "/v1/config" -> json(200, service.config())
        request.method == "PATCH" && request.path == "/v1/config" ->
            json(200, service.patchConfig(request.decode()))
        request.method == "GET" && request.path == "/v1/pairing" -> json(200, service.pairingPayload())
        request.method == "POST" && request.path == "/v1/pairing/inspect" ->
            json(200, service.inspectPairing(request.decode<PairingInspectRequest>()))
        request.method == "POST" && request.path == "/v1/pairing/accept" ->
            json(200, service.acceptPairing(request.decode<PairingAcceptRequest>()))
        request.method == "GET" && request.path == "/v1/devices" -> json(200, service.devices())
        request.method == "POST" && DEVICE_ACTION.matches(request.path) -> {
            val clientId = decodeComponent(DEVICE_ACTION.matchEntire(request.path)!!.groupValues[1])
            json(200, service.deviceAction(clientId, request.decode<DeviceActionRequest>()))
        }
        request.method == "POST" && request.path == "/v1/trust-store/quarantine" ->
            json(200, service.quarantineAction(request.decode<QuarantineActionRequest>()))
        request.method == "POST" && request.path == "/v1/sessions" ->
            json(201, service.createSession(peer, request.decode<CreateSessionRequest>()))
        request.method == "DELETE" && request.path == "/v1/sessions" -> {
            val close = request.decode<CloseSessionRequest>()
            service.closeSession(peer, request.bearer, close.sessionId)
            empty(204)
        }
        request.method == "POST" && request.path == "/v1/notifications" ->
            json(202, service.postNotification(peer, request.bearer, request.decode<NotificationRequest>()))
        request.method == "POST" && request.path == "/v1/runs" ->
            json(202, service.postRunState(peer, request.bearer, request.decode<RunStateRequest>()))
        request.method == "POST" && request.path == "/v1/dismissals" -> runBlocking {
            json(202, service.postDismissal(peer, request.bearer, request.decode<DismissalRequest>()))
        }
        request.method == "POST" && request.path == "/v1/actions" -> runBlocking {
            json(202, service.postAction(peer, request.bearer, request.decode<ActionSendRequest>()))
        }
        request.method == "POST" && EVENT_ACK.matches(request.path) -> {
            val eventId = decodeComponent(EVENT_ACK.matchEntire(request.path)!!.groupValues[1])
            service.acknowledgeEvent(peer, request.bearer, eventId, request.decode<EventAckRequest>())
            empty(204)
        }
        request.method == "POST" && EVENT_COMPLETE.matches(request.path) -> runBlocking {
            val eventId = decodeComponent(EVENT_COMPLETE.matchEntire(request.path)!!.groupValues[1])
            service.completeEvent(peer, request.bearer, eventId, request.decode<EventCompletionRequest>())
            empty(204)
        }
        request.method == "POST" && request.path == "/v1/shutdown" -> {
            service.requestShutdown()
            empty(204)
        }
        else -> throw HttpFailure(404, "not_found", "unknown local API endpoint")
    }

    private fun streamEvents(output: BufferedOutputStream, request: HttpRequest, peer: LocalPeer) {
        val sessionId = request.query["sessionId"] ?: throw HttpFailure(400, "missing_session", "sessionId is required")
        // Authenticate before committing the streaming response status.
        service.awaitEvent(peer, request.bearer, sessionId, 0)
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-ndjson\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()
        try {
            var lastEventId: String? = null
            var lastHeartbeat = System.currentTimeMillis()
            while (!closed.get() && !service.isStopping()) {
                val event = service.awaitEvent(peer, request.bearer, sessionId, 1_000)
                if (event != null && event.id != lastEventId) {
                    output.write((LocalApiJson.encodeToString(event) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    lastEventId = event.id
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= 15_000) {
                    val heartbeat = LocalEvent(
                        id = "heartbeat-${UUID.randomUUID()}",
                        type = LocalEventType.HEARTBEAT,
                        sessionId = sessionId,
                        createdAtEpochMillis = now,
                    )
                    output.write((LocalApiJson.encodeToString(heartbeat) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    lastHeartbeat = now
                }
            }
        } catch (_: Exception) {
            // The response is already committed. EOF, a dead/reused process identity, or a client
            // disconnect simply terminates this close-delimited stream; never append a second HTTP head.
        }
    }

    private fun readRequest(input: InputStream): HttpRequest {
        var consumed = 0
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE).also { consumed += it.length + 2 }
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1") {
            throw HttpFailure(400, "bad_request_line", "expected an HTTP/1.1 request line")
        }
        val method = parts[0]
        if (method !in ALLOWED_METHODS) throw HttpFailure(405, "method_not_allowed", "unsupported method")
        val rawTarget = parts[1]
        if (!rawTarget.startsWith('/') || rawTarget.length > 4096) {
            throw HttpFailure(400, "bad_target", "invalid request target")
        }
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_LINE)
            consumed += line.length + 2
            if (consumed > maxHeaderBytes) throw HttpFailure(431, "headers_too_large", "request headers are too large")
            if (line.isEmpty()) break
            if (line.firstOrNull()?.isWhitespace() == true) {
                throw HttpFailure(400, "folded_header", "folded headers are not supported")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpFailure(400, "bad_header", "malformed request header")
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name in headers) throw HttpFailure(400, "duplicate_header", "duplicate $name header")
            headers[name] = value
        }
        if (headers["transfer-encoding"] != null) {
            throw HttpFailure(400, "transfer_encoding", "chunked requests are not supported")
        }
        val lengthText = headers["content-length"]
        val requiresLength = method == "POST" || method == "PATCH" || method == "DELETE"
        if (requiresLength && lengthText == null) {
            throw HttpFailure(411, "length_required", "Content-Length is required")
        }
        val length = lengthText?.toIntOrNull()
            ?: if (lengthText == null) 0 else throw HttpFailure(400, "bad_length", "invalid Content-Length")
        if (length !in 0..maxBodyBytes) throw HttpFailure(413, "body_too_large", "request body is too large")
        if (length > 0 && headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
            throw HttpFailure(415, "content_type", "request body must be application/json")
        }
        val body = input.readNBytes(length)
        if (body.size != length) throw HttpFailure(400, "truncated_body", "request body is truncated")
        val targetParts = rawTarget.split('?', limit = 2)
        val query = targetParts.getOrNull(1).orEmpty().split('&').filter(String::isNotBlank).associate { entry ->
            val pair = entry.split('=', limit = 2)
            decodeComponent(pair[0]) to decodeComponent(pair.getOrElse(1) { "" })
        }
        val authorization = headers["authorization"]
        val bearer = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
        if (authorization != null && bearer.isNullOrBlank()) {
            throw HttpFailure(401, "bad_authorization", "expected a Bearer token")
        }
        return HttpRequest(method, targetParts[0], query, headers, body, bearer)
    }

    private fun readAsciiLine(input: InputStream, limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val next = input.read()
            if (next < 0) throw EOFException("unexpected end of HTTP headers")
            if (next == '\n'.code) {
                val raw = bytes.toByteArray()
                if (raw.lastOrNull() != '\r'.code.toByte()) {
                    throw HttpFailure(400, "line_ending", "HTTP lines must end in CRLF")
                }
                return String(raw, 0, raw.size - 1, StandardCharsets.US_ASCII)
            }
            bytes.write(next)
        }
        throw HttpFailure(431, "line_too_long", "HTTP header line is too long")
    }

    private fun writeError(output: OutputStream, error: Throwable) {
        val failure = when (error) {
            is HttpFailure -> error
            is java.net.SocketTimeoutException ->
                HttpFailure(408, "request_timeout", "local HTTP request timed out")
            is LocalAuthorizationException -> HttpFailure(401, "unauthorized", error.message ?: "unauthorized")
            is LocalConflictException -> HttpFailure(409, "conflict", error.message ?: "conflict")
            is PeerUnavailableException -> HttpFailure(503, "peer_unavailable", error.message ?: "peer unavailable", true)
            is SerializationException, is IllegalArgumentException ->
                HttpFailure(400, "invalid_request", error.message ?: "invalid request")
            else -> HttpFailure(500, "internal_error", "local daemon request failed")
        }
        writeJson(output, failure.status, ApiError(failure.code, failure.message, failure.retryable))
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val reason = REASONS[response.status] ?: "Response"
        output.write(
            ("HTTP/1.1 ${response.status} $reason\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${response.body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
        )
        output.write(response.body)
        output.flush()
    }

    private inline fun <reified T> writeJson(output: OutputStream, status: Int, value: T) =
        writeResponse(output, json(status, value))

    private inline fun <reified T> json(status: Int, value: T): HttpResponse = HttpResponse(
        status,
        "application/json; charset=utf-8",
        LocalApiJson.encodeToString(value).toByteArray(StandardCharsets.UTF_8),
    )

    private fun empty(status: Int) = HttpResponse(status, "application/json", ByteArray(0))

    private inline fun <reified T> HttpRequest.decode(): T {
        if (body.isEmpty()) throw HttpFailure(400, "missing_body", "JSON request body is required")
        return LocalApiJson.decodeFromString(body.toString(StandardCharsets.UTF_8))
    }

    private fun setSocketPermissions() {
        runCatching { Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun deleteOwnedSocket() {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(socketPath)) return
        val isSocket = runCatching {
            val mode = (Files.getAttribute(socketPath, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
            mode and 0xF000 == 0xC000
        }.getOrDefault(false)
        if (isSocket) runCatching { Files.deleteIfExists(socketPath) }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val bearer: String?,
    )

    private data class HttpResponse(val status: Int, val contentType: String, val body: ByteArray)

    private class HttpFailure(
        val status: Int,
        val code: String,
        override val message: String,
        val retryable: Boolean = false,
    ) : RuntimeException(message)

    private companion object {
        const val CLIENT_SHUTDOWN_SECONDS = 5L
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADER_LINE = 16 * 1024
        val ALLOWED_METHODS = setOf("GET", "POST", "PATCH", "DELETE")
        val DEVICE_ACTION = Regex("^/v1/devices/([^/]+)/actions$")
        val EVENT_ACK = Regex("^/v1/events/([^/]+)/ack$")
        val EVENT_COMPLETE = Regex("^/v1/events/([^/]+)/complete$")
        val REASONS = mapOf(
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            204 to "No Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            408 to "Request Timeout",
            409 to "Conflict",
            411 to "Length Required",
            413 to "Content Too Large",
            415 to "Unsupported Media Type",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
        )
    }
}

private fun decodeComponent(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
