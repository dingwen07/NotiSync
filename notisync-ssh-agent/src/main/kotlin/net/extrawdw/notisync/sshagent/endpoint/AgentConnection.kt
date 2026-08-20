package net.extrawdw.notisync.sshagent.endpoint

import java.security.MessageDigest
import java.security.SecureRandom
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore
import net.extrawdw.notisync.protocol.SshConnectionDirection
import net.extrawdw.notisync.protocol.SshDestinationContext
import net.extrawdw.notisync.protocol.SshDestinationProvenance
import net.extrawdw.notisync.protocol.SshVerifiedBinding
import net.extrawdw.notisync.ssh.core.AgentAddIdentityParser
import net.extrawdw.notisync.ssh.core.AgentIdentity
import net.extrawdw.notisync.ssh.core.AgentMessageCodec
import net.extrawdw.notisync.ssh.core.AgentRequest
import net.extrawdw.notisync.ssh.core.OpenSshSessionBind
import net.extrawdw.notisync.ssh.core.ParsedAgentIdentity
import net.extrawdw.notisync.ssh.core.SshAgentFrameCodec
import net.extrawdw.notisync.ssh.core.SshUserAuthParser
import net.extrawdw.notisync.ssh.core.VerifiedSessionBind
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore
import net.extrawdw.notisync.sshagent.signing.SignCoordinator
import net.extrawdw.notisync.sshagent.signing.SignDecision
import org.newsclub.net.unix.AFUNIXSocket

fun interface IdentityImporter {
    fun import(identityPayload: ByteArray, parsed: ParsedAgentIdentity): Boolean

    object Unsupported : IdentityImporter {
        override fun import(identityPayload: ByteArray, parsed: ParsedAgentIdentity) = false
    }
}

