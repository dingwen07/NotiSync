package net.extrawdw.notisync.desktop.api

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
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
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.ApplicationEventAckRequest
import net.extrawdw.notisync.localapi.ApplicationEventCompletionRequest
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest

interface DaemonLocalApi {
    fun status(): DaemonStatus

    fun devices(): DeviceListResponse = throw UnsupportedOperationException("device listing is not implemented")

    fun putApplication(
        applicationId: String,
        request: ApplicationRegistrationRequest,
    ): ApplicationView

    fun listApplications(): ApplicationListResponse

    fun deleteApplication(applicationId: String)

    fun send(request: SendRequest): SendAccepted

    /**
     * Atomically submits a bounded, non-empty sequence in input order. All records must belong to
     * the same registered application.
     */
    fun sendAll(requests: List<SendRequest>): List<SendAccepted>

    fun openReceive(request: ReceiveRequest): ReceiveStream

    /** Removes this process's exact canonical interest without requiring a stream identifier. */
    fun unregisterReceive(request: ReceiveRequest)

    fun ack(applicationId: String, envelopeId: String)

    fun complete(
        applicationId: String,
        envelopeId: String,
        sends: List<SendRequest> = emptyList(),
    )
}

interface ReceiveStream : Closeable {
    /** Returns null when the daemon closes the stream. */
    fun next(): ReceiveRecord?
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
    private val maxRequestBytes: Int = 8 * 1024 * 1024,
    private val maxSendRecords: Int = 1_024,
    private val requestTimeout: Duration = Duration.ofSeconds(3),
    private val connector: () -> SocketChannel = {
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            configureBlocking(true)
            connect(UnixDomainSocketAddress.of(socketPath))
        }
    },
) : DaemonLocalApi {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
        require(maxSendRecords > 0) { "maxSendRecords must be positive" }
    }

    override fun status(): DaemonStatus = jsonRequest("GET", "/v1/status")

    override fun devices(): DeviceListResponse = jsonRequest("GET", "/v1/devices")

    override fun putApplication(
        applicationId: String,
        request: ApplicationRegistrationRequest,
    ): ApplicationView = jsonRequest(
        method = "PUT",
        target = "/v1/applications/${encodePathPart(requireIdentifier(applicationId, "applicationId"))}",
        body = LocalApiJson.encodeToString(request),
    )

    override fun listApplications(): ApplicationListResponse =
        jsonRequest("GET", "/v1/applications")

    override fun deleteApplication(applicationId: String) {
        jsonRequest<UnitResponse>(
            method = "DELETE",
            target = "/v1/applications/${encodePathPart(requireIdentifier(applicationId, "applicationId"))}",
        )
    }

    override fun send(request: SendRequest): SendAccepted {
        requireIdentifier(request.applicationId, "applicationId")
        return jsonRequest(
            method = "POST",
            target = "/v1/send",
            body = LocalApiJson.encodeToString(request),
        )
    }

    override fun sendAll(requests: List<SendRequest>): List<SendAccepted> {
        require(requests.isNotEmpty()) { "sendAll requires at least one record" }
        require(requests.size <= maxSendRecords) {
            "sendAll accepts at most $maxSendRecords records"
        }
        val applicationId = requireIdentifier(requests.first().applicationId, "applicationId")
        require(requests.all { it.applicationId == applicationId }) {
            "all send records must use the same applicationId"
        }
        if (requests.size == 1) return listOf(send(requests.single()))

        val body = requests.joinToString(separator = "\n", postfix = "\n") {
            LocalApiJson.encodeToString(it)
        }
        return ndjsonRequest(body, requests.size)
    }

    override fun openReceive(request: ReceiveRequest): ReceiveStream {
        requireIdentifier(request.applicationId, "applicationId")
        return LocalApiDeadline.run(requestTimeout) { openReceiveNow(request) }
    }

    private fun openReceiveNow(request: ReceiveRequest): ReceiveStream {
        val channel = connector()
        try {
            writeRequest(
                channel = channel,
                method = "POST",
                target = "/v1/receive",
                body = LocalApiJson.encodeToString(request),
                contentType = JSON,
                accept = NDJSON,
            )
            val input = BufferedInputStream(Channels.newInputStream(channel))
            val head = readHead(input)
            if (head.status !in 200..299) {
                val body = readBody(input, head.contentLength)
                throw error(head.status, body)
            }
            requireContentType(head, NDJSON, "receive stream")
            return NdjsonReceiveStream(
                channel,
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)),
            )
        } catch (error: Exception) {
            channel.close()
            throw error
        }
    }

    override fun unregisterReceive(request: ReceiveRequest) {
        requireIdentifier(request.applicationId, "applicationId")
        jsonRequest<UnitResponse>(
            method = "DELETE",
            target = "/v1/receive",
            body = LocalApiJson.encodeToString(request),
        )
    }

    override fun ack(applicationId: String, envelopeId: String) {
        jsonRequest<UnitResponse>(
            method = "POST",
            target = "/v1/events/${encodePathPart(requireIdentifier(envelopeId, "envelopeId"))}/ack",
            body = LocalApiJson.encodeToString(
                ApplicationEventAckRequest(requireIdentifier(applicationId, "applicationId")),
            ),
        )
    }

    override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) {
        val checkedApplicationId = requireIdentifier(applicationId, "applicationId")
        require(sends.size <= maxSendRecords) {
            "completion accepts at most $maxSendRecords send records"
        }
        require(sends.all { it.applicationId == checkedApplicationId }) {
            "all completion sends must use the completing applicationId"
        }
        jsonRequest<UnitResponse>(
            method = "POST",
            target = "/v1/events/${encodePathPart(requireIdentifier(envelopeId, "envelopeId"))}/complete",
            body = LocalApiJson.encodeToString(
                ApplicationEventCompletionRequest(checkedApplicationId, sends),
            ),
        )
    }

    private inline fun <reified T> jsonRequest(
        method: String,
        target: String,
        body: String? = null,
    ): T = LocalApiDeadline.run(requestTimeout) {
        connector().use { channel ->
            writeRequest(channel, method, target, body, JSON, JSON)
            val input = BufferedInputStream(Channels.newInputStream(channel))
            val head = readHead(input)
            val responseBody = readBody(input, head.contentLength)
            if (head.status !in 200..299) throw error(head.status, responseBody)
            if (T::class == UnitResponse::class && responseBody.isBlank()) {
                @Suppress("UNCHECKED_CAST")
                return@use UnitResponse as T
            }
            requireContentType(head, JSON, "response")
            LocalApiJson.decodeFromString(responseBody)
        }
    }

    private fun ndjsonRequest(body: String, expectedRecords: Int): List<SendAccepted> =
        LocalApiDeadline.run(requestTimeout) {
            connector().use { channel ->
                writeRequest(channel, "POST", "/v1/send", body, NDJSON, NDJSON)
                val input = BufferedInputStream(Channels.newInputStream(channel))
                val head = readHead(input)
                val responseBody = readBody(input, head.contentLength)
                if (head.status !in 200..299) throw error(head.status, responseBody)
                requireContentType(head, NDJSON, "send response")
                decodeAcceptanceLines(responseBody, expectedRecords)
            }
        }

    private fun decodeAcceptanceLines(body: String, expectedRecords: Int): List<SendAccepted> {
        val lines = body.split('\n').let { split ->
            if (split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
        }
        require(lines.size == expectedRecords && lines.none { it.isBlank() }) {
            "daemon returned ${lines.count { it.isNotBlank() }} acceptance records; expected $expectedRecords"
        }
        return lines.map { LocalApiJson.decodeFromString(it.trimEnd('\r')) }
    }

    private fun writeRequest(
        channel: SocketChannel,
        method: String,
        target: String,
        body: String?,
        contentType: String,
        accept: String,
    ) {
        val bytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        require(bytes.size <= maxRequestBytes) { "request body too large" }
        val request = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Accept: ").append(accept).append("\r\n")
            append("Connection: close\r\n")
            if (body != null) {
                append("Content-Type: ").append(contentType).append("\r\n")
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

    private fun requireContentType(head: ResponseHead, expected: String, description: String) {
        val actual = head.headers["content-type"].orEmpty().substringBefore(';').trim()
        require(actual == expected) { "$description returned $actual instead of $expected" }
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

    private fun requireIdentifier(value: String, name: String): String =
        value.also { require(it.isNotBlank()) { "$name must not be blank" } }

    private data class ResponseHead(
        val status: Int,
        val headers: Map<String, String>,
        val contentLength: Int?,
    )

    @kotlinx.serialization.Serializable
    private object UnitResponse

    private class NdjsonReceiveStream(
        private val channel: SocketChannel,
        private val reader: BufferedReader,
    ) : ReceiveStream {
        override fun next(): ReceiveRecord? {
            while (true) {
                val line = reader.readLine() ?: return null
                if (line.isBlank()) continue
                return LocalApiJson.decodeFromString(line)
            }
        }

        override fun close() {
            // Another thread is normally blocked in BufferedReader.readLine() and owns its lock.
            // Closing the channel first wakes that read; closing the reader first waits until the
            // daemon's next heartbeat.
            runCatching { channel.close() }
            runCatching { reader.close() }
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val NDJSON = "application/x-ndjson"
    }
}

/** Hard wall-clock deadline for local control requests; interrupting SocketChannel I/O closes it. */
object LocalApiDeadline {
    private val sequence = AtomicLong()
    private val executor = Executors.newCachedThreadPool { operation ->
        Thread(operation, "notisync-local-client-${sequence.incrementAndGet()}").apply { isDaemon = true }
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
