package net.extrawdw.apps.notisync.data.storage.importer.legacy.core

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.crypto.Hpke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCoreFileReaderAndroidTest {
    @Test
    fun appPrivateFixtureIsReadWithoutMutationOrSecretRendering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "legacy-core-reader-${UUID.randomUUID()}")
        check(directory.mkdir())
        val publicFile = File(directory, "hpke_public.epoch1.bin")
        val privateFile = File(directory, "hpke_private.epoch1.wrapped")
        val tokenFile = File(directory, LegacyCoreFileSourceContract.AUTH_TOKEN_FILE)
        try {
            val pair = Hpke.generateKeyPair()
            val wrappedPrivate = wrapFixture(pair.privateKeyset)
            val wrappedToken = wrapFixture("device-private-token".encodeToByteArray())
            publicFile.writeBytes(pair.publicKeyset)
            privateFile.writeBytes(wrappedPrivate)
            tokenFile.writeBytes(wrappedToken)
            val before = listOf(publicFile, privateFile, tokenFile).associate { file ->
                file.name to (sha256(file) to file.lastModified())
            }

            val result = LegacyCoreFileReader(directory.toPath()).read()

            assertEquals(LegacyCoreReadStatus.READY, result.status)
            val snapshot = requireNotNull(result.snapshot)
            assertArrayEquals(pair.publicKeyset, snapshot.hpkeEpochs.single().publicKeysetCopy())
            assertArrayEquals(wrappedPrivate, snapshot.hpkeEpochs.single().wrappedPrivateKeysetCopy())
            assertArrayEquals(wrappedToken, requireNotNull(snapshot.authToken).wrappedTokenCopy())
            val after = listOf(publicFile, privateFile, tokenFile).associate { file ->
                file.name to (sha256(file) to file.lastModified())
            }
            assertEquals(before, after)
            assertFalse(result.toString().contains("device-private-token"))
        } finally {
            publicFile.delete()
            privateFile.delete()
            tokenFile.delete()
            directory.delete()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun wrapFixture(plaintext: ByteArray): ByteArray =
        byteArrayOf(LegacyCoreFileSourceContract.WRAPPED_IV_BYTES.toByte()) +
            ByteArray(LegacyCoreFileSourceContract.WRAPPED_IV_BYTES) { 1 } +
            plaintext +
            ByteArray(LegacyCoreFileSourceContract.GCM_TAG_BYTES) { 2 }
}
