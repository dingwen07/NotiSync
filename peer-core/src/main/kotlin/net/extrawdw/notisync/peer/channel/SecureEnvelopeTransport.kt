package net.extrawdw.notisync.peer.channel

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.PerRecipientKey
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.RecipientKey

/**
 * Authenticated envelope content handed to the application delivery coordinator.
 *
 * The encoded body is copied on entry and on every read. This object therefore remains the exact result of the
 * verified envelope snapshot even if a transport adapter or caller later mutates one of its byte arrays.
 */
class AuthenticatedEnvelopeMessage internal constructor(
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
    val type: MessageType,
    val signerEpoch: Int,
    val messageId: String,
    val sequence: Long,
    val createdAt: Long,
    val recipientEpoch: Int,
    encodedBody: ByteArray,
) {
    private val encodedBodySnapshot = encodedBody.copyOf()

    /** The opaque, already-authenticated feature body. The application decodes it exactly once. */
    val encodedBody: ByteArray
        get() = encodedBodySnapshot.copyOf()
}

/** Expected result of authenticating and opening one envelope. No result implies an ACK disposition. */
sealed interface InboundEnvelopeResult {
    data class Ready(val message: AuthenticatedEnvelopeMessage) : InboundEnvelopeResult

    /** The claimed signer and epoch are not present in the current trusted directory snapshot. */
    data class UnresolvedSender(
        val claimedSenderId: ClientId,
        val claimedSignerEpoch: Int,
    ) : InboundEnvelopeResult

    /** Signature verification failed, including a malformed signed envelope projection. */
    data class BadSignature(
        val claimedSenderId: ClientId,
        val claimedSignerEpoch: Int,
    ) : InboundEnvelopeResult

    /** The authenticated envelope cannot select a single locally retained recipient key. */
    data class RecipientUnavailable(
        val reason: RecipientUnavailableReason,
        val recipientEpoch: Int? = null,
    ) : InboundEnvelopeResult

    /** The signature was valid, but HPKE/AEAD authentication or decryption failed. */
    data class DecryptFailed(val recipientEpoch: Int) : InboundEnvelopeResult
}

enum class RecipientUnavailableReason {
    NOT_ADDRESSED_TO_THIS_DEVICE,
    AMBIGUOUS_RECIPIENT,
    MISSING_PRIVATE_KEY,
}

/** One durable outbound obligation. Both fields are caller-assigned and defensively copied. */
class EncodedOutboundEnvelope(
    val messageId: String,
    encodedBody: ByteArray,
) {
    private val encodedBodySnapshot = encodedBody.copyOf()

    val encodedBody: ByteArray
        get() = encodedBodySnapshot.copyOf()

    internal fun bodySnapshot(): ByteArray = encodedBodySnapshot.copyOf()
}

/** Normalized, defensively copied routing information returned by the transport. */
class OutboundTransportReport internal constructor(result: SendResult) {
    val delivered: List<ClientId> = result.delivered.toList()
    val missingRoutes: List<ClientId> = result.missingRoutes.toList()
    val invalidRoutes: List<ClientId> = result.invalidRoutes.toList()
    val staleRoutes: List<ClientId> = result.staleRoutes.toList()
}

class AcceptedOutboundEnvelope internal constructor(
    val messageId: String,
    val recipientIds: List<ClientId>,
    val transportReport: OutboundTransportReport,
)

/**
 * Result of a strict send or batch. [acceptedPrefix] is ordered exactly like the request and makes a partial batch
 * explicit without adding a persistence callback inside the crypto/transport boundary.
 */
sealed interface OutboundEnvelopeResult {
    val acceptedPrefix: List<AcceptedOutboundEnvelope>

    data class Accepted(
        override val acceptedPrefix: List<AcceptedOutboundEnvelope>,
    ) : OutboundEnvelopeResult

    data class PolicyRejected(
        val reason: OutboundPolicyRejection,
    ) : OutboundEnvelopeResult {
        override val acceptedPrefix: List<AcceptedOutboundEnvelope> = emptyList()
    }

    /** No currently eligible trusted recipient exists for the requested scope. */
    data object NoAudience : OutboundEnvelopeResult {
        override val acceptedPrefix: List<AcceptedOutboundEnvelope> = emptyList()
    }

    data class Unsealable(
        val reason: OutboundUnsealableReason,
        val failedMessageId: String?,
        val intendedRecipientIds: List<ClientId>,
        val sealedRecipientIds: List<ClientId>,
        override val acceptedPrefix: List<AcceptedOutboundEnvelope>,
    ) : OutboundEnvelopeResult

