package net.extrawdw.apps.notisync.data.corecommand

import java.security.MessageDigest
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandKind
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommandType

/** Identity decoded from the exact authenticated canonical bytes without reducing or signing the aggregate. */
internal data class DecodedCoreCommandIdentity(
    val commandId: String,
    val authenticatedRequestId: String,
    val commandType: CoreCommandKind,
    val decodedCommand: FoundationTrustCommand,
) {
    init {
        requireCompactIdentifier(commandId, "decoded Core command id")
        requireCompactIdentifier(authenticatedRequestId, "decoded authenticated request id")
        require(commandType.matches(decodedCommand.kind)) {
            "decoded Foundation command kind diverges from the Core command type"
        }
    }
}

/** Processor-owned binding between reducer output and one exact authenticated broker delivery. */
internal class CoreCommandBinding private constructor(
    val messageId: String,
    val commandId: String,
    val authenticatedRequestId: String,
    val commandType: CoreCommandKind,
    val senderId: String,
    val senderOwnDevice: Boolean,
    val signerEpoch: Int,
    val signedCreatedAt: Long,
    val deliveryMode: net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode,
    val authenticatedToken: AuthenticatedRelayToken,
    val expectedOperationalGeneration: Long,
    val expectedOperationalIncarnationId: String,
    canonicalCommand: ByteArray,
    commandDigest: ByteArray,
    decodedCommand: FoundationTrustCommand,
) {
    private val storedCanonicalCommand = canonicalCommand.copyOf()
    private val storedCommandDigest = commandDigest.copyOf()
    private val storedDecodedCommand = decodedCommand

    fun canonicalCommandCopy(): ByteArray = storedCanonicalCommand.copyOf()
    fun commandDigestCopy(): ByteArray = storedCommandDigest.copyOf()
    internal fun decodedCommandForPreparation(): FoundationTrustCommand = storedDecodedCommand

    init {
        requireCompactIdentifier(messageId, "bound Core delivery message id")
        requireCompactIdentifier(commandId, "bound Core command id")
        requireCompactIdentifier(authenticatedRequestId, "bound authenticated request id")
        requireCompactIdentifier(senderId, "bound authenticated sender id")
        require(signerEpoch >= 0) { "bound signer epoch must not be negative" }
        require(signedCreatedAt > 0) { "bound signed creation time must be positive" }
        require(commandType.matches(storedDecodedCommand.kind)) {
            "bound decoded command kind diverges from its Core type"
        }
        require(expectedOperationalGeneration > 0) { "bound Operational generation must be positive" }
        requireStorageIncarnationId(expectedOperationalIncarnationId)
        require(storedCanonicalCommand.size in 1..CoreCommandLimits.MAX_CANONICAL_BYTES) {
            "bound canonical Core command exceeds the reviewed limit"
        }
        require(storedCommandDigest.size == CoreCommandLimits.SHA256_BYTES) {
            "bound Core command digest must be SHA-256"
        }
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance("SHA-256").digest(storedCanonicalCommand),
                storedCommandDigest,
            ),
        ) { "bound Core command digest diverges from its exact canonical bytes" }
    }

    internal fun matches(other: CoreCommandBinding): Boolean =
        messageId == other.messageId &&
            commandId == other.commandId &&
            authenticatedRequestId == other.authenticatedRequestId &&
            commandType == other.commandType &&
            senderId == other.senderId &&
            senderOwnDevice == other.senderOwnDevice &&
            signerEpoch == other.signerEpoch &&
            signedCreatedAt == other.signedCreatedAt &&
            deliveryMode == other.deliveryMode &&
            storedDecodedCommand === other.storedDecodedCommand &&
            authenticatedToken == other.authenticatedToken &&
            expectedOperationalGeneration == other.expectedOperationalGeneration &&
            expectedOperationalIncarnationId == other.expectedOperationalIncarnationId &&
            MessageDigest.isEqual(storedCanonicalCommand, other.storedCanonicalCommand) &&
            MessageDigest.isEqual(storedCommandDigest, other.storedCommandDigest)

    internal fun toReceiptIdentity(): CoreCommandReceiptIdentity = CoreCommandReceiptIdentity(
        commandId = commandId,
        authenticatedRequestId = authenticatedRequestId,
        commandDigest = storedCommandDigest,
        commandType = commandType,
    )

    override fun toString(): String =
        "CoreCommandBinding(messageId=$messageId, commandId=$commandId, type=${commandType.token}, " +
            "generation=$expectedOperationalGeneration, canonical=<${storedCanonicalCommand.size} bytes>, " +
            "digest=<${storedCommandDigest.size} bytes>)"

    companion object {
        fun bind(
            delivery: AuthenticatedCoreCommandDelivery,
            decoded: DecodedCoreCommandIdentity,
        ): CoreCommandBinding {
            require(delivery.commandId == decoded.commandId) {
                "decoded Core command id diverges from its authenticated delivery"
            }
            require(delivery.authenticatedRequestId == decoded.authenticatedRequestId) {
                "decoded authenticated request id diverges from its authenticated delivery"
            }
            require(delivery.commandType == decoded.commandType) {
                "decoded Core command type diverges from its authenticated delivery"
            }
            require(delivery.decodedCommand === decoded.decodedCommand) {
                "decoded Core command diverges from its single-decode authenticated delivery"
            }
            return CoreCommandBinding(
                messageId = delivery.messageId,
                commandId = delivery.commandId,
                authenticatedRequestId = delivery.authenticatedRequestId,
                commandType = delivery.commandType,
                senderId = delivery.senderId,
                senderOwnDevice = delivery.senderOwnDevice,
                signerEpoch = delivery.signerEpoch,
                signedCreatedAt = delivery.signedCreatedAt,
                deliveryMode = delivery.deliveryMode,
                authenticatedToken = delivery.authenticatedToken,
                expectedOperationalGeneration = delivery.continuity.generation,
                expectedOperationalIncarnationId = delivery.continuity.storageIncarnationId,
                canonicalCommand = delivery.canonicalCommandCopy(),
                commandDigest = delivery.commandDigestCopy(),
                decodedCommand = decoded.decodedCommand,
            )
        }
    }
}

