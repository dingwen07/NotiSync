package net.extrawdw.notisync.protocol

/** A read-only rendering of a canonical unsigned Git commit payload. [bytes] are never reconstructed. */
data class GitCommitPayload(
    val bytes: ByteArray,
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val headers: List<GitCommitHeader>,
)

data class GitCommitHeader(val name: String, val value: String)

/** Strict parser shared by the desktop qualification path and the Android review surface. */
object GitCommitPayloadParser {
    fun parse(bytes: ByteArray): GitCommitPayload {
        require(bytes.isNotEmpty()) { "commit payload is empty" }
        require(bytes.none { it == 0.toByte() }) { "commit payload contains NUL" }

        val separator = findHeaderSeparator(bytes)
        require(separator >= 0) { "commit payload has no header/message separator" }
        val headerText = bytes.copyOfRange(0, separator).decodeToString(throwOnInvalidSequence = true)
        val message = bytes.copyOfRange(separator + 2, bytes.size).decodeToString(throwOnInvalidSequence = true)
        val physical = headerText.split('\n')
        require(physical.isNotEmpty() && physical.first().startsWith("tree ")) {
            "commit payload must begin with tree"
        }

        val logical = mutableListOf<GitCommitHeader>()
        physical.forEach { line ->
            require('\r' !in line) { "commit header contains carriage return" }
            if (line.startsWith(' ')) {
                require(logical.isNotEmpty()) { "orphaned header continuation" }
                val previous = logical.removeLast()
                logical += previous.copy(value = previous.value + "\n" + line.substring(1))
            } else {
                val split = line.indexOf(' ')
                require(split > 0 && split < line.lastIndex) { "malformed commit header" }
                val name = line.substring(0, split)
                require(HEADER_NAME.matches(name)) { "invalid commit header name" }
                require(!name.startsWith("gpgsig")) { "commit payload already contains a signature" }
                logical += GitCommitHeader(name, line.substring(split + 1))
            }
        }

        val trees = logical.filter { it.name == "tree" }
        require(trees.size == 1 && logical.first().name == "tree" && OBJECT_ID.matches(trees.single().value)) {
            "invalid tree object id"
        }
        val parents = logical.filter { it.name == "parent" }
        require(parents.all { OBJECT_ID.matches(it.value) }) { "invalid parent object id" }
        val authors = logical.filter { it.name == "author" }
        val committers = logical.filter { it.name == "committer" }
        require(authors.size == 1) { "commit payload requires exactly one author" }
        require(committers.size == 1) { "commit payload requires exactly one committer" }
        require(IDENTITY.matches(authors.single().value)) { "invalid author identity/time" }
        require(IDENTITY.matches(committers.single().value)) { "invalid committer identity/time" }

        return GitCommitPayload(
            bytes = bytes,
            treeId = trees.single().value,
            parentIds = parents.map(GitCommitHeader::value),
            author = authors.single().value,
            committer = committers.single().value,
            message = message,
            headers = logical,
        )
    }

    private fun findHeaderSeparator(bytes: ByteArray): Int {
        for (index in 0 until bytes.size - 1) {
            if (bytes[index] == '\n'.code.toByte() && bytes[index + 1] == '\n'.code.toByte()) return index
        }
        return -1
    }

    private val HEADER_NAME = Regex("[A-Za-z0-9-]+")
    private val OBJECT_ID = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    private val IDENTITY = Regex(".+ <[^\\r\\n<>]+> [0-9]+ [+-][0-9]{4}")
}