class AgentConnectionHandler(
    private val signing: SignCoordinator,
    private val snapshots: ProviderSnapshotStore,
    private val lock: AuthorizationLockCoordinator,
    private val callerResolver: LocalCallerResolver = LocalCallerResolver(),
    private val importer: IdentityImporter = IdentityImporter.Unsupported,
    maximumInFlightRequests: Int = 256,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val inFlight = Semaphore(maximumInFlightRequests, true)

    fun handle(socket: AFUNIXSocket) {
        handle(socket.getInputStream(), socket.getOutputStream(), callerResolver.resolve(socket))
    }

    fun handle(
        input: InputStream,
        output: OutputStream,
        processContext: net.extrawdw.notisync.protocol.DesktopProcessContext,
    ) {
        val connectionId = randomId()
        val destination = ConnectionDestinationState()
        while (true) {
            val body = runCatching { SshAgentFrameCodec.read(input) }.getOrElse { return } ?: return
            val response = runCatching {
                dispatch(
                    AgentMessageCodec.decodeRequest(body),
                    connectionId,
                    processContext,
                    destination,
                )
            }.getOrElse { AgentMessageCodec.failure() }
            runCatching { SshAgentFrameCodec.write(output, response) }.getOrElse { return }
        }
    }

    private fun dispatch(
        request: AgentRequest,
        connectionId: String,
        processContext: net.extrawdw.notisync.protocol.DesktopProcessContext,
        destination: ConnectionDestinationState,
    ): ByteArray {
        return when (request) {
        AgentRequest.RequestIdentities -> {
            val identities = if (lock.isLocked()) emptyList() else signing.identities().map {
                AgentIdentity(it.publicKeyBlob, it.comment)
            }
            AgentMessageCodec.identitiesAnswer(identities)
        }
        is AgentRequest.Sign -> {
            if (lock.isLocked()) return AgentMessageCodec.failure()
            if (!inFlight.tryAcquire()) return AgentMessageCodec.failure()
            val resolved = destination.resolve(request.publicKeyBlob, request.data)
            try {
                if (resolved == null) return AgentMessageCodec.failure()
                when (val decision = signing.sign(
                    request.publicKeyBlob,
                    request.data,
                    request.flags,
                    connectionId,
                    callerResolver.refresh(processContext),
                    resolved,
                )) {
                    is SignDecision.Signed -> AgentMessageCodec.signResponse(decision.signatureBlob)
                    SignDecision.RejectedByUser, is SignDecision.Failed -> AgentMessageCodec.failure()
                }
            } finally {
                inFlight.release()
            }
        }
        is AgentRequest.AddIdentity -> {
            if (lock.isLocked()) return AgentMessageCodec.failure()
            if (!inFlight.tryAcquire()) return AgentMessageCodec.failure()
            val mutablePayload = request.identityPayload.copyOf()
            try {
                val parsed = try {
                    AgentAddIdentityParser.parse(mutablePayload, request.constrained)
                } catch (_: Exception) {
                    return AgentMessageCodec.failure()
                }
                val imported = importer.import(mutablePayload, parsed)
                if (imported) AgentMessageCodec.success() else AgentMessageCodec.failure()
            } finally {
                mutablePayload.fill(0)
                inFlight.release()
            }
        }
        is AgentRequest.RemoveIdentity -> {
            if (lock.isLocked()) AgentMessageCodec.failure() else {
                snapshots.hide(request.publicKeyBlob, "SSH_AGENT_REMOVE", now())
                AgentMessageCodec.success()
            }
        }
        AgentRequest.RemoveAllIdentities -> {
            if (lock.isLocked()) AgentMessageCodec.failure() else {
                signing.identities().forEach { snapshots.hide(it.publicKeyBlob, "SSH_AGENT_REMOVE_ALL", now()) }
                AgentMessageCodec.success()
            }
        }
        is AgentRequest.Lock -> if (lock.lock(request.passphrase)) {
            AgentMessageCodec.success()
        } else AgentMessageCodec.failure()
        is AgentRequest.Unlock -> if (lock.unlock(request.passphrase)) {
            AgentMessageCodec.success()
        } else AgentMessageCodec.failure()
        is AgentRequest.Extension -> when (request.name) {
            OpenSshSessionBind.QUERY_EXTENSION_NAME -> {
                if (request.contents.isNotEmpty()) AgentMessageCodec.extensionFailure() else {
                    AgentMessageCodec.extensionQueryResponse(listOf(OpenSshSessionBind.EXTENSION_NAME))
                }
            }
            OpenSshSessionBind.EXTENSION_NAME -> if (destination.bind(request.contents)) {
                AgentMessageCodec.success()
            } else AgentMessageCodec.extensionFailure()
            else -> AgentMessageCodec.extensionFailure()
        }
            is AgentRequest.Unsupported -> AgentMessageCodec.failure()
        }
    }

    internal class ConnectionDestinationState {
        private val bindings = mutableListOf<VerifiedSessionBind>()

        @Synchronized
        fun bind(contents: ByteArray): Boolean {
            val binding = runCatching { OpenSshSessionBind.parseAndVerify(contents) }.getOrNull() ?: return false
            if (bindings.any { it.sessionIdentifier.contentEquals(binding.sessionIdentifier) }) return false
            if (binding.forwarded && bindings.any { !it.forwarded }) return false
            bindings += binding
            return true
        }

        @Synchronized
        fun resolve(publicKeyBlob: ByteArray, data: ByteArray): SshDestinationContext? {
            val userAuth = SshUserAuthParser.parse(data)
            if (userAuth != null && !userAuth.publicKeyBlob.contentEquals(publicKeyBlob)) return null
            if (bindings.isNotEmpty() && userAuth != null) {
                val binding = bindings.firstOrNull {
                    it.sessionIdentifier.contentEquals(userAuth.sessionIdentifier)
                } ?: return null
                if (userAuth.serverHostKeyBlob != null &&
                    !userAuth.serverHostKeyBlob.contentEquals(binding.hostKeyBlob)
                ) return null
                return SshDestinationContext(
                    provenance = SshDestinationProvenance.VERIFIED_SESSION_BIND,
                    connectionDirection = if (binding.forwarded) {
                        SshConnectionDirection.FORWARDED
                    } else SshConnectionDirection.DIRECT,
                    username = userAuth.username,
                    service = userAuth.service,
                    authenticationMethod = userAuth.method,
                    sessionIdSha256 = sha256(binding.sessionIdentifier),
                    serverHostKeyBlob = binding.hostKeyBlob,
                    serverHostKeyBlobSha256 = sha256(binding.hostKeyBlob),
                    bindingChain = bindings.map {
                        SshVerifiedBinding(sha256(it.hostKeyBlob), it.forwarded)
                    },
                )
            }
            if (userAuth != null) {
                return SshDestinationContext(
                    provenance = SshDestinationProvenance.SIGNED_USERAUTH,
                    connectionDirection = SshConnectionDirection.UNKNOWN,
                    username = userAuth.username,
                    service = userAuth.service,
                    authenticationMethod = userAuth.method,
                    sessionIdSha256 = sha256(userAuth.sessionIdentifier),
                )
            }
            return SshDestinationContext(
                SshDestinationProvenance.UNKNOWN,
                if (bindings.any { it.forwarded }) SshConnectionDirection.FORWARDED else SshConnectionDirection.UNKNOWN,
                bindingChain = bindings.map { SshVerifiedBinding(sha256(it.hostKeyBlob), it.forwarded) },
            )
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val RANDOM = SecureRandom()
    }
}
