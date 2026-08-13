package net.extrawdw.notisync.gpg

import java.net.SocketTimeoutException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.LocalApiDeadline
import net.extrawdw.notisync.desktop.api.UnixDaemonClient
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency

sealed interface RemoteSigningOutcome {
    data class Signed(val signature: VerifiedSignature) : RemoteSigningOutcome
    data class Rejected(val reason: String) : RemoteSigningOutcome
}

class RemoteSigningClient(
    private val paths: DesktopPaths,
    private val config: NotisyncGpgConfig,
    private val api: DaemonLocalApi = UnixDaemonClient(paths.socket),
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
    private val onRequestSubmitted: (OpenPgpSignSync) -> Unit = {},
    private val workingDirectory: () -> String? = ::currentWorkingDirectoryContext,
) {
    fun sign(payload: ByteArray, certificate: ResolvedOpenPgpCertificate): RemoteSigningOutcome {
        val status = api.status()
        val requesterId = ClientId(requireNotNull(status.clientId) { "notisyncd has no local client identity" })
        api.putApplication(
            APPLICATION_ID,
            ApplicationRegistrationRequest("NotiSync Seal", version = "1", capabilities = emptySet()),
        )

        val requestId = ByteArray(16).also(random::nextBytes).toLowerHex()
        val issuedAt = now()
        val expiresAt = issuedAt + minOf(
            config.timeoutSeconds * 1_000L,
            OpenPgpSignLimits.MAX_REQUEST_LIFETIME_MILLIS,
        )
        val request = OpenPgpSignSync(
            action = OpenPgpSignAction.REQUEST,
            requestId = requestId,
            requesterClientId = requesterId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            primaryKeyId = certificate.primaryKeyId,
            payloadSha256 = sha256(payload),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            payload = payload,
            workingDirectory = workingDirectory(),
        )
        require(request.validationError(::sha256) == null) { "refusing to send an invalid signing request" }
        val interest = ReceiveRequest(
            applicationId = APPLICATION_ID,
            messageTypes = listOf(MessageType.DATA_SYNC),
            filters = listOf(
                MessageFilter(MessageType.DATA_SYNC, "/kind", listOf(JsonPrimitive(DataSyncKind.OPENPGP_SIGN.name))),
                MessageFilter(MessageType.DATA_SYNC, "/openPgpSign/requestId", listOf(JsonPrimitive(requestId))),
            ),
        )
        val stream = api.openReceive(interest)
        var submitted = false
        try {
            api.send(request.toSendRequest(Urgency.HIGH))
            submitted = true
            runCatching { onRequestSubmitted(request) }
            while (true) {
                val remaining = expiresAt - now()
                if (remaining <= 0) throw SocketTimeoutException("remote signing request expired")
                val record = LocalApiDeadline.run(Duration.ofMillis(remaining)) { stream.next() }
                    ?: throw IllegalStateException("notisyncd closed the signing response stream")
                if (record.recordType == ReceiveRecordType.HEARTBEAT) continue
                val envelopeId = record.envelopeId ?: continue
                try {
                    val response = decodeResponse(record.body) ?: continue
                    if (!matchesOutstanding(response, request, record.senderOwnDevice, record.senderClientId, now())) {
                        continue
                    }
                    when (response.action) {
                        OpenPgpSignAction.REJECT -> return RemoteSigningOutcome.Rejected(
                            response.rejectReason?.name ?: "REJECTED"
                        )
                        OpenPgpSignAction.RESULT -> {
                            val verified = runCatching {
                                GpgSignatureVerifier(config.realGpgPath, paths.dataDirectory).verify(
                                    armor = requireNotNull(response.signatureArmor),
                                    payload = payload,
                                    certificate = certificate,
                                    issuedAtMillis = issuedAt,
                                    expiresAtMillis = expiresAt,
                                )
                            }.getOrNull() ?: continue
                            return RemoteSigningOutcome.Signed(verified)
                        }
                        else -> continue
                    }
                } finally {
                    runCatching { api.complete(APPLICATION_ID, envelopeId) }
                }
            }
        } finally {
            if (submitted) runCatching { api.send(request.cancel(now()).toSendRequest(Urgency.NORMAL)) }
            runCatching { stream.close() }
            runCatching { api.unregisterReceive(interest) }
        }
    }

    private fun decodeResponse(body: String?): OpenPgpSignSync? = runCatching {
        val data = ProtocolCodec.decodeFromCbor<DataSync>(Base64.getDecoder().decode(requireNotNull(body)))
        data.takeIf { it.kind == DataSyncKind.OPENPGP_SIGN }?.openPgpSign
    }.getOrNull()

    private fun matchesOutstanding(
        response: OpenPgpSignSync,
        request: OpenPgpSignSync,
        senderOwnDevice: Boolean?,
        senderClientId: String?,
        receivedAt: Long,
    ): Boolean {
        if (senderOwnDevice != true || senderClientId.isNullOrBlank()) return false
        if (response.action !in setOf(OpenPgpSignAction.RESULT, OpenPgpSignAction.REJECT)) return false
        if (response.validationError(::sha256) != null) return false
        if (
            response.requestId != request.requestId ||
            response.requesterClientId != request.requesterClientId ||
            response.issuedAt != request.issuedAt ||
            response.expiresAt != request.expiresAt ||
            response.primaryKeyId != request.primaryKeyId ||
            !response.payloadSha256.contentEquals(request.payloadSha256) ||
            response.objectKind != request.objectKind
        ) return false
        val actionAt = response.actionAt ?: return false
        if (
            actionAt < request.issuedAt - OpenPgpSignLimits.CLOCK_SKEW_MILLIS ||
            actionAt > request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
        ) return false
        return receivedAt <= request.expiresAt + OpenPgpSignLimits.CLOCK_SKEW_MILLIS
    }

    private fun OpenPgpSignSync.cancel(actionAt: Long): OpenPgpSignSync = copy(
        action = OpenPgpSignAction.CANCEL,
        payload = null,
        signatureArmor = null,
        rejectReason = null,
        actionAt = actionAt,
        workingDirectory = null,
    )

    private fun OpenPgpSignSync.toSendRequest(urgency: Urgency): SendRequest = SendRequest(
        applicationId = APPLICATION_ID,
        messageType = MessageType.DATA_SYNC,
        body = Base64.getEncoder().encodeToString(
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.OPENPGP_SIGN, openPgpSign = this))
        ),
        scope = Recipients.OwnMeshFiltered(
            requiredCapabilities = requiredSignerCapabilities(),
            requireCapabilityRoutingV1 = true,
        ),
        urgency = urgency,
        submissionId = if (action == OpenPgpSignAction.REQUEST) requestId else "$requestId-cancel",
    )

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val APPLICATION_ID = "notisync-gpg"
    }
}

internal fun currentWorkingDirectoryContext(): String? = runCatching {
    Path.of("").toAbsolutePath().normalize().toString()
        .takeIf { path ->
            path.isNotBlank() &&
                path.encodeToByteArray().size <= OpenPgpSignLimits.MAX_WORKING_DIRECTORY_UTF8_BYTES &&
                path.none(Char::isISOControl)
        }
}.getOrNull()
