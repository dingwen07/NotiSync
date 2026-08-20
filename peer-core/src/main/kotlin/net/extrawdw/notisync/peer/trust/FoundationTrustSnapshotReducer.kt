package net.extrawdw.notisync.peer.trust

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.ports.IncomingTrustChange
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning

/** Physical signature grammar of one exact identity-signed trust snapshot. */
enum class SignedTrustSnapshotFormat {
    THREE_SECTION,
    FOUR_SECTION,
}

/**
 * Defensive carrier for the exact signed trust bytes hydrated by the platform repository.
 *
 * The three-section form represents physical absence of the epoch section, not an empty epoch object. A changed
 * three-section snapshot is upgraded exactly as the shipped store is: the first subsequent write emits the current
 * four-section grammar. An unchanged command returns the original bytes without normalization or re-signing.
 */
class SignedTrustSnapshot(
    val format: SignedTrustSnapshotFormat,
    entriesUtf8: ByteArray,
    cardsUtf8: ByteArray,
    overlaysUtf8: ByteArray,
    epochsUtf8: ByteArray?,
    signatureBase64UrlUtf8: ByteArray,
) {
    private val storedEntries = entriesUtf8.copyOf()
    private val storedCards = cardsUtf8.copyOf()
    private val storedOverlays = overlaysUtf8.copyOf()
    private val storedEpochs = epochsUtf8?.copyOf()
    private val storedSignature = signatureBase64UrlUtf8.copyOf()

    init {
        require((format == SignedTrustSnapshotFormat.FOUR_SECTION) == (storedEpochs != null)) {
            "trust signature grammar and epoch-section presence disagree"
        }
        require(storedEntries.isNotEmpty() && storedEntries.size <= MAX_TRUST_SECTION_UTF8_BYTES)
        require(storedCards.isNotEmpty() && storedCards.size <= MAX_TRUST_SECTION_UTF8_BYTES)
        require(storedOverlays.isNotEmpty() && storedOverlays.size <= MAX_TRUST_SECTION_UTF8_BYTES)
        require(storedEpochs == null || storedEpochs.isNotEmpty() && storedEpochs.size <= MAX_TRUST_SECTION_UTF8_BYTES)
        require(storedSignature.isNotEmpty() && storedSignature.size <= MAX_TRUST_SIGNATURE_UTF8_BYTES)
    }

    fun entriesUtf8Copy(): ByteArray = storedEntries.copyOf()
    fun cardsUtf8Copy(): ByteArray = storedCards.copyOf()
    fun overlaysUtf8Copy(): ByteArray = storedOverlays.copyOf()
    fun epochsUtf8CopyOrNull(): ByteArray? = storedEpochs?.copyOf()
    fun signatureBase64UrlUtf8Copy(): ByteArray = storedSignature.copyOf()

    internal fun defensiveCopy(): SignedTrustSnapshot = SignedTrustSnapshot(
        format = format,
        entriesUtf8 = storedEntries,
        cardsUtf8 = storedCards,
        overlaysUtf8 = storedOverlays,
        epochsUtf8 = storedEpochs,
        signatureBase64UrlUtf8 = storedSignature,
    )

    override fun toString(): String =
        "SignedTrustSnapshot(format=$format, entries=<${storedEntries.size} bytes>, " +
            "cards=<${storedCards.size} bytes>, overlays=<${storedOverlays.size} bytes>, " +
            "epochs=<${storedEpochs?.size ?: 0} bytes>, signature=<${storedSignature.size} bytes>)"
}

/** Authentication-derived facts needed by the pure Foundation reducer. */
data class FoundationTrustCommandContext(
    val senderId: ClientId,
    val senderOwnDevice: Boolean,
    val signerEpoch: Int,
    /** Authenticated envelope chronology, used as the stable decision/future-skew time across redelivery. */
    val signedCreatedAt: Long,
) {
    init {
        require(signerEpoch >= 0) { "signer epoch must not be negative" }
        require(signedCreatedAt > 0) { "signed creation time must be positive" }
    }
}

