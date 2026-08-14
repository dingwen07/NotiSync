package net.extrawdw.notisync.sshagent.signing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshImportConstraints
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshImportResultKind
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.ssh.core.ParsedAgentIdentity
import net.extrawdw.notisync.sshagent.AgentConfig
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.bridge.SshApplicationBridge
import net.extrawdw.notisync.sshagent.bridge.SshInboundHandler
import net.extrawdw.notisync.sshagent.endpoint.IdentityImporter

class ImportCoordinator(
    private val requesterClientId: ClientId,
    private val config: AgentConfig,
    private val roster: ProviderRoster,
    private val bridge: SshApplicationBridge,
    private val now: () -> Long = System::currentTimeMillis,
) : IdentityImporter, SshInboundHandler {
    private data class Pending(
        val provider: ClientId,
        val requestDigest: ByteArray,
        val future: CompletableFuture<Boolean>,
    )

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    override fun import(identityPayload: ByteArray, parsed: ParsedAgentIdentity): Boolean {
        val configured = config.defaultProviderClientId?.let(::ClientId) ?: return false
        if (roster.activeProviderIds().none { it == configured }) return false
        val requestedAt = now()
        val request = SshImportRequest(
            requestId = randomId(),
            requesterClientId = requesterClientId,
            requestedAt = requestedAt,
            expiresAt = requestedAt + TimeUnit.MINUTES.toMillis(5),
            sourceType = SshImportSourceType.AGENT_IDENTITY,
            agentIdentity = identityPayload,
            constraints = SshImportConstraints(
                lifetimeSeconds = parsed.constraints.lifetimeSeconds,
                confirmationRequired = parsed.constraints.confirm,
            ),
            suggestedName = parsed.comment.takeIf(String::isNotBlank),
        )
        val operation = Pending(
            configured,
            sha256(ProtocolCodec.encodeToCbor(request)),
            CompletableFuture(),
        )
        check(pending.putIfAbsent(request.requestId, operation) == null)
        return try {
            bridge.sendNormal(
                SshAgentSync(kind = SshAgentSyncKind.IMPORT_REQUEST, importRequest = request),
                listOf(configured),
            )
            operation.future.get(5, TimeUnit.MINUTES)
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(request.requestId, operation)
        }
    }

    override fun onSignResult(authenticatedProvider: ClientId, result: net.extrawdw.notisync.protocol.SshSignResult) = Unit

    override fun onImportResult(authenticatedProvider: ClientId, result: SshImportResult) {
        val operation = pending[result.requestId] ?: return
        if (authenticatedProvider != operation.provider || result.providerClientId != operation.provider ||
            result.requesterClientId != requesterClientId || !result.requestDigest.contentEquals(operation.requestDigest)
        ) return
        operation.future.complete(
            result.kind == SshImportResultKind.IMPORTED || result.kind == SshImportResultKind.ALREADY_PRESENT,
        )
    }

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        val RANDOM = SecureRandom()
    }
}

class CompositeSshInboundHandler(private vararg val handlers: SshInboundHandler) : SshInboundHandler {
    override fun onSignResult(authenticatedProvider: ClientId, result: net.extrawdw.notisync.protocol.SshSignResult) =
        handlers.forEach { it.onSignResult(authenticatedProvider, result) }

    override fun onImportResult(authenticatedProvider: ClientId, result: SshImportResult) =
        handlers.forEach { it.onImportResult(authenticatedProvider, result) }

    override fun onForgetResult(
        authenticatedProvider: ClientId,
        result: net.extrawdw.notisync.protocol.SshForgetResult,
    ) = handlers.forEach { it.onForgetResult(authenticatedProvider, result) }
}
