package net.extrawdw.notisync.sshagent.bridge

import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshForgetResult
import net.extrawdw.notisync.protocol.SshImportResult
import net.extrawdw.notisync.protocol.SshSignResult
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore

interface SshInboundHandler {
    fun onSignResult(authenticatedProvider: ClientId, result: SshSignResult)
    fun onImportResult(authenticatedProvider: ClientId, result: SshImportResult) = Unit
    fun onForgetResult(authenticatedProvider: ClientId, result: SshForgetResult) = Unit

    object None : SshInboundHandler {
        override fun onSignResult(authenticatedProvider: ClientId, result: SshSignResult) = Unit
    }
}

class InboundSshSyncLoop(
    private val api: DaemonLocalApi,
    private val snapshots: ProviderSnapshotStore,
    private val handler: SshInboundHandler = SshInboundHandler.None,
    private val now: () -> Long = System::currentTimeMillis,
    private val onConnected: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val activeStream = AtomicReference<net.extrawdw.notisync.desktop.api.ReceiveStream?>()
    val receiveRequest = ReceiveRequest(
        applicationId = SshApplicationBridge.APPLICATION_ID,
        messageTypes = listOf(MessageType.DATA_SYNC),
        filters = listOf(
            MessageFilter(MessageType.DATA_SYNC, "/kind", listOf(JsonPrimitive(DataSyncKind.SSH_AGENT.name))),
        ),
    )

    fun run() {
        while (!closed.get()) {
            try {
                api.openReceive(receiveRequest).use { stream ->
                    activeStream.set(stream)
                    onConnected()
                    while (!closed.get()) {
                        val record = stream.next() ?: break
                        if (record.recordType == ReceiveRecordType.HEARTBEAT) continue
                        process(record)
                    }
                }
            } catch (_: Exception) {
                if (!closed.get()) Thread.sleep(RECONNECT_DELAY_MILLIS)
            } finally {
                activeStream.set(null)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeStream.getAndSet(null)?.close()
    }

    fun process(record: ReceiveRecord) {
        val envelopeId = record.envelopeId ?: return
        val parsed = decode(record)
        if (parsed == null) {
            api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
            return
        }
        val (sender, sync) = parsed
        when (sync.kind) {
            SshAgentSyncKind.KEYS_SNAPSHOT -> {
                val snapshot = requireNotNull(sync.keysSnapshot)
                if (snapshot.providerClientId != sender) {
                    api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
                    return
                }
                snapshots.apply(sender, snapshot, now())
            }
            SshAgentSyncKind.SIGN_RESULT -> {
                val result = requireNotNull(sync.signResult)
                if (result.providerClientId != sender) {
                    api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
                    return
                }
                handler.onSignResult(sender, result)
            }
            SshAgentSyncKind.IMPORT_RESULT -> {
                val result = requireNotNull(sync.importResult)
                if (result.providerClientId != sender) {
                    api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
                    return
                }
                handler.onImportResult(sender, result)
            }
            SshAgentSyncKind.FORGET_RESULT -> {
                val result = requireNotNull(sync.forgetResult)
                if (result.providerClientId != sender) {
                    api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
                    return
                }
                handler.onForgetResult(sender, result)
            }
            else -> {
                // Provider-to-agent direction accepts snapshots and correlated results only.
                api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
                return
            }
        }
        api.complete(SshApplicationBridge.APPLICATION_ID, envelopeId)
    }

    private fun decode(record: ReceiveRecord): Pair<ClientId, SshAgentSync>? {
        if (record.messageType != MessageType.DATA_SYNC || record.senderOwnDevice != true) return null
        val sender = record.senderClientId?.takeIf(String::isNotBlank)?.let(::ClientId) ?: return null
        val encoded = record.body ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        val dataSync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(bytes) }.getOrNull() ?: return null
        if (dataSync.kind != DataSyncKind.SSH_AGENT) return null
        val ssh = dataSync.sshAgent ?: return null
        if (ssh.validationError(::sha256) != null) return null
        return sender to ssh
    }

    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    private companion object {
        const val RECONNECT_DELAY_MILLIS = 1_000L
    }
}