    data class TransportRejected(
        val failedMessageId: String,
        val reason: TransportRejectionReason,
        val transportReport: OutboundTransportReport?,
        override val acceptedPrefix: List<AcceptedOutboundEnvelope>,
    ) : OutboundEnvelopeResult
}

enum class OutboundPolicyRejection {
    BLANK_MESSAGE_ID,
    DUPLICATE_MESSAGE_ID,
    HIGH_DATA_SYNC_POLICY,
}

enum class OutboundUnsealableReason {
    MISSING_RECIPIENT_KEYS,
    INCONSISTENT_AUDIENCE,
    SIGNER_UNAVAILABLE,
    CRYPTO_FAILURE,
    PARTIAL_RECIPIENT_SEAL,
}

enum class TransportRejectionReason {
    REJECTED,
    FAILURE,
}

/**
 * Pure authenticated-envelope boundary for the durable message lifecycle.
 *
 * Inbound work ends after one sender lookup, signature verification, and HPKE/AEAD open. It owns no deduplication,
 * feature dispatch, persistence, Activity callback, or ACK interpretation. Outbound work accepts stable IDs and
 * already-encoded bodies, resolves one frozen audience and signer per batch, and returns typed transport results.
 * It never creates durable message IDs or invokes a database checkpoint callback.
 *
 * At application cutover this replaces the inbound preparation/dispatch lifecycle in [SecureChannel]; it is not a
 * second live consumer. The application coordinator becomes the sole owner of relay staging, durable disposition,
 * routing, Activity, and ACK finalization, while this component remains the only envelope crypto/transport seam.
 */
