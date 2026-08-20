package net.extrawdw.apps.notisync.data.corecommand.preparation

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.BoundCoreTrustCommand
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandBinding
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandIdentityPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandPreparationPort
import net.extrawdw.apps.notisync.data.corecommand.CoreTrustCommandPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.DecodedCoreCommandIdentity
import net.extrawdw.apps.notisync.data.corecommand.matches
import net.extrawdw.apps.notisync.data.corecommand.toCoreType
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.apps.notisync.data.storage.core.CoreCommandActivity
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.trust.FoundationTrustCommandContext
import net.extrawdw.notisync.peer.trust.FoundationTrustEffect
import net.extrawdw.notisync.peer.trust.FoundationTrustReductionResult
import net.extrawdw.notisync.peer.trust.FoundationTrustSecurityReason
import net.extrawdw.notisync.peer.trust.FoundationTrustSnapshotReducer
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshot
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshotFormat
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.ClientIds

/**
 * Production-ready pure preparation boundary for authenticated PROFILE/TRUST/CARD deliveries.
 *
 * The injected reader completes before the existing Keystore signer is loaded. Decode/reduce/sign then run outside
 * SQL, and the resulting command carries the repository digest for the later Core compare-and-replace transaction.
 */
internal class DefaultCoreCommandPreparation(
    private val snapshotReader: CoreTrustPreparationSnapshotReader,
    private val identitySignerLoader: ExistingIdentitySignerLoader,
    incomingTrustPolicy: IncomingTrustPolicy,
) : CoreCommandPreparationPort {
    private val reducer = FoundationTrustSnapshotReducer(incomingTrustPolicy)

    override suspend fun decodeIdentity(
        delivery: AuthenticatedCoreCommandDelivery,
    ): CoreCommandIdentityPreparationResult {
        currentCoroutineContext().ensureActive()
        val decoded = delivery.decodedCommand
        currentCoroutineContext().ensureActive()
        if (!delivery.commandType.matches(decoded)) {
            return CoreCommandIdentityPreparationResult.SecurityBlocked(CODE_COMMAND_TYPE_MISMATCH)
        }
        return CoreCommandIdentityPreparationResult.Ready(
            DecodedCoreCommandIdentity(
                commandId = delivery.commandId,
                authenticatedRequestId = delivery.authenticatedRequestId,
                commandType = delivery.commandType,
                decodedCommand = decoded,
            ),
        )
    }

    override suspend fun reduceAndSign(
        delivery: AuthenticatedCoreCommandDelivery,
        binding: CoreCommandBinding,
    ): CoreTrustCommandPreparationResult {
        currentCoroutineContext().ensureActive()
        if (!binding.matchesAuthenticatedDelivery(delivery)) {
            return CoreTrustCommandPreparationResult.SecurityBlocked(CODE_BINDING_MISMATCH)
        }
        val preparedSnapshot = when (val result = snapshotReader.readCurrent()) {
            is CoreTrustPreparationSnapshotResult.Ready -> result.snapshot
            is CoreTrustPreparationSnapshotResult.NotReady ->
                return CoreTrustCommandPreparationResult.Retryable(result.errorCode)
            is CoreTrustPreparationSnapshotResult.SecurityBlocked ->
                return CoreTrustCommandPreparationResult.SecurityBlocked(result.errorCode)
        }
        currentCoroutineContext().ensureActive()
        val identity = identitySignerLoader.loadExisting(preparedSnapshot.identityAlias)
            ?: return CoreTrustCommandPreparationResult.Retryable(CODE_IDENTITY_SIGNER_MISSING)
        currentCoroutineContext().ensureActive()
        if (!identity.matches(preparedSnapshot)) {
            return CoreTrustCommandPreparationResult.SecurityBlocked(CODE_IDENTITY_SIGNER_MISMATCH)
        }
        val reduced = reducer.reduce(
            current = preparedSnapshot.trustSnapshotCopy(),
            identity = identity,
            context = FoundationTrustCommandContext(
                senderId = ClientId(delivery.senderId),
                senderOwnDevice = delivery.senderOwnDevice,
                signerEpoch = delivery.signerEpoch,
                signedCreatedAt = delivery.signedCreatedAt,
            ),
            command = binding.decodedCommandForPreparation(),
        )
        currentCoroutineContext().ensureActive()
        return when (reduced) {
            is FoundationTrustReductionResult.SecurityBlocked ->
                CoreTrustCommandPreparationResult.SecurityBlocked(reduced.reason.toStableCode())
            is FoundationTrustReductionResult.Ready -> {
                val command = CoreTrustCommand(
                    commandId = binding.commandId,
                    authenticatedRequestId = binding.authenticatedRequestId,
                    canonicalCommand = binding.canonicalCommandCopy(),
                    commandType = binding.commandType.toCoreType(),
                    expectedOperationalGeneration = binding.expectedOperationalGeneration,
                    expectedOperationalIncarnationId = binding.expectedOperationalIncarnationId,
                    expectedSnapshotDigest = preparedSnapshot.trustSnapshotDigestCopy(),
                    candidateSnapshot = reduced.snapshotCopy().toCoreInput(),
                    activity = reduced.effect.toActivity(delivery),
                )
                CoreTrustCommandPreparationResult.Ready(BoundCoreTrustCommand.bind(binding, command))
            }
        }
    }

    companion object {
        val CODE_COMMAND_TYPE_MISMATCH = RelayStableCode.of("core_command_type_mismatch")
        val CODE_BINDING_MISMATCH = RelayStableCode.of("core_preparation_binding_mismatch")
        val CODE_IDENTITY_SIGNER_MISSING = RelayStableCode.of("core_identity_signer_missing")
        val CODE_IDENTITY_SIGNER_MISMATCH = RelayStableCode.of("core_identity_signer_mismatch")
        val CODE_INVALID_SNAPSHOT = RelayStableCode.of("core_trust_snapshot_invalid")
        val CODE_UNAUTHORIZED_SENDER = RelayStableCode.of("core_sender_unauthorized")
        val CODE_SIGNER_POLICY = RelayStableCode.of("core_signer_policy_mismatch")
        val CODE_PROFILE_SUBJECT = RelayStableCode.of("core_profile_subject_mismatch")
    }
}

