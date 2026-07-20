package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorAuthorizationStoreTest {
    @Test
    fun authorizationAndReplayAreLocalAndReplayStoresOnlyDigest() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("screen-auth-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val repository = ScreenMirrorAuthorizationStore(dataStore)
        val peer = ClientId("peer-a")
        val token = ByteArray(16) { 7 }

        assertFalse(repository.isAuthorized(peer))
        repository.setAuthorized(peer, true)
        assertTrue(repository.isAuthorized(peer))
        assertTrue(repository.consumeRequest("session-secret-name", token, 20_000L, 10_000L))
        assertFalse(repository.consumeRequest("session-secret-name", token, 20_000L, 10_001L))
        assertFalse(repository.consumeRequest("session-secret-name", ByteArray(16) { 8 }, 20_000L, 10_002L))
        assertFalse(repository.consumeRequest("different-session", token, 20_000L, 10_003L))
        assertTrue(
            repository.consumeRequest("fresh-session", ByteArray(16) { 9 }, 20_000L, 10_004L),
        )
        assertFalse(repository.consumeRequest("expired", ByteArray(16), 10_004L, 10_004L))
        assertFalse(repository.consumeRequest("bad-token", ByteArray(15), 20_000L, 10_004L))
        assertFalse(
            repository.consumeRequest(
                "too-far",
                ByteArray(16) { 3 },
                10_004L + 5L * 60 * 1000 + 1,
                10_004L,
            ),
        )

        val encoded = dataStore.data.first()[stringPreferencesKey("screen_mirror_request_replay_v1")].orEmpty()
        assertFalse(encoded.contains("session-secret-name"))
        assertFalse(encoded.contains("BwcHBwcHBwcHBwcHBwcHBw"))

        repository.setAuthorized(peer, false)
        assertFalse(repository.isAuthorized(peer))
        val persistedAuthorizations = dataStore.data.first()[
            stringPreferencesKey("screen_mirror_authorized_peer_ids")
        ].orEmpty()
        assertFalse(persistedAuthorizations.contains(peer.value))
    }

    @Test
    fun corruptReplayStateIsQuarantinedAndDisablesMirroringUntilExplicitRepair() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("screen-auth-corrupt-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val replayKey = stringPreferencesKey("screen_mirror_request_replay_v1")
        val enabledKey = booleanPreferencesKey("screen_mirroring_enabled")
        dataStore.edit {
            it[replayKey] = "{not-json"
            it[enabledKey] = true
        }

        val repository = ScreenMirrorAuthorizationStore(dataStore)

        assertEquals(ScreenReplayStateHealth.CORRUPT, repository.replayStateHealth.value)
        val quarantined = dataStore.data.first()
        assertFalse(quarantined[enabledKey] ?: true)
        assertNull(quarantined[replayKey])
        assertNotNull(quarantined[stringPreferencesKey("screen_mirror_request_replay_v1_quarantine_digest")])
        assertThrows(ScreenReplayStateUnavailableException::class.java) {
            repository.consumeRequest("session", ByteArray(16) { 1 }, 20_000, 10_000)
        }

        repository.repairReplayState()
        assertEquals(ScreenReplayStateHealth.HEALTHY, repository.replayStateHealth.value)
        assertTrue(repository.consumeRequest("session", ByteArray(16) { 1 }, 20_000, 10_000))
    }

    @Test
    fun replayPersistenceFailureIsRetryableAndDoesNotConsumeIdentity() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("screen-auth-failing-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val delegate: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val failing = FailingDataStore(delegate)
        val repository = ScreenMirrorAuthorizationStore(failing)
        failing.failUpdates = true

        assertThrows(ScreenReplayStateUnavailableException::class.java) {
            repository.consumeRequest("session", ByteArray(16) { 2 }, 20_000, 10_000)
        }

        failing.failUpdates = false
        assertTrue(repository.consumeRequest("session", ByteArray(16) { 2 }, 20_000, 10_001))
    }

    @Test
    fun `roster revocation fails closed in memory when persistence is unavailable`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("screen-auth-revoke-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val delegate: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val failing = FailingDataStore(delegate)
        val repository = ScreenMirrorAuthorizationStore(failing)
        val peer = ClientId("peer-a")
        repository.setAuthorized(peer, true)
        assertTrue(repository.isAuthorized(peer))

        failing.failUpdates = true
        repository.retainTrustedOwnPeers(emptyList())

        assertFalse(repository.isAuthorized(peer))
        assertTrue(repository.authorizedPeerIds.value.isEmpty())
        assertEquals(
            ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE,
            repository.authorizationStateHealth.value,
        )

        // A later roster emission repairs the durable fail-closed state after storage recovers.
        failing.failUpdates = false
        repository.retainTrustedOwnPeers(emptyList())
        assertEquals(ScreenAuthorizationStateHealth.HEALTHY, repository.authorizationStateHealth.value)
        assertFalse(
            delegate.data.first()[stringPreferencesKey("screen_mirror_authorized_peer_ids")]
                .orEmpty()
                .contains(peer.value),
        )
    }

    private class FailingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        @Volatile var failUpdates: Boolean = false
        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failUpdates) throw IOException("synthetic DataStore failure")
            return delegate.updateData(transform)
        }
    }
}
