package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.crypto.Hpke
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCoreFileReaderTest {
    private val directories = mutableListOf<Path>()

    @After
    fun tearDown() {
        directories.forEach { directory ->
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    @Test
    fun validEpochAndTokenAreReadWithoutMutatingSourceFiles() = runBlocking {
        val directory = newDirectory()
        val pair = Hpke.generateKeyPair()
        assertTrue(pair.publicKeyset.size <= LegacyCoreFileSourceContract.MAX_V51_HPKE_PUBLIC_KEYSET_BYTES)
        val wrappedPrivate = wrapForFixture(pair.privateKeyset)
        assertTrue(wrappedPrivate.size <= LegacyCoreFileSourceContract.MAX_V51_WRAPPED_HPKE_PRIVATE_BYTES)
        write(directory, "hpke_public.epoch2.bin", pair.publicKeyset)
        write(directory, "hpke_private.epoch2.wrapped", wrappedPrivate)
        val wrappedToken = wrapForFixture("private-auth-token".encodeToByteArray())
        write(directory, LegacyCoreFileSourceContract.AUTH_TOKEN_FILE, wrappedToken)
        write(directory, LegacyCoreFileSourceContract.LEGACY_UNVERSIONED_HPKE_PUBLIC, byteArrayOf(1, 2, 3))
        val before = fileState(directory)

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.READY, result.status)
        assertEquals(3, result.relevantFileCount)
        assertEquals(1, result.skippedUnversionedHpkeFileCount)
        val snapshot = requireNotNull(result.snapshot)
        assertEquals(listOf(2), snapshot.hpkeEpochs.map { it.epoch })
        assertArrayEquals(pair.publicKeyset, snapshot.hpkeEpochs.single().publicKeysetCopy())
        assertArrayEquals(wrappedPrivate, snapshot.hpkeEpochs.single().wrappedPrivateKeysetCopy())
        assertArrayEquals(wrappedToken, requireNotNull(snapshot.authToken).wrappedTokenCopy())
        assertEquals(before, fileState(directory))
        assertFalse(result.toString().contains("private-auth-token"))
        assertFalse(snapshot.toString().contains(wrappedToken.decodeToString()))

        val publicCopy = snapshot.hpkeEpochs.single().publicKeysetCopy()
        publicCopy.fill(0)
        assertArrayEquals(pair.publicKeyset, snapshot.hpkeEpochs.single().publicKeysetCopy())
        val digestCopy = requireNotNull(result.digests).contentDigest
        val expectedDigest = digestCopy.copyOf()
        digestCopy.fill(0)
        assertArrayEquals(expectedDigest, requireNotNull(result.digests).contentDigest)
    }

    @Test
    fun halfPairAndMalformedWrappedValueRequireRecovery() = runBlocking {
        val directory = newDirectory()
        val pair = Hpke.generateKeyPair()
        write(directory, "hpke_public.epoch1.bin", pair.publicKeyset)
        write(
            directory,
            LegacyCoreFileSourceContract.AUTH_TOKEN_FILE,
            byteArrayOf(11) + ByteArray(64),
        )

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertNull(result.snapshot)
        assertTrue(
            LegacyCoreFileIssue(LegacyCoreFileIssueKind.MISSING_HPKE_PRIVATE_HALF, 1) in result.issues,
        )
        assertTrue(
            LegacyCoreFileIssue(LegacyCoreFileIssueKind.INVALID_WRAPPED_AUTH_TOKEN) in result.issues,
        )
    }

    @Test
    fun invalidPublicKeysetAndWrappedPrivateFramingAreRejected() = runBlocking {
        val directory = newDirectory()
        write(
            directory,
            "hpke_public.epoch1.bin",
            ByteArray(120),
        )
        write(
            directory,
            "hpke_private.epoch1.wrapped",
            byteArrayOf(11) + ByteArray(187),
        )

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(result.issues.any { it.kind == LegacyCoreFileIssueKind.INVALID_HPKE_PUBLIC_KEYSET })
        assertTrue(result.issues.any { it.kind == LegacyCoreFileIssueKind.INVALID_WRAPPED_HPKE_PRIVATE })
    }

    @Test
    fun nonCanonicalNamesAndSymbolicLinksAreNeverFollowed() = runBlocking {
        val directory = newDirectory()
        val pair = Hpke.generateKeyPair()
        val target = write(directory, "ordinary-file", pair.publicKeyset)
        write(directory, "hpke_public.epoch01.bin", pair.publicKeyset)
        Files.createSymbolicLink(directory.resolve("hpke_public.epoch2.bin"), target.fileName)
        write(directory, "hpke_private.epoch2.wrapped", wrapForFixture(pair.privateKeyset))

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertTrue(result.issues.any { it.kind == LegacyCoreFileIssueKind.NON_CANONICAL_EPOCH_FILE_NAME })
        assertTrue(result.issues.any { it.kind == LegacyCoreFileIssueKind.SYMBOLIC_LINK && it.epoch == 2 })
    }

    @Test
    fun oversizedTokenIsBoundedBeforeItCanEnterASnapshot() = runBlocking {
        val directory = newDirectory()
        write(
            directory,
            LegacyCoreFileSourceContract.AUTH_TOKEN_FILE,
            ByteArray(LegacyCoreFileSourceContract.MAX_WRAPPED_AUTH_TOKEN_BYTES + 1) { 12 },
        )

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.RECOVERY_REQUIRED, result.status)
        assertNull(result.snapshot)
        assertTrue(result.issues.any { it.kind == LegacyCoreFileIssueKind.SOURCE_FILE_TOO_LARGE })
    }

    @Test
    fun onlySkippedUnversionedFilesDoNotBecomeAnImportSource() = runBlocking {
        val directory = newDirectory()
        write(directory, LegacyCoreFileSourceContract.LEGACY_UNVERSIONED_HPKE_PUBLIC, byteArrayOf(1))
        write(directory, LegacyCoreFileSourceContract.LEGACY_UNVERSIONED_HPKE_PRIVATE, byteArrayOf(2))

        val result = LegacyCoreFileReader(directory).read()

        assertEquals(LegacyCoreReadStatus.ABSENT, result.status)
        assertEquals(0, result.relevantFileCount)
        assertEquals(2, result.skippedUnversionedHpkeFileCount)
    }

    @Test
    fun preCancelledReadDoesNotTouchTheDirectory() {
        val directory = newDirectory()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancel()
                LegacyCoreFileReader(directory).read()
            }
        }
    }

    private fun newDirectory(): Path = Files.createTempDirectory("legacy-core-files-").also(directories::add)

    private fun write(directory: Path, name: String, bytes: ByteArray): Path =
        Files.write(directory.resolve(name), bytes)

    private fun wrapForFixture(plaintext: ByteArray): ByteArray =
        byteArrayOf(LegacyCoreFileSourceContract.WRAPPED_IV_BYTES.toByte()) +
            ByteArray(LegacyCoreFileSourceContract.WRAPPED_IV_BYTES) { index -> (index + 1).toByte() } +
            plaintext +
            ByteArray(LegacyCoreFileSourceContract.GCM_TAG_BYTES) { 0x5a }

    private fun fileState(directory: Path): Map<String, FileState> =
        Files.newDirectoryStream(directory).use { entries ->
            entries.associate { path ->
                path.fileName.toString() to FileState(
                    sha256 = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                        .joinToString("") { byte -> "%02x".format(byte) },
                    modifiedAt = Files.getLastModifiedTime(path),
                )
            }
        }

    private data class FileState(
        val sha256: String,
        val modifiedAt: FileTime,
    )
}
