package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySealEnrollmentStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceAggregate
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceField
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceRead
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesAttemptSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesIssue
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesIssueKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportDigest
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportSealEnrollmentState
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportVerificationResult
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSource
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSources
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyDeviceProfileImportDefaults
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.LegacyOperationalPreferencesMapper
import net.extrawdw.apps.notisync.data.storage.importer.target.preferences.OperationalPreferencesRebuildPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalCutoverCoordinatorTest {
    @Test
    fun cancellationLeavesPartialRowsAndNextCallRebuildsFromZero() = runTest {
        val target = FakeTarget()
        var cancelOnce = true
        val adapters = adapters(
            ledgerCommands = listOf(
                handled("one", 1),
                handled("two", 2),
            ),
        )
        val coordinator = coordinator(adapters, target) { source, _ ->
            if (source == OperationalImportSources.MESSAGE_LEDGER_V2 && cancelOnce) {
                cancelOnce = false
                throw CancellationException("forced")
            }
        }

        try {
            coordinator.rebuild(1, "incarnation-a")
            error("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the first short batch was committed before cancellation.
        }
        assertEquals(1, target.beginCalls)
        assertEquals(2, target.commands.size)

        val result = coordinator.rebuild(1, "incarnation-a")

        assertTrue(result is OperationalRebuildResult.Complete)
        assertEquals(2, target.beginCalls)
        assertEquals(listOf("one", "two"), target.commands.map { (it as OperationalImportCommand.HandledMessageIdOnly).messageId })
        assertEquals(2, adapters.first().loadCalls)
    }

    @Test
    fun optionalAbsenceIsOnlyAnInMemoryCountAndNeverLoadsTheSource() = runTest {
        val adapters = adapters(present = false)
        val target = FakeTarget()

        val result = coordinator(adapters, target).rebuild(3, "incarnation-c")

        val complete = result as OperationalRebuildResult.Complete
        assertEquals(8, complete.summary.absentOptionalSourceCount)
        assertTrue(adapters.all { it.loadCalls == 0 })
        assertEquals(1, target.beginCalls)
    }

    @Test
    fun postCutoverRefusalTouchesNoLegacySource() = runTest {
        val adapters = adapters()
        val target = FakeTarget(beginFailure = OperationalImportFailure(
            ImportFailureDisposition.BLOCKED,
            "target_post_cutover_write_detected",
        ))

        val result = coordinator(adapters, target).rebuild(1, "incarnation-a")

        assertEquals(
            OperationalRebuildResult.Blocked("target_post_cutover_write_detected"),
            result,
        )
        assertTrue(adapters.all { it.presentCalls == 0 && it.loadCalls == 0 })
    }

    @Test
    fun finalFingerprintMismatchBlocksAfterExactTargetVerification() = runTest {
        val adapters = adapters().also { it.first().fingerprintMatches = false }
        val target = FakeTarget()

        val result = coordinator(adapters, target).rebuild(1, "incarnation-a")

        assertEquals(OperationalRebuildResult.Blocked("source_fingerprint_changed"), result)
        assertTrue(target.verifyCalls > 0)
    }

    @Test
    fun malformedPreferencesBlockWithoutApplyingPreferenceRows() = runTest {
        val target = FakeTarget()
        val malformed = attemptSnapshot(
            malformedAggregate = LegacyOperationalPreferenceAggregate.SCREEN,
        )

        val result = coordinator(adapters(), target, preferences = malformed).rebuild(1, "incarnation-a")

        assertEquals(OperationalRebuildResult.Blocked("preferences_invalid_wrong_value_type"), result)
        assertEquals(0, target.preferenceApplyCalls)
    }

    @Test
    fun largeLogicalSourceUsesBoundedShortTargetBatchesWithoutDurableCursor() = runTest {
        val commands = (0 until 300).map { handled("message-$it", it + 1L) }
        val target = FakeTarget()

        val result = coordinator(adapters(ledgerCommands = commands), target).rebuild(1, "incarnation-a")

        val complete = result as OperationalRebuildResult.Complete
        assertEquals(listOf(128, 128, 44), target.batchSizes.filter { it > 0 })
        assertEquals(301L, complete.summary.importedRowCount)
    }

    @Test
    fun unavailableSinglePreferencesSnapshotIsRetryableAndNeverPartiallyApplied() = runTest {
        val target = FakeTarget()
        val coordinator = OperationalCutoverCoordinator(
            adapters = adapters(),
            preferencesReader = { throw java.io.IOException("unavailable") },
            preferencesMapper = LegacyOperationalPreferencesMapper(LegacyDeviceProfileImportDefaults("Fallback")),
            sealEnrollmentMapper = { _, _ ->
                OperationalImportCommand.SealEnrollment(ImportSealEnrollmentState.DISABLED, null, null)
            },
            target = target,
            clock = ImportClock { 100 },
        )

        val result = coordinator.rebuild(1, "incarnation-a")

        assertEquals(OperationalRebuildResult.Retryable("preferences_source_unavailable"), result)
        assertEquals(0, target.preferenceApplyCalls)
    }

    private fun coordinator(
        adapters: List<FakeAdapter>,
        target: FakeTarget,
        preferences: LegacyOperationalPreferencesAttemptSnapshot = attemptSnapshot(),
        afterBatch: suspend (OperationalImportSource, Long) -> Unit = { _, _ -> },
    ) = OperationalCutoverCoordinator(
        adapters = adapters,
        preferencesReader = { preferences },
        preferencesMapper = LegacyOperationalPreferencesMapper(LegacyDeviceProfileImportDefaults("Fallback")),
        sealEnrollmentMapper = { _, _ ->
            OperationalImportCommand.SealEnrollment(ImportSealEnrollmentState.DISABLED, null, null)
        },
        target = target,
        clock = ImportClock { 100 },
        afterBatchCommit = afterBatch,
    )

    private fun adapters(
        present: Boolean = true,
        ledgerCommands: List<OperationalImportCommand> = emptyList(),
    ): List<FakeAdapter> = OperationalImportSources.SQLITE_V51.map { source ->
        FakeAdapter(source, present, if (source == OperationalImportSources.MESSAGE_LEDGER_V2) ledgerCommands else emptyList())
    }

    private class FakeAdapter(
        override val source: OperationalImportSource,
        private val present: Boolean,
        private val commands: List<OperationalImportCommand>,
    ) : OperationalImportSourceAdapter {
        var presentCalls = 0
        var loadCalls = 0
        var fingerprintMatches = true

        override suspend fun isPresent(): Boolean {
            presentCalls++
            return present
        }

        override suspend fun load(identity: OperationalRebuildIdentity): OperationalImportSnapshot {
            loadCalls++
            return snapshot(source, commands)
        }

        override suspend fun fingerprintStillMatches(snapshot: OperationalImportSnapshot): Boolean =
            fingerprintMatches
    }

    private class FakeTarget(
        private val beginFailure: OperationalImportFailure? = null,
    ) : OperationalImportTarget {
        var beginCalls = 0
        var preferenceApplyCalls = 0
        var verifyCalls = 0
        val batchSizes = mutableListOf<Int>()
        val commands = mutableListOf<OperationalImportCommand>()

        override suspend fun beginRebuild(identity: OperationalRebuildIdentity) {
            beginCalls++
            beginFailure?.let { throw it }
            commands.clear()
        }

        override suspend fun applyBatch(
            identity: OperationalRebuildIdentity,
            commands: List<OperationalImportCommand>,
        ) {
            batchSizes += commands.size
            this.commands += commands
        }

        override suspend fun applyPreferences(
            identity: OperationalRebuildIdentity,
            plan: OperationalPreferencesRebuildPlan,
            sealEnrollment: OperationalImportCommand.SealEnrollment,
        ) {
            preferenceApplyCalls++
        }

        override suspend fun verify(
            identity: OperationalRebuildIdentity,
            snapshot: OperationalImportSnapshot,
        ): ImportVerificationResult {
            verifyCalls++
            return ImportVerificationResult.VERIFIED
        }

        override suspend fun verifyPreferences(
            identity: OperationalRebuildIdentity,
            plan: OperationalPreferencesRebuildPlan,
            sealEnrollment: OperationalImportCommand.SealEnrollment,
        ) = ImportVerificationResult.VERIFIED
    }

    private companion object {
        fun handled(id: String, time: Long) = OperationalImportCommand.HandledMessageIdOnly(id, time)

        fun snapshot(
            source: OperationalImportSource,
            commands: List<OperationalImportCommand>,
        ) = object : OperationalImportSnapshot {
            override val source = source
            override val sourceFingerprint = ImportDigest.sha256(ByteArray(32) { source.sourceId.length.toByte() })
            override val logicalContentDigest = ImportDigest.sha256(ByteArray(32) { commands.size.toByte() })
            override val commandCount = commands.size.toLong()
            override val skippedRowCount = 0L
            override val quarantinedRowCount = 0L

            override suspend fun commands(startOrdinal: Long, limit: Int): List<OperationalImportCommand> =
                commands.drop(startOrdinal.toInt()).take(limit)
        }

        fun attemptSnapshot(
            malformedAggregate: LegacyOperationalPreferenceAggregate? = null,
        ): LegacyOperationalPreferencesAttemptSnapshot {
            val reads = LegacyOperationalPreferenceAggregate.entries.associateWith { aggregate ->
                if (aggregate == malformedAggregate) {
                    LegacyOperationalPreferenceRead(
                        aggregate,
                        LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED,
                        presentKeyCount = 1,
                        values = null,
                        issues = setOf(
                            LegacyOperationalPreferencesIssue(
                                LegacyOperationalPreferencesIssueKind.WRONG_VALUE_TYPE,
                                LegacyOperationalPreferenceField.SCREEN_ENABLED,
                            ),
                        ),
                    )
                } else {
                    LegacyOperationalPreferenceRead(
                        aggregate,
                        LegacyOperationalPreferencesReadStatus.ABSENT,
                        presentKeyCount = 0,
                        values = null,
                        issues = emptySet(),
                    )
                }
            }
            return LegacyOperationalPreferencesAttemptSnapshot(
                LegacyOperationalPreferencesSnapshot(reads),
                LegacySealEnrollmentSnapshot(
                    LegacySealEnrollmentStatus.DISABLED,
                    enrollment = null,
                    failure = null,
                    presentKeyCount = 0,
                ),
            )
        }
    }
}
