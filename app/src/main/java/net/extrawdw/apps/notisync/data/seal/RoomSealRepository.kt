package net.extrawdw.apps.notisync.data.seal

import android.database.sqlite.SQLiteConstraintException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalFeatureCommitResult
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSingletons
import net.extrawdw.apps.notisync.data.storage.operational.PreparedOperationalReceipt
import net.extrawdw.apps.notisync.data.storage.operational.SealAcceptResult
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentProtectedEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentState
import net.extrawdw.apps.notisync.data.storage.operational.SealObjectKind
import net.extrawdw.apps.notisync.data.storage.operational.SealOutcomeTransition
import net.extrawdw.apps.notisync.data.storage.operational.SealPendingPayloadEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestOutcome
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestState
import net.extrawdw.apps.notisync.data.storage.operational.SealResponseCustodyEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealResponsePayloadFormat
import net.extrawdw.apps.notisync.data.storage.protection.OperationalProtectedPayloadProtector
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayloadBinding
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyAvailability
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalPayloadKeyEnsurer
import net.extrawdw.apps.notisync.seal.OpenPgpAcceptResult
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollment
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollmentRepository
import net.extrawdw.apps.notisync.seal.OpenPgpKeySelection
import net.extrawdw.apps.notisync.seal.OpenPgpRequestResult
import net.extrawdw.apps.notisync.seal.OpenPgpRequestState
import net.extrawdw.apps.notisync.seal.OpenPgpSignRepository
import net.extrawdw.apps.notisync.seal.PreparedOpenPgpResponse
import net.extrawdw.apps.notisync.seal.StoredOpenPgpRequest
import net.extrawdw.apps.notisync.seal.sealRequestFingerprint
import net.extrawdw.apps.notisync.seal.toDisplaySnapshot
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * Sole production Seal adapter. It maps the Room aggregate to the existing signing domain and owns
 * protected payload handling; no legacy SQLite/DataStore object crosses this boundary.
 */
