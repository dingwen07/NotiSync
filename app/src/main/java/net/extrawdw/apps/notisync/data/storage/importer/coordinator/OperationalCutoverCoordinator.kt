package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesAttemptSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesIssue
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportVerificationResult
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSource
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSources
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildSummary
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyOperationalPreferencesMapper

internal interface OperationalImportSourceAdapter {
    val source: OperationalImportSource

    /** Cheap optional-source discovery; it must not mutate or open a writable source. */
    suspend fun isPresent(): Boolean

    /** Returns one pinned logical source view or a typed payload-free failure. */
    suspend fun load(identity: OperationalRebuildIdentity): OperationalImportSnapshot

    /** Re-checks the source after all target projections have been validated. */
    suspend fun fingerprintStillMatches(snapshot: OperationalImportSnapshot): Boolean

    /** Idempotently removes non-authoritative staging after success or an interrupted attempt. */
    suspend fun cleanupAfterAttempt() = Unit
}

internal fun interface ImportClock {
    fun nowMillis(): Long
}

internal sealed interface OperationalRebuildResult {
    data class Complete(val summary: OperationalRebuildSummary) : OperationalRebuildResult
    data class Retryable(val errorCode: String) : OperationalRebuildResult
    data class Blocked(val errorCode: String) : OperationalRebuildResult
}

/**
 * One disposable Operational rebuild. There is deliberately no durable source journal or cursor:
 * cancellation/process death leaves non-authoritative rows, and the next call clears and rebuilds
 * them from ordinal zero while the application remains in Loading.
 */
