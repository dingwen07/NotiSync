package net.extrawdw.notisync.sshagent.bridge

import java.security.SecureRandom
import java.util.Base64
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SshAgentLimits
import net.extrawdw.notisync.protocol.SshAgentSync
import net.extrawdw.notisync.protocol.SshAgentSyncKind
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshKeysRequest
import net.extrawdw.notisync.protocol.SshSignRequest
import net.extrawdw.notisync.protocol.Urgency

class SshApplicationBridge(
    private val api: DaemonLocalApi,
    private val roster: ProviderRoster,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun register(): ClientId {
        api.putApplication(
            APPLICATION_ID,
            ApplicationRegistrationRequest(
                displayName = "NotiSync SSH Agent",
                version = "1",
                capabilities = setOf(Capability.SSH_AGENT_V1),
            ),
        )
        val clientId = requireNotNull(api.status().clientId) { "notisyncd has no active client identity" }
        return ClientId(clientId)
    }

    fun requestInventory(requesterClientId: ClientId, startup: Boolean): String? {
        val providers = roster.eligibleProviders()
        if (providers.isEmpty()) return null
        val operationId = randomId()
        val requestedAt = now()
        val nonce = ByteArray(SshAgentLimits.INVENTORY_NONCE_BYTES).also(RANDOM::nextBytes)
        val partitions = if (startup) {
            listOf(
                providers.filter(ProviderPeer::supportsFilteredPush) to Urgency.HIGH,
                providers.filterNot(ProviderPeer::supportsFilteredPush) to Urgency.NORMAL,
            )
        } else {
            listOf(providers to Urgency.NORMAL)
        }
        val sends = partitions.filter { it.first.isNotEmpty() }.map { (partition, urgency) ->
            val ids = partition.map(ProviderPeer::clientId)
            val request = SshKeysRequest(
                requestId = operationId,
                requesterClientId = requesterClientId,
                requestedAt = requestedAt,
                expiresAt = requestedAt + SshAgentLimits.MAX_KEYS_REQUEST_LIFETIME_MILLIS,
                startup = startup,
                targetProviderClientIds = ids,
                requesterInventoryNonce = nonce,
            )
            sendRequest(
                SshAgentSync(kind = SshAgentSyncKind.KEYS_REQUEST, keysRequest = request),
                ids,
                urgency,
            )
        }
        api.sendAll(sends)
        return operationId
    }

    fun sendSignRequest(request: SshSignRequest) {
        val byId = roster.eligibleProviders().associateBy(ProviderPeer::clientId)
        val eligible = request.eligibleProviderClientIds.mapNotNull(byId::get)
        require(eligible.size == request.eligibleProviderClientIds.size) {
            "sign request contains a provider that is no longer eligible"
        }
        api.send(
            sendRequest(
                SshAgentSync(kind = SshAgentSyncKind.SIGN_REQUEST, signRequest = request),
                eligible.map(ProviderPeer::clientId),
                Urgency.HIGH,
            ),
        )
    }

    fun sendImportRequest(request: SshImportRequest, targetProviderId: ClientId) {
        require(roster.eligibleProviders().any { it.clientId == targetProviderId }) {
            "import target is no longer an eligible key provider"
        }
        api.send(
            sendRequest(
                SshAgentSync(kind = SshAgentSyncKind.IMPORT_REQUEST, importRequest = request),
                listOf(targetProviderId),
                Urgency.HIGH,
            ),
        )
    }

    fun sendNormal(sync: SshAgentSync, targetProviderIds: List<ClientId>) {
        require(targetProviderIds.isNotEmpty())
        api.send(sendRequest(sync, targetProviderIds.sortedBy(ClientId::value), Urgency.NORMAL))
    }

    private fun sendRequest(sync: SshAgentSync, targetIds: List<ClientId>, urgency: Urgency): SendRequest {
        val validationError = sync.validationError(::sha256)
        require(validationError == null) { validationError ?: "invalid SSH sync" }
        require(targetIds.isNotEmpty() && targetIds.size == targetIds.toSet().size) {
            "SSH target provider set must be non-empty and unique"
        }
        if (urgency == Urgency.HIGH) validateHighRequest(sync, targetIds.toSet())
        // Interactive sign/import requests always create review UI, so they do not need PUSH_FILTERING.
        val required = when {
            urgency != Urgency.HIGH -> SshAgentLimits.NORMAL_PROVIDER_CAPABILITIES
            sync.kind == SshAgentSyncKind.SIGN_REQUEST -> SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES
            sync.kind == SshAgentSyncKind.IMPORT_REQUEST -> SshAgentLimits.HIGH_SIGN_PROVIDER_CAPABILITIES
            else -> SshAgentLimits.HIGH_FILTERING_PROVIDER_CAPABILITIES
        }
        val body = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.SSH_AGENT, sshAgent = sync))
        return SendRequest(
            applicationId = APPLICATION_ID,
            messageType = MessageType.DATA_SYNC,
            body = Base64.getEncoder().encodeToString(body),
            scope = Recipients.OnlyCapableSet(targetIds.toSet(), required),
            urgency = urgency,
            submissionId = randomId(),
        )
    }

    private fun validateHighRequest(sync: SshAgentSync, targets: Set<ClientId>) {
        when (sync.kind) {
            SshAgentSyncKind.KEYS_REQUEST -> {
                val request = requireNotNull(sync.keysRequest)
                require(request.startup) { "only startup inventory may use HIGH urgency" }
                require(request.requesterClientId !in targets) { "SSH audience must exclude requester" }
                require(targets == request.targetProviderClientIds.toSet()) {
                    "HIGH inventory audience must equal the signed target set"
                }
            }
            SshAgentSyncKind.SIGN_REQUEST -> {
                val request = requireNotNull(sync.signRequest)
                require(request.requesterClientId !in targets) { "SSH audience must exclude requester" }
                require(targets == request.eligibleProviderClientIds.toSet()) {
                    "HIGH sign audience must equal the signed eligible set"
                }
            }
            SshAgentSyncKind.IMPORT_REQUEST -> {
                val request = requireNotNull(sync.importRequest)
                require(request.requesterClientId !in targets) { "SSH audience must exclude requester" }
                require(targets.size == 1) { "HIGH import must target exactly one key provider" }
            }
            else -> throw IllegalArgumentException("only SSH keys/sign/import requests may use HIGH urgency")
        }
    }

    private fun randomId(): String = ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val APPLICATION_ID = "notisync-ssh-agent"
        private val RANDOM = SecureRandom()
    }
}
