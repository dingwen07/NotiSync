package net.extrawdw.apps.notisync.data.storage.importer.target

import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import androidx.room3.withWriteTransaction
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEntity
import net.extrawdw.apps.notisync.data.storage.operational.MessageDedupEvidenceKind
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.MirrorLifecycleEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.RunCompareUpsertResult
import net.extrawdw.apps.notisync.data.storage.operational.RunPhaseToken
import net.extrawdw.apps.notisync.data.storage.operational.RunStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentProtectedEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealEnrollmentState
import net.extrawdw.apps.notisync.data.storage.operational.SealObjectKind
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestEntity
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestOutcome
import net.extrawdw.apps.notisync.data.storage.operational.SealRequestState
import net.extrawdw.apps.notisync.data.storage.protection.ProtectedPayload
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesRebuildPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.RoomOperationalPreferencesImportTarget
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalStorageMaintenanceGate

/**
 * The only Room-aware legacy cutover adapter. Legacy DTOs/readers cannot enter this package and the
 * clean Operational schema has no dependency back on the importer.
 */
internal class RoomOperationalImportTarget(
    private val database: OperationalDatabase,
    private val maintenanceGate: OperationalStorageMaintenanceGate,
    private val sealPayloadVerifier: SealImportPayloadVerifier? = null,
) : OperationalImportTarget {
    private val preferencesTarget = RoomOperationalPreferencesImportTarget(database)

    override suspend fun beginRebuild(identity: OperationalRebuildIdentity): Unit = storageAccess {
        maintenanceGate.withExclusiveAccess {
            database.withWriteTransaction {
                val current = database.profileDao().readMaintenance()
                if (current?.postCutoverWriteAt != null) {
                    targetConflict("target_post_cutover_write_detected")
                }
                if (current != null &&
                    (current.operationalGeneration != identity.operationalGeneration ||
                        current.storageIncarnationId != identity.storageIncarnationId)
                ) {
                    // The protected-payload alias is a pure function of generation. Replacing a
                    // pre-authority identity here could strand that alias after process death.
                    // The global migrator must reuse the marker it observes on a later attempt.
                    targetConflict("target_rebuild_identity_changed")
                }
                purgeOperationalRows()
                when (
                    database.profileDao().initializeMaintenance(
                        MaintenanceStateEntity(
                            operationalGeneration = identity.operationalGeneration,
                            storageIncarnationId = identity.storageIncarnationId,
                            postCutoverWriteAt = null,
                            lastIntegrityCheckAt = null,
                            updatedAt = identity.startedAt,
                        ),
                    )
                ) {
                    net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao
                        .MaintenanceInitializeResult.INITIALIZED,
                    net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao
                        .MaintenanceInitializeResult.ALREADY_INITIALIZED,
                    -> Unit
                    net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao
                        .MaintenanceInitializeResult.CONFLICT -> targetConflict("target_marker_initialization_conflict")
                }
                requireIdentity(identity)
            }
        }
    }

    private suspend fun purgeOperationalRows() {
        database.useWriterConnection { connection ->
            REBUILD_PURGE_TABLES.forEach { table ->
                connection.executeSQL("DELETE FROM `$table`")
            }
            connection.executeSQL("DELETE FROM `maintenance_state`")
        }
    }

    override suspend fun applyBatch(
        identity: OperationalRebuildIdentity,
        commands: List<OperationalImportCommand>,
    ): Unit = storageAccess {
        require(commands.isNotEmpty()) { "rebuild batch must not be empty" }
        val protectedGeneration = commands.requireOneProtectedGeneration()
        if (protectedGeneration != null && protectedGeneration != identity.operationalGeneration) {
            throw OperationalImportFailure(
                ImportFailureDisposition.RETRYABLE,
                "seal_generation_changed_before_commit",
            )
        }
        maintenanceGate.withExclusiveAccess {
            database.withWriteTransaction {
                requireIdentity(identity)
                commands.forEach { command ->
                    currentCoroutineContext().ensureActive()
                    applyCommand(command, identity.startedAt, protectedGeneration)
                }
            }
        }
    }

    override suspend fun applyPreferences(
        identity: OperationalRebuildIdentity,
        plan: OperationalPreferencesRebuildPlan,
        sealEnrollment: OperationalImportCommand.SealEnrollment,
    ): Unit = storageAccess {
        val generation = sealEnrollment.protectedEnrollment?.generation
        if (generation != null && generation != identity.operationalGeneration) {
            throw OperationalImportFailure(
                ImportFailureDisposition.RETRYABLE,
                "seal_generation_changed_before_commit",
            )
        }
        maintenanceGate.withExclusiveAccess {
            database.withWriteTransaction {
                requireIdentity(identity)
                preferencesTarget.applyAll(plan, identity.startedAt)
                applyCommand(sealEnrollment, identity.startedAt, generation)
                if (!verifyCommand(sealEnrollment)) targetConflict("seal_enrollment_persisted_mismatch")
            }
        }
    }

    override suspend fun verify(
        identity: OperationalRebuildIdentity,
        snapshot: OperationalImportSnapshot,
    ): ImportVerificationResult = storageAccess {
        maintenanceGate.withExclusiveAccess { requireIdentity(identity) }
        var ordinal = 0L
        while (ordinal < snapshot.commandCount) {
            currentCoroutineContext().ensureActive()
            val commands = snapshot.commands(ordinal, snapshot.source.batchSize)
            if (commands.isEmpty()) {
                return@storageAccess ImportVerificationResult.Failed("target_missing_batch")
            }
            for (command in commands) {
                currentCoroutineContext().ensureActive()
                // Protected payload open/self-test owns the maintenance gate and stays outside Room
                // transactions. A final identity recheck detects any reset during verification.
                if (!verifyCommand(command)) {
                    return@storageAccess ImportVerificationResult.Failed("target_projection_mismatch")
                }
            }
            ordinal += commands.size
        }
        maintenanceGate.withExclusiveAccess { requireIdentity(identity) }
        ImportVerificationResult.VERIFIED
    }

    override suspend fun verifyPreferences(
        identity: OperationalRebuildIdentity,
        plan: OperationalPreferencesRebuildPlan,
        sealEnrollment: OperationalImportCommand.SealEnrollment,
    ): ImportVerificationResult = storageAccess {
        maintenanceGate.withExclusiveAccess {
            database.withWriteTransaction {
                requireIdentity(identity)
                if (preferencesTarget.verifyAll(plan, identity.startedAt) && verifyCommand(sealEnrollment)) {
                    ImportVerificationResult.VERIFIED
                } else {
                    ImportVerificationResult.Failed("preferences_target_projection_mismatch")
                }
            }
        }
    }

    private suspend fun applyCommand(
        command: OperationalImportCommand,
        importTime: Long,
        expectedProtectedGeneration: Long?,
    ) {
        when (command) {
            is OperationalImportCommand.HandledMessageIdOnly -> {
                database.relayDao().insertImportedHandled(
                    MessageDedupEntity(
                        messageId = command.messageId,
                        authenticatedFingerprint = null,
                        evidenceKind = MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY,
                        handledAt = command.handledAt,
                    ),
                )
            }
            is OperationalImportCommand.MirrorLifecycle -> {
                val next = command.toEntity()
                database.mirrorLifecycleDao().findLifecycle(command.sourceClientId, command.sourceKey)
                    ?.let { current ->
                        if (current != next) targetConflict("mirror_lifecycle_conflict")
                        return
                    }
                command.postTime?.let { postTime ->
                    database.mirrorLifecycleDao().acceptPost(
                        command.sourceClientId,
                        command.sourceKey,
                        postTime,
                        command.updatedAt,
                    )
                }
                command.dismissedAt?.let { dismissedAt ->
                    if (!database.mirrorLifecycleDao().recordDismissal(
                            command.sourceClientId,
                            command.sourceKey,
                            dismissedAt,
                            command.updatedAt,
                        )
                    ) targetConflict("mirror_lifecycle_conflict")
                }
                if (database.mirrorLifecycleDao().findLifecycle(command.sourceClientId, command.sourceKey) != next) {
                    targetConflict("mirror_lifecycle_projection_mismatch")
                }
            }
            is OperationalImportCommand.RunState -> command.withBorrowedPayload { payload, digest ->
                val candidate = command.toEntity(payload, digest)
                val current = database.runDao().find(command.hostClientId, command.runId)
                if (current != null) {
                    if (!current.exactlyMatches(candidate)) targetConflict("run_target_conflict")
                    return@withBorrowedPayload
                }
                when (database.runDao().compareAndUpsert(candidate, activity = null)) {
                    RunCompareUpsertResult.INSERTED -> Unit
                    RunCompareUpsertResult.CAPACITY_EXCEEDED -> targetConflict("run_active_capacity_exceeded")
                    RunCompareUpsertResult.UPDATED,
                    RunCompareUpsertResult.EQUAL,
                    RunCompareUpsertResult.OLDER,
                    RunCompareUpsertResult.CONFLICT,
                    -> targetConflict("run_target_conflict")
                }
            }
            is OperationalImportCommand.SealTerminalHistory -> {
                require(command.protectedDisplay.generation == expectedProtectedGeneration) {
                    "Seal display generation escaped its maintenance gate"
                }
                val entity = command.toEntity()
                if (!database.sealDao().insertImportedTerminalHistory(entity)) {
                    // A pre-existing row inside this freshly purged attempt is an internal conflict.
                    targetConflict("seal_history_target_exists")
                }
                val stored = database.sealDao().findRequest(command.requestId)
                if (stored == null || !stored.exactlyMatches(entity, includeProtectedBytes = true)) {
                    targetConflict("seal_history_persisted_mismatch")
                }
            }
            is OperationalImportCommand.SealEnrollment -> {
                command.protectedEnrollment?.let { protected ->
                    require(protected.generation == expectedProtectedGeneration) {
                        "Seal enrollment generation escaped its maintenance gate"
                    }
                }
                val header = SealEnrollmentEntity(
                    state = command.state.toStorageState(),
                    recoveryReasonCode = command.recoveryReasonCode,
                    updatedAt = importTime,
                )
                val protected = command.protectedEnrollment?.toEnrollmentEntity()
                database.sealDao().replaceEnrollment(header, protected)
            }
        }
    }

    private fun List<OperationalImportCommand>.requireOneProtectedGeneration(): Long? {
        val generations = mapNotNull { command ->
            when (command) {
                is OperationalImportCommand.SealTerminalHistory -> command.protectedDisplay.generation
                is OperationalImportCommand.SealEnrollment -> command.protectedEnrollment?.generation
                else -> null
            }
        }.distinct()
        if (generations.size > 1) {
            throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, "seal_protected_generation_mixed")
        }
        return generations.singleOrNull()
    }

    private suspend fun verifyCommand(command: OperationalImportCommand): Boolean {
        return when (command) {
        is OperationalImportCommand.HandledMessageIdOnly ->
            database.relayDao().findHandled(command.messageId)?.let { current ->
                current.authenticatedFingerprint == null &&
                    current.evidenceKind == MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY &&
                    current.handledAt == command.handledAt
            } == true
        is OperationalImportCommand.MirrorLifecycle ->
            database.mirrorLifecycleDao().findLifecycle(command.sourceClientId, command.sourceKey) ==
                command.toEntity()
        is OperationalImportCommand.RunState -> command.withBorrowedPayload { payload, digest ->
            database.runDao().find(command.hostClientId, command.runId)?.let { current ->
                current.exactlyMatches(command.toEntity(payload, digest)) &&
                    MessageDigest.isEqual(current.payload, payload)
            } == true
        }
        is OperationalImportCommand.SealTerminalHistory -> {
            val current = database.sealDao().findRequest(command.requestId) ?: return false
            if (!current.exactlyMatches(command.toEntity(), includeProtectedBytes = false)) return false
            val verifier = sealPayloadVerifier ?: throw OperationalImportFailure(
                ImportFailureDisposition.BLOCKED,
                "seal_payload_verifier_unavailable",
            )
            verifier.verifyDisplay(
                command.requestId,
                current.toProtectedDisplay(),
                command.displayPlaintextDigestCopy(),
            )
        }
        is OperationalImportCommand.SealEnrollment -> {
            val header = database.sealDao().readEnrollment() ?: return false
            header.state == command.state.toStorageState() &&
                header.recoveryReasonCode == command.recoveryReasonCode &&
                database.sealDao().readEnrollmentProtected().contentEquals(command.protectedEnrollment)
        }
        }
    }

    private fun OperationalImportCommand.MirrorLifecycle.toEntity() = MirrorLifecycleEntity(
        sourceClientId = sourceClientId,
        sourceKey = sourceKey,
        postTime = postTime,
        dismissedAt = dismissedAt,
        updatedAt = updatedAt,
    )

    private fun OperationalImportCommand.RunState.toEntity(
        payload: ByteArray,
        digest: ByteArray,
    ) = RunStateEntity(
        hostClientId = hostClientId,
        runId = runId,
        revision = revision,
        phase = phase.toToken(),
        presentedRevision = presentedRevision,
        active = active,
        updatedAt = updatedAt,
        endedAt = endedAt,
        receivedAt = receivedAt,
        payload = payload,
        payloadDigest = digest,
    )

    private fun OperationalImportCommand.SealTerminalHistory.toEntity(): SealRequestEntity {
        val display = protectedDisplay
        return SealRequestEntity(
            requestId = requestId,
            requesterClientId = requesterClientId,
            senderClientId = senderClientId,
            requestFingerprint = requestFingerprintCopy(),
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            payloadSha256 = payloadSha256Copy(),
            objectKind = SealObjectKind.GIT_COMMIT,
            displayProtectionScheme = display.scheme,
            displayProtectionVersion = display.protectionVersion,
            displayProtectionKeyRef = display.keyRef,
            displayProtectionGeneration = display.generation,
            displayPayloadCodecVersion = display.payloadCodecVersion,
            displayCiphertext = display.ciphertextCopy(),
            displayNonce = display.nonceCopy(),
            displayTruncated = displayTruncated,
            state = state.toStorageState(),
            outcome = outcome.toStorageOutcome(),
            decisionAt = decisionAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ProtectedPayload.toEnrollmentEntity() = SealEnrollmentProtectedEntity(
        protectionScheme = scheme,
        protectionVersion = protectionVersion,
        protectionKeyRef = keyRef,
        protectionGeneration = generation,
        payloadCodecVersion = payloadCodecVersion,
        payloadCiphertext = ciphertextCopy(),
        payloadNonce = nonceCopy(),
    )

    private fun SealRequestEntity.toProtectedDisplay(): ProtectedPayload = ProtectedPayload.fromStorage(
        scheme = displayProtectionScheme,
        protectionVersion = displayProtectionVersion,
        generation = displayProtectionGeneration,
        keyRef = displayProtectionKeyRef,
        payloadCodecVersion = displayPayloadCodecVersion,
        nonce = displayNonce,
        ciphertext = displayCiphertext,
    )

    private fun SealRequestEntity.exactlyMatches(
        other: SealRequestEntity,
        includeProtectedBytes: Boolean,
    ): Boolean =
        requestId == other.requestId && requesterClientId == other.requesterClientId &&
            senderClientId == other.senderClientId &&
            MessageDigest.isEqual(requestFingerprint, other.requestFingerprint) &&
            issuedAt == other.issuedAt && expiresAt == other.expiresAt &&
            MessageDigest.isEqual(payloadSha256, other.payloadSha256) && objectKind == other.objectKind &&
            displayTruncated == other.displayTruncated && state == other.state && outcome == other.outcome &&
            decisionAt == other.decisionAt && createdAt == other.createdAt && updatedAt == other.updatedAt &&
            (!includeProtectedBytes || (
                displayProtectionScheme == other.displayProtectionScheme &&
                    displayProtectionVersion == other.displayProtectionVersion &&
                    displayProtectionKeyRef == other.displayProtectionKeyRef &&
                    displayProtectionGeneration == other.displayProtectionGeneration &&
                    displayPayloadCodecVersion == other.displayPayloadCodecVersion &&
                    MessageDigest.isEqual(displayCiphertext, other.displayCiphertext) &&
                    MessageDigest.isEqual(displayNonce, other.displayNonce)
                ))

    private fun SealEnrollmentProtectedEntity?.contentEquals(payload: ProtectedPayload?): Boolean = when {
        this == null || payload == null -> this == null && payload == null
        else -> protectionScheme == payload.scheme && protectionVersion == payload.protectionVersion &&
            protectionKeyRef == payload.keyRef && protectionGeneration == payload.generation &&
            payloadCodecVersion == payload.payloadCodecVersion &&
            MessageDigest.isEqual(payloadCiphertext, payload.ciphertextCopy()) &&
            MessageDigest.isEqual(payloadNonce, payload.nonceCopy())
    }

    private fun RunStateEntity.exactlyMatches(other: RunStateEntity): Boolean =
        hostClientId == other.hostClientId && runId == other.runId && revision == other.revision &&
            phase == other.phase && presentedRevision == other.presentedRevision && active == other.active &&
            updatedAt == other.updatedAt && endedAt == other.endedAt && receivedAt == other.receivedAt &&
            MessageDigest.isEqual(payloadDigest, other.payloadDigest)

    private fun ImportRunPhase.toToken(): RunPhaseToken = when (this) {
        ImportRunPhase.RUNNING -> RunPhaseToken.RUNNING
        ImportRunPhase.BLOCKED -> RunPhaseToken.BLOCKED
        ImportRunPhase.COMPLETED -> RunPhaseToken.COMPLETED
        ImportRunPhase.FAILED_TO_START -> RunPhaseToken.FAILED_TO_START
    }

    private fun ImportSealEnrollmentState.toStorageState(): SealEnrollmentState = when (this) {
        ImportSealEnrollmentState.DISABLED -> SealEnrollmentState.DISABLED
        ImportSealEnrollmentState.ENROLLED -> SealEnrollmentState.ENROLLED
        ImportSealEnrollmentState.RECOVERY_REQUIRED -> SealEnrollmentState.RECOVERY_REQUIRED
    }

    private fun ImportSealRequestState.toStorageState(): SealRequestState = when (this) {
        ImportSealRequestState.SENT -> SealRequestState.SENT
        ImportSealRequestState.CANCELLED -> SealRequestState.CANCELLED
        ImportSealRequestState.EXPIRED -> SealRequestState.EXPIRED
        ImportSealRequestState.FAILED -> SealRequestState.FAILED
    }

    private fun ImportSealRequestOutcome.toStorageOutcome(): SealRequestOutcome = when (this) {
        ImportSealRequestOutcome.APPROVED -> SealRequestOutcome.APPROVED
        ImportSealRequestOutcome.REJECTED -> SealRequestOutcome.REJECTED
        ImportSealRequestOutcome.CANCELLED -> SealRequestOutcome.CANCELLED
        ImportSealRequestOutcome.EXPIRED -> SealRequestOutcome.EXPIRED
        ImportSealRequestOutcome.FAILED -> SealRequestOutcome.FAILED
    }

    private suspend fun requireIdentity(identity: OperationalRebuildIdentity) {
        val marker = database.profileDao().readMaintenance()
            ?: targetConflict("target_maintenance_marker_missing")
        if (marker.operationalGeneration != identity.operationalGeneration ||
            marker.storageIncarnationId != identity.storageIncarnationId ||
            marker.postCutoverWriteAt != null
        ) {
            targetConflict("target_storage_continuity_mismatch")
        }
    }

    private fun targetConflict(code: String): Nothing = throw OperationalImportFailure(
        ImportFailureDisposition.BLOCKED,
        code,
    )

    private suspend fun <T> storageAccess(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: OperationalImportFailure) {
        throw expected
    } catch (expected: OperationalPreferencesImportFailure) {
        throw OperationalImportFailure(
            when (expected.disposition) {
                net.extrawdw.apps.notisync.data.storage.importer.target.preferences
                    .OperationalPreferencesFailureDisposition.RETRYABLE -> ImportFailureDisposition.RETRYABLE
                net.extrawdw.apps.notisync.data.storage.importer.target.preferences
                    .OperationalPreferencesFailureDisposition.BLOCKED -> ImportFailureDisposition.BLOCKED
            },
            expected.errorCode,
            expected,
        )
    } catch (failure: SQLiteException) {
        throw failure.toImportFailure()
    } catch (failure: IllegalArgumentException) {
        throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, "target_invariant_rejected", failure)
    } catch (failure: IllegalStateException) {
        throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, "target_consistency_failure", failure)
    }

    private fun SQLiteException.toImportFailure(): OperationalImportFailure = when (this) {
        is SQLiteFullException -> OperationalImportFailure(
            ImportFailureDisposition.RETRYABLE,
            "target_storage_full",
            this,
        )
        is SQLiteDatabaseCorruptException -> OperationalImportFailure(
            ImportFailureDisposition.BLOCKED,
            "target_database_corrupt",
            this,
        )
        is SQLiteCantOpenDatabaseException,
        is SQLiteDatabaseLockedException,
        is SQLiteDiskIOException,
        is SQLiteOutOfMemoryException,
        is SQLiteTableLockedException,
        -> OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "target_temporarily_unavailable", this)
        is SQLiteAccessPermException,
        is SQLiteReadOnlyDatabaseException,
        -> OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "target_unwritable", this)
        else -> OperationalImportFailure(ImportFailureDisposition.BLOCKED, "target_database_failure", this)
    }

    internal companion object {
        /** Every non-maintenance Operational v1 table, ordered child before parent for FK safety. */
        val REBUILD_PURGE_TABLES: List<String> = listOf(
            "android_seen_channel",
            "android_seen_group",
            "android_subscope_policy",
            "android_app_policy",
            "incoming_filter_rule",
            "incoming_filter",
            "seal_enrollment_protected",
            "seal_enrollment",
            "seal_response_custody",
            "seal_pending_payload",
            "seal_request",
            "ssh_reset_alias",
            "ssh_reset_journal",
            "ssh_wrapped_operational_material",
            "ssh_operational_key",
            "ssh_export_copy",
            "ssh_key_lifecycle_candidate",
            "ssh_key_lifecycle",
            "ssh_host_authorization",
            "ssh_peer_authorization",
            "ssh_known_host",
            "ssh_key",
            "ssh_provider_response_custody",
            "ssh_provider_pending_payload",
            "ssh_provider_request",
            "local_profile",
            "notification_capture_state",
            "ios_app_allowlist",
            "ios_seen_app",
            "activity_event",
            "message_dedup",
            "relay_batch_stage",
            "mirror_lifecycle",
            "run_state",
            "screen_authorized_peer",
            "screen_replay_token",
            "screen_security_state",
            "screen_codec_preference",
            "ssh_provider_state",
            "ssh_authorization_floor",
        )
    }
}
