package net.extrawdw.notisync.gpg

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import net.extrawdw.notisync.protocol.OpenPgpSignSync

/** Best-effort comparison prompt that bypasses Git's captured stdout/stderr without requiring a TTY. */
internal class SigningRequestNotice(
    private val openTerminal: () -> OutputStream? = ::openControllingTerminal,
) {
    fun show(request: OpenPgpSignSync): Boolean = runCatching {
        val terminal = openTerminal() ?: return false
        OutputStreamWriter(terminal, StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(comparisonLine(request))
            writer.flush()
        }
        true
    }.getOrDefault(false)
}

internal fun comparisonLine(request: OpenPgpSignSync): String =
    "NotiSync Seal: compare verification code ${request.payloadSha256.toLowerHex().take(7)} " +
        "on your phone (request ${request.requestId.take(8)})"

private fun openControllingTerminal(): OutputStream? {
    val target = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "CONOUT$"
    } else {
        "/dev/tty"
    }
    return runCatching { FileOutputStream(target) }.getOrNull()
}

private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
