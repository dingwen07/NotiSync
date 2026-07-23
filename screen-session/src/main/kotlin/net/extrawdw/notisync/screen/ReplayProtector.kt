package net.extrawdw.notisync.screen

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

interface ReplayProtector {
    /** Returns true exactly once for an unexpired session/token pair. */
    fun record(sessionId: String, routingToken: ByteArray, expiresAtEpochMillis: Long): Boolean
}

open class InMemoryReplayProtector(
    private val clock: Clock = Clock.systemUTC(),
    private val maximumLifetime: Duration = Duration.ofMinutes(5),
    private val maximumEntries: Int = 1_024,
) : ReplayProtector {
    protected val entries = LinkedHashMap<String, Long>()

    @Synchronized
    override fun record(sessionId: String, routingToken: ByteArray, expiresAtEpochMillis: Long): Boolean {
        requireUtf8(sessionId, 1, 128, "sessionId")
        require(routingToken.size == ROUTING_TOKEN_BYTES)
        val now = clock.millis()
        if (expiresAtEpochMillis <= now || expiresAtEpochMillis > now + maximumLifetime.toMillis()) return false
        entries.entries.removeIf { it.value <= now }
        val sessionDigest = replayDigest("session", sessionId.encodeToByteArray())
        val tokenDigest = replayDigest("token", routingToken)
        if (sessionDigest in entries || tokenDigest in entries) return false
        if (entries.size.toLong() + 2 > maximumEntries.toLong() * 2) return false
        // Both independent replay keys are added before the single durable write.
        entries[sessionDigest] = expiresAtEpochMillis
        entries[tokenDigest] = expiresAtEpochMillis
        try {
            afterMutation()
        } catch (error: Throwable) {
            // A caller must never treat an entry as accepted unless its durable mutation succeeds.
            entries.remove(sessionDigest)
            entries.remove(tokenDigest)
            throw error
        }
        return true
    }

    protected open fun afterMutation() = Unit

    protected fun load(values: Map<String, Long>) {
        entries.putAll(values)
    }
}

class FileReplayProtector(
    private val path: Path,
    clock: Clock = Clock.systemUTC(),
    maximumLifetime: Duration = Duration.ofMinutes(5),
    maximumEntries: Int = 1_024,
) : InMemoryReplayProtector(clock, maximumLifetime, maximumEntries) {
    init {
        if (Files.isRegularFile(path)) {
            val parsed = Files.readAllLines(path).mapNotNull { line ->
                val pieces = line.split(' ', limit = 2)
                val expiry = pieces.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val digest = pieces.getOrNull(1)?.takeIf {
                    it.length == 66 && it[1] == ':' && it[0] in setOf('s', 't') &&
                        it.drop(2).all { character -> character in '0'..'9' || character in 'a'..'f' }
                }
                    ?: return@mapNotNull null
                digest to expiry
            }.toMap()
            load(parsed)
        }
    }

    override fun afterMutation() {
        path.parent?.let(Files::createDirectories)
        val temporary = path.resolveSibling(".${path.fileName}.tmp-${UUID.randomUUID()}")
        val body = entries.entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") {
            "${it.value} ${it.key}"
        }
        Files.writeString(temporary, body, StandardCharsets.US_ASCII)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun replayDigest(domain: String, value: ByteArray): String {
    val domainBytes = "notisync-screen-replay/v1/$domain".encodeToByteArray()
    val input = ByteBuffer.allocate(4 + domainBytes.size + 4 + value.size)
        .putInt(domainBytes.size).put(domainBytes).putInt(value.size).put(value).array()
    val prefix = if (domain == "session") "s:" else "t:"
    return prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input))
}