enum class FoundationTrustSecurityReason {
    INVALID_SIGNED_SNAPSHOT,
    UNAUTHORIZED_SENDER,
    SIGNER_POLICY_MISMATCH,
    PROFILE_SUBJECT_MISMATCH,
}

/** Privacy-safe semantic summary; signed payload values never cross into Activity through this type. */
sealed interface FoundationTrustEffect {
    data object None : FoundationTrustEffect

    data class ProfileChanged(
        val peerId: ClientId,
        val revision: Long,
    ) : FoundationTrustEffect

    data class TrustChanged(
        val senderId: ClientId,
        val promptCount: Int,
        val highestRevision: Long?,
        val hasConflict: Boolean,
    ) : FoundationTrustEffect {
        init {
            require(promptCount > 0) { "a trust effect requires at least one prompt" }
            require(highestRevision == null || highestRevision >= 0) {
                "trust effect revision must not be negative"
            }
        }
    }
}

sealed interface FoundationTrustReductionResult {
    class Ready(
        snapshot: SignedTrustSnapshot,
        val changed: Boolean,
        val effect: FoundationTrustEffect,
    ) : FoundationTrustReductionResult {
        private val storedSnapshot = snapshot.defensiveCopy()

        fun snapshotCopy(): SignedTrustSnapshot = storedSnapshot.defensiveCopy()
    }

    data class SecurityBlocked(
        val reason: FoundationTrustSecurityReason,
    ) : FoundationTrustReductionResult
}

/**
 * Pure reducer for PROFILE/TRUST/CARD over one exact signed snapshot.
 *
 * It performs no persistence, Room access, network work, feature registration, or legacy fallback. Decode, crypto
 * verification, state reduction, canonical section encoding, and identity signing all finish before a platform
 * repository starts its compare-and-replace transaction.
 */
