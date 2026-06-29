package net.extrawdw.apps.notisync.foundation

import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType

/**
 * Inbound authorization for an own/other device. Collapses the five scattered
 * `if (!sender.ownDevice) return` gates that used to live in MirrorEngine.handleEnvelope into one
 * auditable predicate.
 *
 * The rule: an "other" device (someone else's, kept in the synced private contact list) may ONLY
 * send a `PROFILE` update — its rename converges across the mesh. Everything else — notifications,
 * dismissals, asset repair, trust rosters, card deliveries, notification filters — is own-mesh only.
 */
object SendPolicy {
    /** May a message of this [typ] / DataSync [kind] be accepted from a sender with this classification? */
    fun mayAccept(typ: MessageType, kind: DataSyncKind?, senderOwnDevice: Boolean): Boolean =
        if (typ == MessageType.DATA_SYNC && kind == DataSyncKind.PROFILE) true
        else senderOwnDevice
}
