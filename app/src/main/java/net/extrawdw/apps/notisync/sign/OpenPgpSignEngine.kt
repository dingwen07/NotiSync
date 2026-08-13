package net.extrawdw.apps.notisync.sign

import android.content.Context
import java.security.MessageDigest
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.GitCommitPayloadParser
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency

/** Android owner of authenticated remote signing requests and the durable response outbox. */
class OpenPgpSignEngine(
    private val context: Context,
    private val channel: SecureChannel,
    private val enrollmentStore: OpenPgpEnrollmentStore,
    private val store: OpenPgpSignStore,
    private val notifications: OpenPgpSignNotificationPresenter,
    private val deviceNameOf: (ClientId) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Called inline by FoundationEngine; persistence must complete before the relay item is acknowledged. */
    fun onOpenPgpSignSync(message: InboundMessage, sync: DataSync) {
        if (!message.senderOwnDevice || sync.kind != DataSyncKind.OPENPGP_SIGN) return
        val body = sync.openPgpSign ?: return
        when (body.action) {
            OpenPgpSignAction.REQUEST -> receiveRequest(message, body)
            OpenPgpSignAction.CANCEL -> receiveCancel(message, body)
            OpenPgpSignAction.RESULT, OpenPgpSignAction.REJECT -> Unit
        }
    }

    private fun receiveRequest(message: InboundMessage, request: OpenPgpSignSync) {
        if (request.requesterClientId != message.senderId) return
        if (request.validationError(::sha256) != null) return
        val receivedAt = now()
        if (
            request.issuedAt > receivedAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS ||
            receivedAt > request.expiresAt
        ) return
        if (message.createdAt > 0 &&
            kotlin.math.abs(message.createdAt - request.issuedAt) > OpenPgpSignLimits.CLOCK_SKEW_MILLIS
        ) return
        val payload = request.payload ?: return
        if (runCatching { GitCommitPayloadParser.parse(payload) }.isFailure) return

        val enrollment = enrollmentStore.enrollment.value
        if (
            !enrollment.enabled || enrollment.providerId.isNullOrBlank() ||
            enrollment.providerKeyReference.isNullOrBlank() ||
            enrollment.primaryKeyId != request.primaryKeyId
        ) return

        val accepted = try {
            store.accept(request, message.senderId, receivedAt)
        } catch (failure: Exception) {
            throw RetryableDeliveryException("could not persist OpenPGP signing request", failure)
        }
        when (accepted) {
            OpenPgpAcceptResult.STORED -> {
                runCatching {
                    notifications.post(
                        request.requestId,
                        deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                    )
                }
                OpenPgpSignExpiryWorker.enqueue(context, request.requestId, request.expiresAt)
            }
            OpenPgpAcceptResult.DUPLICATE -> {
                val stored = store.find(request.requestId)
                when (stored?.state) {
                    OpenPgpRequestState.PENDING_REVIEW -> {
                        runCatching {
                            notifications.post(
                                request.requestId,
                                deviceNameOf(message.senderId) ?: message.senderId.shortForm(),
                            )
                        }
                        OpenPgpSignExpiryWorker.enqueue(context, request.requestId, request.expiresAt)
                    }
                    in OUTBOX_STATES -> OpenPgpSignResponseWorker.enqueue(context, request.requestId)
                    else -> Unit
                }
            }
            OpenPgpAcceptResult.CONFLICT, OpenPgpAcceptResult.RATE_LIMITED -> Unit
        }
    }

    private fun receiveCancel(message: InboundMessage, cancel: OpenPgpSignSync) {
        if (cancel.requesterClientId != message.senderId || cancel.validationError(::sha256) != null) return
        val existing = store.find(cancel.requestId) ?: return
        if (
            existing.senderClientId != message.senderId ||
            existing.request.requesterClientId != cancel.requesterClientId ||
            existing.request.primaryKeyId != cancel.primaryKeyId ||
            existing.request.objectKind != cancel.objectKind ||
            existing.request.issuedAt != cancel.issuedAt ||
            existing.request.expiresAt != cancel.expiresAt ||
            !MessageDigest.isEqual(existing.request.payloadSha256, cancel.payloadSha256)
        ) return
        if (store.cancel(cancel.requestId, message.senderId, now())) {
            notifications.dismiss(cancel.requestId)
        }
    }

    suspend fun sendPersistedResponse(requestId: String): Boolean {
        val stored = store.find(requestId) ?: return true
        if (stored.state !in OUTBOX_STATES) return true
        val currentTime = now()
        if (currentTime > stored.request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS) {
            store.markExpired(requestId, currentTime)
            notifications.dismiss(requestId)
            return true
        }
        val encoded = stored.encodedResponse ?: return false
        val response = runCatching { ProtocolCodec.decodeFromCbor<OpenPgpSignSync>(encoded) }.getOrNull()
            ?: return false
        if (
            response.requestId != stored.request.requestId ||
            response.requesterClientId != stored.request.requesterClientId ||
            response.issuedAt != stored.request.issuedAt ||
            response.expiresAt != stored.request.expiresAt ||
            response.primaryKeyId != stored.request.primaryKeyId ||
            response.objectKind != stored.request.objectKind ||
            !MessageDigest.isEqual(response.payloadSha256, stored.request.payloadSha256) ||
            response.validationError(::sha256) != null
        ) return false
        val accepted = channel.send(
            MessageType.DATA_SYNC,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = response)),
            Recipients.Only(stored.request.requesterClientId),
            Urgency.NORMAL,
        ) > 0
        if (accepted) {
            store.markSent(requestId, currentTime)
            notifications.dismiss(requestId)
        }
        return accepted
    }

    fun reconcile() {
        val currentTime = now()
        store.expireDue(currentTime).forEach(notifications::dismiss)
        store.requests.value.forEach { stored ->
            when (stored.state) {
                OpenPgpRequestState.PENDING_REVIEW -> {
                    runCatching {
                        notifications.post(
                            stored.request.requestId,
                            deviceNameOf(stored.senderClientId) ?: stored.senderClientId.shortForm(),
                        )
                    }
                    OpenPgpSignExpiryWorker.enqueue(
                        context,
                        stored.request.requestId,
                        stored.request.expiresAt,
                    )
                }
                in OUTBOX_STATES -> {
                    OpenPgpSignResponseWorker.enqueue(context, stored.request.requestId)
                    OpenPgpSignExpiryWorker.enqueue(
                        context,
                        stored.request.requestId,
                        stored.request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS,
                    )
                }
                else -> Unit
            }
        }
    }

    companion object {
        internal val OUTBOX_STATES = setOf(
            OpenPgpRequestState.SIGNED_PENDING_SEND,
            OpenPgpRequestState.REJECTED_PENDING_SEND,
        )

        private fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
