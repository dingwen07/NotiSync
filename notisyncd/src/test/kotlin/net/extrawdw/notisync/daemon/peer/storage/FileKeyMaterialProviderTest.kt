package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKeyMaterialProviderTest : StorageTestSupport() {
    @Test
    fun `identity epoch and HPKE keys survive provider recreation`() {
        val layout = layout()
        val first = FileKeyMaterialProvider(layout)
        val clientId = first.identity.clientId
        val challenge = "hello desktop peer".encodeToByteArray()

        assertTrue(
            IdentityVerifier.verify(
                first.identity.publicKeySpki,
                challenge,
                first.identity.sign(challenge),
            ),
        )
        val verifiedEpochOrNull = KeyEpochs.verify(first.currentKeyEpoch())
        assertNotNull(verifiedEpochOrNull)
        val verifiedEpoch = requireNotNull(verifiedEpochOrNull)
        assertEquals(1, verifiedEpoch.epoch)
        assertEquals(clientId, verifiedEpoch.clientId)

        val privateKeyset = first.hpkePrivateKeyset(1)!!
        val publicKeyset = first.hpkePublicKeyset(1)
        val context = "test context".encodeToByteArray()
        val plaintext = "secret".encodeToByteArray()
        assertArrayEquals(
            plaintext,
            Hpke.open(Hpke.seal(plaintext, publicKeyset, context), privateKeyset, context),
        )

        val recreated = FileKeyMaterialProvider(layout)
        assertEquals(clientId, recreated.identity.clientId)
        assertArrayEquals(first.identity.publicKeySpki, recreated.identity.publicKeySpki)
        assertArrayEquals(publicKeyset, recreated.hpkePublicKeyset(1))

        Files.list(layout.privateKeysDirectory).use { files ->
            files.forEach { path ->
                assertEquals(
                    SecureFileSystem.FILE_PERMISSIONS,
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
                )
            }
        }
    }

    @Test
    fun `future key minting does not activate until requested and active epoch cannot be destroyed`() {
        val layout = layout()
        val provider = FileKeyMaterialProvider(layout)
        val identity = provider.identity.clientId

        assertEquals(2, provider.operationalSigner(2).signerEpoch)
        provider.hpkePublicKeyset(2)
        assertEquals(1, KeyEpochs.verify(provider.currentKeyEpoch())!!.epoch)

        provider.activateEpoch(2)
        assertEquals(2, provider.currentOperationalSigner().signerEpoch)
        assertEquals(2, KeyEpochs.verify(provider.currentKeyEpoch())!!.epoch)
        assertEquals(identity, provider.identity.clientId)
        assertThrows(IllegalArgumentException::class.java) { provider.destroyEpoch(2) }

        provider.destroyEpoch(1)
        assertNull(provider.hpkePrivateKeyset(1))
        assertFalse(provider.retainedEpochs().contains(1))
        assertTrue(provider.retainedEpochs().contains(2))

        val recreated = FileKeyMaterialProvider(layout)
        assertEquals(2, recreated.currentOperationalSigner().signerEpoch)
        assertEquals(identity, recreated.identity.clientId)
    }

    private fun layout() = DaemonStorageLayout(temporaryDirectory.resolve(".notisync"))
}