class FoundationTrustSnapshotReducer(
    private val incomingTrustPolicy: IncomingTrustPolicy,
) {
    fun reduce(
        current: SignedTrustSnapshot,
        identity: IdentitySigner,
        context: FoundationTrustCommandContext,
        command: FoundationTrustCommand,
    ): FoundationTrustReductionResult {
        val decoded = current.decodeVerified(identity)
            ?: return FoundationTrustReductionResult.SecurityBlocked(
                FoundationTrustSecurityReason.INVALID_SIGNED_SNAPSHOT,
            )
        val reduction = when (command) {
            is FoundationTrustCommand.Profile -> reduceProfile(decoded, context, command.updateCopy())
            is FoundationTrustCommand.Trust -> reduceTrust(decoded, context, command.tableCopy())
            is FoundationTrustCommand.Card -> reduceCard(decoded, context, command.deliveryCopy())
        }
        if (reduction.securityReason != null) {
            return FoundationTrustReductionResult.SecurityBlocked(reduction.securityReason)
        }
        if (!reduction.changed) {
            return FoundationTrustReductionResult.Ready(
                snapshot = current,
                changed = false,
                effect = FoundationTrustEffect.None,
            )
        }
        return FoundationTrustReductionResult.Ready(
            snapshot = reduction.state.encodeAndSign(identity),
            changed = true,
            effect = reduction.effect,
        )
    }

    private fun reduceProfile(
        state: TrustSnapshotState,
        context: FoundationTrustCommandContext,
        update: ProfileUpdate,
    ): StateReduction {
        if (context.signerEpoch <= 0) {
            return StateReduction.security(state, FoundationTrustSecurityReason.SIGNER_POLICY_MISMATCH)
        }
        if (update.clientId != context.senderId) {
            return StateReduction.security(state, FoundationTrustSecurityReason.PROFILE_SUBJECT_MISMATCH)
        }
        if (state.entries[update.clientId]?.status != TrustStatus.TRUSTED) return StateReduction.unchanged(state)
        val card = state.cards[update.clientId]?.decodeOrNull<ClientCard>()
            ?: return StateReduction.unchanged(state)
        val floor = state.overlays[update.clientId]?.updatedAt ?: card.createdAt
        if (update.updatedAt <= floor) return StateReduction.unchanged(state)
        val next = state.copy(
            overlays = state.overlays + (
                update.clientId to ProfileOverlay(
                    displayName = update.displayName,
                    platform = update.platform,
                    capabilities = update.capabilities.toList(),
                    updatedAt = update.updatedAt,
                )
            ),
        )
        return StateReduction.changed(
            state = next,
            effect = FoundationTrustEffect.ProfileChanged(update.clientId, update.updatedAt),
        )
    }

    private fun reduceTrust(
        state: TrustSnapshotState,
        context: FoundationTrustCommandContext,
        table: TrustTable,
    ): StateReduction {
        if (!context.senderOwnDevice) {
            return StateReduction.security(state, FoundationTrustSecurityReason.UNAUTHORIZED_SENDER)
        }
        if (context.signerEpoch != 0) {
            return StateReduction.security(state, FoundationTrustSecurityReason.SIGNER_POLICY_MISMATCH)
        }
        var entries = state.entries
        var changed = false
        var promptCount = 0
        var highestPromptRevision: Long? = null
        var hasConflict = false
        for (wire in table.entries) {
            if (wire.clientId == context.senderId || wire.clientId == state.selfId) continue
            if (wire.status != TrustStatus.TRUSTED && wire.status != TrustStatus.REVOKED) continue
            val resolved = TrustMachine.resolveIncoming(entries[wire.clientId], wire, context.senderId)
            val prompt = resolved.prompt
            val finalEntry = if (
                prompt != null && incomingTrustPolicy.shouldAutoApply(
                    IncomingTrustChange(
                        senderId = context.senderId,
                        subjectId = wire.clientId,
                        prompt = prompt,
                        senderIsTrustedOwnDevice = context.senderOwnDevice,
                    ),
                )
            ) {
                resolvePrompt(
                    current = resolved.entry,
                    prompt = prompt,
                    now = maxOf(context.signedCreatedAt, resolved.entry.updatedAt),
                ) ?: resolved.entry
            } else {
                resolved.entry
            }
            if (finalEntry != entries[wire.clientId]) {
                entries = entries + (wire.clientId to finalEntry)
                changed = true
            }
            if (prompt != null) {
                promptCount++
                hasConflict = hasConflict || prompt == TrustPrompt.CONFLICT
                if (finalEntry.updatedAt >= 0) {
                    highestPromptRevision = maxOf(highestPromptRevision ?: 0, finalEntry.updatedAt)
                }
            }
        }
        if (!changed) return StateReduction.unchanged(state)
        val effect = if (promptCount == 0) {
            FoundationTrustEffect.None
        } else {
            FoundationTrustEffect.TrustChanged(
                senderId = context.senderId,
                promptCount = promptCount,
                highestRevision = highestPromptRevision,
                hasConflict = hasConflict,
            )
        }
        return StateReduction.changed(state.copy(entries = entries), effect)
    }

    private fun reduceCard(
        state: TrustSnapshotState,
        context: FoundationTrustCommandContext,
        delivery: CardDelivery,
    ): StateReduction {
        if (!context.senderOwnDevice) {
            return StateReduction.security(state, FoundationTrustSecurityReason.UNAUTHORIZED_SENDER)
        }
        var next = state
        var changed = false
        delivery.card?.let { card ->
            val result = next.applyCard(delivery.clientId, card, context.signedCreatedAt)
            next = result.state
            changed = changed || result.changed
        }
        delivery.epochBlob?.let { epoch ->
            val result = next.applyKeyEpoch(delivery.clientId, epoch)
            next = result.state
            changed = changed || result.changed
        }
        return if (changed) {
            StateReduction.changed(next, FoundationTrustEffect.None)
        } else {
            StateReduction.unchanged(state)
        }
    }
}

private data class TrustSnapshotState(
    val selfId: ClientId,
    val entries: Map<ClientId, TrustEntry>,
    val cards: Map<ClientId, SignedBlob>,
    val overlays: Map<ClientId, ProfileOverlay>,
    val epochs: EpochSection,
)

private data class StateChange(
    val state: TrustSnapshotState,
    val changed: Boolean,
)

