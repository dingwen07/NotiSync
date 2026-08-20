package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.protocol.crypto.Hpke

/**
 * Read-only v51 reader for the app-private HPKE epoch files and wrapped broker token.
 *
 * The reader never follows symbolic links, unwraps a value, generates a key, repairs a pair, or
 * writes to the directory. Private-key and token blobs receive framing validation only; the later
 * activation boundary owns Keystore unwrap, exact persisted-byte self-test, and target mapping.
 */
internal class LegacyCoreFileReader(
    private val filesDirectory: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(): LegacyCoreFileReadResult = withContext(ioDispatcher) {
        try {
            currentCoroutineContext().ensureActive()
            readOnIoDispatcher()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw LegacyCoreSourceReadException(
                source = LegacyCoreSourceKind.FILES,
                kind = LegacyCoreSourceFailureKind.SOURCE_IO,
                cause = failure,
            )
        } catch (failure: SecurityException) {
            throw LegacyCoreSourceReadException(
                source = LegacyCoreSourceKind.FILES,
                kind = LegacyCoreSourceFailureKind.SOURCE_IO,
                cause = failure,
            )
        }
    }

    private suspend fun readOnIoDispatcher(): LegacyCoreFileReadResult {
        if (!Files.exists(filesDirectory, LinkOption.NOFOLLOW_LINKS)) return absent(skippedCount = 0)
        if (Files.isSymbolicLink(filesDirectory)) {
            return recovery(
                issues = setOf(LegacyCoreFileIssue(LegacyCoreFileIssueKind.SYMBOLIC_LINK)),
                relevantFileCount = 0,
                skippedCount = 0,
                digests = null,
            )
        }
        if (!Files.isDirectory(filesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return recovery(
                issues = setOf(LegacyCoreFileIssue(LegacyCoreFileIssueKind.NOT_REGULAR_FILE)),
                relevantFileCount = 0,
                skippedCount = 0,
                digests = null,
            )
        }

        val before = observeRelevantEntries()
        val skippedCount = before.entries.values.count { it.kind == LegacyFileNameKind.SKIPPED_UNVERSIONED }
        val importEntries = before.entries.values.filter { it.kind != LegacyFileNameKind.SKIPPED_UNVERSIONED }
        if (importEntries.isEmpty()) return absent(skippedCount)

        val issues = linkedSetOf<LegacyCoreFileIssue>()
        val acquired = linkedMapOf<String, ByteArray?>()
        val publicByEpoch = sortedMapOf<Int, ByteArray>()
        val privateByEpoch = sortedMapOf<Int, ByteArray>()
        var wrappedAuthToken: ByteArray? = null

        importEntries.sortedBy { it.name }.forEach { entry ->
            currentCoroutineContext().ensureActive()
            if (entry.stamp.symbolicLink) {
                issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.SYMBOLIC_LINK, entry.epoch)
                acquired[entry.name] = null
                return@forEach
            }
            if (!entry.stamp.regularFile) {
                issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.NOT_REGULAR_FILE, entry.epoch)
                acquired[entry.name] = null
                return@forEach
            }
            when (entry.kind) {
                LegacyFileNameKind.NON_CANONICAL -> {
                    issues += LegacyCoreFileIssue(
                        LegacyCoreFileIssueKind.NON_CANONICAL_EPOCH_FILE_NAME,
                    )
                    acquired[entry.name] = null
                }

                LegacyFileNameKind.HPKE_PUBLIC -> {
                    val bytes = readBounded(
                        entry.path,
                        LegacyCoreFileSourceContract.MAX_V51_HPKE_PUBLIC_KEYSET_BYTES,
                    )
                    acquired[entry.name] = bytes
                    if (bytes == null) {
                        issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.SOURCE_FILE_TOO_LARGE, entry.epoch)
                    } else if (!bytes.isValidV51PublicKeyset()) {
                        issues += LegacyCoreFileIssue(
                            LegacyCoreFileIssueKind.INVALID_HPKE_PUBLIC_KEYSET,
                            entry.epoch,
                        )
                    } else {
                        publicByEpoch.putValue(entry, bytes, issues)
                    }
                }

                LegacyFileNameKind.HPKE_PRIVATE -> {
                    val bytes = readBounded(
                        entry.path,
                        LegacyCoreFileSourceContract.MAX_V51_WRAPPED_HPKE_PRIVATE_BYTES,
                    )
                    acquired[entry.name] = bytes
                    if (bytes == null) {
                        issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.SOURCE_FILE_TOO_LARGE, entry.epoch)
                    } else if (!bytes.isValidV51WrappedPrivateKeyset()) {
                        issues += LegacyCoreFileIssue(
                            LegacyCoreFileIssueKind.INVALID_WRAPPED_HPKE_PRIVATE,
                            entry.epoch,
                        )
                    } else {
                        privateByEpoch.putValue(entry, bytes, issues)
                    }
                }

                LegacyFileNameKind.AUTH_TOKEN -> {
                    val bytes = readBounded(
                        entry.path,
                        LegacyCoreFileSourceContract.MAX_WRAPPED_AUTH_TOKEN_BYTES,
                    )
                    acquired[entry.name] = bytes
                    if (bytes == null) {
                        issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.SOURCE_FILE_TOO_LARGE)
                    } else if (!bytes.hasV51WrappedValueFraming(requirePlaintext = true)) {
                        issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.INVALID_WRAPPED_AUTH_TOKEN)
                    } else {
                        wrappedAuthToken = bytes
                    }
                }

                LegacyFileNameKind.SKIPPED_UNVERSIONED -> error("skipped entries are filtered")
            }
        }

        val canonicalEpochs = importEntries.mapNotNull { it.epoch }.toSortedSet()
        canonicalEpochs.forEach { epoch ->
            val hasPublicName = importEntries.any {
                it.kind == LegacyFileNameKind.HPKE_PUBLIC && it.epoch == epoch
            }
            val hasPrivateName = importEntries.any {
                it.kind == LegacyFileNameKind.HPKE_PRIVATE && it.epoch == epoch
            }
            if (!hasPublicName) {
                issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.MISSING_HPKE_PUBLIC_HALF, epoch)
            }
            if (!hasPrivateName) {
                issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.MISSING_HPKE_PRIVATE_HALF, epoch)
            }
        }

        currentCoroutineContext().ensureActive()
        val after = observeRelevantEntries()
        if (before != after) {
            issues += LegacyCoreFileIssue(LegacyCoreFileIssueKind.SOURCE_CHANGED_DURING_READ)
        }
        val digests = if (before == after) before.digests(acquired) else null
        if (issues.isNotEmpty()) {
            return recovery(
                issues = issues,
                relevantFileCount = importEntries.size,
                skippedCount = skippedCount,
                digests = digests,
            )
        }

        val hpkeEpochs = canonicalEpochs.map { epoch ->
            LegacyHpkeEpochFileSource(
                epoch = epoch,
                publicKeyset = requireNotNull(publicByEpoch[epoch]),
                wrappedPrivateKeyset = requireNotNull(privateByEpoch[epoch]),
            )
        }
        val snapshot = LegacyCoreFileSnapshot(
            hpkeEpochs = hpkeEpochs,
            authToken = wrappedAuthToken?.let(::LegacyWrappedAuthTokenSource),
            skippedUnversionedHpkeFileCount = skippedCount,
            digests = requireNotNull(digests),
        )
        return LegacyCoreFileReadResult(
            status = LegacyCoreReadStatus.READY,
            snapshot = snapshot,
            issues = emptySet(),
            relevantFileCount = importEntries.size,
            skippedUnversionedHpkeFileCount = skippedCount,
            digests = digests,
        )
    }

    private fun MutableMap<Int, ByteArray>.putValue(
        entry: ObservedFile,
        bytes: ByteArray,
        issues: MutableSet<LegacyCoreFileIssue>,
    ) {
        val epoch = requireNotNull(entry.epoch)
        if (put(epoch, bytes) != null) {
            issues += LegacyCoreFileIssue(
                LegacyCoreFileIssueKind.NON_CANONICAL_EPOCH_FILE_NAME,
                epoch,
            )
        }
    }

    private suspend fun observeRelevantEntries(): SourceObservation {
        val entries = linkedMapOf<String, ObservedFile>()
        Files.newDirectoryStream(filesDirectory).use { directory ->
            directory.forEach { path ->
                currentCoroutineContext().ensureActive()
                val name = path.fileName.toString()
                val classified = classify(name) ?: return@forEach
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                entries[name] = ObservedFile(
                    name = name,
                    path = path,
                    kind = classified.kind,
                    epoch = classified.epoch,
                    stamp = FileStamp(
                        regularFile = attributes.isRegularFile,
                        symbolicLink = attributes.isSymbolicLink,
                        size = attributes.size(),
                        modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                        fileKey = attributes.fileKey(),
                    ),
                )
            }
        }
        return SourceObservation(entries.toSortedMap())
    }

    private suspend fun readBounded(path: Path, maximumBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximumBytes) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.isValidV51PublicKeyset(): Boolean =
        size in 1..LegacyCoreFileSourceContract.MAX_V51_HPKE_PUBLIC_KEYSET_BYTES &&
            runCatching { Hpke.rawPublicKey(this).size == RAW_X25519_PUBLIC_BYTES }.getOrDefault(false)

    private fun ByteArray.isValidV51WrappedPrivateKeyset(): Boolean =
        size <= LegacyCoreFileSourceContract.MAX_V51_WRAPPED_HPKE_PRIVATE_BYTES &&
            hasV51WrappedValueFraming(requirePlaintext = true)

    private fun ByteArray.hasV51WrappedValueFraming(requirePlaintext: Boolean): Boolean {
        if (isEmpty() || (first().toInt() and 0xff) != LegacyCoreFileSourceContract.WRAPPED_IV_BYTES) {
            return false
        }
        val ciphertextAndTagBytes = size - 1 - LegacyCoreFileSourceContract.WRAPPED_IV_BYTES
        val minimum = LegacyCoreFileSourceContract.GCM_TAG_BYTES + if (requirePlaintext) 1 else 0
        return ciphertextAndTagBytes >= minimum
    }

    private fun classify(name: String): ClassifiedName? {
        if (name == LegacyCoreFileSourceContract.LEGACY_UNVERSIONED_HPKE_PUBLIC ||
            name == LegacyCoreFileSourceContract.LEGACY_UNVERSIONED_HPKE_PRIVATE
        ) {
            return ClassifiedName(LegacyFileNameKind.SKIPPED_UNVERSIONED, epoch = null)
        }
        if (name == LegacyCoreFileSourceContract.AUTH_TOKEN_FILE) {
            return ClassifiedName(LegacyFileNameKind.AUTH_TOKEN, epoch = null)
        }
        PUBLIC_FILE_PATTERN.matchEntire(name)?.let { match ->
            val epoch = match.groupValues[1].toIntOrNull()
            if (epoch != null && epoch > 0) return ClassifiedName(LegacyFileNameKind.HPKE_PUBLIC, epoch)
            return ClassifiedName(LegacyFileNameKind.NON_CANONICAL, epoch = null)
        }
        PRIVATE_FILE_PATTERN.matchEntire(name)?.let { match ->
            val epoch = match.groupValues[1].toIntOrNull()
            if (epoch != null && epoch > 0) return ClassifiedName(LegacyFileNameKind.HPKE_PRIVATE, epoch)
            return ClassifiedName(LegacyFileNameKind.NON_CANONICAL, epoch = null)
        }
        if (name.startsWith(LegacyCoreFileSourceContract.HPKE_PUBLIC_PREFIX) ||
            name.startsWith(LegacyCoreFileSourceContract.HPKE_PRIVATE_PREFIX)
        ) {
            return ClassifiedName(LegacyFileNameKind.NON_CANONICAL, epoch = null)
        }
        return null
    }

    private fun SourceObservation.digests(acquired: Map<String, ByteArray?>): LegacyCoreSourceDigests {
        val content = LegacyCoreDigestAccumulator().apply {
            text("NotiSync/core-files/v51")
            entries.values.sortedBy { it.name }.forEach { entry ->
                text(entry.name)
                text(entry.kind.name)
                long(entry.stamp.size)
                bytes(acquired[entry.name])
            }
        }.digest()
        val logical = LegacyCoreDigestAccumulator().apply {
            text("NotiSync/core-files-logical-fingerprint/v1")
            int(LegacyCoreFileSourceContract.CONTRACT_VERSION)
            bytes(content)
        }.digest()
        return LegacyCoreSourceDigests(content, logical)
    }

    private fun absent(skippedCount: Int): LegacyCoreFileReadResult {
        val observation = SourceObservation(emptyMap())
        return LegacyCoreFileReadResult(
            status = LegacyCoreReadStatus.ABSENT,
            snapshot = null,
            issues = emptySet(),
            relevantFileCount = 0,
            skippedUnversionedHpkeFileCount = skippedCount,
            digests = observation.digests(emptyMap()),
        )
    }

    private fun recovery(
        issues: Set<LegacyCoreFileIssue>,
        relevantFileCount: Int,
        skippedCount: Int,
        digests: LegacyCoreSourceDigests?,
    ): LegacyCoreFileReadResult = LegacyCoreFileReadResult(
        status = LegacyCoreReadStatus.RECOVERY_REQUIRED,
        snapshot = null,
        issues = issues,
        relevantFileCount = relevantFileCount,
        skippedUnversionedHpkeFileCount = skippedCount,
        digests = digests,
    )

    private enum class LegacyFileNameKind {
        HPKE_PUBLIC,
        HPKE_PRIVATE,
        AUTH_TOKEN,
        SKIPPED_UNVERSIONED,
        NON_CANONICAL,
    }

    private data class ClassifiedName(
        val kind: LegacyFileNameKind,
        val epoch: Int?,
    )

    private data class FileStamp(
        val regularFile: Boolean,
        val symbolicLink: Boolean,
        val size: Long,
        val modifiedAtMillis: Long,
        val fileKey: Any?,
    )

    private data class ObservedFile(
        val name: String,
        val path: Path,
        val kind: LegacyFileNameKind,
        val epoch: Int?,
        val stamp: FileStamp,
    )

    private data class SourceObservation(val entries: Map<String, ObservedFile>)

    private companion object {
        const val RAW_X25519_PUBLIC_BYTES = 32
        const val READ_BUFFER_BYTES = 8 * 1024
        val PUBLIC_FILE_PATTERN = Regex("hpke_public\\.epoch([1-9][0-9]*)\\.bin")
        val PRIVATE_FILE_PATTERN = Regex("hpke_private\\.epoch([1-9][0-9]*)\\.wrapped")
    }
}