private fun CoreCommandKind.matches(command: FoundationTrustCommand): Boolean = matches(command.kind)

private fun CoreCommandBinding.matchesAuthenticatedDelivery(delivery: AuthenticatedCoreCommandDelivery): Boolean =
    messageId == delivery.messageId &&
        commandId == delivery.commandId &&
        authenticatedRequestId == delivery.authenticatedRequestId &&
        commandType == delivery.commandType &&
        senderId == delivery.senderId &&
        senderOwnDevice == delivery.senderOwnDevice &&
        signerEpoch == delivery.signerEpoch &&
        signedCreatedAt == delivery.signedCreatedAt &&
        deliveryMode == delivery.deliveryMode &&
        decodedCommandForPreparation() === delivery.decodedCommand &&
        authenticatedToken == delivery.authenticatedToken &&
        expectedOperationalGeneration == delivery.continuity.generation &&
        expectedOperationalIncarnationId == delivery.continuity.storageIncarnationId &&
        MessageDigest.isEqual(canonicalCommandCopy(), delivery.canonicalCommandCopy()) &&
        MessageDigest.isEqual(commandDigestCopy(), delivery.commandDigestCopy())

private fun net.extrawdw.notisync.protocol.crypto.IdentitySigner.matches(
    snapshot: CoreTrustPreparationSnapshot,
): Boolean {
    val spki = publicKeySpki.copyOf()
    val derived = runCatching { ClientIds.derive(spki).value }.getOrNull() ?: return false
    return clientId.value == snapshot.identityClientId &&
        derived == snapshot.identityClientId &&
        MessageDigest.isEqual(spki, snapshot.identityPublicSpkiCopy())
}

private fun FoundationTrustSecurityReason.toStableCode(): RelayStableCode = when (this) {
    FoundationTrustSecurityReason.INVALID_SIGNED_SNAPSHOT -> DefaultCoreCommandPreparation.CODE_INVALID_SNAPSHOT
    FoundationTrustSecurityReason.UNAUTHORIZED_SENDER -> DefaultCoreCommandPreparation.CODE_UNAUTHORIZED_SENDER
    FoundationTrustSecurityReason.SIGNER_POLICY_MISMATCH -> DefaultCoreCommandPreparation.CODE_SIGNER_POLICY
    FoundationTrustSecurityReason.PROFILE_SUBJECT_MISMATCH -> DefaultCoreCommandPreparation.CODE_PROFILE_SUBJECT
}

private fun SignedTrustSnapshot.toCoreInput(): TrustSnapshotInput = when (format) {
    SignedTrustSnapshotFormat.THREE_SECTION -> TrustSnapshotInput.ThreeSection(
        entriesUtf8 = entriesUtf8Copy(),
        cardsUtf8 = cardsUtf8Copy(),
        overlaysUtf8 = overlaysUtf8Copy(),
        signatureBase64UrlUtf8 = signatureBase64UrlUtf8Copy(),
    )
    SignedTrustSnapshotFormat.FOUR_SECTION -> TrustSnapshotInput.FourSection(
        entriesUtf8 = entriesUtf8Copy(),
        cardsUtf8 = cardsUtf8Copy(),
        overlaysUtf8 = overlaysUtf8Copy(),
        epochsUtf8 = requireNotNull(epochsUtf8CopyOrNull()),
        signatureBase64UrlUtf8 = signatureBase64UrlUtf8Copy(),
    )
}

private fun FoundationTrustEffect.toActivity(
    delivery: AuthenticatedCoreCommandDelivery,
): CoreCommandActivity? = when (this) {
    FoundationTrustEffect.None -> null
    is FoundationTrustEffect.ProfileChanged -> CoreCommandActivity(
        action = ActivityAction.APPLIED,
        peerClientId = peerId.value,
        deliveryMode = delivery.deliveryMode,
        renderArgs = ActivityRenderArgs.V1(revision = revision.takeIf { it >= 0 }),
        occurredAt = delivery.signedCreatedAt,
    )
    is FoundationTrustEffect.TrustChanged -> CoreCommandActivity(
        action = if (hasConflict) ActivityAction.CONFLICT else ActivityAction.APPLIED,
        peerClientId = senderId.value,
        deliveryMode = delivery.deliveryMode,
        renderArgs = ActivityRenderArgs.V1(count = promptCount, revision = highestRevision),
        occurredAt = delivery.signedCreatedAt,
    )
}
