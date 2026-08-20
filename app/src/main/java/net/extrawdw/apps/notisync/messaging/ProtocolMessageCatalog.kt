package net.extrawdw.apps.notisync.messaging

import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.AssetSyncKind
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.SshAgentSyncKind

internal enum class SenderPolicy {
    TRUSTED_PEER,
    TRUSTED_OWN_DEVICE,
}

internal enum class SignerPolicy {
    ANY_VERIFIED,
    OPERATIONAL,
    IDENTITY,
}

/** Database authority that must own the authenticated receipt for this leaf. */
internal enum class InboundCommitBoundary {
    OPERATIONAL_OWNER,
    CORE_THEN_OPERATIONAL_RECEIPT,
}

/** Closed semantic route key produced by the single protocol decode. */
internal sealed interface ProtocolLeaf {
    data object Notification : ProtocolLeaf
    data object Dismissal : ProtocolLeaf
    data class Action(val kind: ActionKind) : ProtocolLeaf
    data class Asset(val kind: AssetSyncKind) : ProtocolLeaf
    data object Profile : ProtocolLeaf
    data object Trust : ProtocolLeaf
    data object Card : ProtocolLeaf
    data object Filter : ProtocolLeaf
    data object QuietNotification : ProtocolLeaf
    data class Run(val kind: RunSyncKind) : ProtocolLeaf
    data class Screen(val action: ScreenMirrorAction) : ProtocolLeaf
    data class Seal(val action: OpenPgpSignAction) : ProtocolLeaf
    data class Ssh(val kind: SshAgentSyncKind) : ProtocolLeaf
}

/** Only behavior that the production router or coordinator actually enforces. */
internal data class ProtocolMessageDescriptor(
    val messageType: MessageType,
    val leaf: ProtocolLeaf,
    val senderPolicy: SenderPolicy,
    val signerPolicy: SignerPolicy,
    val commitBoundary: InboundCommitBoundary,
)

/**
 * Exhaustive behavior mapping over the already-decoded sealed leaf. There is no parallel metadata inventory:
 * idempotency, Activity, sensitivity, and ACK policy belong to the concrete owner transaction and its typed result.
 */
internal fun ProtocolLeaf.routingDescriptor(): ProtocolMessageDescriptor = when (this) {
    ProtocolLeaf.Notification -> operationalDescriptor(MessageType.NOTIFICATION)
    ProtocolLeaf.Dismissal -> operationalDescriptor(MessageType.DISMISSAL)
    is ProtocolLeaf.Action -> operationalDescriptor(MessageType.ACTION)
    is ProtocolLeaf.Asset,
    ProtocolLeaf.QuietNotification,
    is ProtocolLeaf.Run,
    is ProtocolLeaf.Screen,
    is ProtocolLeaf.Seal,
    is ProtocolLeaf.Ssh,
    -> operationalDescriptor(MessageType.DATA_SYNC)
    ProtocolLeaf.Profile,
    ProtocolLeaf.Filter,
    -> operationalDescriptor(
        messageType = MessageType.DATA_SYNC,
        senderPolicy = SenderPolicy.TRUSTED_PEER,
        commitBoundary = if (this == ProtocolLeaf.Profile) {
            InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT
        } else {
            InboundCommitBoundary.OPERATIONAL_OWNER
        },
    )
    ProtocolLeaf.Trust -> coreDescriptor(SignerPolicy.IDENTITY)
    ProtocolLeaf.Card -> coreDescriptor(SignerPolicy.ANY_VERIFIED)
}

private fun ProtocolLeaf.operationalDescriptor(
    messageType: MessageType,
    senderPolicy: SenderPolicy = SenderPolicy.TRUSTED_OWN_DEVICE,
    commitBoundary: InboundCommitBoundary = InboundCommitBoundary.OPERATIONAL_OWNER,
): ProtocolMessageDescriptor = ProtocolMessageDescriptor(
    messageType = messageType,
    leaf = this,
    senderPolicy = senderPolicy,
    signerPolicy = SignerPolicy.OPERATIONAL,
    commitBoundary = commitBoundary,
)

private fun ProtocolLeaf.coreDescriptor(signerPolicy: SignerPolicy): ProtocolMessageDescriptor =
    ProtocolMessageDescriptor(
        messageType = MessageType.DATA_SYNC,
        leaf = this,
        senderPolicy = SenderPolicy.TRUSTED_OWN_DEVICE,
        signerPolicy = signerPolicy,
        commitBoundary = InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT,
    )

/** Strict structural validation used after the single outer DATA_SYNC decode. */
internal fun DataSync.validateOneMatchingBody(): String? {
    val populated = listOfNotNull(
        asset?.let { DataSyncKind.ASSET },
        profile?.let { DataSyncKind.PROFILE },
        trust?.let { DataSyncKind.TRUST },
        card?.let { DataSyncKind.CARD },
        filter?.let { DataSyncKind.FILTER },
        notification?.let { DataSyncKind.NOTIFICATION },
        run?.let { DataSyncKind.RUN },
        screenMirror?.let { DataSyncKind.SCREEN_MIRRORING },
        openPgpSign?.let { DataSyncKind.OPENPGP_SIGN },
        sshAgent?.let { DataSyncKind.SSH_AGENT },
    )
    return when {
        populated.isEmpty() -> "DATA_SYNC body is missing"
        populated.size != 1 -> "DATA_SYNC contains multiple bodies"
        populated.single() != kind -> "DATA_SYNC body does not match discriminator"
        else -> null
    }
}
