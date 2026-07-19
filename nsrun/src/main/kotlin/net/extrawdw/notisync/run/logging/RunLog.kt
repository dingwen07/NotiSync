package net.extrawdw.notisync.run.logging

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.localapi.LocalApiJson

@Serializable
data class RunLogRecord(
    val sequence: Long,
    val elapsedNanos: Long,
    val type: String,
    val pwd: String? = null,
    val argv: List<String> = emptyList(),
    val state: String? = null,
    val outputBase64: String? = null,
    val progressCurrent: Long? = null,
    val progressTotal: Long? = null,
    val exitCode: Int? = null,
)

/** Private, append-only NDJSON record. Raw output is base64 so arbitrary terminal bytes remain lossless. */
class RunLog private constructor(
    val runId: String,
    val path: Path,
    private val output: OutputStream,
    private val startedNanos: Long,
    private val maximumBytes: Long,
) : AutoCloseable {
    private val sequence = AtomicLong()
    private var bytesWritten = 0L
    private var closed = false

    @Synchronized
    fun header(pwd: Path, argv: List<String>) = write(
        RunLogRecord(sequence.getAndIncrement(), elapsed(), "start", pwd = pwd.toString(), argv = argv),
    )

    @Synchronized
    fun output(bytes: ByteArray, length: Int = bytes.size) = write(
        RunLogRecord(
            sequence.getAndIncrement(),
            elapsed(),
            "output",
            outputBase64 = Base64.getEncoder().encodeToString(bytes.copyOf(length)),
        ),
    )

    @Synchronized
    fun state(value: String) = write(
        RunLogRecord(sequence.getAndIncrement(), elapsed(), "state", state = value),
    )

    @Synchronized
    fun progress(current: Long, total: Long) = write(
        RunLogRecord(
            sequence.getAndIncrement(),
            elapsed(),
            "progress",
            progressCurrent = current,
            progressTotal = total,
        ),
    )

    @Synchronized
    fun completed(exitCode: Int) = write(
        RunLogRecord(sequence.getAndIncrement(), elapsed(), "complete", exitCode = exitCode),
    )

    private fun elapsed(): Long = System.nanoTime() - startedNanos

    private fun write(record: RunLogRecord) {
        if (closed) return
        val bytes = (LocalApiJson.encodeToString(record) + "\n").encodeToByteArray()
        if (bytesWritten > maximumBytes - bytes.size) return
        output.write(bytes)
        output.flush()
        bytesWritten += bytes.size
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }

    companion object {
        fun create(
            config: NSRunConfig,
            paths: DesktopPaths = DesktopPaths.default(),
            pid: Long = ProcessHandle.current().pid(),
            processStartMillis: Long = ProcessHandle.current().info().startInstant()
                .orElse(Instant.now()).toEpochMilli(),
        ): RunLog {
            val root = PrivateFiles.ensureDirectory(paths.runsDirectory)
            prune(root, config.logRetentionDays, config.logMaxBytes)
            val runId = "$pid-$processStartMillis"
            val directory = PrivateFiles.ensureDirectory(root.resolve(runId))
            val path = directory.resolve("run.ndjson")
            PrivateFiles.atomicWrite(path, ByteArray(0))
            val output = BufferedOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            ))
            return RunLog(runId, path, output, System.nanoTime(), config.logMaxBytes)
        }

        internal fun prune(root: Path, retentionDays: Int, maxBytes: Long, now: Instant = Instant.now()) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return
            val entries = Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .map { directory ->
                        val modified = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS)
                        Entry(directory, modified, directoryBytes(directory))
                    }
                    .toList()
            }.sortedBy { it.modified }
            val cutoff = now.minus(Duration.ofDays(retentionDays.toLong()))
            val retained = entries.toMutableList()
            for (entry in entries) {
                if (retentionDays == 0 || entry.modified.toInstant().isBefore(cutoff)) {
                    deleteDirectory(entry.path)
                    retained.remove(entry)
                }
            }
            var total = retained.sumOf(Entry::bytes)
            for (entry in retained) {
                if (total <= maxBytes) break
                deleteDirectory(entry.path)
                total -= entry.bytes
            }
        }

        private fun directoryBytes(directory: Path): Long = Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }

        private fun deleteDirectory(directory: Path) {
            require(directory.parent != null && directory.fileName.toString().isNotBlank())
            require(!Files.isSymbolicLink(directory))
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (!Files.isSymbolicLink(path) || path != directory) Files.deleteIfExists(path)
                }
            }
        }

        private data class Entry(val path: Path, val modified: FileTime, val bytes: Long)
    }
}
