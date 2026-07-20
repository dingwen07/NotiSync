package net.extrawdw.notisync.cli

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.localapi.ApiError
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.PairingInspectRequest
import net.extrawdw.notisync.localapi.PairingPayloadResponse
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.desktop.api.LocalApiDeadline

class DaemonAdminException(
    val status: Int,
    val error: ApiError?,
) : RuntimeException(error?.message ?: "notisyncd returned HTTP $status")

class DaemonConnectionException(
    val socketPath: Path,
    cause: IOException,
) : IOException("unable to connect to notisyncd", cause) {
    val daemonNotRunning: Boolean =
        !Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS) ||
            cause is ConnectException ||
            cause.message?.contains("connection refused", ignoreCase = true) == true
}

interface DaemonAdministration {
    fun status(): DaemonStatus
    fun config(): DaemonConfigView
    fun patchConfig(patch: DaemonConfigPatch): DaemonConfigView
    fun pairing(): PairingPayloadResponse
    fun inspectPairing(payload: String): PairingCandidate
    fun acceptPairing(request: PairingAcceptRequest): PairingCandidate
    fun devices(): DeviceListResponse
    fun deviceAction(clientId: String, action: DeviceActionRequest): DeviceListResponse
    fun quarantine(request: QuarantineActionRequest): DeviceListResponse
    fun applications(): ApplicationListResponse
    fun removeApplication(applicationId: String)
    fun shutdown()
}

/** One-request-per-connection client for the administrative portion of the local UDS API. */
class DaemonAdminClient(
    private val socketPath: Path,
    private val maximumResponseBytes: Int = 2 * 1024 * 1024,
    private val requestTimeout: Duration = Duration.ofSeconds(3),
) : DaemonAdministration {
    override fun status(): DaemonStatus = request("GET", "/v1/status")
    internal fun readinessStatus(): DaemonStatus = request("GET", "/v1/status?probe=ready")
    override fun config(): DaemonConfigView = request("GET", "/v1/config")
    override fun patchConfig(patch: DaemonConfigPatch): DaemonConfigView =
        request("PATCH", "/v1/config", LocalApiJson.encodeToString(patch))
    override fun pairing(): PairingPayloadResponse = request("GET", "/v1/pairing")
    override fun inspectPairing(payload: String): PairingCandidate =
        request("POST", "/v1/pairing/inspect", LocalApiJson.encodeToString(PairingInspectRequest(payload)))
    override fun acceptPairing(request: PairingAcceptRequest): PairingCandidate =
        request("POST", "/v1/pairing/accept", LocalApiJson.encodeToString(request))
    override fun devices(): DeviceListResponse = request("GET", "/v1/devices")
    override fun deviceAction(clientId: String, action: DeviceActionRequest): DeviceListResponse = request(
        "POST",
        "/v1/devices/${encodePath(clientId)}/actions",
        LocalApiJson.encodeToString(action),
    )
    override fun quarantine(request: QuarantineActionRequest): DeviceListResponse = request(
        "POST",
        "/v1/trust-store/quarantine",
        LocalApiJson.encodeToString(request),
    )
    override fun applications(): ApplicationListResponse = request("GET", "/v1/applications")
    override fun removeApplication(applicationId: String) {
        requestNoContent("DELETE", "/v1/applications/${encodePath(applicationId)}", "{}")
    }
    override fun shutdown() {
        requestNoContent("POST", "/v1/shutdown", "{}")
    }

    private inline fun <reified T> request(method: String, target: String, body: String? = null): T {
        val response = exchange(method, target, body)
        if (response.status !in 200..299) throw response.failure()
        return LocalApiJson.decodeFromString(response.body)
    }

    private fun requestNoContent(method: String, target: String, body: String? = null) {
        val response = exchange(method, target, body)
        if (response.status !in 200..299) throw response.failure()
    }

    private fun exchange(method: String, target: String, body: String?): Response {
        return LocalApiDeadline.run(requestTimeout) { exchangeNow(method, target, body) }
    }

    private fun exchangeNow(method: String, target: String, body: String?): Response {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            try {
                channel.connect(UnixDomainSocketAddress.of(socketPath))
            } catch (failure: IOException) {
                throw DaemonConnectionException(socketPath, failure)
            }
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
            val head = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: localhost\r\nAccept: application/json\r\nConnection: close\r\n")
                if (body != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                }
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            writeFully(channel, head)
            if (bodyBytes.isNotEmpty()) writeFully(channel, bodyBytes)

            val input = BufferedInputStream(Channels.newInputStream(channel))
            val statusLine = readLine(input, 8192)
            val parts = statusLine.split(' ', limit = 3)
            require(parts.size >= 2 && parts[0] == "HTTP/1.1") { "malformed daemon HTTP response" }
            val status = parts[1].toIntOrNull() ?: error("malformed daemon status")
            val headers = linkedMapOf<String, String>()
            var headerBytes = statusLine.length
            while (true) {
                val line = readLine(input, 16 * 1024)
                headerBytes += line.length
                require(headerBytes <= 64 * 1024) { "daemon response headers are too large" }
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                require(separator > 0) { "malformed daemon response header" }
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
            require(!headers["transfer-encoding"].orEmpty().contains("chunked", ignoreCase = true)) {
                "chunked local responses are unsupported"
            }
            val contentLength = headers["content-length"]?.toIntOrNull()
            require(contentLength == null || contentLength in 0..maximumResponseBytes) {
                "daemon response is too large"
            }
            val bytes = if (contentLength != null) {
                input.readNBytes(contentLength).also { require(it.size == contentLength) { "truncated daemon response" } }
            } else {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maximumResponseBytes) { "daemon response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return Response(status, bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun writeFully(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        while (output.size() <= limit) {
            val next = input.read()
            if (next < 0) throw EOFException("notisyncd closed its response early")
            if (next == '\n'.code) {
                val bytes = output.toByteArray()
                val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, StandardCharsets.US_ASCII)
            }
            output.write(next)
        }
        error("daemon HTTP line is too long")
    }

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private data class Response(val status: Int, val body: String) {
        fun failure(): DaemonAdminException = DaemonAdminException(
            status,
            runCatching { LocalApiJson.decodeFromString<ApiError>(body) }.getOrNull(),
        )
    }
}
