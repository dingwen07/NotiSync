package net.extrawdw.notisync.peer.foundation

import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.TrustTable

/** Closed subset of DATA_SYNC commands that replace the identity-signed trust aggregate. */
enum class FoundationTrustCommandKind {
    PROFILE,
    TRUST,
    CARD,
}

/**
 * One structurally validated Foundation command decoded from the exact authenticated DATA_SYNC bytes.
 *
 * Protocol DTOs containing lists or byte arrays are copied on construction and access. The reducer can therefore
 * retain this value between identity decoding and snapshot reduction without retaining a mutable transport buffer.
 */
sealed class FoundationTrustCommand private constructor(
    val kind: FoundationTrustCommandKind,
) {
    class Profile internal constructor(update: ProfileUpdate) :
        FoundationTrustCommand(FoundationTrustCommandKind.PROFILE) {
        private val storedUpdate = update.defensiveCopy()

        internal fun updateCopy(): ProfileUpdate = storedUpdate.defensiveCopy()
    }

    class Trust internal constructor(table: TrustTable) :
        FoundationTrustCommand(FoundationTrustCommandKind.TRUST) {
        private val storedTable = table.defensiveCopy()

        internal fun tableCopy(): TrustTable = storedTable.defensiveCopy()
    }

    class Card internal constructor(delivery: CardDelivery) :
        FoundationTrustCommand(FoundationTrustCommandKind.CARD) {
        private val storedDelivery = delivery.defensiveCopy()

        internal fun deliveryCopy(): CardDelivery = storedDelivery.defensiveCopy()
    }

    companion object {
        /**
         * Defensively retain a DATA_SYNC value already decoded by the authenticated inbound router.
         *
         * Callers must pass the value produced by the same single decode that classified the exact authenticated
         * body retained alongside this command. This factory deliberately does not re-encode or normalize it.
         */
        fun fromDecoded(sync: DataSync): FoundationTrustCommandDecodeResult {
            if (!sync.hasExactlyOneMatchingBody()) return FoundationTrustCommandDecodeResult.Malformed
            val command = when (sync.kind) {
                DataSyncKind.PROFILE -> Profile(requireNotNull(sync.profile))
                DataSyncKind.TRUST -> Trust(requireNotNull(sync.trust))
                DataSyncKind.CARD -> Card(requireNotNull(sync.card))
                else -> return FoundationTrustCommandDecodeResult.Unsupported
            }
            return FoundationTrustCommandDecodeResult.Ready(command)
        }

        /** Decode and validate exactly one matching PROFILE/TRUST/CARD body. */
        fun decode(canonicalCommand: ByteArray): FoundationTrustCommandDecodeResult {
            val sync = try {
                ProtocolCodec.decodeFromCbor<DataSync>(canonicalCommand.copyOf())
            } catch (_: Exception) {
                return FoundationTrustCommandDecodeResult.Malformed
            }
            return fromDecoded(sync)
        }
    }
}

sealed interface FoundationTrustCommandDecodeResult {
    data class Ready(val command: FoundationTrustCommand) : FoundationTrustCommandDecodeResult
    data object Malformed : FoundationTrustCommandDecodeResult
    data object Unsupported : FoundationTrustCommandDecodeResult
}

private fun DataSync.hasExactlyOneMatchingBody(): Boolean {
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
    return populated.size == 1 && populated.single() == kind
}

private fun ProfileUpdate.defensiveCopy(): ProfileUpdate = copy(capabilities = capabilities.toList())

private fun TrustTable.defensiveCopy(): TrustTable = copy(entries = entries.toList())

private fun CardDelivery.defensiveCopy(): CardDelivery = copy(
    card = card?.defensiveCopy(),
    epochBlob = epochBlob?.defensiveCopy(),
)

private fun SignedBlob.defensiveCopy(): SignedBlob = copy(
    payload = payload.copyOf(),
    sig = sig.copyOf(),
)
