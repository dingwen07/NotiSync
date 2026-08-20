package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyDigestAccumulator
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyFailureKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyImportException
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentFailure
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealHistoryOutcome
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealHistoryRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealRequestState
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealV3Reader
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportDigest
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportSealEnrollmentState
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportSealRequestOutcome
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportSealRequestState
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSource
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSources
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.apps.notisync.data.storage.importer.target.SealCommitDisplayImportMaterial
import net.extrawdw.apps.notisync.data.storage.importer.target.SealDisplayHeaderImportMaterial
import net.extrawdw.apps.notisync.data.storage.importer.target.SealEnrollmentImportMaterial
import net.extrawdw.apps.notisync.data.storage.importer.target.SealImportPayloadMaterializer
import net.extrawdw.apps.notisync.data.storage.importer.target.SealTerminalDisplayImportMaterial

internal class LegacySealHistorySourceAdapter(
    private val sourceFile: File,
    private val payloadMaterializer: SealImportPayloadMaterializer,
    private val reader: LegacySealV3Reader = LegacySealV3Reader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OperationalImportSourceAdapter {
    override val source: OperationalImportSource = OperationalImportSources.SEAL_HISTORY_V3

    override suspend fun isPresent(): Boolean = withContext(ioDispatcher) { sourceFile.isFile }

    override suspend fun load(identity: OperationalRebuildIdentity): OperationalImportSnapshot {
        val snapshot = readSource()
        val commands = ArrayList<OperationalImportCommand>(snapshot.terminalRows.size)
        for (row in snapshot.terminalRows) {
            currentCoroutineContext().ensureActive()
            commands += row.toCommand(payloadMaterializer, identity.operationalGeneration)
        }
        val skipped = checkedCount(snapshot.skippedActivePendingCount, snapshot.skippedResponsePendingCount)
        return ImmutableImportSnapshot(
            source = source,
            sourceFingerprint = ImportDigest.sha256(snapshot.digests.logicalFingerprint),
            logicalContentDigest = ImportDigest.sha256(snapshot.digests.contentDigest),
            commands = commands,
            skippedRowCount = skipped,
            quarantinedRowCount = snapshot.malformedDisplayCount,
        )
    }

    override suspend fun fingerprintStillMatches(snapshot: OperationalImportSnapshot): Boolean =
        MessageDigest.isEqual(readSource().digests.logicalFingerprint, snapshot.sourceFingerprint.copyBytes())

    private suspend fun readSource() = withContext(ioDispatcher) {
        try {
            reader.read(sourceFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LegacyImportException) {
            throw failure.toOperationalFailure("seal_history")
        }
    }
}

/** Maps the enrollment portion of the already-collected Preferences attempt; it never opens DataStore. */
internal fun interface SealEnrollmentCommandMapper {
    suspend fun map(
        snapshot: LegacySealEnrollmentSnapshot,
        operationalGeneration: Long,
    ): OperationalImportCommand.SealEnrollment
}

internal class LegacySealEnrollmentMapper(
    private val payloadMaterializer: SealImportPayloadMaterializer,
) : SealEnrollmentCommandMapper {
    override suspend fun map(
        snapshot: LegacySealEnrollmentSnapshot,
        operationalGeneration: Long,
    ): OperationalImportCommand.SealEnrollment =
        when (snapshot.status) {
            LegacySealEnrollmentStatus.DISABLED -> OperationalImportCommand.SealEnrollment(
                state = ImportSealEnrollmentState.DISABLED,
                recoveryReasonCode = null,
                protectedEnrollment = null,
            )
            LegacySealEnrollmentStatus.RECOVERY_REQUIRED -> OperationalImportCommand.SealEnrollment(
                state = ImportSealEnrollmentState.RECOVERY_REQUIRED,
                recoveryReasonCode = requireNotNull(snapshot.failure).safeCode(),
                protectedEnrollment = null,
            )
            LegacySealEnrollmentStatus.READY -> {
                val enrollment = requireNotNull(snapshot.enrollment)
                val protected = payloadMaterializer.protectEnrollment(
                    SealEnrollmentImportMaterial(
                        providerId = enrollment.providerId,
                        providerKeyReference = enrollment.providerKeyReference,
                        primaryKeyId = enrollment.primaryKeyId,
                        displayIdentity = enrollment.displayIdentity,
                        enrolledAt = enrollment.enrolledAt,
                    ),
                    operationalGeneration = operationalGeneration,
                )
                OperationalImportCommand.SealEnrollment(
                    state = ImportSealEnrollmentState.ENROLLED,
                    recoveryReasonCode = null,
                    protectedEnrollment = protected,
                )
            }
        }
}

private suspend fun LegacySealHistoryRow.toCommand(
    materializer: SealImportPayloadMaterializer,
    operationalGeneration: Long,
): OperationalImportCommand.SealTerminalHistory {
    val display = materializer.protectDisplay(
        requestId = requestId,
        material = SealTerminalDisplayImportMaterial(
            primaryKeyId = primaryKeyId,
            workingDirectory = workingDirectory,
            commit = commit?.let { snapshot ->
                SealCommitDisplayImportMaterial(
                    treeId = snapshot.treeId,
                    parentIds = snapshot.parentIds.toList(),
                    author = snapshot.author,
                    committer = snapshot.committer,
                    message = snapshot.message,
                    extraHeaders = snapshot.extraHeaders.map { header ->
                        SealDisplayHeaderImportMaterial(header.name, header.value)
                    },
                    payloadBytes = snapshot.payloadBytes,
                    truncated = snapshot.truncated,
                )
            },
        ),
        operationalGeneration = operationalGeneration,
    )
    return OperationalImportCommand.SealTerminalHistory(
        requestId = requestId,
        requesterClientId = requesterClientId,
        senderClientId = senderClientId,
        requestFingerprint = retainedRequestFingerprint(),
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        payloadSha256 = ImportDigest.sha256(payloadDigestCopy()),
        state = state.toImportState(),
        outcome = outcome.toImportOutcome(),
        decisionAt = updatedAt,
        createdAt = issuedAt,
        updatedAt = updatedAt,
        protectedDisplay = display.payload,
        displayPlaintextDigest = display.plaintextDigest,
        displayTruncated = display.truncated,
    )
}

/** Domain-separated fingerprint of the exact retained request context used by v51 same-context checks. */
private fun LegacySealHistoryRow.retainedRequestFingerprint(): ImportDigest = ImportDigest.sha256(
    LegacyDigestAccumulator().apply {
        text("NotiSync/seal/retained-request-context/v1")
        text("request")
        int(1)
        text(requestId)
        text(requesterClientId)
        text(senderClientId)
        long(issuedAt)
        long(expiresAt)
        text(primaryKeyId)
        bytes(payloadDigestCopy())
        text(objectKind)
        text(workingDirectory)
    }.digest(),
)

private fun LegacySealRequestState.toImportState(): ImportSealRequestState = when (this) {
    LegacySealRequestState.SENT -> ImportSealRequestState.SENT
    LegacySealRequestState.CANCELLED -> ImportSealRequestState.CANCELLED
    LegacySealRequestState.EXPIRED -> ImportSealRequestState.EXPIRED
    LegacySealRequestState.FAILED -> ImportSealRequestState.FAILED
}

private fun LegacySealHistoryOutcome.toImportOutcome(): ImportSealRequestOutcome = when (this) {
    LegacySealHistoryOutcome.APPROVED -> ImportSealRequestOutcome.APPROVED
    LegacySealHistoryOutcome.REJECTED -> ImportSealRequestOutcome.REJECTED
    LegacySealHistoryOutcome.CANCELED -> ImportSealRequestOutcome.CANCELLED
    LegacySealHistoryOutcome.EXPIRED -> ImportSealRequestOutcome.EXPIRED
    LegacySealHistoryOutcome.FAILED -> ImportSealRequestOutcome.FAILED
}

private fun LegacySealEnrollmentFailure.safeCode(): String = "seal_enrollment_${name.lowercase()}"

private class ImmutableImportSnapshot(
    override val source: OperationalImportSource,
    override val sourceFingerprint: ImportDigest,
    override val logicalContentDigest: ImportDigest,
    commands: List<OperationalImportCommand>,
    override val skippedRowCount: Long,
    override val quarantinedRowCount: Long,
) : OperationalImportSnapshot {
    private val values = commands.toList()
    override val commandCount: Long = values.size.toLong()

    override suspend fun commands(startOrdinal: Long, limit: Int): List<OperationalImportCommand> {
        require(startOrdinal in 0..commandCount && limit > 0) { "invalid Seal import page" }
        val start = startOrdinal.toInt()
        return values.subList(start, minOf(values.size, start + limit)).toList()
    }
}

private fun checkedCount(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, "seal_history_count_overflow")
}

private fun LegacyImportException.toOperationalFailure(prefix: String): OperationalImportFailure {
    val disposition = if (kind == LegacyFailureKind.SOURCE_IO) {
        ImportFailureDisposition.RETRYABLE
    } else {
        ImportFailureDisposition.BLOCKED
    }
    return OperationalImportFailure(disposition, "${prefix}_${kind.name.lowercase()}", this)
}
