package net.extrawdw.apps.notisync.screen

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.TrustStatus

enum class ScreenReplayStateHealth { HEALTHY, CORRUPT }
enum class ScreenAuthorizationStateHealth { HEALTHY, PERSISTENCE_UNAVAILABLE }

class ScreenReplayStateUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

private class CorruptScreenReplayState(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Local authorization and replay state for screen control.
 *
 * Authorizations never converge through the peer trust table: control of this phone is an explicit local
 * decision. Replay rows contain only SHA-256 digests and expiry times, never rendezvous tokens or PSKs.
 */
class ScreenMirrorAuthorizationStore(
    private val store: DataStore<Preferences>,
) {
    private val lock = Any()
    private val authorizedKey = stringPreferencesKey("screen_mirror_authorized_peer_ids")
    private val replayKey = stringPreferencesKey("screen_mirror_request_replay_v1")
    private val replayBlockedKey = booleanPreferencesKey("screen_mirror_request_replay_v1_blocked")
    private val replayQuarantineDigestKey = stringPreferencesKey("screen_mirror_request_replay_v1_quarantine_digest")
    private val replayQuarantinedAtKey = longPreferencesKey("screen_mirror_request_replay_v1_quarantined_at")
    private val screenMirroringEnabledKey = booleanPreferencesKey("screen_mirroring_enabled")

    private val _authorizedPeerIds = MutableStateFlow(loadAuthorized())
    val authorizedPeerIds: StateFlow<Set<String>> = _authorizedPeerIds.asStateFlow()
    private val _authorizationStateHealth = MutableStateFlow(ScreenAuthorizationStateHealth.HEALTHY)
    val authorizationStateHealth: StateFlow<ScreenAuthorizationStateHealth> =
        _authorizationStateHealth.asStateFlow()
    private val _replayStateHealth = MutableStateFlow(ScreenReplayStateHealth.HEALTHY)
    val replayStateHealth: StateFlow<ScreenReplayStateHealth> = _replayStateHealth.asStateFlow()

    init {
        _replayStateHealth.value = inspectAndQuarantineReplayState()
    }

    fun isAuthorized(peerId: ClientId): Boolean =
        _authorizationStateHealth.value == ScreenAuthorizationStateHealth.HEALTHY &&
            peerId.value in _authorizedPeerIds.value

    fun setAuthorized(peerId: ClientId, authorized: Boolean) = synchronized(lock) {
        val current = _authorizedPeerIds.value
        val next = if (authorized) current + peerId.value else current - peerId.value
        if (next == current && _authorizationStateHealth.value == ScreenAuthorizationStateHealth.HEALTHY) {
            return@synchronized
        }
        if (authorized) {
            // A new grant never becomes routing authority until it is durable.
            if (persistAuthorizedOrFailClosed(next)) _authorizedPeerIds.value = next
        } else {
            // Revocation is fail-closed in the opposite order: active sessions observe it immediately,
            // even if DataStore is currently unavailable.
            _authorizedPeerIds.value = next
            persistAuthorizedOrFailClosed(next)
        }
    }

    /** Remove grants as soon as a peer is no longer a trusted own device. */
    fun retainTrustedOwnPeers(roster: Collection<RosterDevice>) = synchronized(lock) {
        val allowed = roster.asSequence()
            .filter { it.ownDevice && it.status == TrustStatus.TRUSTED && it.verified }
            .map { it.clientId.value }
            .toSet()
        val current = _authorizedPeerIds.value
        val next = current.intersect(allowed)
        if (next == current && _authorizationStateHealth.value == ScreenAuthorizationStateHealth.HEALTHY) {
            return@synchronized
        }
        // Publish before I/O so roster revocation terminates a live controller synchronously.
        _authorizedPeerIds.value = next
        persistAuthorizedOrFailClosed(next)
    }

    /**
     * Atomically and durably consumes a valid request identity. False means it was already consumed.
     * The synchronous contract is intentional: the secure channel may acknowledge immediately after its
     * handler returns, so replay state must reach DataStore first.
     */
    fun consumeRequest(
        sessionId: String,
        routingToken: ByteArray,
        expiresAt: Long,
        now: Long,
    ): Boolean = synchronized(lock) {
        if (
            sessionId.isBlank() || sessionId.length > 128 || sessionId.any(Char::isISOControl) ||
            routingToken.size != 16 || expiresAt <= now || expiresAt - now > MAX_REQUEST_LIFETIME_MS
        ) return@synchronized false
        if (_replayStateHealth.value != ScreenReplayStateHealth.HEALTHY) {
            throw ScreenReplayStateUnavailableException("screen replay state is quarantined")
        }
        var accepted = false
        var encodedReplay: String? = null
        try {
            runBlocking {
                store.edit { preferences ->
                    if (preferences[replayBlockedKey] == true) {
                        throw CorruptScreenReplayState("screen replay state is quarantined")
                    }
                    encodedReplay = preferences[replayKey]
                    val rows = decodeReplayStrict(encodedReplay)
                        .filterValues { it > now }
                        .toMutableMap()
                    val sessionDigest = digest(SESSION_REPLAY_DOMAIN, sessionId.toByteArray(Charsets.UTF_8))
                    val tokenDigest = digest(TOKEN_REPLAY_DOMAIN, routingToken)
                    if (
                        sessionDigest !in rows && tokenDigest !in rows &&
                        rows.size <= MAX_REPLAY_ROWS - ROWS_PER_REQUEST
                    ) {
                        rows[sessionDigest] = expiresAt
                        rows[tokenDigest] = expiresAt
                        accepted = true
                    }
                    preferences[replayKey] = ProtocolCodec.encodeToJson(
                        rows.entries
                            .sortedByDescending { it.value }
                            .associate { it.key to it.value },
                    )
                }
            }
            accepted
        } catch (error: CorruptScreenReplayState) {
            quarantineReplayState(encodedReplay, now)
            throw ScreenReplayStateUnavailableException("screen replay state failed validation", error)
        } catch (error: ScreenReplayStateUnavailableException) {
            throw error
        } catch (error: Throwable) {
            // The signed envelope must remain unacknowledged so the relay can retry after storage recovers.
            throw ScreenReplayStateUnavailableException("could not durably consume screen request", error)
        }
    }

    /** Explicit recovery hook. Call only from a user-visible repair action while mirroring is disabled. */
    fun repairReplayState() = synchronized(lock) {
        try {
            runBlocking {
                store.edit { preferences ->
                    preferences.remove(replayKey)
                    preferences.remove(replayBlockedKey)
                    preferences.remove(replayQuarantineDigestKey)
                    preferences.remove(replayQuarantinedAtKey)
                }
            }
            _replayStateHealth.value = ScreenReplayStateHealth.HEALTHY
        } catch (error: Throwable) {
            throw ScreenReplayStateUnavailableException("could not repair screen replay state", error)
        }
    }

    private fun loadAuthorized(): Set<String> = runBlocking {
        decodeAuthorized(store.data.first()[authorizedKey])
    }

    private suspend fun persistAuthorized(value: Set<String>) {
        store.edit { it[authorizedKey] = ProtocolCodec.encodeToJson(value.sorted().toSet()) }
    }

    /**
     * Returns true only when [value] became durable. Any failure removes every in-memory grant and
     * tries to persist that fail-closed state. The health flow disables capability advertisement if
     * even the empty fallback cannot be committed.
     */
    private fun persistAuthorizedOrFailClosed(value: Set<String>): Boolean {
        return try {
            runBlocking { persistAuthorized(value) }
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.HEALTHY
            true
        } catch (_: Throwable) {
            _authorizedPeerIds.value = emptySet()
            val cleared = runCatching { runBlocking { persistAuthorized(emptySet()) } }.isSuccess
            _authorizationStateHealth.value = if (cleared) {
                ScreenAuthorizationStateHealth.HEALTHY
            } else {
                ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE
            }
            false
        }
    }

    private fun decodeAuthorized(encoded: String?): Set<String> = encoded?.let {
        runCatching { ProtocolCodec.decodeFromJson<Set<String>>(it) }.getOrDefault(emptySet())
    } ?: emptySet()

    private fun inspectAndQuarantineReplayState(): ScreenReplayStateHealth = runBlocking {
        val preferences = store.data.first()
        if (preferences[replayBlockedKey] == true) return@runBlocking ScreenReplayStateHealth.CORRUPT
        val encoded = preferences[replayKey] ?: return@runBlocking ScreenReplayStateHealth.HEALTHY
        try {
            decodeReplayStrict(encoded)
            ScreenReplayStateHealth.HEALTHY
        } catch (_: CorruptScreenReplayState) {
            quarantineReplayState(encoded, System.currentTimeMillis())
            ScreenReplayStateHealth.CORRUPT
        }
    }

    private fun quarantineReplayState(encoded: String?, detectedAt: Long) {
        _replayStateHealth.value = ScreenReplayStateHealth.CORRUPT
        runCatching {
            runBlocking {
                store.edit { preferences ->
                    encoded?.let {
                        preferences[replayQuarantineDigestKey] = digest(
                            CORRUPT_REPLAY_DOMAIN,
                            it.toByteArray(Charsets.UTF_8),
                        )
                    }
                    preferences[replayQuarantinedAtKey] = detectedAt
                    preferences[replayBlockedKey] = true
                    preferences.remove(replayKey)
                    // A corrupt replay ledger must also remove routing authority until an explicit repair.
                    preferences[screenMirroringEnabledKey] = false
                }
            }
        }
    }

    private fun decodeReplayStrict(encoded: String?): Map<String, Long> {
        if (encoded == null) return emptyMap()
        val decoded = try {
            ProtocolCodec.decodeFromJson<Map<String, Long>>(encoded)
        } catch (error: Throwable) {
            throw CorruptScreenReplayState("screen replay state is not valid JSON", error)
        }
        if (
            decoded.size > MAX_REPLAY_ROWS ||
            decoded.any { (key, expiry) ->
                key.length != SHA256_BASE64URL_LENGTH || key.any { it !in BASE64URL_CHARS } || expiry <= 0
            }
        ) {
            throw CorruptScreenReplayState("screen replay state contains invalid rows")
        }
        return decoded
    }

    private fun digest(domain: ByteArray, value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        digest.update(value)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }

    private companion object {
        const val MAX_REPLAY_ROWS = 512
        const val ROWS_PER_REQUEST = 2
        const val MAX_REQUEST_LIFETIME_MS = 5L * 60 * 1000
        const val SHA256_BASE64URL_LENGTH = 43
        const val BASE64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val SESSION_REPLAY_DOMAIN = "notisync-screen/replay/v1/session\u0000".toByteArray(Charsets.UTF_8)
        val TOKEN_REPLAY_DOMAIN = "notisync-screen/replay/v1/token\u0000".toByteArray(Charsets.UTF_8)
        val CORRUPT_REPLAY_DOMAIN = "notisync-screen/replay/v1/corrupt\u0000".toByteArray(Charsets.UTF_8)
    }
}
