package net.extrawdw.apps.notisync.screen

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSingletons
import net.extrawdw.apps.notisync.data.storage.operational.ScreenAuthorizationAggregate
import net.extrawdw.apps.notisync.data.storage.operational.ScreenAuthorizedPeerEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenDao
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayConsumeResult
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayHealth
import net.extrawdw.apps.notisync.data.storage.operational.ScreenSecurityStateEntity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus

enum class ScreenReplayStateHealth { HEALTHY, CORRUPT }
enum class ScreenAuthorizationStateHealth { HEALTHY, PERSISTENCE_UNAVAILABLE }

class ScreenReplayStateUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Room-backed local authorization and replay state for screen control.
 *
 * Authorizations never converge through the peer trust table: control of this phone is an explicit
 * local decision. Replay rows contain only SHA-256 digests and expiry times, never rendezvous tokens
 * or PSKs. [ScreenDao] owns the transaction and receipt boundary; this adapter only keeps the
 * synchronous API required by the authenticated screen channel and exposes hot projections to UI.
 */
class ScreenMirrorAuthorizationStore internal constructor(
    private val dao: ScreenDao,
    scope: CoroutineScope,
) {
    private val lock = Any()
    private var aggregate: ScreenAuthorizationAggregate = loadAggregate()

    private val _authorizedPeerIds = MutableStateFlow(aggregate.peers.map { it.peerId }.toSet())
    val authorizedPeerIds: StateFlow<Set<String>> = _authorizedPeerIds.asStateFlow()

    private val _authorizationStateHealth = MutableStateFlow(ScreenAuthorizationStateHealth.HEALTHY)
    val authorizationStateHealth: StateFlow<ScreenAuthorizationStateHealth> =
        _authorizationStateHealth.asStateFlow()

    private val _replayStateHealth = MutableStateFlow(aggregate.replayHealth())
    val replayStateHealth: StateFlow<ScreenReplayStateHealth> = _replayStateHealth.asStateFlow()

    private val _screenMirroringEnabled = MutableStateFlow(aggregate.securityState?.enabled == true)
    val screenMirroringEnabled: StateFlow<Boolean> = _screenMirroringEnabled.asStateFlow()

    init {
        // A Room flow keeps the security header current when a maintenance/import action or another
        // owner changes it. Peer rows are only changed through this adapter after initialization, so
        // the existing DAO API intentionally does not add a second full-list peer flow.
        scope.launch {
            try {
                dao.observeSecurityState().collect { state ->
                    synchronized(lock) {
                        aggregate = aggregate.copy(securityState = state)
                        publishSecurityState(state)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                synchronized(lock) {
                    _authorizationStateHealth.value = ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE
                    _screenMirroringEnabled.value = false
                }
            }
        }
    }

    fun isAuthorized(peerId: ClientId): Boolean =
        _authorizationStateHealth.value == ScreenAuthorizationStateHealth.HEALTHY &&
            peerId.value in _authorizedPeerIds.value

    /**
     * Changes a local grant in the same Room transaction as its security revision. A grant is not
     * published in the in-memory projection until that transaction succeeds; revocation is still
     * published before the I/O so a live session fails closed immediately.
     */
    fun setAuthorized(peerId: ClientId, authorized: Boolean) = synchronized(lock) {
        val current = _authorizedPeerIds.value
        val next = if (authorized) current + peerId.value else current - peerId.value
        if (next == current && _authorizationStateHealth.value == ScreenAuthorizationStateHealth.HEALTHY) {
            return@synchronized
        }
        requireValidPeerId(peerId.value)
        val state = nextSecurityState()
        if (!authorized) _authorizedPeerIds.value = next
        try {
            val peers = next.toRoomPeers(state.updatedAt)
            runBlocking { dao.replaceAuthorizations(peers, state) }
            aggregate = ScreenAuthorizationAggregate(state, peers)
            _authorizedPeerIds.value = next
            publishSecurityState(state)
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.HEALTHY
        } catch (_: Throwable) {
            if (authorized) _authorizedPeerIds.value = current
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE
            return@synchronized
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
        val state = nextSecurityState()
        // Publish before I/O so roster revocation terminates a live controller synchronously.
        _authorizedPeerIds.value = next
        try {
            val peers = next.toRoomPeers(state.updatedAt)
            runBlocking { dao.replaceAuthorizations(peers, state) }
            aggregate = ScreenAuthorizationAggregate(state, peers)
            publishSecurityState(state)
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.HEALTHY
        } catch (_: Throwable) {
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE
            return@synchronized
        }
    }

    /** Persist the local screen-control master switch in the same security header as replay state. */
    fun setScreenMirroringEnabled(enabled: Boolean) = synchronized(lock) {
        val state = nextSecurityState().copy(enabled = enabled)
        try {
            runBlocking { dao.replaceSecurityState(state) }
            aggregate = aggregate.copy(securityState = state)
            publishSecurityState(state)
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.HEALTHY
        } catch (_: Throwable) {
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.PERSISTENCE_UNAVAILABLE
            return@synchronized
        }
    }

    fun screenMirroringEnabledNow(): Boolean = _screenMirroringEnabled.value

    /**
     * Atomically and durably consumes a valid request identity. False means it was already consumed,
     * disabled, quarantined, or at capacity. Storage failures remain retryable so the signed envelope
     * is not acknowledged before the Room commit.
     */
    fun consumeRequest(
        sessionId: String,
        routingToken: ByteArray,
        issuedAt: Long,
        expiresAt: Long,
        now: Long,
    ): Boolean = synchronized(lock) {
        if (
            sessionId.isBlank() || sessionId.length > 128 || sessionId.any(Char::isISOControl) ||
            routingToken.size != 16 ||
            !ScreenMirrorRequestValidator.validRequestTimeWindow(issuedAt, expiresAt, now)
        ) return@synchronized false
        if (_replayStateHealth.value != ScreenReplayStateHealth.HEALTHY) {
            throw ScreenReplayStateUnavailableException("screen replay state is quarantined")
        }
        val sessionDigest = digest(SESSION_REPLAY_DOMAIN, sessionId.toByteArray(Charsets.UTF_8))
        val tokenDigest = digest(TOKEN_REPLAY_DOMAIN, routingToken)
        return@synchronized try {
            when (
                runBlocking {
                    dao.consumeReplay(
                        sessionDigest = sessionDigest,
                        routingTokenDigest = tokenDigest,
                        expiresAt = expiresAt,
                        consumedAt = now,
                    )
                }
            ) {
                ScreenReplayConsumeResult.CONSUMED -> true
                ScreenReplayConsumeResult.DUPLICATE,
                ScreenReplayConsumeResult.CAPACITY_EXCEEDED,
                ScreenReplayConsumeResult.DISABLED,
                ScreenReplayConsumeResult.QUARANTINED -> false
            }
        } catch (error: Throwable) {
            throw if (error is ScreenReplayStateUnavailableException) {
                error
            } else {
                ScreenReplayStateUnavailableException("could not durably consume screen request", error)
            }
        }
    }

    /** Explicit recovery hook. Call only from a user-visible repair action while mirroring is disabled. */
    fun repairReplayState() = synchronized(lock) {
        try {
            val repairedAt = System.currentTimeMillis()
            runBlocking { dao.repairReplay(repairedAt) }
            val current = aggregate.securityState
            val repaired = (current ?: defaultSecurityState(repairedAt)).copy(
                enabled = false,
                replayHealth = ScreenReplayHealth.HEALTHY,
                quarantineDigest = null,
                quarantinedAt = null,
                updatedAt = repairedAt,
            )
            aggregate = aggregate.copy(securityState = repaired)
            publishSecurityState(repaired)
            _replayStateHealth.value = ScreenReplayStateHealth.HEALTHY
            _authorizationStateHealth.value = ScreenAuthorizationStateHealth.HEALTHY
        } catch (error: Throwable) {
            throw ScreenReplayStateUnavailableException("could not repair screen replay state", error)
        }
    }

    private fun loadAggregate(): ScreenAuthorizationAggregate = runBlocking { dao.readAuthorizations() }

    private fun nextSecurityState(): ScreenSecurityStateEntity {
        val now = System.currentTimeMillis()
        val current = aggregate.securityState
        return (current ?: defaultSecurityState(now)).copy(
            authorizationRevision = (current?.authorizationRevision ?: 0L) + 1L,
            updatedAt = now,
        )
    }

    private fun defaultSecurityState(now: Long) = ScreenSecurityStateEntity(
        singletonId = OperationalSingletons.ID,
        enabled = false,
        replayHealth = ScreenReplayHealth.HEALTHY,
        quarantineDigest = null,
        quarantinedAt = null,
        authorizationRevision = 0,
        updatedAt = now,
    )

    private fun publishSecurityState(state: ScreenSecurityStateEntity?) {
        _screenMirroringEnabled.value = state?.enabled == true
        _replayStateHealth.value = state.replayHealth()
    }

    private fun ScreenAuthorizationAggregate.replayHealth(): ScreenReplayStateHealth =
        securityState?.replayHealth.toReplayStateHealth()

    private fun ScreenSecurityStateEntity?.replayHealth(): ScreenReplayStateHealth =
        this?.replayHealth.toReplayStateHealth()

    private fun ScreenReplayHealth?.toReplayStateHealth(): ScreenReplayStateHealth = when (this) {
        null, ScreenReplayHealth.HEALTHY -> ScreenReplayStateHealth.HEALTHY
        ScreenReplayHealth.QUARANTINED -> ScreenReplayStateHealth.CORRUPT
    }

    private fun Set<String>.toRoomPeers(updatedAt: Long): List<ScreenAuthorizedPeerEntity> = sorted().map { id ->
        val previous = aggregate.peers.firstOrNull { it.peerId == id }
        ScreenAuthorizedPeerEntity(
            peerId = id,
            grantedAt = previous?.grantedAt ?: updatedAt,
            updatedAt = updatedAt,
        )
    }

    private fun digest(domain: ByteArray, value: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        digest.update(value)
        return digest.digest()
    }

    private companion object {
        val SESSION_REPLAY_DOMAIN = "notisync-screen/replay/v1/session\u0000".toByteArray(Charsets.UTF_8)
        val TOKEN_REPLAY_DOMAIN = "notisync-screen/replay/v1/token\u0000".toByteArray(Charsets.UTF_8)
    }
}

private fun requireValidPeerId(value: String) {
    require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) {
        "screen authorization peer id is invalid"
    }
}
