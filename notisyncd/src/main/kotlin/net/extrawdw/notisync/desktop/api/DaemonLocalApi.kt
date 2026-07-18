package net.extrawdw.notisync.desktop.api

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.SocketTimeoutException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.localapi.AcceptedResponse
import net.extrawdw.notisync.localapi.ActionSendRequest
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.CloseSessionRequest
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DismissalRequest
import net.extrawdw.notisync.localapi.EventAckRequest
import net.extrawdw.notisync.localapi.EventCompletionRequest
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.localapi.SessionResponse

interface DaemonLocalApi {
    fun status(): DaemonStatus
    fun createSession(request: CreateSessionRequest): SessionResponse
    fun closeSession(session: SessionResponse)
    fun postNotification(session: SessionResponse, request: NotificationRequest): AcceptedResponse
    fun postRunState(session: SessionResponse, request: RunStateRequest): AcceptedResponse =
        throw UnsupportedOperationException("Run state sends are not implemented by this client")
    fun postDismissal(session: SessionResponse, request: DismissalRequest): AcceptedResponse =
        throw UnsupportedOperationException("dismissal sends are not implemented by this client")
    fun postAction(session: SessionResponse, request: ActionSendRequest): AcceptedResponse =
        throw UnsupportedOperationException("action sends are not implemented by this client")
    fun openEvents(session: SessionResponse): EventStream
    fun acknowledgeEvent(session: SessionResponse, eventId: String)
    fun completeEvent(session: SessionResponse, eventId: String, request: EventCompletionRequest) {
        acknowledgeEvent(session, eventId)
    }
}

interface EventStream : Closeable {
    /** Returns null when the daemon closes the stream. */
    fun next(): LocalEvent?
}

class LocalApiException(
    val status: Int,
    val apiError: ApiError?,
    message: String,
) : java.io.IOException(message)

/**
 * Minimal one-request-per-connection HTTP client for notisyncd's Unix-domain socket. It deliberately
 * does not reuse sockets or implement chunking, mirroring the local API contract.
 */
