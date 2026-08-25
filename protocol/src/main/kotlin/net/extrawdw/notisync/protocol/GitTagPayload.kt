package net.extrawdw.notisync.protocol

/** A read-only rendering of the canonical payload Git passes to GPG for an annotated tag. */
data class GitTagPayload(
    override val bytes: ByteArray,
    val objectId: String,
    val objectType: String,
    val tagName: String,
    val tagger: String,
    val message: String,
) : GitSigningPayload {
    override val objectKind: OpenPgpObjectKind = OpenPgpObjectKind.GIT_TAG
}

/** Strict parser shared by the desktop qualification path and the Android review surface. */
object GitTagPayloadParser {
    fun parse(bytes: ByteArray): GitTagPayload {
        require(bytes.isNotEmpty()) { "tag payload is empty" }
        require(bytes.none { it == 0.toByte() }) { "tag payload contains NUL" }

        val separator = findHeaderSeparator(bytes)
        require(separator >= 0) { "tag payload has no header/message separator" }
        val headerText = bytes.copyOfRange(0, separator).decodeToString(throwOnInvalidSequence = true)
        val message = bytes.copyOfRange(separator + 2, bytes.size).decodeToString(throwOnInvalidSequence = true)
        val physical = headerText.split('\n')
        require(physical.size == REQUIRED_HEADERS.size) { "tag payload has an unexpected header shape" }

        val headers = physical.map { line ->
            require('\r' !in line) { "tag header contains carriage return" }
            val split = line.indexOf(' ')
            require(split > 0 && split < line.lastIndex) { "malformed tag header" }
            line.substring(0, split) to line.substring(split + 1)
        }
        require(headers.map { it.first } == REQUIRED_HEADERS) {
            "tag payload headers are missing or out of order"
        }

        val objectId = headers[0].second
        val objectType = headers[1].second
        val tagName = headers[2].second
        val tagger = headers[3].second
        require(OBJECT_ID.matches(objectId)) { "invalid tagged object id" }
        require(objectType in OBJECT_TYPES) { "invalid tagged object type" }
        require(tagName.isNotBlank() && tagName.none(Char::isISOControl)) { "invalid tag name" }
        require(IDENTITY.matches(tagger)) { "invalid tagger identity/time" }

        return GitTagPayload(
            bytes = bytes,
            objectId = objectId,
            objectType = objectType,
            tagName = tagName,
            tagger = tagger,
            message = message,
        )
    }

    private fun findHeaderSeparator(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == '\n'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) return index
        }
        return -1
    }

    private val REQUIRED_HEADERS = listOf("object", "type", "tag", "tagger")
    private val OBJECT_TYPES = setOf("blob", "tree", "commit", "tag")
    private val OBJECT_ID = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    private val IDENTITY = Regex(".+ <[^\\r\\n<>]+> [0-9]+ [+-][0-9]{4}")
}
