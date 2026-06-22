package net.extrawdw.apps.notisync.assets

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AssetManagerTest {

    /** A fake broker: opaque (clientId, assetId) -> ciphertext, with first-writer-wins overwrite-reject. */
    private class FakeTransport : Transport {
        override val type = TransportType.WEBSOCKET
        val store = HashMap<String, ByteArray>()
        var uploads = 0
        private fun key(c: ClientId, id: String) = "${c.value}/$id"

        override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): Boolean {
            uploads++
            store.putIfAbsent(key(sourceClientId, assetId), ciphertext)
            return true // 200 stored or 409 exists both mean "the broker holds it"
        }

        override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? =
            store[key(sourceClientId, assetId)]

        fun corrupt(sourceClientId: ClientId, assetId: String) {
            store[key(sourceClientId, assetId)] = ByteArray(40) // garbage of plausible length
        }

        override suspend fun publishCard(card: SignedBlob) = Unit
        override suspend fun publishRoutes(routes: List<SignedBlob>) = Unit
        override suspend fun fetchCard(clientId: ClientId): SignedBlob? = null
        override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult = SendResult(false)
        override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> Unit) = Unit
    }

    private fun manager(transport: Transport): Pair<AssetManager, AssetCache> {
        val dir = Files.createTempDirectory("notisync-assets").toFile()
        val cache = AssetCache(dir)
        return AssetManager(transport, cache, TicketStore(dir)) to cache
    }

    @Test
    fun uploadsOnce_thenReferencesAndReceiverFetchesVerifiesAndCaches() = runBlocking {
        val transport = FakeTransport()
        val src = ClientId("sender")
        val plaintext = ByteArray(2000) { (it % 251).toByte() }

        val (sender, _) = manager(transport)
        val ref1 = sender.ensureUploaded(plaintext, AssetRole.LARGE_ICON, "image/webp", src)!!
        assertEquals(1, transport.uploads)

        // Same plaintext again -> reference-only (ticket fresh): no new upload, same opaque id + key.
        val ref2 = sender.ensureUploaded(plaintext, AssetRole.LARGE_ICON, "image/webp", src)!!
        assertEquals(1, transport.uploads)
        assertEquals(ref1.assetId, ref2.assetId)
        assertArrayEquals(ref1.assetKey, ref2.assetKey)

        // A fresh receiver fetches, decrypts, verifies the hash, and caches the plaintext.
        val (receiver, receiverCache) = manager(transport)
        assertTrue(receiver.ensureLocal(listOf(ref1)).newlyAvailable)
        assertArrayEquals(plaintext, receiverCache.read(ref1.assetHash))

        // Already cached -> nothing newly available, so no re-render is triggered.
        assertFalse(receiver.ensureLocal(listOf(ref1)).newlyAvailable)
    }

    @Test
    fun rejectsCorruptServerBytes_andDoesNotCache() = runBlocking {
        val transport = FakeTransport()
        val src = ClientId("sender")
        val (sender, _) = manager(transport)
        val ref = sender.ensureUploaded(byteArrayOf(1, 2, 3, 4), AssetRole.LARGE_ICON, "image/webp", src)!!

        transport.corrupt(src, ref.assetId) // simulate a malicious/garbled server substitution

        val (receiver, cache) = manager(transport)
        assertFalse(receiver.ensureLocal(listOf(ref)).newlyAvailable)
        assertFalse(cache.has(ref.assetHash))
    }

    @Test
    fun missingServerBlob_isNotFatal() = runBlocking {
        val transport = FakeTransport()
        val src = ClientId("sender")
        val (sender, _) = manager(transport)
        val ref = sender.ensureUploaded(byteArrayOf(9, 9, 9), AssetRole.LARGE_ICON, "image/webp", src)!!
        transport.store.clear() // server lost the blob (e.g. TTL expiry)

        val (receiver, cache) = manager(transport)
        assertFalse(receiver.ensureLocal(listOf(ref)).newlyAvailable)
        assertNull(cache.read(ref.assetHash))
    }

    @Test
    fun repair_reUploadsUnderSameId_soReceiverCanFetchAfterServerLoss() = runBlocking {
        val transport = FakeTransport()
        val src = ClientId("sender")
        val plaintext = ByteArray(1500) { (it % 200).toByte() }
        val (sender, _) = manager(transport)
        val ref = sender.ensureUploaded(plaintext, AssetRole.LARGE_ICON, "image/webp", src)!!

        transport.store.clear() // server lost the blob (e.g. TTL expiry)

        val (receiver, cache) = manager(transport)
        val missing = receiver.ensureLocal(listOf(ref))
        assertFalse(missing.newlyAvailable)
        assertEquals(listOf(ref.assetId), missing.stillMissing.map { it.assetId })

        // Provider repairs: re-uploads under the SAME opaque id.
        val repaired = sender.repair(ref.assetHash, src)!!
        assertEquals(ref.assetId, repaired.assetId)

        // The receiver can now fetch + verify + cache using its original ref.
        assertTrue(receiver.ensureLocal(listOf(ref)).newlyAvailable)
        assertArrayEquals(plaintext, cache.read(ref.assetHash))
    }
}