/** Opaque reducer result accepted by Core only after exact delivery binding validation. */
internal class BoundCoreTrustCommand private constructor(
    val binding: CoreCommandBinding,
    private val command: CoreTrustCommand,
) {
    internal fun commandForAuthority(): CoreTrustCommand = command

    companion object {
        fun bind(binding: CoreCommandBinding, command: CoreTrustCommand): BoundCoreTrustCommand {
            require(command.commandId == binding.commandId) { "reducer output changed the Core command id" }
            require(command.authenticatedRequestId == binding.authenticatedRequestId) {
                "reducer output changed the authenticated request id"
            }
            require(command.commandType == binding.commandType.toCoreType()) {
                "reducer output changed the Core command type"
            }
            require(command.expectedOperationalGeneration == binding.expectedOperationalGeneration) {
                "reducer output changed the expected Operational generation"
            }
            require(command.expectedOperationalIncarnationId == binding.expectedOperationalIncarnationId) {
                "reducer output changed the expected Operational storage incarnation"
            }
            require(
                MessageDigest.isEqual(command.canonicalCommandCopy(), binding.canonicalCommandCopy()),
            ) { "reducer output changed the exact authenticated canonical bytes" }
            require(
                MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(command.canonicalCommandCopy()),
                    binding.commandDigestCopy(),
                ),
            ) { "reducer output digest diverges from the authenticated command" }
            return BoundCoreTrustCommand(binding, command)
        }
    }
}

internal sealed interface CoreCommandIdentityPreparationResult {
    data class Ready(val identity: DecodedCoreCommandIdentity) : CoreCommandIdentityPreparationResult
    data class Retryable(val errorCode: RelayStableCode) : CoreCommandIdentityPreparationResult
    data class SecurityBlocked(val errorCode: RelayStableCode) : CoreCommandIdentityPreparationResult
}

internal sealed interface CoreTrustCommandPreparationResult {
    data class Ready(val command: BoundCoreTrustCommand) : CoreTrustCommandPreparationResult
    data class Retryable(val errorCode: RelayStableCode) : CoreTrustCommandPreparationResult
    data class SecurityBlocked(val errorCode: RelayStableCode) : CoreTrustCommandPreparationResult
}

/** Pure protocol/reducer/signing boundary. Implementations must not open either Room database. */
internal interface CoreCommandPreparationPort {
    suspend fun decodeIdentity(
        delivery: AuthenticatedCoreCommandDelivery,
    ): CoreCommandIdentityPreparationResult

    suspend fun reduceAndSign(
        delivery: AuthenticatedCoreCommandDelivery,
        binding: CoreCommandBinding,
    ): CoreTrustCommandPreparationResult
}

internal fun CoreCommandKind.toCoreType(): CoreTrustCommandType = when (this) {
    CoreCommandKind.DATA_SYNC_PROFILE -> CoreTrustCommandType.DATA_SYNC_PROFILE
    CoreCommandKind.DATA_SYNC_TRUST -> CoreTrustCommandType.DATA_SYNC_TRUST
    CoreCommandKind.DATA_SYNC_CARD -> CoreTrustCommandType.DATA_SYNC_CARD
}

internal fun CoreTrustCommandType.toDeliveryKind(): CoreCommandKind = when (this) {
    CoreTrustCommandType.DATA_SYNC_PROFILE -> CoreCommandKind.DATA_SYNC_PROFILE
    CoreTrustCommandType.DATA_SYNC_TRUST -> CoreCommandKind.DATA_SYNC_TRUST
    CoreTrustCommandType.DATA_SYNC_CARD -> CoreCommandKind.DATA_SYNC_CARD
}

internal fun CoreCommandKind.matches(kind: FoundationTrustCommandKind): Boolean = when (this) {
    CoreCommandKind.DATA_SYNC_PROFILE -> kind == FoundationTrustCommandKind.PROFILE
    CoreCommandKind.DATA_SYNC_TRUST -> kind == FoundationTrustCommandKind.TRUST
    CoreCommandKind.DATA_SYNC_CARD -> kind == FoundationTrustCommandKind.CARD
}
