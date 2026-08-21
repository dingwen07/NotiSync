package net.extrawdw.apps.notisync.sshagent

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import net.extrawdw.notisync.protocol.SshProviderFailure
import net.extrawdw.notisync.protocol.SshProviderFailureCode
import net.extrawdw.notisync.protocol.SshExportCopyBackendPolicy
import net.extrawdw.notisync.protocol.SshRememberScope
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.protocol.SshSignResultKind
import net.extrawdw.notisync.protocol.SshUserVerificationPolicy
import net.extrawdw.notisync.protocol.Urgency

/** Android application edge for authenticated SSH Agent traffic. */
internal class SshAgentProviderEngine(
    private val context: Context,
    private val providerClientId: ClientId,
    private val channel: SecureChannel,
    private val store: SshAgentProviderRepository,
    private val notifications: SshAgentNotificationPresenter,
    private val scope: CoroutineScope,
    private val deviceNameOf: (ClientId) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val responseSendMutex = Mutex()

    fun onSshAgentSync(message: InboundMessage, dataSync: DataSync) {
        scope.launch { handleSshAgentSync(message, dataSync) }
    }

    /** Broker-custody receive path; returns only after the decoded SSH operation has completed. */
    suspend fun handleSshAgentSync(message: InboundMessage, dataSync: DataSync) {
        if (!message.senderOwnDevice || dataSync.kind != DataSyncKind.SSH_AGENT) return
        val sync = dataSync.sshAgent ?: return
        if (sync.validationError(::sha256) != null) return
        when (sync.kind) {
            SshAgentSyncKind.KEYS_REQUEST -> receiveKeysRequest(message, requireNotNull(sync.keysRequest))
            SshAgentSyncKind.SIGN_REQUEST -> receiveSignRequest(message, requireNotNull(sync.signRequest))
            SshAgentSyncKind.SIGN_REQUEST_CANCELLED -> {
                val cancel = requireNotNull(sync.signRequestCancelled)
                if (cancel.requesterClientId == message.senderId && providerClientId in cancel.targetProviderClientIds &&
                    store.cancelSign(cancel.requestId, message.senderId, now())
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
                    forget.requesterClientId,
                    providerClientId,
                    now(),
                    SshForgetResultKind.APPLIED,
                    forget.invalidatedThroughEpoch,
                )
                send(
                    forget.requesterClientId,
                    SshAgentSync(kind = SshAgentSyncKind.FORGET_RESULT, forgetResult = result),
                )
                if (outcome.inventoryChanged) broadcastSnapshot()
            }
            SshAgentSyncKind.KEYS_SNAPSHOT,
            SshAgentSyncKind.SIGN_RESULT,
            SshAgentSyncKind.IMPORT_RESULT,
            SshAgentSyncKind.FORGET_RESULT,
            -> Unit
        }
    }

    suspend fun approve(requestId: String): SshSignResult? {
        val result = store.approve(requestId, providerClientId, now())
        if (result != null) {
            notifications.dismiss(requestId)
            sendResponseNowOrEnqueue(requestId)
        }
        return result
    }

    suspend fun approveImport(
        requestId: String,
        allowExport: Boolean,
        exportCopyBackendPolicy: SshExportCopyBackendPolicy,
        userVerificationPolicy: SshUserVerificationPolicy,
        passphrase: CharArray? = null,
    ): SshImportApprovalOutcome? {
        val outcome = store.approveImport(
            requestId,
            providerClientId,
            now(),
            allowExport,
            exportCopyBackendPolicy,
            userVerificationPolicy,
            passphrase,
        ) ?: return null
        if (outcome == SshImportApprovalOutcome.Completed) {
            notifications.dismiss(requestId)
            sendResponseNowOrEnqueue(requestId)
            publishInventory()
        }
        return outcome
    }

    suspend fun completePreparedImport(
        prepared: PreparedSshImportStorage,
        authenticatedCipher: javax.crypto.Cipher?,
        authenticatedSignature: java.security.Signature?,
    ): SshImportApprovalOutcome? {
        val outcome = store.completePreparedImport(
            prepared,
            authenticatedCipher,
            authenticatedSignature,
            providerClientId,
            now(),
        )
        if (outcome == SshImportApprovalOutcome.Completed) {
            notifications.dismiss(prepared.requestId)
            sendResponseNowOrEnqueue(prepared.requestId)
            publishInventory()
        }
        return outcome
    }

    suspend fun cancelPreparedImport(prepared: PreparedSshImportStorage) = store.cancelPreparedImport(prepared)

    suspend fun prepareUserVerifiedSignature(requestId: String): PreparedSshSignature? {
        val prepared = store.prepareUserVerifiedSignature(requestId, providerClientId, now())
        if (prepared == null && store.find(requestId)?.state == SshProviderRequestState.RESPONSE_PENDING_SEND) {
            notifications.dismiss(requestId)
            sendResponseNowOrEnqueue(requestId)
        }
        return prepared
    }

    suspend fun completeUserVerifiedSignature(
        prepared: PreparedSshSignature,
        signature: java.security.Signature?,
        cipher: javax.crypto.Cipher?,
    ): SshSignResult? {
        val result = store.completeUserVerifiedSignature(prepared, signature, cipher, providerClientId, now())
        if (result != null) {
            notifications.dismiss(prepared.requestId)
            sendResponseNowOrEnqueue(prepared.requestId)
        }
        return result
    }

    suspend fun cancelPreparedSignature(prepared: PreparedSshSignature) = store.cancelPreparedSignature(prepared)

    suspend fun failUserVerification(prepared: PreparedSshSignature, code: SshProviderFailureCode) {
        store.cancelPreparedSignature(prepared)
        if (store.failUserVerification(prepared.requestId, providerClientId, now(), code)) {
            notifications.dismiss(prepared.requestId)
            sendResponseNowOrEnqueue(prepared.requestId)
        }
    }

    suspend fun reject(requestId: String) {
        if (store.reject(requestId, providerClientId, now())) {
            notifications.dismiss(requestId)
            sendResponseNowOrEnqueue(requestId)
        }
    }

    suspend fun approveAndRemember(requestId: String, rememberScope: SshRememberScope): SshSignResult? {
        val result = store.approveAndRemember(requestId, providerClientId, rememberScope, now())
        if (result != null) {
            notifications.dismiss(requestId)
            sendResponseNowOrEnqueue(requestId)
            publishInventory()
        }
        return result
    }

    /** Rebuilds pending notifications after presentation-only state such as a saved hostname changes. */
    fun refreshPendingNotifications() {
        scope.launch {
            store.pendingReview().forEach { request ->
                notifications.post(
                    request,
                    deviceNameOf(request.requesterClientId) ?: request.requesterClientId.shortForm(),
                )
            }
        }
    }

    suspend fun sendPersistedResponse(requestId: String): Boolean = responseSendMutex.withLock {
        val stored = store.find(requestId) ?: return true
        if (stored.state != SshProviderRequestState.RESPONSE_PENDING_SEND) return true
        val prepared = store.prepareResponse(requestId, now()) ?: return false
        val bytes = prepared.encodedBody
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
            if (store.completeResponse(prepared, now())) {
                store.find(requestId)?.let { notifyAutoApproval(it) }
            }
            if (stored.kind == SshProviderRequestKind.IMPORT) broadcastSnapshot()
        }
        accepted
    }

    private suspend fun sendResponseNowOrEnqueue(requestId: String) {
        val sent = try {
            sendPersistedResponse(requestId)
        } catch (cancelled: CancellationException) {
            SshAgentResponseWorker.enqueue(context, requestId)
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!sent) SshAgentResponseWorker.enqueue(context, requestId)
    }

    fun reconcile() {
        scope.launch {
            store.expireDue(now()).forEach(notifications::dismiss)
            store.cancelInvalidatedPending(now()).forEach(notifications::dismiss)
            store.pendingReview().forEach { request ->
                val autoApproved = if (request.kind == SshProviderRequestKind.SIGN) {
                    store.autoApproveRemembered(request.requestId, providerClientId, now())
                } else {
                    null
                }
                if (autoApproved != null) {
                    notifications.dismiss(request.requestId)
                    sendResponseNowOrEnqueue(request.requestId)
                } else {
                    notifications.post(
                        request,
                        deviceNameOf(request.requesterClientId) ?: request.requesterClientId.shortForm(),
                    )
                }
            }
            store.pendingResponses().forEach { sendResponseNowOrEnqueue(it.requestId) }
        }
    }

    /** Publishes a UI-originated key inventory mutation without blocking the main thread. */
    fun publishInventory() {
        scope.launch { runCatching { broadcastSnapshot() } }
    }

    /** Sends an export-copy-backed private key to one explicitly selected Android key-provider peer. */
    suspend fun sendPrivateKeyImport(
        targetProviderClientId: ClientId,
        privateKeyFile: ByteArray,
        suggestedName: String,
    ): Boolean {
        require(targetProviderClientId != providerClientId) { "SSH key transfer target must be another device" }
        val requestedAt = now()
        val request = SshImportRequest(
            requestId = randomId(),
            requesterClientId = providerClientId,
            requestedAt = requestedAt,
            expiresAt = requestedAt + SshAgentLimits.MAX_IMPORT_LIFETIME_MILLIS,
            sourceType = SshImportSourceType.PRIVATE_KEY_FILE,
            fileBytes = privateKeyFile,
            suggestedName = suggestedName,
        )
        val sync = SshAgentSync(kind = SshAgentSyncKind.IMPORT_REQUEST, importRequest = request)
        require(sync.validationError(::sha256) == null) { "invalid SSH key transfer request" }
        return channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.SSH_AGENT, sshAgent = sync)),
            sshKeyTransferRecipients(targetProviderClientId),
            Urgency.NORMAL,
        ) == 1
    }

    private suspend fun receiveKeysRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshKeysRequest) {
        if (request.requesterClientId != message.senderId || providerClientId !in request.targetProviderClientIds ||
            !fresh(request.requestedAt, request.expiresAt, message.createdAt)
        ) return
        val snapshot = store.snapshot(providerClientId, request.requestId, now())
        send(
            request.requesterClientId,
            SshAgentSync(kind = SshAgentSyncKind.KEYS_SNAPSHOT, keysSnapshot = snapshot),
        )
    }

    private suspend fun receiveSignRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshSignRequest) {
        if (request.requesterClientId != message.senderId || providerClientId !in request.eligibleProviderClientIds ||
            !fresh(request.requestedAt, request.expiresAt, message.createdAt)
        ) return
        suspend fun sendKeyNotFound() {
            val result = SshSignResult(
                request.requestId,
                request.requesterClientId,
                sha256(request.publicKeyBlob),
                SshSignResultKind.PROVIDER_FAILURE,
                now(),
                providerClientId,
                failure = SshProviderFailure(SshProviderFailureCode.KEY_NOT_FOUND),
            )
            send(
                request.requesterClientId,
                SshAgentSync(kind = SshAgentSyncKind.SIGN_RESULT, signResult = result),
            )
        }
        when (store.acceptSign(request, now())) {
            SshProviderAcceptResult.STORED, SshProviderAcceptResult.DUPLICATE -> {
                val stored = store.find(request.requestId) ?: return
                when (stored.state) {
                    SshProviderRequestState.PENDING_REVIEW -> {
                        val autoApproved = store.autoApproveRemembered(request.requestId, providerClientId, now())
                        if (autoApproved != null) {
                            notifications.dismiss(request.requestId)
                            sendResponseNowOrEnqueue(request.requestId)
                        } else {
                            notifications.post(
                                stored,
                                deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                            )
                        }
                    }
                    SshProviderRequestState.RESPONSE_PENDING_SEND -> sendResponseNowOrEnqueue(request.requestId)
                    else -> Unit
                }
            }
            SshProviderAcceptResult.CONFLICT,
            SshProviderAcceptResult.RATE_LIMITED,
            SshProviderAcceptResult.AUTHORIZATION_INVALIDATED,
            -> Unit
            SshProviderAcceptResult.KEY_NOT_FOUND -> sendKeyNotFound()
        }
    }

    private suspend fun notifyAutoApproval(stored: StoredSshProviderRequest) {
        if (stored.history.approvalKind != SshRequestApprovalKind.REMEMBERED_AUTHORIZATION) return
        notifications.postAutoApproved(
            stored,
            deviceNameOf(stored.requesterClientId) ?: stored.requesterClientId.shortForm(),
        )
    }

    private suspend fun receiveImportRequest(message: InboundMessage, request: net.extrawdw.notisync.protocol.SshImportRequest) {
        if (request.requesterClientId != message.senderId || !fresh(request.requestedAt, request.expiresAt, message.createdAt)) return
        when (store.acceptImport(request, now())) {
            SshProviderAcceptResult.STORED, SshProviderAcceptResult.DUPLICATE -> {
                val stored = store.find(request.requestId) ?: return
                when (stored.state) {
                    SshProviderRequestState.PENDING_REVIEW -> notifications.post(
                        stored,
                        deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                    )
                    SshProviderRequestState.RESPONSE_PENDING_SEND -> sendResponseNowOrEnqueue(request.requestId)
                    else -> Unit
                }
            }
            SshProviderAcceptResult.CONFLICT,
            SshProviderAcceptResult.RATE_LIMITED,
            SshProviderAcceptResult.AUTHORIZATION_INVALIDATED,
            SshProviderAcceptResult.KEY_NOT_FOUND,
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
            sshAgentInventoryRecipients(),
            Urgency.NORMAL,
        )
    }

    private suspend fun send(requester: ClientId, sync: SshAgentSync): Boolean = channel.send(
        MessageType.DATA_SYNC,
        ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.SSH_AGENT, sshAgent = sync)),
        sshAgentDirectRecipients(requester),
        Urgency.NORMAL,
    ) > 0

    private fun fresh(requestedAt: Long, expiresAt: Long, envelopeCreatedAt: Long): Boolean {
        val current = now()
        return requestedAt <= current + CLOCK_SKEW_MILLIS && current <= expiresAt &&
            (envelopeCreatedAt <= 0 || abs(envelopeCreatedAt - requestedAt) <= CLOCK_SKEW_MILLIS)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CLOCK_SKEW_MILLIS = 2 * 60_000L
        val RANDOM = SecureRandom()
    }
}

internal val SSH_AGENT_INVENTORY_RECIPIENT_CAPABILITIES =
    setOf(Capability.CAPABILITY_ROUTING_V1, Capability.SSH_AGENT_V1)

/** SSH_AGENT_V1 selects consumers of unsolicited inventory; it is not authorization for request/reply traffic. */
internal fun sshAgentInventoryRecipients(): Recipients = Recipients.OwnMeshFiltered(
    requiredCapabilities = SSH_AGENT_INVENTORY_RECIPIENT_CAPABILITIES,
    requireCapabilityRoutingV1 = true,
)

/** An authenticated own-device requester remains a valid reply target even if its profile lacks SSH_AGENT_V1. */
internal fun sshAgentDirectRecipients(requester: ClientId): Recipients = Recipients.Only(requester)
