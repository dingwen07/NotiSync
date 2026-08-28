package net.extrawdw.notisync.sshagent.signing

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Types
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshSignCancellationReason
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.SshSignRequestCancelled
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshSignatureAlgorithm
import net.extrawdw.notisync.ssh.core.AgentNumbers
import net.extrawdw.notisync.ssh.core.SshPublicKeyCodec
import net.extrawdw.notisync.ssh.core.SshSignatureMethod
import net.extrawdw.notisync.ssh.core.SshSignatureVerifier
import net.extrawdw.notisync.sshagent.AgentConfig
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.bridge.SshApplicationBridge
import net.extrawdw.notisync.sshagent.bridge.SshInboundHandler
import net.extrawdw.notisync.sshagent.cache.AgentDatabase
import net.extrawdw.notisync.sshagent.cache.AgentMetadataStore
import net.extrawdw.notisync.sshagent.cache.AggregateIdentity
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore

sealed interface SignDecision {
    data class Signed(val signatureBlob: ByteArray, val provider: ClientId) : SignDecision
    data object RejectedByUser : SignDecision
    data class Failed(val reason: String) : SignDecision
}

class SignCoordinator(
    private val requesterClientId: ClientId,
    private val config: AgentConfig,
    private val roster: ProviderRoster,
    private val snapshots: ProviderSnapshotStore,
    private val metadata: AgentMetadataStore,
    private val bridge: SshApplicationBridge,
    database: AgentDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : SshInboundHandler {
    private val journal = SignOperationJournal(database)
    private val operations = java.util.concurrent.ConcurrentHashMap<String, SignOperation>()
    private val accepting = AtomicBoolean(true)

    fun identities(): List<AggregateIdentity> {
        val namespace = metadata.authorizationNamespace()
        return snapshots.aggregate(
            roster.activeProviderIds(),
            requesterClientId,
            namespace.generation,
            namespace.epoch,
            now(),
        )
    }

    fun sign(
        publicKeyBlob: ByteArray,
        data: ByteArray,
        flags: Long,
        connectionId: String,
        processContext: DesktopProcessContext = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
        destinationContext: SshDestinationContext = SshDestinationContext(
            SshDestinationProvenance.UNKNOWN,
            SshConnectionDirection.UNKNOWN,
        ),
        confirmationRequired: Boolean = false,
    ): SignDecision {
        if (!accepting.get()) return SignDecision.Failed("SSH agent is locked")
        val identity = identities().firstOrNull { it.publicKeyBlob.contentEquals(publicKeyBlob) }
            ?: return SignDecision.Failed("identity is not in the active provider cache")
        val decodedKey = runCatching { SshPublicKeyCodec.decode(publicKeyBlob) }.getOrElse {
            return SignDecision.Failed("invalid cached SSH public key")
        }
        val method = runCatching {
            SshSignatureVerifier.methodFor(decodedKey.type, flags, config.allowLegacyRsaSha1)
        }.getOrElse { return SignDecision.Failed(it.message ?: "unsupported signature flags") }
        val eligible = identity.candidates.map(ProviderCandidateId).distinct().sortedBy(ClientId::value)
        if (eligible.isEmpty()) return SignDecision.Failed("no eligible key provider")
        val namespace = metadata.authorizationNamespace()
        val requestedAt = now()
        val request = SshSignRequest(
            requestId = randomId(),
            requesterClientId = requesterClientId,
            requestedAt = requestedAt,
            expiresAt = requestedAt + config.signTimeoutSeconds * 1000,
            publicKeyBlob = publicKeyBlob.copyOf(),
            data = data.copyOf(),
            flags = flags,
            requestedSignatureAlgorithm = method.toProtocolAlgorithm(),
            eligibleProviderClientIds = eligible,
            authorizationGeneration = namespace.generation,
            authorizationEpoch = namespace.epoch,
            processContext = processContext,
            destinationContext = destinationContext,
            connectionId = connectionId,
            confirmationRequired = confirmationRequired,
        )
        val operation = SignOperation(request, method)
        journal.begin(request)
        if (!accepting.get()) {
            val decision = SignDecision.Failed("SSH agent is locked")
            journal.terminal(request.requestId, decision, null, now())
            return decision
        }
        check(operations.putIfAbsent(request.requestId, operation) == null)
        if (!accepting.get()) {
            operations.remove(request.requestId, operation)
            return finishLocally(operation, SignDecision.Failed("SSH agent is locked"), SshSignCancellationReason.AGENT_LOCKED)
        }
        try {
            bridge.sendSignRequest(request)
        } catch (failure: Exception) {
            operations.remove(request.requestId, operation)
            val decision = SignDecision.Failed(failure.message ?: "could not submit sign request")
            journal.terminal(request.requestId, decision, null, now())
            return decision
        }

        return try {
            operation.future.get(config.signTimeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            finishLocally(operation, SignDecision.Failed("sign request timed out"), SshSignCancellationReason.REQUEST_TIMEOUT)
        } finally {
            operations.remove(request.requestId, operation)
        }
    }

    override fun onSignResult(authenticatedProvider: ClientId, result: SshSignResult) {
        val operation = operations[result.requestId]
        if (operation == null || !operation.matches(authenticatedProvider, result)) return
        val normalized = if (result.kind == SshSignResultKind.SIGNED) {
            val signatureBlob = requireNotNull(result.signature).signatureBlob
            if (SshSignatureVerifier.verify(
                    operation.request.publicKeyBlob,
                    operation.request.data,
                    signatureBlob,
                    operation.method,
                    config.allowLegacyRsaSha1,
                )
            ) result else result.copy(
                kind = SshSignResultKind.PROVIDER_FAILURE,
                signature = null,
                failure = net.extrawdw.notisync.protocol.SshProviderFailure(
                    net.extrawdw.notisync.protocol.SshProviderFailureCode.INTERNAL_FAILURE,
                    message = "provider returned an invalid signature",
                ),
            )
        } else result
        journal.outcome(operation.request.requestId, authenticatedProvider, normalized, now())
        val transition = operation.accept(authenticatedProvider, normalized)
        if (transition.decision != null) {
            journal.terminal(operation.request.requestId, transition.decision, transition.winner, now())
            operation.future.complete(transition.decision)
            cancel(operation, transition.pendingProviders, transition.cancelReason)
        }
    }

    fun suspendForLock() {
        accepting.set(false)
        operations.values.forEach { operation ->
            finishLocally(operation, SignDecision.Failed("SSH agent locked"), SshSignCancellationReason.AGENT_LOCKED)
        }
    }

    fun resumeAfterUnlock() {
        accepting.set(true)
    }

    private fun finishLocally(
        operation: SignOperation,
        decision: SignDecision,
        reason: SshSignCancellationReason,
    ): SignDecision {
        val pending = operation.finish(decision)
        if (pending != null) {
            journal.terminal(operation.request.requestId, decision, null, now())
            operation.future.complete(decision)
            cancel(operation, pending, reason)
        }
        return operation.future.getNow(decision)
    }

    private fun cancel(
        operation: SignOperation,
        providers: List<ClientId>,
        reason: SshSignCancellationReason?,
    ) {
        if (providers.isEmpty() || reason == null) return
        runCatching {
            bridge.sendSignRequestCancelled(
                SshSignRequestCancelled(
                    operation.request.requestId,
                    requesterClientId,
                    now(),
                    reason,
                    providers.sortedBy(ClientId::value),
                ),
            )
        }
    }

    private class SignOperation(
        val request: SshSignRequest,
        val method: SshSignatureMethod,
    ) {
        val future = CompletableFuture<SignDecision>()
        private val pending = request.eligibleProviderClientIds.toMutableSet()
        private var terminal = false

        fun matches(provider: ClientId, result: SshSignResult): Boolean =
            provider in request.eligibleProviderClientIds &&
                result.providerClientId == provider &&
                result.requesterClientId == request.requesterClientId &&
                result.publicKeyBlobSha256.contentEquals(sha256(request.publicKeyBlob)) &&
                (result.signature?.let { signature ->
                    signature.authorizationGeneration == request.authorizationGeneration &&
                        signature.authorizationEpoch == request.authorizationEpoch
                } ?: true) &&
                result.resultAt <= request.expiresAt + 30_000

        @Synchronized
        fun accept(provider: ClientId, result: SshSignResult): Transition {
            if (terminal || provider !in pending) return Transition()
            return when (result.kind) {
                SshSignResultKind.SIGNED -> {
                    terminal = true
                    pending.remove(provider)
                    Transition(
                        SignDecision.Signed(requireNotNull(result.signature).signatureBlob, provider),
                        pending.toList(),
                        provider,
                        SshSignCancellationReason.SIGNED_ELSEWHERE,
                    )
                }
                SshSignResultKind.REJECTED_BY_USER -> {
                    terminal = true
                    pending.remove(provider)
                    Transition(
                        SignDecision.RejectedByUser,
                        pending.toList(),
                        provider,
                        SshSignCancellationReason.REJECTED_ELSEWHERE,
                    )
                }
                SshSignResultKind.PROVIDER_FAILURE -> {
                    pending.remove(provider)
                    if (pending.isEmpty()) {
                        terminal = true
                        Transition(SignDecision.Failed("all providers failed"), emptyList(), null, null)
                    } else Transition()
                }
            }
        }

        @Synchronized
        fun finish(decision: SignDecision): List<ClientId>? {
            if (terminal) return null
            terminal = true
            return pending.toList()
        }

        data class Transition(
            val decision: SignDecision? = null,
            val pendingProviders: List<ClientId> = emptyList(),
            val winner: ClientId? = null,
            val cancelReason: SshSignCancellationReason? = null,
        )
    }

    private class SignOperationJournal(private val database: AgentDatabase) {
        fun begin(request: SshSignRequest) = database.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO sign_operation_log(
                    request_id, public_blob_hash, data_sha256, eligible_provider_ids, created_at
                ) VALUES(?,?,?,?,?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, request.requestId)
                statement.setBytes(2, sha256(request.publicKeyBlob))
                statement.setBytes(3, sha256(request.data))
                statement.setString(4, request.eligibleProviderClientIds.joinToString(",", transform = ClientId::value))
                statement.setLong(5, request.requestedAt)
                statement.executeUpdate()
            }
        }

        fun outcome(requestId: String, provider: ClientId, result: SshSignResult, receivedAt: Long) =
            database.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO provider_outcomes(request_id, provider_id, outcome_kind, received_at, details) VALUES(?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, requestId)
                    statement.setString(2, provider.value)
                    statement.setString(3, result.kind.name)
                    statement.setLong(4, receivedAt)
                    val details = result.failure?.code?.name ?: result.rejection?.reason?.name
                    if (details == null) statement.setNull(5, Types.VARCHAR) else statement.setString(5, details)
                    statement.executeUpdate()
                }
            }

        fun terminal(requestId: String, decision: SignDecision, winner: ClientId?, terminalAt: Long) =
            database.transaction { connection ->
                connection.prepareStatement(
                    "UPDATE sign_operation_log SET terminal_at=?, terminal_kind=?, winning_provider=? WHERE request_id=? AND terminal_at IS NULL",
                ).use { statement ->
                    statement.setLong(1, terminalAt)
                    statement.setString(2, decision.javaClass.simpleName)
                    if (winner == null) statement.setNull(3, Types.VARCHAR) else statement.setString(3, winner.value)
                    statement.setString(4, requestId)
                    statement.executeUpdate()
                }
            }
    }

    private object ProviderCandidateId : (net.extrawdw.notisync.sshagent.cache.ProviderCandidate) -> ClientId {
        override fun invoke(candidate: net.extrawdw.notisync.sshagent.cache.ProviderCandidate) = candidate.providerClientId
    }

    private fun SshSignatureMethod.toProtocolAlgorithm(): SshSignatureAlgorithm = when (this) {
        SshSignatureMethod.ED25519 -> SshSignatureAlgorithm.SSH_ED25519
        SshSignatureMethod.RSA_SHA2_256 -> SshSignatureAlgorithm.RSA_SHA2_256
        SshSignatureMethod.RSA_SHA2_512 -> SshSignatureAlgorithm.RSA_SHA2_512
        SshSignatureMethod.ECDSA_NISTP256 -> SshSignatureAlgorithm.ECDSA_NISTP256
        SshSignatureMethod.WEBAUTHN_SK_ECDSA_NISTP256 ->
            SshSignatureAlgorithm.WEBAUTHN_SK_ECDSA_NISTP256
        SshSignatureMethod.RSA_SHA1_LEGACY -> SshSignatureAlgorithm.RSA_SHA1_LEGACY
    }

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val RANDOM = SecureRandom()
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
