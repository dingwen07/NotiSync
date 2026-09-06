package net.extrawdw.apps.notisync.appicon

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import java.io.IOException
import java.net.URI
import kotlinx.io.readByteArray

internal const val MAX_ICON_SOURCE_BYTES = 20 * 1024 * 1024
internal class IconSourceTooLargeException : IOException()

/** Streaming prevents a large/chunked response from being buffered before the size check. */
internal suspend fun HttpClient.downloadIconBytes(url: String, maxBytes: Int = MAX_ICON_SOURCE_BYTES): ByteArray {
    require(URI(url).scheme.equals("https", ignoreCase = true))
    return prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) throw IOException("Icon request failed")
        val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredSize != null && declaredSize > maxBytes) throw IconSourceTooLargeException()
        val bytes = response.bodyAsChannel().readBoundedIconBytes(maxBytes)
        if (bytes.isEmpty()) throw IOException("Empty icon response")
        bytes
    }
}

internal suspend fun ByteReadChannel.readBoundedIconBytes(maxBytes: Int): ByteArray {
    val bytes = readRemaining(maxBytes.toLong() + 1).use { it.readByteArray() }
    if (bytes.size > maxBytes) throw IconSourceTooLargeException()
    return bytes
}
