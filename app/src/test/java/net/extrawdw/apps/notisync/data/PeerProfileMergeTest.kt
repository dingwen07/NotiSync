package net.extrawdw.apps.notisync.data

import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Last-writer-wins, mutable-fields-only semantics of [applyProfileUpdate]. */
class PeerProfileMergeTest {

    private fun peer(id: String, name: String, profileTs: Long) = Peer(
        clientId = ClientId(id),
        displayName = name,
        platform = "android",
        identityPublicKeyB64 = "identity-key-$id",
        hpkePublicKeysetB64 = "hpke-key-$id",
        addedAt = 1L,
        profileUpdatedAt = profileTs,
    )

    @Test
    fun appliesNewerUpdate_toMutableFieldsOnly() {
        val before = listOf(peer("a", "Old Name", profileTs = 100L))
        val update = ProfileUpdate(
            clientId = ClientId("a"),
            displayName = "New Name",
            platform = "android",
            capabilities = listOf(Capability.DISPLAY),
            updatedAt = 200L,
        )

        val after = applyProfileUpdate(before, update)!!.single()

        assertEquals("New Name", after.displayName)
        assertEquals(listOf(Capability.DISPLAY), after.capabilities)
        assertEquals(200L, after.profileUpdatedAt)
        // Immutable trust anchors + addedAt must survive untouched.
        assertEquals("identity-key-a", after.identityPublicKeyB64)
        assertEquals("hpke-key-a", after.hpkePublicKeysetB64)
        assertEquals(1L, after.addedAt)
    }

    @Test
    fun ignoresStaleOrEqualTimestamp() {
        val before = listOf(peer("a", "Current", profileTs = 200L))
        assertNull(applyProfileUpdate(before, update("a", "Older", 150L)))
        assertNull(applyProfileUpdate(before, update("a", "Same-ts", 200L)))
    }

    @Test
    fun ignoresUnknownClient() {
        val before = listOf(peer("a", "A", profileTs = 100L))
        assertNull(applyProfileUpdate(before, update("zzz", "Stranger", 999L)))
    }

    @Test
    fun touchesOnlyTheMatchingPeer() {
        val before = listOf(peer("a", "A", 100L), peer("b", "B", 100L))
        val after = applyProfileUpdate(before, update("b", "B2", 300L))!!
        assertEquals("A", after.first { it.clientId == ClientId("a") }.displayName)
        assertEquals("B2", after.first { it.clientId == ClientId("b") }.displayName)
    }

    private fun update(id: String, name: String, ts: Long) =
        ProfileUpdate(ClientId(id), name, "android", emptyList(), ts)
}