internal class OperationalCutoverCoordinator(
    adapters: List<OperationalImportSourceAdapter>,
    private val preferencesReader: LegacyOperationalPreferencesAttemptSource,
    private val preferencesMapper: LegacyOperationalPreferencesMapper,
    private val sealEnrollmentMapper: SealEnrollmentCommandMapper,
    private val target: OperationalImportTarget,
    private val clock: ImportClock = ImportClock(System::currentTimeMillis),
    private val afterBatchCommit: suspend (OperationalImportSource, Long) -> Unit = { _, _ -> },
) {
    private val adapters = adapters.sortedBy { it.source.sourceId }.also { sorted ->
        require(sorted.map { it.source }.toSet().size == sorted.size) {
            "duplicate Operational import source adapter"
        }
        require(sorted.map { it.source }.toSet() == OperationalImportSources.SQLITE_V51.toSet()) {
            "Operational rebuild requires every shipped SQLite source adapter"
        }
    }
    private val mutex = Mutex()

    suspend fun rebuild(
        operationalGeneration: Long,
        storageIncarnationId: String,
    ): OperationalRebuildResult = mutex.withLock {
        val identity = OperationalRebuildIdentity(
            operationalGeneration = operationalGeneration,
            storageIncarnationId = storageIncarnationId,
            startedAt = clock.nowMillis(),
        )
        var cleaned = false
        try {
            target.beginRebuild(identity)
            var importedRows = 0L
            var skippedRows = 0L
            var absentSources = 0
            val loaded = ArrayList<Pair<OperationalImportSourceAdapter, OperationalImportSnapshot>>()

            for (adapter in adapters) {
                currentCoroutineContext().ensureActive()
                if (!adapter.isPresent()) {
                    if (!adapter.source.optional) failBlocked("required_source_missing")
                    absentSources = Math.addExact(absentSources, 1)
                    continue
                }
                val snapshot = adapter.load(identity).also { it.requireValidFor(adapter.source) }
                if (snapshot.quarantinedRowCount != 0L) failBlocked("source_quarantine_unsupported")
                var ordinal = 0L
                while (ordinal < snapshot.commandCount) {
                    currentCoroutineContext().ensureActive()
                    val commands = snapshot.commands(ordinal, adapter.source.batchSize)
                    val maximum = minOf(
                        adapter.source.batchSize.toLong(),
                        snapshot.commandCount - ordinal,
                    ).toInt()
                    if (commands.isEmpty() || commands.size > maximum) {
                        failBlocked("source_batch_shape_changed")
                    }
                    target.applyBatch(identity, commands)
                    ordinal = Math.addExact(ordinal, commands.size.toLong())
                    afterBatchCommit(adapter.source, ordinal)
                }
                importedRows = Math.addExact(importedRows, snapshot.commandCount)
                skippedRows = Math.addExact(skippedRows, snapshot.skippedRowCount)
                loaded += adapter to snapshot
            }

            currentCoroutineContext().ensureActive()
            val preferences = preferencesReader.read()
            preferences.operational.aggregates.map(preferences.operational::read).firstOrNull {
                it.status == LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED
            }?.let { malformed ->
                val issue = malformed.issues.sortedWith(
                    compareBy<LegacyOperationalPreferencesIssue>({ it.kind.name }, { it.field.name }),
                ).first()
                failBlocked("preferences_invalid_${issue.kind.name.lowercase()}")
            }
            val preferencesPlan = preferencesMapper.mapAll(preferences.operational, identity.startedAt)
            val sealEnrollment = sealEnrollmentMapper.map(
                preferences.sealEnrollment,
                identity.operationalGeneration,
            )
            target.applyPreferences(identity, preferencesPlan, sealEnrollment)
            importedRows = Math.addExact(importedRows, preferencesPlan.importedRowCount)
            importedRows = Math.addExact(importedRows, 1L)
            absentSources = Math.addExact(absentSources, preferencesPlan.absentAggregateCount)

            loaded.forEach { (_, snapshot) ->
                when (val verification = target.verify(identity, snapshot)) {
                    ImportVerificationResult.VERIFIED -> Unit
                    is ImportVerificationResult.Failed -> failBlocked(verification.errorCode)
                }
            }
            when (val verification = target.verifyPreferences(identity, preferencesPlan, sealEnrollment)) {
                ImportVerificationResult.VERIFIED -> Unit
                is ImportVerificationResult.Failed -> failBlocked(verification.errorCode)
            }
            for ((adapter, snapshot) in loaded) {
                currentCoroutineContext().ensureActive()
                if (!adapter.fingerprintStillMatches(snapshot)) failBlocked("source_fingerprint_changed")
            }

            cleanupAll()
            cleaned = true
            OperationalRebuildResult.Complete(
                OperationalRebuildSummary(importedRows, skippedRows, absentSources),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OperationalImportFailure) {
            when (failure.disposition) {
                ImportFailureDisposition.RETRYABLE -> OperationalRebuildResult.Retryable(failure.errorCode)
                ImportFailureDisposition.BLOCKED -> OperationalRebuildResult.Blocked(failure.errorCode)
            }
        } catch (_: IOException) {
            OperationalRebuildResult.Retryable("preferences_source_unavailable")
        } catch (_: IllegalArgumentException) {
            OperationalRebuildResult.Blocked("source_invariant_rejected")
        } finally {
            if (!cleaned) cleanupAfterFailure()
        }
    }

    private suspend fun cleanupAll() {
        adapters.forEach { it.cleanupAfterAttempt() }
    }

    private suspend fun cleanupAfterFailure() = withContext(NonCancellable) {
        adapters.forEach { adapter ->
            try {
                adapter.cleanupAfterAttempt()
            } catch (_: Exception) {
                // A stale no-backup stage is rejected and replaced at the next load().
            }
        }
    }

    private fun OperationalImportSnapshot.requireValidFor(expected: OperationalImportSource) {
        require(source == expected) { "snapshot source contract changed" }
        require(commandCount >= 0 && skippedRowCount >= 0 && quarantinedRowCount >= 0) {
            "snapshot counts must not be negative"
        }
    }

    private fun failBlocked(code: String): Nothing = throw OperationalImportFailure(
        ImportFailureDisposition.BLOCKED,
        code,
    )
}
