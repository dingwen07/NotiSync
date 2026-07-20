package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMirrorCodecPreferenceStoreTest {
    @Test
    fun `preferences persist per source and auto removes the override`() = runBlocking {
        val dataStore = dataStore("codec-preference")
        val preferences = ScreenMirrorCodecPreferenceStore(dataStore)
        val first = ClientId("source-one")
        val second = ClientId("source-two")

        preferences.setPreferredCodec(first, ScreenMirrorCodec.AV1)
        preferences.setPreferredCodec(second, ScreenMirrorCodec.H264)

        val reloaded = ScreenMirrorCodecPreferenceStore(dataStore)
        assertEquals(ScreenMirrorCodec.AV1, reloaded.preferredCodec(first))
        assertEquals(ScreenMirrorCodec.H264, reloaded.preferredCodec(second))

        reloaded.setPreferredCodec(first, null)
        assertNull(reloaded.preferredCodec(first))
        assertEquals(ScreenMirrorCodec.H264, reloaded.preferredCodec(second))
    }

    @Test
    fun `unknown persisted codec is dropped without losing known preferences`() = runBlocking {
        val dataStore = dataStore("codec-forward-compat")
        dataStore.edit {
            it[stringPreferencesKey(KEY)] = ProtocolCodec.encodeToJson(
                mapOf("known" to "h265", "future" to "vvc"),
            )
        }

        val preferences = ScreenMirrorCodecPreferenceStore(dataStore)

        assertEquals(ScreenMirrorCodec.H265, preferences.preferredCodec(ClientId("known")))
        assertNull(preferences.preferredCodec(ClientId("future")))
    }

    @Test
    fun `revoked reclassified and removed sources lose their preference`() = runBlocking {
        val dataStore = dataStore("codec-trust-cleanup")
        val preferences = ScreenMirrorCodecPreferenceStore(dataStore)
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
        val persisted = dataStore.data.first()[stringPreferencesKey(KEY)].orEmpty()
        assertEquals(false, persisted.contains(revoked.value))
        assertEquals(false, persisted.contains(other.value))
    }

    private fun dataStore(name: String): DataStore<Preferences> {
        val file = File.createTempFile("$name-${System.nanoTime()}", ".preferences_pb")
            .also(File::delete)
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) { file }
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

    private companion object {
        const val KEY = "screen_mirror_codec_preferences_v1"
    }
}
