package net.extrawdw.notisync.protocol.crypto

import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.PrivateAssetRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetAeadTest {

    private fun ref(
        plaintext: ByteArray,
        role: AssetRole = AssetRole.LARGE_ICON,
        assetId: String = AssetAead.generateAssetId(),
        assetKey: ByteArray = AssetAead.generateAssetKey(),
        mimeType: String = "image/webp",
        src: ClientId = ClientId("phone"),
    ) = PrivateAssetRef(
        role = role,
        assetHash = AssetHash.of(plaintext),
        mimeType = mimeType,
        sizeBytes = plaintext.size,
        sourceClientId = src,
        assetId = assetId,
        assetKey = assetKey,
    )

    @Test
    fun base32_roundTripsArbitraryBytes() {
        val bytes = ByteArray(24).also { secureRandom.nextBytes(it) }
        assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
        // A generated asset id decodes back to exactly 24 bytes (the opaque-id floor the server checks).
        assertEquals(24, Base32.decode(AssetAead.generateAssetId())!!.size)
    }

    @Test
    fun sealThenOpenRoundTripsAndVerifiesHash() {
        val plaintext = ByteArray(5000).also { secureRandom.nextBytes(it) }
        val r = ref(plaintext)
        val sealed = AssetAead.seal(r, plaintext)

        // The blob is opaque (does not start with the plaintext) and carries the 12-byte nonce prefix.
        assertFalse(sealed.copyOfRange(12, 12 + plaintext.size).contentEquals(plaintext))

        val opened = AssetAead.open(r, sealed)
        assertArrayEquals(plaintext, opened)
        assertTrue(AssetHash.matches(opened, r.assetHash))
    }

    @Test
    fun aadIsDeterministicAndIndependentOfKey() {
        val plaintext = byteArrayOf(1, 2, 3)
        val r = ref(plaintext)
        // Re-encoding the AAD for the same logical ref is byte-identical (cross-stack determinism).
        assertArrayEquals(AssetAead.aadBytes(r), AssetAead.aadBytes(r.copy()))
        // The key is NOT part of the AAD: a different key keeps the same AAD bytes.
        assertArrayEquals(AssetAead.aadBytes(r), AssetAead.aadBytes(r.copy(assetKey = AssetAead.generateAssetKey())))
        // But the bound role/assetId/size ARE part of it.
        assertFalse(AssetAead.aadBytes(r).contentEquals(AssetAead.aadBytes(r.copy(role = AssetRole.AVATAR))))
        assertFalse(AssetAead.aadBytes(r).contentEquals(AssetAead.aadBytes(r.copy(assetId = "different"))))
    }

    @Test
    fun wrongKeyFailsToOpen() {
        val plaintext = byteArrayOf(9, 8, 7, 6)
        val r = ref(plaintext)
        val sealed = AssetAead.seal(r, plaintext)
        assertThrows(Exception::class.java) {
            AssetAead.open(r.copy(assetKey = AssetAead.generateAssetKey()), sealed)
        }
    }

    @Test
    fun tamperedBlobFailsToOpen() {
        val plaintext = byteArrayOf(4, 5, 6, 7)
        val r = ref(plaintext)
        val sealed = AssetAead.seal(r, plaintext)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { AssetAead.open(r, sealed) }
    }

    @Test
    fun mismatchedAadFailsToOpen() {
        // Same key + ciphertext, but the receiver reconstructs a different AAD (e.g. a substituted
        // assetId): GCM authentication fails, so a server cannot pass off a blob under a different id.
        val plaintext = byteArrayOf(1, 1, 2, 3, 5, 8)
        val r = ref(plaintext)
        val sealed = AssetAead.seal(r, plaintext)
        assertThrows(Exception::class.java) { AssetAead.open(r.copy(assetId = "swapped"), sealed) }
    }

    @Test
    fun matchesRejectsWrongPlaintext() {
        val r = ref(byteArrayOf(1, 2, 3))
        assertFalse(AssetHash.matches(byteArrayOf(1, 2, 4), r.assetHash))
    }
}
