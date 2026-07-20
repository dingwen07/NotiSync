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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.localapi.ApplicationEventAckRequest
import net.extrawdw.notisync.localapi.ApplicationEventCompletionRequest
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

class UnixHttpServer(
    private val socketPath: Path,
    private val service: DaemonService,
    private val identityResolver: ProcessIdentityResolver = ProcessIdentityResolver(),
    private val maxHeaderBytes: Int = 64 * 1024,
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
    private val requestReadTimeoutMillis: Int = 15_000,
    private val logger: DaemonLogger = DaemonLogger("WARN"),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listenerClosed = AtomicBoolean(false)
    private val clients = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("notisyncd-http-", 0).factory(),
    )
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
            val startedAt = System.nanoTime()
            var peer: LocalPeer? = null
            var executable: ProcessExecutable? = null
            var request: HttpRequest? = null
            var responseStatus: Int? = null
            var accessLogged = false
            try {
                peer = identityResolver.resolve(it)
                executable = peer.pid?.let(identityResolver::executable)
                if (peer.uid != daemonUid) {
                    responseStatus = 403
                    writeJson(output, 403, ApiError("wrong_uid", "local API access is restricted to the daemon uid"))
                    return
                }
                val verifiedPeer = checkNotNull(peer)
                val parsedRequest = readRequest(BufferedInputStream(it.getInputStream()))
                request = parsedRequest
                if (parsedRequest.path == "/v1/receive" && parsedRequest.method == "POST") {
                    streamReceive(output, parsedRequest, verifiedPeer) {
                        responseStatus = 200
                        accessLogged = true
                        logAccess(
                            parsedRequest,
                            verifiedPeer,
                            executable,
                            200,
                            System.nanoTime() - startedAt,
                        )
                    }
                } else {
                    val response = route(parsedRequest, verifiedPeer)
                    responseStatus = response.status
                    writeResponse(output, response)
                }
            } catch (error: Throwable) {
                if (error is java.net.SocketTimeoutException) {
                    val failure = error.toHttpFailure()
                    responseStatus = failure.status
                    runCatching { writeError(output, failure) }
                    return
                }
                if (error is java.net.SocketException || error is java.io.IOException && closed.get()) return
                val failure = error.toHttpFailure()
                responseStatus = failure.status
                runCatching { writeError(output, failure) }
            } finally {
                runCatching { output.flush() }
                val completedRequest = request
                val completedPeer = peer
                val completedStatus = responseStatus
                if (!accessLogged && completedRequest != null && completedPeer != null && completedStatus != null) {
                    logAccess(
                        completedRequest,
                        completedPeer,
                        executable,
                        completedStatus,
                        System.nanoTime() - startedAt,
                    )
                }
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
        request.method == "PUT" && APPLICATION.matches(request.path) -> {
            val applicationId = decodeComponent(APPLICATION.matchEntire(request.path)!!.groupValues[1])
            json(200, service.registerApplication(applicationId, request.decode<ApplicationRegistrationRequest>()))
        }
        request.method == "GET" && request.path == "/v1/applications" -> json(200, service.applications())
        request.method == "DELETE" && APPLICATION.matches(request.path) -> {
            val applicationId = decodeComponent(APPLICATION.matchEntire(request.path)!!.groupValues[1])
            service.removeApplication(applicationId)
            empty(204)
        }
        request.method == "POST" && request.path == "/v1/send" -> sendResponse(request)
        request.method == "DELETE" && request.path == "/v1/receive" -> {
            service.unregisterReceive(peer, request.decode<ReceiveRequest>())
            empty(204)
        }
        request.method == "POST" && EVENT_ACK.matches(request.path) -> {
            val eventId = decodeComponent(EVENT_ACK.matchEntire(request.path)!!.groupValues[1])
            val ack = request.decode<ApplicationEventAckRequest>()
            service.acknowledgeEvent(ack.applicationId, eventId)
            empty(204)
        }
        request.method == "POST" && EVENT_COMPLETE.matches(request.path) -> {
            val eventId = decodeComponent(EVENT_COMPLETE.matchEntire(request.path)!!.groupValues[1])
            service.completeEvent(eventId, request.decode<ApplicationEventCompletionRequest>())
            empty(204)
        }
        request.method == "POST" && request.path == "/v1/shutdown" -> {
            service.requestShutdown()
            empty(204)
        }
        else -> throw HttpFailure(404, "not_found", "unknown local API endpoint")
    }

    private fun sendResponse(request: HttpRequest): HttpResponse {
        return if (request.contentType == NDJSON_CONTENT_TYPE) {
            val lines = request.body.toString(StandardCharsets.UTF_8).split('\n').toMutableList().apply {
                if (lastOrNull()?.isEmpty() == true) removeLast()
            }
            if (lines.isEmpty()) throw HttpFailure(400, "missing_body", "NDJSON send body is empty")
            if (lines.size > MAXIMUM_NDJSON_RECORDS) {
                throw HttpFailure(413, "too_many_records", "NDJSON send contains too many records")
            }
            if (lines.any(String::isBlank)) {
                throw HttpFailure(400, "invalid_ndjson", "NDJSON send must contain one JSON record per line")
            }
            val records = lines.map { line -> LocalApiJson.decodeFromString<SendRequest>(line) }
            val accepted = service.acceptSends(records)
            ndjson(202, accepted)
        } else {
            json(202, service.acceptSends(listOf(request.decode<SendRequest>())).single())
        }
    }

    private fun streamReceive(
        output: BufferedOutputStream,
        request: HttpRequest,
        peer: LocalPeer,
        onEstablished: () -> Unit,
    ) {
        val receive = request.decode<ReceiveRequest>()
        // Registration, filter validation, and replay all happen before committing HTTP 200.
        val handle = service.openReceive(peer, receive)
        var committed = false
        try {
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ndjson\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
            committed = true
            onEstablished()
            var lastHeartbeat = System.currentTimeMillis()
            while (!closed.get() && !service.isStopping() && handle.isAttached()) {
                val event = handle.awaitRecord(1_000)
                if (event != null) {
                    output.write((LocalApiJson.encodeToString(event) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    lastHeartbeat = System.currentTimeMillis()
                    continue
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= 15_000) {
                    val heartbeat = ReceiveRecord(
                        recordType = ReceiveRecordType.HEARTBEAT,
                        applicationId = receive.applicationId,
                        receivedAtEpochMillis = now,
                    )
                    output.write((LocalApiJson.encodeToString(heartbeat) + "\n").toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    lastHeartbeat = now
                }
            }
        } catch (error: Exception) {
            // The response is committed. A dead process or client disconnect simply closes the stream.
            if (!committed) throw error
        } finally {
            // Socket disconnect detaches only this view; its process interest survives until exit/unregister.
            handle.close()
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
        val requiresLength = method == "POST" || method == "PUT" || method == "PATCH"
        if (requiresLength && lengthText == null) {
            throw HttpFailure(411, "length_required", "Content-Length is required")
        }
        val length = lengthText?.toIntOrNull()
            ?: if (lengthText == null) 0 else throw HttpFailure(400, "bad_length", "invalid Content-Length")
        if (length !in 0..maxBodyBytes) throw HttpFailure(413, "body_too_large", "request body is too large")
        val contentType = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        val rawPath = rawTarget.substringBefore('?')
        val allowedContentTypes = if (method == "POST" && rawPath == "/v1/send") {
            setOf(JSON_CONTENT_TYPE, NDJSON_CONTENT_TYPE)
        } else {
            setOf(JSON_CONTENT_TYPE)
        }
        if (length > 0 && contentType !in allowedContentTypes) {
            throw HttpFailure(
                415,
                "content_type",
                if (rawPath == "/v1/send") {
                    "send body must be application/json or application/x-ndjson"
                } else {
                    "request body must be application/json"
                },
            )
        }
        val body = input.readNBytes(length)
        if (body.size != length) throw HttpFailure(400, "truncated_body", "request body is truncated")
        val targetParts = rawTarget.split('?', limit = 2)
        val query = targetParts.getOrNull(1).orEmpty().split('&').filter(String::isNotBlank).associate { entry ->
            val pair = entry.split('=', limit = 2)
            decodeComponent(pair[0]) to decodeComponent(pair.getOrElse(1) { "" })
        }
        return HttpRequest(
            method = method,
            path = targetParts[0],
            rawTarget = rawTarget,
            version = parts[2],
            query = query,
            headers = headers,
            body = body,
            contentType = contentType,
        )
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

    private fun Throwable.toHttpFailure(): HttpFailure =
        when (this) {
            is HttpFailure -> this
            is java.net.SocketTimeoutException ->
                HttpFailure(408, "request_timeout", "local HTTP request timed out")
            is LocalAuthorizationException -> HttpFailure(401, "unauthorized", message ?: "unauthorized")
            is LocalConflictException -> HttpFailure(409, "conflict", message ?: "conflict")
            is GenericSendRejection -> HttpFailure(409, "conflict", message ?: "send was rejected")
            is LocalEventQueueFullException ->
                HttpFailure(503, "local_inbox_full", message ?: "local inbox is full", true)
            is PeerUnavailableException -> HttpFailure(503, "peer_unavailable", message ?: "peer unavailable", true)
            is SerializationException, is IllegalArgumentException ->
                HttpFailure(400, "invalid_request", message ?: "invalid request")
            else -> HttpFailure(500, "internal_error", "local daemon request failed")
        }

    private fun writeError(output: OutputStream, failure: HttpFailure) {
        writeJson(output, failure.status, ApiError(failure.code, failure.message, failure.retryable))
    }

    private fun logAccess(
        request: HttpRequest,
        peer: LocalPeer,
        executable: ProcessExecutable?,
        status: Int,
        elapsedNanos: Long,
    ) {
        val process = executable?.name?.logValue() ?: "unknown"
        val executablePath = executable?.path?.logValue() ?: "unknown"
        val pid = peer.pid?.toString() ?: "unknown"
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        val requestLine = "${request.method} ${request.rawTarget} ${request.version}".logQuotedValue()
        val message = "pid=$pid process=$process executable=$executablePath " +
            "$requestLine $status ${elapsedMillis}ms"
        when {
            status >= 500 -> logger.error(message)
            status >= 400 -> logger.warn(message)
            request.method == "GET" && request.path == "/v1/status" && request.query["probe"] == "ready" ->
                logger.debug(message)
            else -> logger.info(message)
        }
    }

    private fun String.logValue(): String {
        if (isNotEmpty() && all { it.isLetterOrDigit() || it in "._/:-+" }) return this
        return logQuotedValue()
    }

    private fun String.logQuotedValue(): String {
        return buildString {
            append('"')
            this@logQuotedValue.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.isISOControl()) append('?') else append(character)
                }
            }
            append('"')
        }
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

    private inline fun <reified T> ndjson(status: Int, values: List<T>): HttpResponse = HttpResponse(
        status,
        "$NDJSON_CONTENT_TYPE; charset=utf-8",
        values.joinToString(separator = "\n", postfix = "\n") { LocalApiJson.encodeToString(it) }
            .toByteArray(StandardCharsets.UTF_8),
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
        val rawTarget: String,
        val version: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val contentType: String?,
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
        const val MAXIMUM_NDJSON_RECORDS = 1_024
        const val JSON_CONTENT_TYPE = "application/json"
        const val NDJSON_CONTENT_TYPE = "application/x-ndjson"
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        val DEVICE_ACTION = Regex("^/v1/devices/([^/]+)/actions$")
        val APPLICATION = Regex("^/v1/applications/([^/]+)$")
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
