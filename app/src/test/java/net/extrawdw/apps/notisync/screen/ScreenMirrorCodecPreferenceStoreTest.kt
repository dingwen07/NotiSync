package net.extrawdw.apps.notisync.screen

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.apps.notisync.testsupport.InMemoryOperationalApplicationState
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMirrorCodecPreferenceStoreTest {
    @Test
    fun `preferences persist per source and auto removes the override`() = runBlocking {
        val state = InMemoryOperationalApplicationState()
        val preferences = ScreenMirrorCodecPreferenceStore(state)
        val first = ClientId("source-one")
        val second = ClientId("source-two")

        preferences.setPreferredCodec(first, ScreenMirrorCodec.AV1)
        preferences.setPreferredCodec(second, ScreenMirrorCodec.H264)

        val reloaded = ScreenMirrorCodecPreferenceStore(state)
        assertEquals(ScreenMirrorCodec.AV1, reloaded.preferredCodec(first))
        assertEquals(ScreenMirrorCodec.H264, reloaded.preferredCodec(second))

        reloaded.setPreferredCodec(first, null)
        assertNull(reloaded.preferredCodec(first))
        assertEquals(ScreenMirrorCodec.H264, reloaded.preferredCodec(second))
    }

    @Test
    fun `unknown persisted codec is dropped without losing known preferences`() = runBlocking {
        val state = InMemoryOperationalApplicationState(
            initialCodecs = listOf(
                ScreenCodecPreferenceEntity("known", "h265"),
                ScreenCodecPreferenceEntity("future", "vvc"),
            ),
        )
        val preferences = ScreenMirrorCodecPreferenceStore(state)

        assertEquals(ScreenMirrorCodec.H265, preferences.preferredCodec(ClientId("known")))
        assertNull(preferences.preferredCodec(ClientId("future")))
    }

    @Test
    fun `revoked reclassified and removed sources lose their preference`() = runBlocking {
        val state = InMemoryOperationalApplicationState()
        val preferences = ScreenMirrorCodecPreferenceStore(state)
        val retained = ClientId("retained")
        val revoked = ClientId("revoked")
        val other = ClientId("other")
        preferences.setPreferredCodec(retained, ScreenMirrorCodec.AV1)
        preferences.setPreferredCodec(revoked, ScreenMirrorCodec.H265)
        preferences.setPreferredCodec(other, ScreenMirrorCodec.H264)

        preferences.retainTrustedOwnPeers(
            listOf(
                roster(retained),
                roster(revoked, status = TrustStatus.REVOKED),
                roster(other, ownDevice = false),
            ),
        )

        assertEquals(mapOf(retained.value to ScreenMirrorCodec.AV1), preferences.preferredCodecs.value)
        val persisted = state.screenCodecPreferences().map(ScreenCodecPreferenceEntity::peerId)
        assertEquals(false, revoked.value in persisted)
        assertEquals(false, other.value in persisted)
    }

    @Test
    fun `unchanged roster pruning and codec selection do not write`() = runBlocking {
        val peer = ClientId("source")
        val state = InMemoryOperationalApplicationState(
            initialCodecs = listOf(ScreenCodecPreferenceEntity(peer.value, "av1")),
        )
        val preferences = ScreenMirrorCodecPreferenceStore(state)
        state.failWrites = true

        preferences.retainTrustedOwnPeers(listOf(roster(peer)))
        preferences.setPreferredCodec(peer, ScreenMirrorCodec.AV1)

        assertEquals(0, state.codecWrites)
        assertEquals(ScreenMirrorCodec.AV1, preferences.preferredCodec(peer))
    }

    @Test
    fun `changing one peer preserves other durable rows and auto removes unknown codecs`() = runBlocking {
        val future = ScreenCodecPreferenceEntity("future", "vvc")
        val state = InMemoryOperationalApplicationState(initialCodecs = listOf(future))
        val preferences = ScreenMirrorCodecPreferenceStore(state)

        preferences.setPreferredCodec(ClientId("known"), ScreenMirrorCodec.H264)
        assertEquals(true, future in state.screenCodecPreferences())
        assertEquals(1, state.codecWrites)

        preferences.setPreferredCodec(ClientId("future"), null)
        assertEquals(false, future in state.screenCodecPreferences())
        preferences.retainTrustedOwnPeers(emptyList())
        assertEquals(emptyList<ScreenCodecPreferenceEntity>(), state.screenCodecPreferences())
    }

    private fun roster(
        clientId: ClientId,
        status: TrustStatus = TrustStatus.TRUSTED,
        ownDevice: Boolean = true,
    ) = RosterDevice(
        clientId = clientId,
        status = status,
        displayName = clientId.value,
        keyAvailable = true,
        introducedByName = null,
        revokedAt = null,
        ownDevice = ownDevice,
        verified = true,
    )
}
