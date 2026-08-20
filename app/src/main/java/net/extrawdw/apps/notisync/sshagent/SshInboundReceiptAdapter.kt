package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.apps.notisync.data.ssh.RoomSshProviderRepository
import net.extrawdw.apps.notisync.messaging.inbound.SshInboundReceiptPort

/**
 * Narrow graph adapter for the authenticated inbound owner boundary. The dispatch layer knows
 * only this port; SSH remains responsible for preparing protected request/history projections.
 */
internal fun RoomSshProviderRepository.inboundReceiptPort(): SshInboundReceiptPort =
    SshInboundReceiptPort { request, senderClientId, now, receipt ->
        acceptWithReceipt(request, senderClientId, now, receipt)
    }