private data class StateReduction(
    val state: TrustSnapshotState,
    val changed: Boolean,
    val effect: FoundationTrustEffect,
    val securityReason: FoundationTrustSecurityReason?,
) {
    companion object {
        fun unchanged(state: TrustSnapshotState) = StateReduction(
            state,
            changed = false,
            FoundationTrustEffect.None,
            securityReason = null,
        )

        fun changed(state: TrustSnapshotState, effect: FoundationTrustEffect) = StateReduction(
            state,
            changed = true,
            effect,
            securityReason = null,
        )

        fun security(state: TrustSnapshotState, reason: FoundationTrustSecurityReason) = StateReduction(
            state,
            changed = false,
            FoundationTrustEffect.None,
            securityReason = reason,
        )
    }
}

private fun SignedTrustSnapshot.decodeVerified(identity: IdentitySigner): TrustSnapshotState? {
    val entriesJson = entriesUtf8Copy().decodeStrictUtf8OrNull() ?: return null
    val cardsJson = cardsUtf8Copy().decodeStrictUtf8OrNull() ?: return null
    val overlaysJson = overlaysUtf8Copy().decodeStrictUtf8OrNull() ?: return null
    val epochsJson = epochsUtf8CopyOrNull()?.decodeStrictUtf8OrNull()
    val signature = signatureBase64UrlUtf8Copy().decodeStrictUtf8OrNull() ?: return null
    val signatureValid = when (format) {
        SignedTrustSnapshotFormat.THREE_SECTION -> TrustStoreSigning.verifyLegacyThreeSection(
            publicKeySpki = identity.publicKeySpki.copyOf(),
            selfId = identity.clientId,
            entriesJson = entriesJson,
            cardsJson = cardsJson,
            overlaysJson = overlaysJson,
            signatureB64 = signature,
        )

        SignedTrustSnapshotFormat.FOUR_SECTION -> TrustStoreSigning.verify(
            publicKeySpki = identity.publicKeySpki.copyOf(),
            selfId = identity.clientId,
            entriesJson = entriesJson,
            cardsJson = cardsJson,
            overlaysJson = overlaysJson,
            epochsJson = epochsJson ?: return null,
            signatureB64 = signature,
        )
    }
    if (!signatureValid) return null
    return try {
        val entries = ProtocolCodec.decodeFromJson<List<TrustEntry>>(entriesJson)
            .associateByTo(linkedMapOf()) { it.clientId }
        val encodedCards = ProtocolCodec.decodeFromJson<Map<String, String>>(cardsJson)
        val cards = linkedMapOf<ClientId, SignedBlob>()
        encodedCards.forEach { (clientId, encoded) ->
            val blob = ProtocolCodec.decodeFromCbor<SignedBlob>(Base64.getDecoder().decode(encoded))
            cards[ClientId(clientId)] = blob.defensiveCopy()
        }
        val encodedOverlays = ProtocolCodec.decodeFromJson<Map<String, ProfileOverlay>>(overlaysJson)
        val overlays = linkedMapOf<ClientId, ProfileOverlay>()
        encodedOverlays.forEach { (clientId, overlay) ->
            overlays[ClientId(clientId)] = overlay.copy(capabilities = overlay.capabilities.toList())
        }
        val epochs = if (format == SignedTrustSnapshotFormat.FOUR_SECTION) {
            ProtocolCodec.decodeFromJson<EpochSection>(requireNotNull(epochsJson))
        } else {
            EpochSection()
        }
        TrustSnapshotState(identity.clientId, entries, cards, overlays, epochs.defensiveCopy())
    } catch (_: Exception) {
        null
    }
}

private fun TrustSnapshotState.encodeAndSign(identity: IdentitySigner): SignedTrustSnapshot {
    val cleanedOverlays = overlays.filterKeys(entries::containsKey)
    val entriesJson = ProtocolCodec.encodeToJson(entries.values.toList())
    val cardsJson = ProtocolCodec.encodeToJson(
        cards.mapKeys { it.key.value }.mapValues { (_, blob) ->
            Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(blob))
        },
    )
    val overlaysJson = ProtocolCodec.encodeToJson(cleanedOverlays.mapKeys { it.key.value })
    val epochsJson = ProtocolCodec.encodeToJson(epochs)
    val signature = TrustStoreSigning.sign(identity, entriesJson, cardsJson, overlaysJson, epochsJson)
    return SignedTrustSnapshot(
        format = SignedTrustSnapshotFormat.FOUR_SECTION,
        entriesUtf8 = entriesJson.encodeToByteArray(),
        cardsUtf8 = cardsJson.encodeToByteArray(),
        overlaysUtf8 = overlaysJson.encodeToByteArray(),
        epochsUtf8 = epochsJson.encodeToByteArray(),
        signatureBase64UrlUtf8 = signature.encodeToByteArray(),
    )
}