class SecureEnvelopeTransport(
    private val identitySigner: IdentitySigner,
    private val operationalSigner: () -> OperationalSigner,
    private val myHpkePrivate: (epoch: Int) -> ByteArray?,
    private val transport: Transport,
    private val directory: PeerDirectory,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val sequence = AtomicLong(now())

    /** Authenticate and decrypt exactly one defensive envelope snapshot. */
    suspend fun authenticateAndDecrypt(envelope: Envelope): InboundEnvelopeResult {
        currentCoroutineContext().ensureActive()
        val snapshot = envelope.defensiveCopy()
        val sender = directory.resolveSender(snapshot.signerId, snapshot.signerEpoch)
            ?: return InboundEnvelopeResult.UnresolvedSender(snapshot.signerId, snapshot.signerEpoch)
        currentCoroutineContext().ensureActive()

        val verifySpki = sender.verifySpki.copyOf()
        val verified = try {
            EnvelopeCrypto.verify(snapshot, verifySpki)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            verifySpki.fill(0)
        }
        currentCoroutineContext().ensureActive()
        if (!verified) {
            return InboundEnvelopeResult.BadSignature(snapshot.signerId, snapshot.signerEpoch)
        }

        val localRecipients = snapshot.recipients.filter { it.recipientId == identitySigner.clientId }
        if (localRecipients.isEmpty()) {
            return InboundEnvelopeResult.RecipientUnavailable(
                RecipientUnavailableReason.NOT_ADDRESSED_TO_THIS_DEVICE,
            )
        }
        if (localRecipients.size != 1) {
            return InboundEnvelopeResult.RecipientUnavailable(
                RecipientUnavailableReason.AMBIGUOUS_RECIPIENT,
            )
        }
        val recipientEpoch = localRecipients.single().recipientEpoch
        val privateKey = myHpkePrivate(recipientEpoch)?.copyOf()
            ?: return InboundEnvelopeResult.RecipientUnavailable(
                RecipientUnavailableReason.MISSING_PRIVATE_KEY,
                recipientEpoch,
            )
        currentCoroutineContext().ensureActive()

        val opened = try {
            EnvelopeCrypto.open(snapshot, identitySigner.clientId, privateKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return InboundEnvelopeResult.DecryptFailed(recipientEpoch)
        } finally {
            privateKey.fill(0)
        }
        currentCoroutineContext().ensureActive()

        return try {
            InboundEnvelopeResult.Ready(
                AuthenticatedEnvelopeMessage(
                    senderId = snapshot.signerId,
                    senderOwnDevice = sender.ownDevice,
                    type = snapshot.typ,
                    signerEpoch = snapshot.signerEpoch,
                    messageId = snapshot.messageId,
                    sequence = snapshot.seq,
                    createdAt = snapshot.createdAt,
                    recipientEpoch = recipientEpoch,
                    encodedBody = opened,
                ),
            )
        } finally {
            opened.fill(0)
        }
    }

    suspend fun send(
        type: MessageType,
        message: EncodedOutboundEnvelope,
        scope: Recipients,
        urgency: Urgency,
        signWith: SignerSelection = SignerSelection.OPERATIONAL,
    ): OutboundEnvelopeResult = sendBatch(type, listOf(message), scope, urgency, signWith)

    /**
     * Strict ordered batch send. Policy, audience, unsealable peers, and signer are each resolved once. The first
     * failure stops the suffix; any accepted prefix is returned so the durable coordinator can checkpoint it.
     */
    suspend fun sendBatch(
        type: MessageType,
        messages: List<EncodedOutboundEnvelope>,
        scope: Recipients,
        urgency: Urgency,
        signWith: SignerSelection = SignerSelection.OPERATIONAL,
    ): OutboundEnvelopeResult {
        currentCoroutineContext().ensureActive()
        val batch = messages.toList()
        val scopeSnapshot = scope.defensiveCopy()

        validateBatch(type, batch, scopeSnapshot, urgency)?.let { return it }
        if (batch.isEmpty()) return OutboundEnvelopeResult.Accepted(emptyList())
        currentCoroutineContext().ensureActive()

        val audience = directory.resolveAudience(scopeSnapshot)
        val recipients = audience.recipients.map(RecipientKey::defensiveCopy)
        val unsealable = audience.unsealableRecipientIds
        currentCoroutineContext().ensureActive()

        val recipientIds = recipients.map(RecipientKey::clientId)
        if (recipientIds.toSet().size != recipientIds.size) {
            return OutboundEnvelopeResult.Unsealable(
                reason = OutboundUnsealableReason.INCONSISTENT_AUDIENCE,
                failedMessageId = null,
                intendedRecipientIds = (recipientIds + unsealable).distinct(),
                sealedRecipientIds = emptyList(),
                acceptedPrefix = emptyList(),
            )
        }
        if (unsealable.isNotEmpty()) {
            return OutboundEnvelopeResult.Unsealable(
                reason = OutboundUnsealableReason.MISSING_RECIPIENT_KEYS,
                failedMessageId = null,
                intendedRecipientIds = (recipientIds + unsealable).distinct(),
                sealedRecipientIds = recipientIds,
                acceptedPrefix = emptyList(),
            )
        }
        if (recipients.isEmpty()) return OutboundEnvelopeResult.NoAudience

        val resolvedOperationalSigner = if (signWith == SignerSelection.OPERATIONAL) {
            try {
                operationalSigner()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return OutboundEnvelopeResult.Unsealable(
                    reason = OutboundUnsealableReason.SIGNER_UNAVAILABLE,
                    failedMessageId = null,
                    intendedRecipientIds = recipientIds,
                    sealedRecipientIds = emptyList(),
                    acceptedPrefix = emptyList(),
                )
            }
        } else {
            null
        }
        if (
            resolvedOperationalSigner != null &&
            (resolvedOperationalSigner.clientId != identitySigner.clientId || resolvedOperationalSigner.signerEpoch < 1)
        ) {
            return OutboundEnvelopeResult.Unsealable(
                reason = OutboundUnsealableReason.SIGNER_UNAVAILABLE,
                failedMessageId = null,
                intendedRecipientIds = recipientIds,
                sealedRecipientIds = emptyList(),
                acceptedPrefix = emptyList(),
            )
        }

        val accepted = mutableListOf<AcceptedOutboundEnvelope>()
        for (message in batch) {
            currentCoroutineContext().ensureActive()
            val body = message.bodySnapshot()
            val envelope = try {
                if (resolvedOperationalSigner != null) {
                    EnvelopeCrypto.seal(
                        resolvedOperationalSigner,
                        type,
                        body,
                        recipients,
                        message.messageId,
                        sequence.incrementAndGet(),
                        now(),
                    )
                } else {
                    EnvelopeCrypto.seal(
                        identitySigner,
                        type,
                        body,
                        recipients,
                        message.messageId,
                        sequence.incrementAndGet(),
                        now(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return OutboundEnvelopeResult.Unsealable(
                    reason = OutboundUnsealableReason.CRYPTO_FAILURE,
                    failedMessageId = message.messageId,
                    intendedRecipientIds = recipientIds,
                    sealedRecipientIds = emptyList(),
                    acceptedPrefix = accepted.toList(),
                )
            } finally {
                body.fill(0)
            }
            currentCoroutineContext().ensureActive()

            val sealedRecipientIds = envelope.recipients.map(PerRecipientKey::recipientId)
            val sealedRecipientEpochs = envelope.recipients.map(PerRecipientKey::recipientEpoch)
            val expectedRecipientEpochs = recipients.map(RecipientKey::recipientEpoch)
            if (sealedRecipientIds != recipientIds || sealedRecipientEpochs != expectedRecipientEpochs) {
                return OutboundEnvelopeResult.Unsealable(
                    reason = OutboundUnsealableReason.PARTIAL_RECIPIENT_SEAL,
                    failedMessageId = message.messageId,
                    intendedRecipientIds = recipientIds,
                    sealedRecipientIds = sealedRecipientIds,
                    acceptedPrefix = accepted.toList(),
                )
            }

            val transportResult = try {
                transport.send(envelope, urgency)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return OutboundEnvelopeResult.TransportRejected(
                    failedMessageId = message.messageId,
                    reason = TransportRejectionReason.FAILURE,
                    transportReport = null,
                    acceptedPrefix = accepted.toList(),
                )
            }
            currentCoroutineContext().ensureActive()
            val report = OutboundTransportReport(transportResult)
            if (!transportResult.accepted) {
                return OutboundEnvelopeResult.TransportRejected(
                    failedMessageId = message.messageId,
                    reason = TransportRejectionReason.REJECTED,
                    transportReport = report,
                    acceptedPrefix = accepted.toList(),
                )
            }
            accepted += AcceptedOutboundEnvelope(message.messageId, recipientIds.toList(), report)
        }
        return OutboundEnvelopeResult.Accepted(accepted.toList())
    }

    private fun validateBatch(
        type: MessageType,
        messages: List<EncodedOutboundEnvelope>,
        scope: Recipients,
        urgency: Urgency,
    ): OutboundEnvelopeResult.PolicyRejected? {
        if (messages.any { it.messageId.isBlank() }) {
            return OutboundEnvelopeResult.PolicyRejected(OutboundPolicyRejection.BLANK_MESSAGE_ID)
        }
        if (messages.map(EncodedOutboundEnvelope::messageId).toSet().size != messages.size) {
            return OutboundEnvelopeResult.PolicyRejected(OutboundPolicyRejection.DUPLICATE_MESSAGE_ID)
        }
        if (type != MessageType.DATA_SYNC || urgency != Urgency.HIGH) return null
        return try {
            if (messages.isEmpty()) {
                HighDataSyncPolicy.validateEmpty(scope)
            } else {
                messages.forEach { message ->
                    val body = message.bodySnapshot()
                    try {
                        HighDataSyncPolicy.validate(body, scope, identitySigner.clientId)
                    } finally {
                        body.fill(0)
                    }
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            OutboundEnvelopeResult.PolicyRejected(OutboundPolicyRejection.HIGH_DATA_SYNC_POLICY)
        }
    }
}

private fun Envelope.defensiveCopy(): Envelope = copy(
    bodyCiphertext = bodyCiphertext.copyOf(),
    recipients = recipients.map { recipient ->
        recipient.copy(sealedDek = recipient.sealedDek.copyOf())
    },
    sig = sig.copyOf(),
)

private fun RecipientKey.defensiveCopy(): RecipientKey = RecipientKey(
    clientId = clientId,
    hpkePublicKey = hpkePublicKey.copyOf(),
    recipientEpoch = recipientEpoch,
)

private fun Recipients.defensiveCopy(): Recipients = when (this) {
    Recipients.OwnMesh -> Recipients.OwnMesh
    Recipients.AllTrusted -> Recipients.AllTrusted
    is Recipients.OwnMeshFiltered -> copy(
        excluded = excluded.toSet(),
        excludedPlatforms = excludedPlatforms.toSet(),
        legacyExcludedPlatforms = legacyExcludedPlatforms.toSet(),
        requiredCapabilities = requiredCapabilities.toSet(),
        forbiddenCapabilities = forbiddenCapabilities.toSet(),
    )
    is Recipients.Only -> copy()
    is Recipients.OnlyCapable -> copy(requiredCapabilities = requiredCapabilities.toSet())
    is Recipients.OnlyCapableSet -> copy(
        ids = ids.toSet(),
        requiredCapabilities = requiredCapabilities.toSet(),
    )
}