class UnixDaemonClient(
    private val socketPath: Path,
    private val maxResponseBytes: Int = 2 * 1024 * 1024,
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val connector: () -> SocketChannel = {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            configureBlocking(true)
            connect(UnixDomainSocketAddress.of(socketPath))
        }
    },
) : DaemonLocalApi {
    override fun status(): DaemonStatus = request("GET", "/v1/status", null, null)

    override fun createSession(request: CreateSessionRequest): SessionResponse =
        request("POST", "/v1/sessions", LocalApiJson.encodeToString(request), null)

    override fun closeSession(session: SessionResponse) {
        request<UnitResponse>(
            "DELETE",
            "/v1/sessions",
            LocalApiJson.encodeToString(CloseSessionRequest(session.sessionId)),
            session,
        )
    }

    override fun postNotification(
        session: SessionResponse,
        request: NotificationRequest,
    ): AcceptedResponse = request(
        "POST",
        "/v1/notifications",
        LocalApiJson.encodeToString(request),
        session,
    )

    override fun postRunState(session: SessionResponse, request: RunStateRequest): AcceptedResponse {
        require(request.sessionId == session.sessionId) { "Run state session does not match bearer" }
        return request(
            "POST",
            "/v1/runs",
            LocalApiJson.encodeToString(request),
            session,
        )
    }

    override fun postDismissal(session: SessionResponse, request: DismissalRequest): AcceptedResponse {
        require(request.sessionId == session.sessionId) { "dismissal session does not match bearer" }
        return request(
            "POST",
            "/v1/dismissals",
            LocalApiJson.encodeToString(request),
            session,
        )
    }

    override fun postAction(session: SessionResponse, request: ActionSendRequest): AcceptedResponse {
        require(request.sessionId == session.sessionId) { "action session does not match bearer" }
        return request(
            "POST",
            "/v1/actions",
            LocalApiJson.encodeToString(request),
            session,
        )
    }

    override fun openEvents(session: SessionResponse): EventStream {
        return LocalApiDeadline.run(requestTimeout) { openEventsNow(session) }
    }

    private fun openEventsNow(session: SessionResponse): EventStream {
        val channel = connector()
        try {
            writeRequest(channel, "GET", "/v1/events?sessionId=${encodePathPart(session.sessionId)}", null, session)
            val input = BufferedInputStream(Channels.newInputStream(channel))
            val head = readHead(input)
            if (head.status !in 200..299) {
                val body = readBody(input, head.contentLength)
                throw error(head.status, body)
            }
            val contentType = head.headers["content-type"].orEmpty().substringBefore(';').trim()
            require(contentType == "application/x-ndjson") {
                "daemon event stream returned $contentType instead of application/x-ndjson"
            }
            return NdjsonEventStream(channel, BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)))
        } catch (error: Exception) {
            channel.close()
            throw error
        }
    }

    override fun acknowledgeEvent(session: SessionResponse, eventId: String) {
        request<UnitResponse>(
            "POST",
            "/v1/events/${encodePathPart(eventId)}/ack",
            LocalApiJson.encodeToString(EventAckRequest(session.sessionId)),
            session,
        )
    }

    override fun completeEvent(session: SessionResponse, eventId: String, request: EventCompletionRequest) {
        require(request.sessionId == session.sessionId) { "event completion session does not match bearer" }
        request<UnitResponse>(
            "POST",
            "/v1/events/${encodePathPart(eventId)}/complete",
            LocalApiJson.encodeToString(request),
            session,
        )
    }

    private inline fun <reified T> request(
        method: String,
        target: String,
        body: String?,
        session: SessionResponse?,
    ): T = LocalApiDeadline.run(requestTimeout) {
        connector().use { channel ->
            writeRequest(channel, method, target, body, session)
            val input = BufferedInputStream(Channels.newInputStream(channel))
            val head = readHead(input)
            val responseBody = readBody(input, head.contentLength)
            if (head.status !in 200..299) throw error(head.status, responseBody)
            if (T::class == UnitResponse::class && responseBody.isBlank()) {
                @Suppress("UNCHECKED_CAST")
                return@use UnitResponse as T
            }
            LocalApiJson.decodeFromString(responseBody)
        }
    }

    private fun writeRequest(
        channel: SocketChannel,
        method: String,
        target: String,
        body: String?,
        session: SessionResponse?,
    ) {
        val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val request = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Accept: application/json\r\n")
            append("Connection: close\r\n")
            session?.let { append("Authorization: Bearer ").append(it.bearerToken).append("\r\n") }
            if (body != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        writeFully(channel, request)
        if (bytes.isNotEmpty()) writeFully(channel, bytes)
    }

    private fun writeFully(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readHead(input: InputStream): ResponseHead {
        val statusLine = readAsciiLine(input, 8 * 1024) ?: throw EOFException("daemon closed before status")
        val parts = statusLine.split(' ', limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("HTTP/1.")) { "malformed status line" }
        val status = parts[1].toIntOrNull() ?: error("malformed HTTP status")
        val headers = linkedMapOf<String, String>()
        var total = statusLine.length
        while (true) {
            val line = readAsciiLine(input, 16 * 1024) ?: throw EOFException("truncated response headers")
            total += line.length
            require(total <= 64 * 1024) { "response headers too large" }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            require(separator > 0) { "malformed response header" }
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()
        require(length == null || length in 0..maxResponseBytes) { "response body too large" }
        require(!headers["transfer-encoding"].orEmpty().contains("chunked", ignoreCase = true)) {
            "chunked local responses are unsupported"
        }
        return ResponseHead(status, headers, length)
    }

    private fun readBody(input: InputStream, length: Int?): String {
        val bytes = if (length != null) {
            input.readNBytes(length).also { require(it.size == length) { "truncated response body" } }
        } else {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= maxResponseBytes) { "response body too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun error(status: Int, body: String): LocalApiException {
        val parsed = runCatching { LocalApiJson.decodeFromString<ApiError>(body) }.getOrNull()
        return LocalApiException(status, parsed, parsed?.message ?: "daemon returned HTTP $status")
    }

    private fun readAsciiLine(input: InputStream, limit: Int): String? {
        val output = java.io.ByteArrayOutputStream()
        while (output.size() <= limit) {
            val byte = input.read()
            if (byte < 0) return if (output.size() == 0) null else throw EOFException("truncated line")
            if (byte == '\n'.code) {
                val raw = output.toByteArray()
                val size = if (raw.lastOrNull() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, size, StandardCharsets.US_ASCII)
            }
            output.write(byte)
        }
        throw IllegalArgumentException("HTTP line too long")
    }

    private fun encodePathPart(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private data class ResponseHead(
        val status: Int,
        val headers: Map<String, String>,
        val contentLength: Int?,
    )

    @kotlinx.serialization.Serializable
    private object UnitResponse

    private class NdjsonEventStream(
        private val channel: SocketChannel,
        private val reader: BufferedReader,
    ) : EventStream {
        override fun next(): LocalEvent? {
            while (true) {
                val line = reader.readLine() ?: return null
                if (line.isBlank()) continue
                return LocalApiJson.decodeFromString(line)
            }
        }

        override fun close() {
            // Another thread is normally blocked in BufferedReader.readLine() and owns its lock.
            // Closing the channel first wakes that read; closing the reader first waits until the
            // daemon's next 15-second heartbeat and makes nsrun appear to hang after child exit.
            runCatching { channel.close() }
            runCatching { reader.close() }
        }
    }
}

/** Hard wall-clock deadline for local control requests; interrupting SocketChannel I/O closes it. */
internal object LocalApiDeadline {
    private val sequence = AtomicLong()
    private val executor = Executors.newCachedThreadPool { operation ->
        Thread(operation, "notisync-local-api-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }

    fun <T> run(timeout: Duration, operation: () -> T): T {
        require(!timeout.isNegative && !timeout.isZero) { "local API timeout must be positive" }
        val future = executor.submit(Callable(operation))
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeoutError: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("notisyncd did not respond within ${timeout.toMillis()} ms").apply {
                initCause(timeoutError)
            }
        } catch (execution: java.util.concurrent.ExecutionException) {
            throw execution.cause ?: execution
        }
    }
}