private fun TrustSnapshotState.applyCard(
    clientId: ClientId,
    cardBlob: SignedBlob,
    now: Long,
): StateChange {
    val card = verifyCard(cardBlob) ?: return StateChange(this, false)
    if (card.clientId != clientId || !card.createdAt.isWithinCardFutureSkew(now)) return StateChange(this, false)
    val pinnedIdentity = pinnedIdentityOf(clientId)
    if (pinnedIdentity != null && !pinnedIdentity.contentEquals(card.identityPublicKey)) {
        return StateChange(this, false)
    }
    val existing = cards[clientId]?.let(::verifyCard)
    if (existing != null && card.createdAt <= existing.createdAt) return StateChange(this, false)
    val overlay = overlays[clientId]
    return StateChange(
        copy(
            cards = cards + (clientId to cardBlob.defensiveCopy()),
            overlays = if (overlay != null && overlay.updatedAt < card.createdAt) {
                overlays - clientId
            } else {
                overlays
            },
        ),
        true,
    )
}

private fun TrustSnapshotState.applyKeyEpoch(clientId: ClientId, keyEpochBlob: SignedBlob): StateChange {
    val existing = epochs.peers[clientId.value]
    val floor = existing?.floor ?: 0
    val keyEpoch = KeyEpochs.verify(
        keyEpochBlob.defensiveCopy(),
        pinnedIdentitySpki = pinnedIdentityOf(clientId),
    ) ?: return StateChange(this, false)
    if (keyEpoch.clientId != clientId || keyEpoch.epoch < floor || keyEpoch.minEpoch < floor) {
        return StateChange(this, false)
    }
    val sameEpoch = existing?.let(::decodeRing)?.firstOrNull { it.first.epoch == keyEpoch.epoch }
    if (sameEpoch != null) {
        val held = sameEpoch.first
        if (!held.operationalSigningKey.contentEquals(keyEpoch.operationalSigningKey) ||
            !held.hpkePublicKey.contentEquals(keyEpoch.hpkePublicKey) ||
            held.purposes.toSet() != keyEpoch.purposes.toSet() ||
            held.identityPublicKey.isNotEmpty() && keyEpoch.identityPublicKey.isEmpty() ||
            held.semanticallyEquals(keyEpoch)
        ) {
            return StateChange(this, false)
        }
    }
    val nextFloor = maxOf(floor, keyEpoch.minEpoch)
    val nextEpochs = PeerEpochs(
        ringB64 = mergeRing(existing?.ringB64.orEmpty(), keyEpochBlob, keyEpoch.epoch, nextFloor),
        floor = nextFloor,
    )
    if (existing == nextEpochs) return StateChange(this, false)
    return StateChange(
        copy(epochs = epochs.copy(peers = epochs.peers + (clientId.value to nextEpochs))),
        true,
    )
}

private fun TrustSnapshotState.pinnedIdentityOf(clientId: ClientId): ByteArray? {
    cards[clientId]?.decodeOrNull<ClientCard>()?.identityPublicKey?.takeIf(ByteArray::isNotEmpty)?.let {
        return it.copyOf()
    }
    val peerEpochs = epochs.peers[clientId.value] ?: return null
    return decodeRing(peerEpochs)
        .sortedByDescending { it.first.epoch }
        .firstNotNullOfOrNull { it.first.identityPublicKey.takeIf(ByteArray::isNotEmpty)?.copyOf() }
}