internal class RoomSealRepository(
    private val database: OperationalDatabase,
    private val protector: OperationalProtectedPayloadProtector,
    private val payloadKeyEnsurer: OperationalPayloadKeyEnsurer,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : OpenPgpEnrollmentRepository, OpenPgpSignRepository {
    private val dao = database.sealDao()

    /** Reconstructable negative responses are intentionally process-local; a lost transient send is acceptable. */
    private val transientRejectReasons = ConcurrentHashMap<String, OpenPgpRejectReason>()

    override val enrollment: StateFlow<OpenPgpEnrollment> = dao.observeEnrollment()
        .mapLatest { header -> decodeEnrollment(header) }
        .stateIn(scope, SharingStarted.Eagerly, OpenPgpEnrollment())

    override val requests: StateFlow<List<StoredOpenPgpRequest>> = dao.observeHistory()
        .mapLatest { entities -> decodeHistory(entities) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun save(selection: OpenPgpKeySelection, enrolledAt: Long) {
        require(selection.primaryKeyId.matches(PRIMARY_KEY_ID)) { "OpenPGP primary key id is invalid" }
        require(enrolledAt > 0) { "OpenPGP enrollment time must be positive" }
        val material = OpenPgpEnrollment(
            enabled = true,
            providerId = selection.providerId,
            providerKeyReference = selection.providerKeyReference,
            primaryKeyId = selection.primaryKeyId,
            displayIdentity = selection.displayIdentity,
            enrolledAt = enrolledAt,
        )
        val plaintext = SealPayloadCodec.encodeEnrollment(material)
        try {
            val generation = requireReadyGeneration()
            val protected = protectAtGeneration(plaintext, ProtectedPayloadBinding.sealEnrollment(), generation)
            dao.replaceEnrollment(
                SealEnrollmentEntity(
                    state = SealEnrollmentState.ENROLLED,
                    recoveryReasonCode = null,
                    updatedAt = enrolledAt,
                ),
                protected.toEnrollmentEntity(),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun clear() {
        dao.replaceEnrollment(
            SealEnrollmentEntity(
                state = SealEnrollmentState.DISABLED,
                recoveryReasonCode = null,
                updatedAt = now().coerceAtLeast(1),
            ),
            null,
        )
    }

    override suspend fun accept(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        now: Long,
    ): OpenPgpAcceptResult = withPreparedRequest(request, senderClientId, now) { entity, pending ->
        try {
            dao.accept(entity, pending, activity = null, now = now).toDomainResult()
        } catch (_: SQLiteConstraintException) {
            val existing = dao.findRequest(request.requestId)
            when {
                existing == null -> throw IllegalStateException("Seal request insert conflicted")
                existing.requestFingerprint.contentEquals(entity.requestFingerprint) -> OpenPgpAcceptResult.DUPLICATE
                else -> OpenPgpAcceptResult.CONFLICT
            }
        }
    }

    /** Owner transaction used by authenticated broker delivery; no second generic receipt write follows it. */
    internal suspend fun acceptWithReceipt(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        now: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult = withPreparedRequest(request, senderClientId, now) { entity, pending ->
        dao.acceptWithReceipt(entity, pending, receipt, now)
    }

    private suspend fun <T> withPreparedRequest(
        request: OpenPgpSignSync,
        senderClientId: ClientId,
        now: Long,
        commit: suspend (SealRequestEntity, SealPendingPayloadEntity) -> T,
    ): T {
        require(request.action == OpenPgpSignAction.REQUEST) { "Seal request must use REQUEST action" }
        require(request.validationError(::sha256) == null) { "invalid OpenPGP signing request" }
        require(request.requesterClientId == senderClientId) { "Seal request sender mismatch" }
        val payload = requireNotNull(request.payload)
        val displaySnapshot = payload.toDisplaySnapshot()
            ?: throw IllegalArgumentException("Seal request payload is not a valid Git commit")
        require(now > 0) { "Seal request receipt time must be positive" }

        val generation = requireReadyGeneration()
        val pendingPlaintext = ProtocolCodec.encodeToCbor(request)
        val displayEncoded = SealPayloadCodec.encodeDisplayBounded(
            primaryKeyId = request.primaryKeyId,
            workingDirectory = request.workingDirectory,
            commit = displaySnapshot,
        )
        val display = requireNotNull(displayEncoded.snapshot)
        val displayPlaintext = displayEncoded.bytes
        try {
            val pending = protectAtGeneration(
                pendingPlaintext,
                ProtectedPayloadBinding.sealPending(request.requestId),
                generation,
            )
            val protectedDisplay = protectAtGeneration(
                displayPlaintext,
                ProtectedPayloadBinding.sealDisplay(request.requestId),
                generation,
            )
            val entity = SealRequestEntity(
                requestId = request.requestId,
                requesterClientId = request.requesterClientId.value,
                senderClientId = senderClientId.value,
                requestFingerprint = request.sealRequestFingerprint(senderClientId),
                issuedAt = request.issuedAt,
                expiresAt = request.expiresAt,
                payloadSha256 = request.payloadSha256.copyOf(),
                objectKind = SealObjectKind.GIT_COMMIT,
                displayProtectionScheme = protectedDisplay.scheme,
                displayProtectionVersion = protectedDisplay.protectionVersion,
                displayProtectionKeyRef = protectedDisplay.keyRef,
                displayProtectionGeneration = protectedDisplay.generation,
                displayPayloadCodecVersion = protectedDisplay.payloadCodecVersion,
                displayCiphertext = protectedDisplay.ciphertextCopy(),
                displayNonce = protectedDisplay.nonceCopy(),
                displayTruncated = display.truncated,
                state = SealRequestState.PENDING_REVIEW,
                outcome = null,
                decisionAt = null,
                createdAt = request.issuedAt,
                updatedAt = now,
            )
            return commit(entity, pending.toPendingEntity(request.requestId, now))
        } finally {
            pendingPlaintext.fill(0)
            displayPlaintext.fill(0)
        }
    }

    override suspend fun find(requestId: String): StoredOpenPgpRequest? {
        val generation = requireReadyGeneration()
        return dao.findRequest(requestId)?.let { decodeEntity(it, generation) }
    }

    override suspend fun approve(requestId: String, now: Long): Boolean =
        dao.markUserApproved(requestId, now)

    override suspend fun markProviderInteraction(requestId: String, now: Long): Boolean =
        dao.markProviderInteraction(requestId, now)

    override suspend fun storeResult(requestId: String, signatureArmor: String, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (
            stored.state !in setOf(OpenPgpRequestState.USER_APPROVED, OpenPgpRequestState.PROVIDER_INTERACTION) ||
            now > stored.request.expiresAt
        ) return false
        val response = stored.request.copy(
            action = OpenPgpSignAction.RESULT,
            payload = null,
            signatureArmor = signatureArmor,
            rejectReason = null,
            actionAt = now,
            workingDirectory = null,
        )
        require(response.validationError(::sha256) == null) { "invalid OpenPGP signing response" }
        val encoded = ProtocolCodec.encodeToCbor(response)
        try {
            val generation = requireReadyGeneration()
            val protected = protectAtGeneration(
                encoded,
                ProtectedPayloadBinding.sealResponse(requestId),
                generation,
            )
            return dao.recordOutcomeAndQueueResponse(
                SealOutcomeTransition(
                    requestId = requestId,
                    outcome = SealRequestOutcome.APPROVED,
                    decidedAt = now,
                    responseCustody = protected.toResponseCustodyEntity(requestId, now),
                    activity = null,
                ),
            )
        } finally {
            encoded.fill(0)
        }
    }

    override suspend fun storeReject(
        requestId: String,
        reason: OpenPgpRejectReason,
        now: Long,
    ): Boolean {
        val stored = find(requestId) ?: return false
        if (
            stored.state !in setOf(
                OpenPgpRequestState.PENDING_REVIEW,
                OpenPgpRequestState.USER_APPROVED,
                OpenPgpRequestState.PROVIDER_INTERACTION,
            ) || now > stored.request.expiresAt
        ) return false
        val outcome = reason.toOutcome()
        val changed = dao.recordOutcomeAndQueueResponse(
            SealOutcomeTransition(requestId, outcome, now, responseCustody = null, activity = null),
        )
        if (changed) transientRejectReasons[requestId] = reason
        return changed
    }

    override suspend fun cancel(requestId: String, senderClientId: ClientId, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.senderClientId != senderClientId || stored.state !in ACTIVE_STATES) return false
        transientRejectReasons.remove(requestId)
        return dao.terminalWithoutResponse(
            requestId = requestId,
            outcome = SealRequestOutcome.CANCELLED,
            decidedAt = now,
            activity = null,
        )
    }

    override suspend fun markExpired(requestId: String, now: Long): Boolean {
        val stored = find(requestId) ?: return false
        if (stored.state == OpenPgpRequestState.REJECTED_PENDING_SEND) {
            // The negative response is reconstructable and intentionally process-local. Once its
            // retry window closes, retain only the terminal history row and discard the reason.
            transientRejectReasons.remove(requestId)
            return true
        }
        if (stored.state !in ACTIVE_STATES) return false
        transientRejectReasons.remove(requestId)
        return dao.terminalWithoutResponse(
            requestId = requestId,
            outcome = SealRequestOutcome.EXPIRED,
            decidedAt = now,
            activity = null,
        )
    }

    override suspend fun expireDue(now: Long): List<String> {
        val expired = mutableListOf<String>()
        dao.observeHistory().first()
            .filter { it.state in ACTIVE_STORAGE_STATES && now > it.expiresAt }
            .forEach { request ->
                if (markExpired(request.requestId, now)) expired += request.requestId
            }
        return expired
    }

    override suspend fun prepareResponse(requestId: String, now: Long): PreparedOpenPgpResponse? {
        val stored = find(requestId) ?: return null
        if (stored.state == OpenPgpRequestState.REJECTED_PENDING_SEND) {
            val reason = transientRejectReasons[requestId] ?: return null
            val response = stored.request.copy(
                action = OpenPgpSignAction.REJECT,
                payload = null,
                signatureArmor = null,
                rejectReason = reason,
                actionAt = now,
                workingDirectory = null,
            )
            require(response.validationError(::sha256) == null) { "invalid reconstructed Seal rejection" }
            return PreparedOpenPgpResponse(
                requestId = requestId,
                encodedBody = ProtocolCodec.encodeToCbor(response),
                durableCustody = false,
            )
        }
        if (stored.state != OpenPgpRequestState.SIGNED_PENDING_SEND) return null

        var current = dao.findResponseCustody(requestId) ?: return null
        if (current.payloadFormat == SealResponsePayloadFormat.BODY) {
            val prepared = current.copy(
                payloadFormat = SealResponsePayloadFormat.PREPARED_ENVELOPE,
                updatedAt = maxOf(now, current.updatedAt + 1),
            )
            when (dao.prepareResponse(current, prepared)) {
                net.extrawdw.apps.notisync.data.storage.operational.SealResponsePrepareResult.NOT_FOUND,
                net.extrawdw.apps.notisync.data.storage.operational.SealResponsePrepareResult.CONFLICT,
                net.extrawdw.apps.notisync.data.storage.operational.SealResponsePrepareResult.STALE,
                -> return null
                else -> Unit
            }
            current = dao.findResponseCustody(requestId) ?: return null
        }
        if (current.payloadFormat != SealResponsePayloadFormat.PREPARED_ENVELOPE) return null
        val generation = requireReadyGeneration()
        val encoded = openAtGeneration(current.toProtectedPayload(), ProtectedPayloadBinding.sealResponse(requestId), generation)
        val response = runCatching { ProtocolCodec.decodeFromCbor<OpenPgpSignSync>(encoded) }.getOrNull()
        if (response == null || !response.isCompatibleResponse(stored.request)) {
            encoded.fill(0)
            return null
        }
        return PreparedOpenPgpResponse(requestId, encoded, durableCustody = true)
    }

    override suspend fun completeResponse(prepared: PreparedOpenPgpResponse, sentAt: Long): Boolean {
        if (!prepared.durableCustody) {
            return try {
                transientRejectReasons.remove(prepared.requestId)
                true
            } finally {
                prepared.encodedBody.fill(0)
            }
        }
        return try {
            val custody = dao.findResponseCustody(prepared.requestId) ?: return false
            val result = dao.completeResponse(custody, sentAt)
            result == net.extrawdw.apps.notisync.data.storage.operational.SealResponseCompleteResult.SENT ||
                result == net.extrawdw.apps.notisync.data.storage.operational.SealResponseCompleteResult.ALREADY_SENT
        } finally {
            prepared.encodedBody.fill(0)
        }
    }

    private suspend fun decodeEnrollment(header: SealEnrollmentEntity?): OpenPgpEnrollment {
        if (header?.state != SealEnrollmentState.ENROLLED) return OpenPgpEnrollment()
        val protected = dao.readEnrollmentProtected() ?: return OpenPgpEnrollment()
        val generation = readyGenerationOrNull() ?: return OpenPgpEnrollment()
        return try {
            SealPayloadCodec.decodeEnrollment(
                openAtGeneration(
                    protected.toProtectedPayload(),
                    ProtectedPayloadBinding.sealEnrollment(),
                    generation,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            OpenPgpEnrollment()
        }
    }

    private suspend fun decodeHistory(entities: List<SealRequestEntity>): List<StoredOpenPgpRequest> {
        val generation = readyGenerationOrNull() ?: return emptyList()
        return entities.mapNotNull { entity ->
            try {
                decodeEntity(entity, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun decodeEntity(
        entity: SealRequestEntity,
        generation: Long,
    ): StoredOpenPgpRequest? {
        val display = SealPayloadCodec.decodeDisplay(
            openAtGeneration(
                entity.toProtectedDisplay(),
                ProtectedPayloadBinding.sealDisplay(entity.requestId),
                generation,
            ),
        )
        val pending = dao.findPendingPayload(entity.requestId)
        val request = if (pending != null) {
            val decoded = ProtocolCodec.decodeFromCbor<OpenPgpSignSync>(
                openAtGeneration(
                    pending.toProtectedPayload(),
                    ProtectedPayloadBinding.sealPending(entity.requestId),
                    generation,
                ),
            )
            if (decoded.action != OpenPgpSignAction.REQUEST || !decoded.matches(entity, display)) return null
            decoded
        } else {
            OpenPgpSignSync(
                action = OpenPgpSignAction.REQUEST,
                requestId = entity.requestId,
                requesterClientId = ClientId(entity.requesterClientId),
                issuedAt = entity.issuedAt,
                expiresAt = entity.expiresAt,
                primaryKeyId = display.primaryKeyId,
                payloadSha256 = entity.payloadSha256.copyOf(),
                objectKind = OpenPgpObjectKind.GIT_COMMIT,
                payload = null,
                workingDirectory = display.workingDirectory,
            )
        }
        return StoredOpenPgpRequest(
            request = request,
            senderClientId = ClientId(entity.senderClientId),
            state = entity.toDomainState(transientRejectReasons.containsKey(entity.requestId)),
            updatedAt = entity.updatedAt,
            commit = display.commit,
            result = entity.outcome?.toDomainResult(),
        )
    }

    private suspend fun requireReadyGeneration(): Long = when (val availability = payloadKeyEnsurer.ensureCurrent()) {
        is OperationalPayloadKeyAvailability.Ready -> availability.generation
        is OperationalPayloadKeyAvailability.Unavailable ->
            throw IllegalStateException("Seal protected storage is unavailable: ${availability.failure.code}")
    }

    private suspend fun readyGenerationOrNull(): Long? = try {
        requireReadyGeneration()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun protectAtGeneration(
        plaintext: ByteArray,
        binding: ProtectedPayloadBinding,
        generation: Long,
    ): ProtectedPayload = withContext(ioDispatcher) {
        protector.protect(plaintext, binding, generation)
    }

    private suspend fun openAtGeneration(
        payload: ProtectedPayload,
        binding: ProtectedPayloadBinding,
        generation: Long,
    ): ByteArray {
        require(payload.generation == generation) { "Seal protected payload belongs to an obsolete generation" }
        return withContext(ioDispatcher) { protector.open(payload, binding) }
    }

    private fun OpenPgpSignSync.matches(
        entity: SealRequestEntity,
        display: SealPayloadCodec.SealDisplayPayload,
    ): Boolean =
        requestId == entity.requestId && requesterClientId.value == entity.requesterClientId &&
            issuedAt == entity.issuedAt && expiresAt == entity.expiresAt &&
            payloadSha256.contentEquals(entity.payloadSha256) &&
            objectKind == OpenPgpObjectKind.GIT_COMMIT &&
            payload?.isNotEmpty() == true &&
            primaryKeyId == display.primaryKeyId && workingDirectory == display.workingDirectory

    private fun OpenPgpSignSync.isCompatibleResponse(request: OpenPgpSignSync): Boolean =
        requestId == request.requestId && requesterClientId == request.requesterClientId &&
            issuedAt == request.issuedAt && expiresAt == request.expiresAt &&
            primaryKeyId == request.primaryKeyId && objectKind == request.objectKind &&
            MessageDigest.isEqual(payloadSha256, request.payloadSha256) &&
            validationError(::sha256) == null

    private fun SealRequestEntity.toProtectedDisplay(): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = displayProtectionScheme,
        protectionVersion = displayProtectionVersion,
        generation = displayProtectionGeneration,
        keyRef = displayProtectionKeyRef,
        payloadCodecVersion = displayPayloadCodecVersion,
        nonce = displayNonce,
        ciphertext = displayCiphertext,
    )

    private fun SealEnrollmentProtectedEntity.toProtectedPayload(): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = protectionScheme,
        protectionVersion = protectionVersion,
        generation = protectionGeneration,
        keyRef = protectionKeyRef,
        payloadCodecVersion = payloadCodecVersion,
        nonce = payloadNonce,
        ciphertext = payloadCiphertext,
    )

    private fun SealPendingPayloadEntity.toProtectedPayload(): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = protectionScheme,
        protectionVersion = protectionVersion,
        generation = protectionGeneration,
        keyRef = protectionKeyRef,
        payloadCodecVersion = payloadCodecVersion,
        nonce = payloadNonce,
        ciphertext = payloadCiphertext,
    )

    private fun SealResponseCustodyEntity.toProtectedPayload(): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = protectionScheme,
        protectionVersion = protectionVersion,
        generation = protectionGeneration,
        keyRef = protectionKeyRef,
        payloadCodecVersion = payloadCodecVersion,
        nonce = payloadNonce,
        ciphertext = payloadCiphertext,
    )

    private fun ProtectedPayload.toEnrollmentEntity() = SealEnrollmentProtectedEntity(
        singletonId = OperationalSingletons.ID,
        protectionScheme = scheme,
        protectionVersion = protectionVersion,
        protectionKeyRef = keyRef,
        protectionGeneration = generation,
        payloadCodecVersion = payloadCodecVersion,
        payloadCiphertext = ciphertextCopy(),
        payloadNonce = nonceCopy(),
    )

    private fun ProtectedPayload.toPendingEntity(requestId: String, now: Long) = SealPendingPayloadEntity(
        requestId = requestId,
        protectionScheme = scheme,
        protectionVersion = protectionVersion,
        protectionKeyRef = keyRef,
        protectionGeneration = generation,
        payloadCodecVersion = payloadCodecVersion,
        payloadCiphertext = ciphertextCopy(),
        payloadNonce = nonceCopy(),
        createdAt = now,
        updatedAt = now,
    )

    private fun ProtectedPayload.toResponseCustodyEntity(requestId: String, now: Long) =
        SealResponseCustodyEntity(
            requestId = requestId,
            payloadFormat = SealResponsePayloadFormat.BODY,
            protectionScheme = scheme,
            protectionVersion = protectionVersion,
            protectionKeyRef = keyRef,
            protectionGeneration = generation,
            payloadCodecVersion = payloadCodecVersion,
            payloadCiphertext = ciphertextCopy(),
            payloadNonce = nonceCopy(),
            createdAt = now,
            updatedAt = now,
        )

    private fun SealRequestEntity.toDomainState(transientNegative: Boolean): OpenPgpRequestState = when (state) {
        SealRequestState.PENDING_REVIEW -> OpenPgpRequestState.PENDING_REVIEW
        SealRequestState.USER_APPROVED -> OpenPgpRequestState.USER_APPROVED
        SealRequestState.PROVIDER_INTERACTION -> OpenPgpRequestState.PROVIDER_INTERACTION
        SealRequestState.RESPONSE_QUEUED -> OpenPgpRequestState.SIGNED_PENDING_SEND
        SealRequestState.SENT -> OpenPgpRequestState.SENT
        SealRequestState.CANCELLED -> if (transientNegative) OpenPgpRequestState.REJECTED_PENDING_SEND else OpenPgpRequestState.CANCELLED
        SealRequestState.EXPIRED -> if (transientNegative) OpenPgpRequestState.REJECTED_PENDING_SEND else OpenPgpRequestState.EXPIRED
        SealRequestState.FAILED -> if (transientNegative) OpenPgpRequestState.REJECTED_PENDING_SEND else OpenPgpRequestState.FAILED
    }

    private fun SealRequestOutcome.toDomainResult(): OpenPgpRequestResult = when (this) {
        SealRequestOutcome.APPROVED -> OpenPgpRequestResult.APPROVED
        SealRequestOutcome.REJECTED -> OpenPgpRequestResult.REJECTED
        SealRequestOutcome.CANCELLED -> OpenPgpRequestResult.CANCELED
        SealRequestOutcome.EXPIRED -> OpenPgpRequestResult.EXPIRED
        SealRequestOutcome.FAILED -> OpenPgpRequestResult.FAILED
    }

    private fun OpenPgpRejectReason.toOutcome(): SealRequestOutcome = when (this) {
        OpenPgpRejectReason.USER_REJECTED -> SealRequestOutcome.REJECTED
        OpenPgpRejectReason.EXPIRED -> SealRequestOutcome.EXPIRED
        OpenPgpRejectReason.PROVIDER_CANCELLED -> SealRequestOutcome.CANCELLED
        OpenPgpRejectReason.PROVIDER_UNAVAILABLE,
        OpenPgpRejectReason.UNSUPPORTED_KEY,
        OpenPgpRejectReason.PROVIDER_FAILURE -> SealRequestOutcome.FAILED
    }

    private fun SealAcceptResult.toDomainResult(): OpenPgpAcceptResult = when (this) {
        SealAcceptResult.STORED -> OpenPgpAcceptResult.STORED
        SealAcceptResult.DUPLICATE -> OpenPgpAcceptResult.DUPLICATE
        SealAcceptResult.CONFLICT -> OpenPgpAcceptResult.CONFLICT
        SealAcceptResult.RATE_LIMITED -> OpenPgpAcceptResult.RATE_LIMITED
    }

    private companion object {
        val ACTIVE_STATES = setOf(
            OpenPgpRequestState.PENDING_REVIEW,
            OpenPgpRequestState.USER_APPROVED,
            OpenPgpRequestState.PROVIDER_INTERACTION,
        )
        val ACTIVE_STORAGE_STATES = setOf(
            SealRequestState.PENDING_REVIEW,
            SealRequestState.USER_APPROVED,
            SealRequestState.PROVIDER_INTERACTION,
        )
        val PRIMARY_KEY_ID = Regex("[0-9A-F]{16}")

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
