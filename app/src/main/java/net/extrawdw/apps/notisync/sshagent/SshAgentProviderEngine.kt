package net.extrawdw.apps.notisync.sshagent

import android.content.Context
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshForgetResult
import net.extrawdw.notisync.protocol.SshForgetResultKind
import net.extrawdw.notisync.protocol.SshProviderFailure
import net.extrawdw.notisync.protocol.SshProviderFailureCode
import net.extrawdw.notisync.protocol.SshExportability
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.protocol.Urgency

/** Android application edge for authenticated SSH Agent traffic. */
class SshAgentProviderEngine(
    private val context: Context,
    private val providerClientId: ClientId,
    private val channel: SecureChannel,
    private val store: SshKeyProviderStore,
    private val notifications: SshAgentNotificationPresenter,
    private val scope: CoroutineScope,
    private val deviceNameOf: (ClientId) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun onSshAgentSync(message: InboundMessage, dataSync: DataSync) {
        if (!message.senderOwnDevice || dataSync.kind != DataSyncKind.SSH_AGENT) return
        val sync = dataSync.sshAgent ?: return
        if (sync.validationError(::sha256) != null) return
        when (sync.kind) {
            SshAgentSyncKind.KEYS_REQUEST -> receiveKeysRequest(message, requireNotNull(sync.keysRequest))
            SshAgentSyncKind.SIGN_REQUEST -> receiveSignRequest(message, requireNotNull(sync.signRequest))
            SshAgentSyncKind.SIGN_REQUEST_CANCELLED -> {
                val cancel = requireNotNull(sync.signRequestCancelled)
                if (cancel.requesterClientId == message.senderId && providerClientId in cancel.targetProviderClientIds &&
                    store.cancelSign(cancel.requestId, message.senderId, cancel.requestDigest, now())
                ) notifications.dismiss(cancel.requestId)
            }
            SshAgentSyncKind.IMPORT_REQUEST -> receiveImportRequest(message, requireNotNull(sync.importRequest))
            SshAgentSyncKind.FORGET_AUTHORIZATION -> {
                val forget = requireNotNull(sync.forgetAuthorization)
                if (forget.requesterClientId != message.senderId || providerClientId !in forget.targetProviderClientIds ||
                    !fresh(forget.requestedAt, forget.expiresAt, message.createdAt)
                ) return
                val outcome = store.forgetAuthorization(
                    forget.requesterClientId,
                    forget.authorizationGeneration,
                    forget.invalidatedThroughEpoch,
                    now(),
                )
                outcome.cancelledRequestIds.forEach(notifications::dismiss)
                val result = SshForgetResult(
                    forget.requestId,
                    sha256(ProtocolCodec.encodeToCbor(forget)),
                    forget.requesterClientId,
                    providerClientId,
                    now(),
                    SshForgetResultKind.APPLIED,
                    forget.invalidatedThroughEpoch,
                )
                scope.launch {
                    send(
                        forget.requesterClientId,
                        SshAgentSync(kind = SshAgentSyncKind.FORGET_RESULT, forgetResult = result),
                    )
                    if (outcome.inventoryChanged) broadcastSnapshot()
                }
            }
            SshAgentSyncKind.KEYS_SNAPSHOT,
            SshAgentSyncKind.SIGN_RESULT,
            SshAgentSyncKind.IMPORT_RESULT,
            SshAgentSyncKind.FORGET_RESULT,
            -> Unit
        }
    }

    fun approve(requestId: String): SshSignResult? {
        val result = store.approve(requestId, providerClientId, now())
        if (result != null) {
            notifications.dismiss(requestId)
            SshAgentResponseWorker.enqueue(context, requestId)
        }
        return result
    }

    fun approveImport(
        requestId: String,
        exportability: SshExportability,
        preferStrongBox: Boolean,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray? = null,
    ): SshImportApprovalOutcome? {
        val outcome = store.approveImport(
            requestId,
            providerClientId,
            now(),
            exportability,
            preferStrongBox,
            userVerificationPolicy,
            passphrase,
        ) ?: return null
        if (outcome == SshImportApprovalOutcome.Completed) {
            notifications.dismiss(requestId)
            SshAgentResponseWorker.enqueue(context, requestId)
            publishInventory()
        }
        return outcome
    }

    fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: javax.crypto.Cipher,
    ): Boolean {
        val completed = store.completePreparedImport(prepared, authenticatedCipher, providerClientId, now())
        if (completed) {
            notifications.dismiss(prepared.requestId)
            SshAgentResponseWorker.enqueue(context, prepared.requestId)
            publishInventory()
        }
        return completed
    }

    fun cancelPreparedImport(prepared: PreparedSshImportStorage) = store.cancelPreparedImport(prepared)

    fun prepareUserVerifiedSignature(requestId: String): PreparedSshSignature? =
        store.prepareUserVerifiedSignature(requestId, providerClientId, now())

    fun completeUserVerifiedSignature(
        prepared: PreparedSshSignature,
        signature: java.security.Signature?,
        cipher: javax.crypto.Cipher?,
    ): SshSignResult? {
        val result = store.completeUserVerifiedSignature(prepared, signature, cipher, providerClientId, now())
        if (result != null) {
            notifications.dismiss(prepared.requestId)
            SshAgentResponseWorker.enqueue(context, prepared.requestId)
        }
        return result
    }

    fun failUserVerification(requestId: String, code: SshProviderFailureCode) {
        if (store.failUserVerification(requestId, providerClientId, now(), code)) {
            notifications.dismiss(requestId)
            SshAgentResponseWorker.enqueue(context, requestId)
        }
    }

    fun reject(requestId: String) {
        if (store.reject(requestId, providerClientId, now())) {
            notifications.dismiss(requestId)
            SshAgentResponseWorker.enqueue(context, requestId)
        }
    }

    fun approveAndRemember(requestId: String, rememberScope: SshRememberScope): SshSignResult? {
        val result = store.approveAndRemember(requestId, providerClientId, rememberScope, now())
        if (result != null) {
            notifications.dismiss(requestId)
            SshAgentResponseWorker.enqueue(context, requestId)
            publishInventory()
        }
        return result
    }

    suspend fun sendPersistedResponse(requestId: String): Boolean {
        val stored = store.find(requestId) ?: return true
        if (stored.state != SshProviderRequestState.RESPONSE_PENDING_SEND) return true
        val bytes = stored.encodedResponse ?: return false
        val sync = when (stored.kind) {
            SshProviderRequestKind.SIGN -> SshAgentSync(
                kind = SshAgentSyncKind.SIGN_RESULT,
                signResult = runCatching { ProtocolCodec.decodeFromCbor<SshSignResult>(bytes) }.getOrNull() ?: return false,
            )
            SshProviderRequestKind.IMPORT -> SshAgentSync(
                kind = SshAgentSyncKind.IMPORT_RESULT,
                importResult = runCatching {
                    ProtocolCodec.decodeFromCbor<net.extrawdw.notisync.protocol.SshImportResult>(bytes)
                }.getOrNull() ?: return false,
            )
        }
        val accepted = send(stored.requesterClientId, sync)
        if (accepted) {
            store.markSent(requestId, now())
            if (stored.kind == SshProviderRequestKind.IMPORT) broadcastSnapshot()
        }
        return accepted
    }

    fun reconcile() {
        store.expireDue(now()).forEach(notifications::dismiss)
        store.cancelInvalidatedPending(now()).forEach(notifications::dismiss)
        store.pendingReview().forEach { request ->
            if (request.kind == SshProviderRequestKind.SIGN &&
                store.autoApproveRemembered(request.requestId, providerClientId, now())
            ) {
                notifications.dismiss(request.requestId)
                SshAgentResponseWorker.enqueue(context, request.requestId)
            } else {
                notifications.post(
                    request,
                    deviceNameOf(request.requesterClientId) ?: request.requesterClientId.shortForm(),
                )
            }
        }
        store.pendingResponses().forEach { SshAgentResponseWorker.enqueue(context, it.requestId) }
    }

    /** Publishes a UI-originated key inventory mutation without blocking the main thread. */
    fun publishInventory() {
        scope.launch { runCatching { broadcastSnapshot() } }
    }

    private fun receiveKeysRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshKeysRequest) {
        if (request.requesterClientId != message.senderId || providerClientId !in request.targetProviderClientIds ||
            !fresh(request.requestedAt, request.expiresAt, message.createdAt)
        ) return
        scope.launch {
            val snapshot = store.snapshot(providerClientId, request.requestId, now())
            send(
                request.requesterClientId,
                SshAgentSync(kind = SshAgentSyncKind.KEYS_SNAPSHOT, keysSnapshot = snapshot),
            )
        }
    }

    private fun receiveSignRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshSignRequest) {
        if (request.requesterClientId != message.senderId || providerClientId !in request.eligibleProviderClientIds ||
            !fresh(request.requestedAt, request.expiresAt, message.createdAt)
        ) return
        if (!store.owns(request.publicKeyBlob, now())) {
            val result = SshSignResult(
                request.requestId,
                sha256(ProtocolCodec.encodeToCbor(request)),
                request.requesterClientId,
                sha256(request.publicKeyBlob),
                SshSignResultKind.PROVIDER_FAILURE,
                now(),
                providerClientId,
                failure = SshProviderFailure(SshProviderFailureCode.KEY_NOT_FOUND),
            )
            scope.launch {
                send(
                    request.requesterClientId,
                    SshAgentSync(kind = SshAgentSyncKind.SIGN_RESULT, signResult = result),
                )
            }
            return
        }
        when (store.acceptSign(request, now())) {
            SshProviderAcceptResult.STORED, SshProviderAcceptResult.DUPLICATE -> {
                val stored = store.find(request.requestId) ?: return
                when (stored.state) {
                    SshProviderRequestState.PENDING_REVIEW -> {
                        if (store.autoApproveRemembered(request.requestId, providerClientId, now())) {
                            notifications.dismiss(request.requestId)
                            SshAgentResponseWorker.enqueue(context, request.requestId)
                        } else {
                            notifications.post(
                                stored,
                                deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                            )
                        }
                    }
                    SshProviderRequestState.RESPONSE_PENDING_SEND -> SshAgentResponseWorker.enqueue(context, request.requestId)
                    else -> Unit
                }
            }
            SshProviderAcceptResult.CONFLICT,
            SshProviderAcceptResult.RATE_LIMITED,
            SshProviderAcceptResult.AUTHORIZATION_INVALIDATED,
            -> Unit
        }
    }

    private fun receiveImportRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshImportRequest) {
        if (request.requesterClientId != message.senderId || !fresh(request.requestedAt, request.expiresAt, message.createdAt)) return
        when (store.acceptImport(request, now())) {
            SshProviderAcceptResult.STORED, SshProviderAcceptResult.DUPLICATE -> {
                val stored = store.find(request.requestId) ?: return
                when (stored.state) {
                    SshProviderRequestState.PENDING_REVIEW -> notifications.post(
                        stored,
                        deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                    )
                    SshProviderRequestState.RESPONSE_PENDING_SEND -> SshAgentResponseWorker.enqueue(context, request.requestId)
                    else -> Unit
                }
            }
            SshProviderAcceptResult.CONFLICT,
            SshProviderAcceptResult.RATE_LIMITED,
            SshProviderAcceptResult.AUTHORIZATION_INVALIDATED,
            -> Unit
        }
    }

    private suspend fun broadcastSnapshot() {
        val snapshot = store.snapshot(providerClientId, null, now())
        channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.SSH_AGENT,
                    sshAgent = SshAgentSync(kind = SshAgentSyncKind.KEYS_SNAPSHOT, keysSnapshot = snapshot),
                ),
            ),
            Recipients.OwnMeshFiltered(
                requiredCapabilities = AGENT_CAPABILITIES,
                requireCapabilityRoutingV1 = true,
            ),
            Urgency.NORMAL,
        )
    }

    private suspend fun send(requester: ClientId, sync: SshAgentSync): Boolean = channel.send(
        MessageType.DATA_SYNC,
        ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.SSH_AGENT, sshAgent = sync)),
        Recipients.OnlyCapable(requester, AGENT_CAPABILITIES),
        Urgency.NORMAL,
    ) > 0

    private fun fresh(requestedAt: Long, expiresAt: Long, envelopeCreatedAt: Long): Boolean {
        val current = now()
        return requestedAt <= current + CLOCK_SKEW_MILLIS && current <= expiresAt &&
            (envelopeCreatedAt <= 0 || abs(envelopeCreatedAt - requestedAt) <= CLOCK_SKEW_MILLIS)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val CLOCK_SKEW_MILLIS = 2 * 60_000L
        val AGENT_CAPABILITIES = setOf(Capability.CAPABILITY_ROUTING_V1, Capability.SSH_AGENT_V1)
    }
}