private fun verifyCard(blob: SignedBlob): ClientCard? {
    return try {
        if (blob.typ != SignedType.CLIENT_CARD) return null
        val card = blob.decode<ClientCard>()
        if (card.clientId != blob.signerId) return null
        if (!IdentityVerifier.verifyBound(blob.signerId, card.identityPublicKey, blob.payload, blob.sig)) return null
        card
    } catch (_: Exception) {
        null
    }
}

private inline fun <reified T> SignedBlob.decodeOrNull(): T? = try {
    decode<T>()
} catch (_: Exception) {
    null
}

private fun Long.isWithinCardFutureSkew(now: Long): Boolean {
    val latestAccepted = if (now > Long.MAX_VALUE - MAX_CARD_FUTURE_SKEW_MS) {
        Long.MAX_VALUE
    } else {
        now + MAX_CARD_FUTURE_SKEW_MS
    }
    return this <= latestAccepted
}

private fun decodeRing(peerEpochs: PeerEpochs): List<Pair<ClientKeyEpoch, SignedBlob>> =
    peerEpochs.ringB64.mapNotNull { encoded ->
        val blob = try {
            ProtocolCodec.decodeFromCbor<SignedBlob>(Base64.getDecoder().decode(encoded))
        } catch (_: Exception) {
            return@mapNotNull null
        }
        blob.decodeOrNull<ClientKeyEpoch>()?.let { it to blob }
    }

private fun mergeRing(
    ring: List<String>,
    blob: SignedBlob,
    epoch: Int,
    floor: Int,
): List<String> {
    val byEpoch = sortedMapOf<Int, String>()
    ring.forEach { encoded ->
        val held = try {
            val heldBlob = ProtocolCodec.decodeFromCbor<SignedBlob>(Base64.getDecoder().decode(encoded))
            heldBlob.decode<ClientKeyEpoch>()
        } catch (_: Exception) {
            null
        }
        if (held != null && held.epoch >= floor) byEpoch[held.epoch] = encoded
    }
    byEpoch[epoch] = Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(blob.defensiveCopy()))
    return byEpoch.keys.sorted().takeLast(TRUST_EPOCH_RING_SIZE).map(byEpoch::getValue)
}

private fun ClientKeyEpoch.semanticallyEquals(other: ClientKeyEpoch): Boolean =
    suite == other.suite &&
        clientId == other.clientId &&
        identityPublicKey.contentEquals(other.identityPublicKey) &&
        epoch == other.epoch &&
        operationalSigningKey.contentEquals(other.operationalSigningKey) &&
        hpkePublicKey.contentEquals(other.hpkePublicKey) &&
        purposes.toSet() == other.purposes.toSet() &&
        notBefore == other.notBefore &&
        notAfter == other.notAfter &&
        minEpoch == other.minEpoch

private fun SignedBlob.defensiveCopy(): SignedBlob = copy(payload = payload.copyOf(), sig = sig.copyOf())

private fun EpochSection.defensiveCopy(): EpochSection = copy(
    peers = peers.mapValues { (_, peer) -> peer.copy(ringB64 = peer.ringB64.toList()) },
    pending = pending?.copy(),
)

private fun resolvePrompt(current: TrustEntry, prompt: TrustPrompt, now: Long): TrustEntry? = when (prompt) {
    TrustPrompt.NEW_TRUST,
    TrustPrompt.RE_TRUST,
    -> if (current.status == TrustStatus.PENDING_TRUST) TrustMachine.approveTrust(current, now) else null

    TrustPrompt.NEW_REVOKE ->
        if (current.status == TrustStatus.PENDING_REVOKE) TrustMachine.confirmRevoke(current, now) else null

    TrustPrompt.CONFLICT,
    TrustPrompt.OTHER_ADDED,
    TrustPrompt.OTHER_REMOVED,
    -> null
}

private fun ByteArray.decodeStrictUtf8OrNull(): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: Exception) {
    null
}

private const val MAX_TRUST_SECTION_UTF8_BYTES = 4 * 1024 * 1024
private const val MAX_TRUST_SIGNATURE_UTF8_BYTES = 4 * 1024
private const val TRUST_EPOCH_RING_SIZE = 3
private const val MAX_CARD_FUTURE_SKEW_MS = 5L * 60 * 1000
