package net.extrawdw.apps.notisync.screen

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.ScreenMirrorStateEntity
import net.extrawdw.apps.notisync.testsupport.InMemoryOperationalApplicationState
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
        val state = InMemoryOperationalApplicationState()
        val repository = ScreenMirrorAuthorizationStore(state)
        val peer = ClientId("peer-a")
        val token = ByteArray(16) { 7 }

        assertFalse(repository.isAuthorized(peer))
        repository.setAuthorized(peer, true)
        assertTrue(repository.isAuthorized(peer))
        assertTrue(repository.consumeRequest("session-secret-name", token, 10_000L, 20_000L, 10_000L))
        assertFalse(repository.consumeRequest("session-secret-name", token, 10_000L, 20_000L, 10_001L))
        assertFalse(
            repository.consumeRequest(
                "session-secret-name",
                ByteArray(16) { 8 },
                10_000L,
                20_000L,
                10_002L,
            ),
        )
        assertFalse(repository.consumeRequest("different-session", token, 10_000L, 20_000L, 10_003L))
        assertTrue(
            repository.consumeRequest(
                "fresh-session",
                ByteArray(16) { 9 },
                10_000L,
                20_000L,
                10_004L,
            ),
        )
        assertFalse(repository.consumeRequest("expired", ByteArray(16), 10_000L, 10_004L, 10_004L))
        assertFalse(repository.consumeRequest("bad-token", ByteArray(15), 10_000L, 20_000L, 10_004L))
        assertFalse(
            repository.consumeRequest(
                "too-far",
                ByteArray(16) { 3 },
                10_004L,
                10_004L + 5L * 60 * 1000 + 1,
                10_004L,
            ),
        )

        val encoded = state.screenMirrorState().requestReplayJson.orEmpty()
        assertFalse(encoded.contains("session-secret-name"))
        assertFalse(encoded.contains("BwcHBwcHBwcHBwcHBwcHBw"))

        repository.setAuthorized(peer, false)
        assertFalse(repository.isAuthorized(peer))
        val persistedAuthorizations = state.screenMirrorState().authorizedPeerIdsJson
        assertFalse(persistedAuthorizations.contains(peer.value))
    }

    @Test
    fun corruptReplayStateIsQuarantinedAndDisablesMirroringUntilExplicitRepair() = runBlocking {
        val state = InMemoryOperationalApplicationState(
            initialScreenState = screenState(enabled = true, replay = "{not-json"),
        )
        val repository = ScreenMirrorAuthorizationStore(state)

        assertEquals(ScreenReplayStateHealth.CORRUPT, repository.replayStateHealth.value)
        val quarantined = state.screenMirrorState()
        assertFalse(quarantined.enabled)
        assertNull(quarantined.requestReplayJson)
        assertNotNull(quarantined.replayQuarantineDigest)
        assertThrows(ScreenReplayStateUnavailableException::class.java) {
            repository.consumeRequest("session", ByteArray(16) { 1 }, 10_000, 20_000, 10_000)
        }

        repository.repairReplayState()
        assertEquals(ScreenReplayStateHealth.HEALTHY, repository.replayStateHealth.value)
        assertTrue(repository.consumeRequest("session", ByteArray(16) { 1 }, 10_000, 20_000, 10_000))
    }

    @Test
    fun replayPersistenceFailureIsRetryableAndDoesNotConsumeIdentity() = runBlocking {
        val state = InMemoryOperationalApplicationState()
        val repository = ScreenMirrorAuthorizationStore(state)
        state.failWrites = true

        assertThrows(ScreenReplayStateUnavailableException::class.java) {
            repository.consumeRequest("session", ByteArray(16) { 2 }, 10_000, 20_000, 10_000)
        }

        state.failWrites = false
        assertTrue(repository.consumeRequest("session", ByteArray(16) { 2 }, 10_000, 20_000, 10_001))
    }

    @Test
    fun requesterClockAheadWithinValidatorSkew_isConsumedOnce() = runBlocking {
        val repository = ScreenMirrorAuthorizationStore(InMemoryOperationalApplicationState())
        val sourceNow = 10_000L
        val requesterIssuedAt = sourceNow + 1_700L
        val expiresAt = requesterIssuedAt + ScreenMirrorRequestValidator.MAX_REQUEST_LIFETIME_MS
        val token = ByteArray(16) { 3 }

        assertTrue(
            repository.consumeRequest(
                "clock-skewed-session",
                token,
                requesterIssuedAt,
                expiresAt,
                sourceNow,
            ),
        )
        assertFalse(
            repository.consumeRequest(
                "clock-skewed-session",
                token,
                requesterIssuedAt,
                expiresAt,
                sourceNow + 1,
            ),
        )
    }

    @Test
    fun `roster revocation fails closed in memory when persistence is unavailable`() = runBlocking {
        val state = InMemoryOperationalApplicationState()
        val repository = ScreenMirrorAuthorizationStore(state)
        val peer = ClientId("peer-a")
        repository.setAuthorized(peer, true)
        assertTrue(repository.isAuthorized(peer))

        state.failWrites = true
        repository.retainTrustedOwnPeers(emptyList())

        assertFalse(repository.isAuthorized(peer))
        assertTrue(repository.authorizedPeerIds.value.isEmpty())
        assertEquals(
            ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE,
            repository.authorizationStateHealth.value,
        )

        // A later roster emission repairs the durable fail-closed state after storage recovers.
        state.failWrites = false
        repository.retainTrustedOwnPeers(emptyList())
        assertEquals(ScreenAuthorizationStateHealth.HEALTHY, repository.authorizationStateHealth.value)
        assertFalse(state.screenMirrorState().authorizedPeerIdsJson.contains(peer.value))
    }

    private fun screenState(enabled: Boolean = false, replay: String? = null) = ScreenMirrorStateEntity(
        enabled = enabled,
        authorizedPeerIdsJson = "[]",
        requestReplayJson = replay,
        replayBlocked = false,
        replayQuarantineDigest = null,
        replayQuarantinedAt = null,
    )
}
