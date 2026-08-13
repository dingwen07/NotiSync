package net.extrawdw.apps.notisync.seal

/** Shared presentation-only projections; none of these values are added to the signed wire request. */
internal fun String.commitSubject(): String = lineSequence().firstOrNull().orEmpty().trim()

internal fun String.commitBody(): String = lineSequence().drop(1).joinToString("\n").trim()

internal fun String.workingDirectoryName(): String =
    trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { this }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
