package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.incomingfilter.CanonicalIncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterCanonicalizer
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalDatabaseTest {
    @Test
    fun maintenanceImportAndDeliveryFactsAreMonotonic() = runBlocking {
        withDatabase { database ->
            val profile = database.profileDao()
            val marker = maintenanceState(1, "incarnation-a", 1)
            assertEquals(
                OperationalProfileDao.MaintenanceInitializeResult.INITIALIZED,
                profile.initializeMaintenance(marker),
            )
            assertEquals(
                OperationalProfileDao.MaintenanceInitializeResult.ALREADY_INITIALIZED,
                profile.initializeMaintenance(marker),
            )
            assertEquals(
                OperationalProfileDao.MaintenanceInitializeResult.CONFLICT,
                profile.initializeMaintenance(marker.copy(storageIncarnationId = "incarnation-b")),
            )
            profile.replaceMaintenance(marker.copy(postCutoverWriteAt = 2, lastIntegrityCheckAt = 3, updatedAt = 3))
            val updated = requireNotNull(profile.readMaintenance())
            assertEquals(1L, updated.operationalGeneration)
            assertEquals("incarnation-a", updated.storageIncarnationId)
            expectIllegalArgument {
                profile.replaceMaintenance(updated.copy(postCutoverWriteAt = null, updatedAt = 4))
            }

            assertEquals(20L, profile.advanceNotificationCaptureLastSeenPostTime(20, 8))
            assertEquals(20L, profile.advanceNotificationCaptureLastSeenPostTime(10, 9))
            val capture = requireNotNull(profile.observeNotificationCaptureState().first())
            assertEquals(20L, capture.lastSeenPostTime)
            assertEquals(
                setOf("singleton_id", "last_seen_post_time", "updated_at"),
                tableColumns(database, "notification_capture_state"),
            )
        }
    }

    @Test
    fun policyFilterAndIosChildrenPreserveTheirIndependentLifecycles() = runBlocking {
        withDatabase { database ->
            val policy = database.notificationPolicyDao()
            policy.upsertApp(appPolicy("net.example"))
            policy.upsertSubscope(
                AndroidSubscopePolicyEntity(
                    "net.example",
                    AndroidPolicyScope.CHANNEL,
                    "messages",
                    enabled = true,
                    updatedAt = 1,
                ),
            )
            policy.recordSeenChannel(
                group = AndroidSeenGroupEntity("net.example", "people", "People", 1, 2),
                channel = AndroidSeenChannelEntity("net.example", "messages", "Messages", "people", 1, 2),
            )
            policy.recordSeenChannel(
                group = null,
                channel = AndroidSeenChannelEntity("net.example", "ungrouped", null, null, 1, 2),
            )
            assertEquals(
                "people",
                policy.observeSeenChannels("net.example").first().single { it.channelId == "messages" }.groupId,
            )
            assertTrue(policy.observeSeenChannels("net.example").first().any { it.groupId == null })
            expectFailure {
                database.useWriterConnection { connection ->
                    connection.executeSQL(
                        "INSERT INTO android_seen_channel(" +
                            "package_name, channel_id, channel_name, group_id, first_seen_at, last_seen_at" +
                            ") VALUES ('net.example', 'orphan', NULL, 'missing', 1, 1)",
                    )
                }
            }
            expectFailure { policy.forgetSeenGroup("net.example", "people") }
            assertEquals(1, policy.forgetApp("net.example"))
            assertTrue(policy.observeSeenGroups("net.example").first().isEmpty())

            val filter = database.incomingFilterDao()
            val canonical = IncomingFilterCanonicalizer.canonicalize(
                listOf(
                    IncomingFilterRuleValue(
                        CanonicalIncomingFilterOrigin.IOS_ANCS,
                        "com.example.ios",
                        null,
                    ),
                ),
            )
            val setDigest = canonical.digestCopy()
            val rule = IncomingFilterRuleEntity(
                requesterClientId = "requester",
                ruleDigest = canonical.rules.single().digestCopy(),
                position = 0,
                originPlatform = NotificationOriginPlatform.IOS_ANCS,
                appId = "com.example.ios",
                channelId = null,
            )
            assertEquals(
                IncomingFilterReplaceResult.INSERTED,
                filter.replace(IncomingFilterEntity("requester", 1, 10, 11, setDigest), listOf(rule)),
            )
            assertEquals(
                IncomingFilterReplaceResult.UNCHANGED,
                filter.replace(IncomingFilterEntity("requester", 1, 10, 11, setDigest), listOf(rule)),
            )

            val ios = database.iosAppDao()
            assertTrue(ios.setEnabled("com.example.ios", true))
            ios.recordSeen("com.example.ios", "Example", 10)
            assertEquals(1, ios.forgetSeen("com.example.ios"))
            assertTrue(ios.findAllowlisted("com.example.ios") != null)
            assertNull(ios.findSeen("com.example.ios"))
        }
    }

    @Test
    fun receiptFinalizationBindsContinuityFingerprintAndDeterministicActivity() = runBlocking {
        withDatabase { database ->
            database.profileDao().initializeMaintenance(maintenanceState(1, "relay-incarnation", 1))
            val relay = database.relayDao()
            val legacy = MessageDedupEntity(
                messageId = "legacy-message",
                authenticatedFingerprint = null,
                evidenceKind = MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY,
                handledAt = 2,
            )
            assertTrue(relay.insertImportedHandled(legacy))
            assertFalse(relay.insertImportedHandled(legacy))
            assertEquals(
                RelayFinalizeResult.LEGACY_RETAINED_NO_ACK,
                relay.finalizeHandled(
                    handled("legacy-message", fingerprint(1), 3),
                    expectedOperationalGeneration = 1,
                    expectedStorageIncarnationId = "relay-incarnation",
                    activity = null,
                ),
            )

            val activity = activity("event-1", 3)
            val handled = handled("modern-message", fingerprint(2), 3)
            assertEquals(
                RelayFinalizeResult.STORAGE_CONTINUITY_MISMATCH,
                relay.finalizeHandled(handled, 2, "relay-incarnation", activity),
            )
            assertEquals(RelayFinalizeResult.APPLIED, relay.finalizeHandled(handled, 1, "relay-incarnation", activity))
            assertEquals(
                RelayFinalizeResult.ALREADY_FINALIZED,
                relay.finalizeHandled(handled.copy(handledAt = 30), 1, "relay-incarnation", activity),
            )
            assertEquals(
                RelayFinalizeResult.CONFLICT,
                relay.finalizeHandled(handled.copy(authenticatedFingerprint = fingerprint(3)), 1, "relay-incarnation", activity),
            )
            assertEquals(
                RelayFinalizeResult.CONFLICT,
                relay.finalizeHandled(
                    handled.copy(handledAt = 31),
                    1,
                    "relay-incarnation",
                    activity.copy(outcome = ActivityOutcome.FAILED),
                ),
            )
            assertEquals(3L, relay.findHandled("modern-message")?.handledAt)
            val storedActivity = requireNotNull(database.activityDao().find("event-1"))
            assertEquals(activity.eventId, storedActivity.eventId)
            assertEquals(activity.outcome, storedActivity.outcome)
            assertTrue(activity.renderArgs.contentEquals(storedActivity.renderArgs))
            assertEquals(
                RelayHandledResolutionResult.EXACT_AUTHENTICATED,
                relay.resolveHandled("modern-message", fingerprint(2), 1, "relay-incarnation"),
            )
            assertEquals(
                RelayHandledResolutionResult.CONFLICT,
                relay.resolveHandled("modern-message", fingerprint(3), 1, "relay-incarnation"),
            )
            assertEquals(
                RelayHandledResolutionResult.LEGACY_RETAINED_NO_ACK,
                relay.resolveHandled("legacy-message", fingerprint(1), 1, "relay-incarnation"),
            )
            assertEquals(
                RelayHandledResolutionResult.MISSING,
                relay.resolveHandled("missing", fingerprint(1), 1, "relay-incarnation"),
            )
            database.useWriterConnection { connection ->
                connection.executeSQL("UPDATE maintenance_state SET operational_generation = 2 WHERE singleton_id = 1")
            }
            assertEquals(
                RelayHandledResolutionResult.STORAGE_CONTINUITY_MISMATCH,
                relay.resolveHandled("modern-message", fingerprint(2), 1, "relay-incarnation"),
            )
            assertEquals(3L, relay.findHandled("modern-message")?.handledAt)

            val names = tableNames(database)
            setOf(
                "relay_inbox",
                "relay_ack_outbox",
                "core_command_inbox",
                "mirror_message_index",
                "channel_outbox",
                "storage_import",
            ).forEach { removed -> assertFalse(removed in names) }
        }
    }

    @Test
    fun featureOwnedMirrorRunAndFilterReceiptsCommitAtomically() = runBlocking {
        withDatabase { database ->
            database.profileDao().initializeMaintenance(maintenanceState(1, "feature-incarnation", 1))

            val mirror = database.mirrorLifecycleDao()
            val postReceipt = receipt("mirror-post-message", 20, "mirror-post-event", 2)
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                mirror.acceptPostWithReceipt("source", "key", 2, 2, postReceipt),
            )
            assertEquals(2L, mirror.findLifecycle("source", "key")?.postTime)
            assertTrue(database.activityDao().find("mirror-post-event") != null)
            assertTrue(database.relayDao().findHandled("mirror-post-message") != null)
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.DUPLICATE),
                mirror.acceptPostWithReceipt(
                    "source",
                    "key",
                    99,
                    99,
                    receipt("mirror-post-message", 20, "mirror-post-event", 2, handledAt = 99),
                ),
            )
            assertEquals(2L, mirror.findLifecycle("source", "key")?.postTime)

            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                mirror.recordDismissalWithReceipt(
                    "source",
                    "key",
                    3,
                    3,
                    receipt(
                        "mirror-dismiss-message",
                        32,
                        "mirror-dismiss-event",
                        3,
                        action = ActivityAction.DISMISSED,
                    ),
                ),
            )
            assertEquals(3L, mirror.findLifecycle("source", "key")?.dismissedAt)
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.SUPERSEDED),
                mirror.acceptPostWithReceipt(
                    "source",
                    "key",
                    2,
                    4,
                    receipt("mirror-stale-message", 33, "mirror-stale-event", 4),
                ),
            )
            assertTrue(database.relayDao().findHandled("mirror-stale-message") != null)
            assertNull(database.activityDao().find("mirror-stale-event"))

            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                mirror.finalizeNotificationActionReceipt(
                    receipt(
                        "notification-action-message",
                        21,
                        "notification-action-event",
                        3,
                        action = ActivityAction.ACCEPTED,
                    ),
                ),
            )

            val runReceipt = receipt(
                "run-message",
                22,
                "run-event",
                4,
                feature = ActivityFeature.RUN,
                action = ActivityAction.RECEIVED,
            )
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                database.runDao().compareAndUpsertWithReceipt(
                    runState(1, RunPhaseToken.RUNNING, active = true, receivedAt = 4),
                    runReceipt,
                ),
            )
            assertEquals(1L, database.runDao().find("host-1", "run-1")?.revision)
            assertTrue(database.activityDao().find("run-event") != null)
            assertTrue(
                database.activityDao().insert(
                    activity("run-equal-event", 5).copy(
                        feature = ActivityFeature.RUN,
                        semanticAction = ActivityAction.RECEIVED,
                        outcome = ActivityOutcome.FAILED,
                    ),
                ),
            )
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.DUPLICATE),
                database.runDao().compareAndUpsertWithReceipt(
                    runState(1, RunPhaseToken.RUNNING, active = true, receivedAt = 4),
                    receipt(
                        "run-equal-message",
                        34,
                        "run-equal-event",
                        5,
                        feature = ActivityFeature.RUN,
                        action = ActivityAction.RECEIVED,
                    ),
                ),
            )
            assertTrue(database.relayDao().findHandled("run-equal-message") != null)
            assertEquals(ActivityOutcome.FAILED, database.activityDao().find("run-equal-event")?.outcome)

            val canonical = IncomingFilterCanonicalizer.canonicalize(
                listOf(
                    IncomingFilterRuleValue(
                        CanonicalIncomingFilterOrigin.ANDROID_LOCAL,
                        "net.example.filtered",
                        "messages",
                    ),
                ),
            )
            val filterHeader = IncomingFilterEntity(
                "filter-requester",
                IncomingFilterCanonicalizer.VERSION,
                5,
                5,
                canonical.digestCopy(),
            )
            val filterRules = listOf(
                IncomingFilterRuleEntity(
                    "filter-requester",
                    canonical.rules.single().digestCopy(),
                    0,
                    NotificationOriginPlatform.ANDROID_LOCAL,
                    "net.example.filtered",
                    "messages",
                ),
            )
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                database.incomingFilterDao().replaceWithReceipt(
                    filterHeader,
                    filterRules,
                    receipt(
                        "filter-message",
                        23,
                        "filter-event",
                        5,
                        feature = ActivityFeature.PROFILE,
                    ),
                ),
            )
            assertTrue(
                requireNotNull(database.incomingFilterDao().find("filter-requester")).ruleSetDigest
                    .contentEquals(filterHeader.ruleSetDigest),
            )
        }
    }

    @Test
    fun featureOwnedReceiptPreflightBlocksLegacyContinuityAndActivityConflictsBeforeMutation() = runBlocking {
        withDatabase { database ->
            database.profileDao().initializeMaintenance(maintenanceState(1, "feature-incarnation", 1))
            database.relayDao().insertImportedHandled(
                MessageDedupEntity(
                    "legacy-feature-message",
                    null,
                    MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY,
                    2,
                ),
            )
            assertEquals(
                OperationalFeatureCommitResult.LegacyRetainedNoAck,
                database.mirrorLifecycleDao().acceptPostWithReceipt(
                    "legacy-source",
                    "legacy-key",
                    2,
                    2,
                    receipt("legacy-feature-message", 24, "legacy-feature-event", 2),
                ),
            )
            assertNull(database.mirrorLifecycleDao().findLifecycle("legacy-source", "legacy-key"))
            assertNull(database.activityDao().find("legacy-feature-event"))

            assertEquals(
                OperationalFeatureCommitResult.StorageContinuityMismatch,
                database.mirrorLifecycleDao().recordDismissalWithReceipt(
                    "stale-source",
                    "stale-key",
                    3,
                    3,
                    receipt(
                        "stale-continuity-message",
                        25,
                        "stale-continuity-event",
                        3,
                        expectedGeneration = 2,
                    ),
                ),
            )
            assertNull(database.mirrorLifecycleDao().findLifecycle("stale-source", "stale-key"))

            assertTrue(database.activityDao().insert(activity("collision-event", 4)))
            val collisionReceipt = receipt(
                "collision-message",
                26,
                "collision-event",
                4,
                outcome = ActivityOutcome.FAILED,
            )
            assertEquals(
                OperationalFeatureCommitResult.ConflictNoAck,
                database.mirrorLifecycleDao().acceptPostWithReceipt(
                    "collision-source",
                    "collision-key",
                    4,
                    4,
                    collisionReceipt,
                ),
            )
            assertNull(database.mirrorLifecycleDao().findLifecycle("collision-source", "collision-key"))
            assertNull(database.relayDao().findHandled("collision-message"))

            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                database.mirrorLifecycleDao().acceptPostWithReceipt(
                    "no-activity-source",
                    "no-activity-key",
                    5,
                    5,
                    receiptWithoutActivity("no-activity-message", 36, 5),
                ),
            )
            assertTrue(database.mirrorLifecycleDao().findLifecycle("no-activity-source", "no-activity-key") != null)
            assertTrue(database.relayDao().findHandled("no-activity-message") != null)

            expectIllegalArgument {
                database.mirrorLifecycleDao().finalizeNotificationActionReceipt(
                    receiptWithoutActivity("action-without-activity", 37, 6),
                )
            }
            assertNull(database.relayDao().findHandled("action-without-activity"))
        }
    }

    @Test
    fun protectedFeatureReceiptsCommitOrSecurityBlockWithoutPartialHandledEvidence() = runBlocking {
        withDatabase { database ->
            database.profileDao().initializeMaintenance(maintenanceState(1, "feature-incarnation", 1))
            val screen = database.screenDao()
            screen.replaceAuthorizations(
                emptyList(),
                ScreenSecurityStateEntity(
                    enabled = true,
                    replayHealth = ScreenReplayHealth.HEALTHY,
                    quarantineDigest = null,
                    quarantinedAt = null,
                    authorizationRevision = 0,
                    updatedAt = 1,
                ),
            )
            val session = fingerprint(27)
            val routing = fingerprint(28)
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                screen.consumeReplayWithReceipt(
                    session,
                    routing,
                    100,
                    2,
                    receipt(
                        "screen-message",
                        27,
                        "screen-event",
                        2,
                        feature = ActivityFeature.SCREEN_MIRRORING,
                        action = ActivityAction.REQUESTED,
                    ),
                ),
            )
            assertEquals(
                OperationalFeatureCommitResult.SecurityBlocked("screen_replay_token_reused"),
                screen.consumeReplayWithReceipt(
                    session,
                    routing,
                    100,
                    3,
                    receipt(
                        "screen-reuse-message",
                        29,
                        "screen-reuse-event",
                        3,
                        feature = ActivityFeature.SCREEN_MIRRORING,
                        action = ActivityAction.REQUESTED,
                    ),
                ),
            )
            assertNull(database.relayDao().findHandled("screen-reuse-message"))
            assertNull(database.activityDao().find("screen-reuse-event"))

            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                database.sealDao().acceptWithReceipt(
                    sealRequest("seal-receipt-request"),
                    sealPending("seal-receipt-request"),
                    receipt(
                        "seal-message",
                        30,
                        "seal-event",
                        4,
                        feature = ActivityFeature.SEAL,
                        action = ActivityAction.REQUESTED,
                    ),
                    now = 4,
                ),
            )
            assertTrue(database.sealDao().findPendingPayload("seal-receipt-request") != null)
            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.DUPLICATE),
                database.sealDao().acceptWithReceipt(
                    sealRequest("seal-receipt-request"),
                    sealPending("seal-receipt-request"),
                    receipt(
                        "seal-duplicate-message",
                        35,
                        "seal-duplicate-event",
                        5,
                        feature = ActivityFeature.SEAL,
                        action = ActivityAction.REQUESTED,
                    ),
                    now = 5,
                ),
            )
            assertTrue(database.relayDao().findHandled("seal-duplicate-message") != null)
            assertNull(database.activityDao().find("seal-duplicate-event"))

            assertEquals(
                OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.APPLIED),
                database.sshRequestDao().acceptProviderRequestWithReceipt(
                    sshProviderRequest("ssh-receipt-request"),
                    sshProviderPending("ssh-receipt-request"),
                    receipt(
                        "ssh-message",
                        31,
                        "ssh-event",
                        5,
                        feature = ActivityFeature.SSH_AGENT,
                        action = ActivityAction.REQUESTED,
                    ),
                    now = 5,
                ),
            )
            assertTrue(database.sshRequestDao().findProviderPendingPayload("ssh-receipt-request") != null)
            assertTrue(database.relayDao().findHandled("seal-message") != null)
            assertTrue(database.relayDao().findHandled("ssh-message") != null)
        }
    }

    @Test
    fun handledEvidenceSurvivesCloseAndExactRedeliveryDoesNotRewriteChronology() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "operational-redelivery-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            val first = openFileDatabase(context, name)
            try {
                first.profileDao().initializeMaintenance(maintenanceState(1, "reopen-incarnation", 1))
                assertEquals(
                    RelayFinalizeResult.APPLIED,
                    first.relayDao().finalizeHandled(
                        handled("redelivery", fingerprint(4), 2),
                        1,
                        "reopen-incarnation",
                        null,
                    ),
                )
            } finally {
                first.close()
            }
            val reopened = openFileDatabase(context, name)
            try {
                assertEquals(
                    RelayFinalizeResult.ALREADY_FINALIZED,
                    reopened.relayDao().finalizeHandled(
                        handled("redelivery", fingerprint(4), 99),
                        1,
                        "reopen-incarnation",
                        null,
                    ),
                )
                assertEquals(2L, reopened.relayDao().findHandled("redelivery")?.handledAt)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun relayBatchScratchCollapsesExactItemsFlagsConflictsAndPagesMetadataOnly() = runBlocking {
        withDatabase { database ->
            val stage = database.relayBatchStageDao()
            assertEquals(
                RelayBatchRecordResult.INSERTED,
                stage.recordItem("message-b", fingerprint(1), RelayBatchPresentationKind.NOTIFICATION),
            )
            assertEquals(
                RelayBatchRecordResult.EXACT,
                stage.recordItem("message-b", fingerprint(1), RelayBatchPresentationKind.NOTIFICATION),
            )
            assertEquals(
                RelayBatchRecordResult.EXACT,
                stage.recordItem("message-b", fingerprint(1), RelayBatchPresentationKind.DISMISSAL),
            )
            assertEquals(
                RelayBatchPresentationKind.NOTIFICATION,
                stage.find("message-b")?.presentationKind,
            )
            assertEquals(
                RelayBatchRecordResult.CONFLICT,
                stage.recordItem("message-b", fingerprint(2), RelayBatchPresentationKind.NOTIFICATION),
            )
            assertTrue(requireNotNull(stage.find("message-b")).conflict)
            assertEquals(
                RelayBatchRecordResult.INSERTED,
                stage.recordItem("message-a", fingerprint(3), RelayBatchPresentationKind.DISMISSAL),
            )
            assertEquals(
                RelayBatchRecordResult.INSERTED,
                stage.recordItem("message-c", fingerprint(4), RelayBatchPresentationKind.NONE),
            )
            assertEquals(listOf("message-a"), stage.presentationPage(null, 1).map { it.messageId })
            assertEquals(listOf("message-b"), stage.presentationPage("message-a", 1).map { it.messageId })
            assertEquals(listOf("message-c"), stage.nonPresentationPage(null, 1).map { it.messageId })
            expectIllegalArgument {
                stage.recordItem("invalid-fingerprint", byteArrayOf(1), RelayBatchPresentationKind.NONE)
            }
            expectIllegalArgument { stage.presentationPage(null, OperationalStorageLimits.RELAY_BATCH_PAGE_MAX_ROWS + 1) }

            val exact = requireNotNull(stage.find("message-a"))
            assertFalse(stage.deleteExact(exact.copy(authenticatedFingerprint = fingerprint(9))))
            assertTrue(stage.deleteExact(exact))
            assertEquals(2, stage.clearAtDrainBoundary())
            assertTrue(stage.presentationPage(null, 1).isEmpty())
            assertEquals(
                setOf("message_id", "authenticated_fingerprint", "conflict", "presentation_kind"),
                tableColumns(database, "relay_batch_stage"),
            )
        }
    }

    @Test
    fun sealEnrollmentAndResponseCustodyAreNormalizedAndExact() = runBlocking {
        withDatabase { database ->
            val seal = database.sealDao()
            seal.replaceEnrollment(
                SealEnrollmentEntity(state = SealEnrollmentState.DISABLED, recoveryReasonCode = null, updatedAt = 1),
                null,
            )
            expectIllegalArgument {
                seal.replaceEnrollment(
                    SealEnrollmentEntity(state = SealEnrollmentState.ENROLLED, recoveryReasonCode = null, updatedAt = 2),
                    null,
                )
            }
            val enrollment = protectedSealEnrollment(1)
            seal.replaceEnrollment(
                SealEnrollmentEntity(state = SealEnrollmentState.ENROLLED, recoveryReasonCode = null, updatedAt = 2),
                enrollment,
            )
            assertTrue(
                requireNotNull(seal.readEnrollmentProtected()).payloadCiphertext
                    .contentEquals(enrollment.payloadCiphertext),
            )

            val request = sealRequest("seal-request")
            assertEquals(SealAcceptResult.STORED, seal.accept(request, sealPending("seal-request"), null, now = 2))
            val body = sealResponse("seal-request", SealResponsePayloadFormat.BODY, 3, 3)
            assertTrue(
                seal.recordOutcomeAndQueueResponse(
                    SealOutcomeTransition("seal-request", SealRequestOutcome.APPROVED, 3, body, null),
                ),
            )
            assertNull(seal.findPendingPayload("seal-request"))
            val prepared = sealResponse("seal-request", SealResponsePayloadFormat.PREPARED_ENVELOPE, 4, 4)
            assertEquals(SealResponsePrepareResult.UPDATED, seal.prepareResponse(body, prepared))
            assertEquals(SealResponsePrepareResult.STALE, seal.prepareResponse(body, prepared.copy(updatedAt = 5)))
            assertEquals(SealResponseCompleteResult.SENT, seal.completeResponse(prepared, 5))
            assertNull(seal.findResponseCustody("seal-request"))

            database.useWriterConnection { connection ->
                connection.executeSQL("DELETE FROM seal_enrollment WHERE singleton_id = 1")
            }
            assertNull(seal.readEnrollmentProtected())
        }
    }

    @Test
    fun screenReplayRunAndSshLifecycleCommandsRemainFailClosed() = runBlocking {
        withDatabase { database ->
            val screen = database.screenDao()
            screen.replaceAuthorizations(
                listOf(ScreenAuthorizedPeerEntity("peer-1", 1, 1)),
                ScreenSecurityStateEntity(
                    enabled = true,
                    replayHealth = ScreenReplayHealth.HEALTHY,
                    quarantineDigest = null,
                    quarantinedAt = null,
                    authorizationRevision = 1,
                    updatedAt = 1,
                ),
            )
            assertFalse("authorization_revision" in tableColumns(database, "screen_authorized_peer"))
            val session = fingerprint(5)
            val routing = fingerprint(6)
            assertEquals(ScreenReplayConsumeResult.CONSUMED, screen.consumeReplay(session, routing, 100, 2))
            assertEquals(ScreenReplayConsumeResult.DUPLICATE, screen.consumeReplay(session, routing, 100, 3))
            screen.quarantineReplay(fingerprint(7), 4)
            assertEquals(
                ScreenReplayConsumeResult.QUARANTINED,
                screen.consumeReplay(fingerprint(8), fingerprint(9), 100, 5),
            )

            val runs = database.runDao()
            val running = runState(1, RunPhaseToken.RUNNING, active = true, receivedAt = 1)
            assertEquals(RunCompareUpsertResult.INSERTED, runs.compareAndUpsert(running, null))
            assertEquals(1, runs.markStaleActiveInactive(2))
            expectIllegalArgument {
                runs.compareAndUpsert(runState(2, RunPhaseToken.COMPLETED, active = true, receivedAt = 2), null)
            }

            val ssh = database.sshKeyDao()
            val operational = candidate("key-1", SshLifecycleCandidatePurpose.OPERATIONAL, "ssh-operational-key-1", 1)
            val export = candidate("key-1", SshLifecycleCandidatePurpose.EXPORT, "ssh-export-key-1", 2)
            ssh.beginLifecycle(
                SshKeyLifecycleEntity(
                    providerKeyId = "key-1",
                    operationalAlias = operational.keyAlias,
                    storageKind = SshStorageKind.WRAPPED,
                    state = SshKeyLifecycleState.PROVISIONING,
                    createdAt = 1,
                    updatedAt = 1,
                ),
                listOf(operational, export),
            )
            assertEquals(2, ssh.findCandidates("key-1").size)
            ssh.finalizeWrappedProvisioning(
                key = sshKey("key-1"),
                operationalKey = SshOperationalKeyEntity(
                    "key-1",
                    operational.keyAlias,
                    operational.securityLevel,
                    SshUserVerificationToken.NONE,
                    strongBoxAttempted = false,
                    strongBoxFallback = false,
                    lastVerifiedAt = 2,
                ),
                wrappedMaterial = SshWrappedOperationalMaterialEntity(
                    "key-1",
                    operational.privateKeyCiphertext,
                    operational.privateKeyNonce,
                ),
                exportCopy = SshExportCopyEntity(
                    "key-1",
                    export.keyAlias,
                    export.privateKeyCiphertext,
                    export.privateKeyNonce,
                    export.securityLevel,
                    SshExportBackendToken.BEST_AVAILABLE,
                    SshExportAuthenticationToken.STRONG_BIOMETRIC_OR_DEVICE_CREDENTIAL_PER_USE,
                    strongBoxAttempted = false,
                    strongBoxFallback = false,
                    lastVerifiedAt = 2,
                ),
                nextProviderState = SshProviderStateEntity(
                    inventoryGeneration = "generation-1",
                    revision = 1,
                    updatedAt = 2,
                ),
            )
            assertNull(ssh.findLifecycle("key-1"))
            assertTrue(ssh.findWrappedOperationalMaterial("key-1") != null)
        }
    }

    @Test
    fun sshProviderResponseCustodyHasNoGenericRetryLifecycle() = runBlocking {
        withDatabase { database ->
            val requests = database.sshRequestDao()
            val request = sshProviderRequest("ssh-request")
            assertEquals(
                SshProviderAcceptResult.STORED,
                requests.acceptProviderRequest(request, sshProviderPending("ssh-request"), null, now = 2),
            )
            val body = sshProviderResponse("ssh-request", SshProviderResponsePayloadFormat.BODY, 3, 3)
            assertTrue(
                requests.recordProviderOutcomeAndQueueResponse(
                    SshProviderOutcomeTransition("ssh-request", SshProviderRequestOutcome.SIGNED, 3, body, null),
                ),
            )
            val prepared = sshProviderResponse(
                "ssh-request",
                SshProviderResponsePayloadFormat.PREPARED_ENVELOPE,
                4,
                4,
            )
            assertEquals(SshProviderResponsePrepareResult.UPDATED, requests.prepareProviderResponse(body, prepared))
            assertEquals(SshProviderResponseCompleteResult.SENT, requests.completeProviderResponse(prepared, 5))
            assertNull(requests.findProviderResponseCustody("ssh-request"))
            assertEquals(SshProviderRequestState.SENT, requests.findProviderRequest("ssh-request")?.state)
            assertEquals(
                setOf(
                    "request_id",
                    "payload_format",
                    "protection_scheme",
                    "protection_version",
                    "protection_key_ref",
                    "protection_generation",
                    "payload_codec_version",
                    "payload_ciphertext",
                    "payload_nonce",
                    "created_at",
                    "updated_at",
                ),
                tableColumns(database, "ssh_provider_response_custody"),
            )
            assertEquals(
                setOf("host_key_sha256", "first_approved_at", "last_approved_at"),
                tableColumns(database, "ssh_known_host"),
            )
        }
    }

    private suspend fun withDatabase(block: suspend (OperationalDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        ).setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun openFileDatabase(context: Context, name: String): OperationalDatabase =
        Room.databaseBuilder<OperationalDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private suspend fun tableColumns(database: OperationalDatabase, table: String): Set<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA table_info($table)") { statement ->
                buildSet { while (statement.step()) add(statement.getText(1)) }
            }
        }

    private suspend fun tableNames(database: OperationalDatabase): Set<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertTrue("expected IllegalArgumentException but was $thrown", thrown is IllegalArgumentException)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            thrown = failure
        }
        assertTrue("expected database operation to fail", thrown != null)
    }

    private fun maintenanceState(generation: Long, incarnationId: String, updatedAt: Long) = MaintenanceStateEntity(
        operationalGeneration = generation,
        storageIncarnationId = incarnationId,
        postCutoverWriteAt = null,
        lastIntegrityCheckAt = null,
        updatedAt = updatedAt,
    )

    private fun appPolicy(packageName: String) = AndroidAppPolicyEntity(
        packageName,
        true,
        false,
        0,
        false,
        false,
        false,
        null,
        1,
    )

    private fun fingerprint(marker: Int) = ByteArray(OperationalStorageLimits.SHA256_BYTES) { marker.toByte() }

    private fun handled(messageId: String, fingerprint: ByteArray, handledAt: Long) = MessageDedupEntity(
        messageId,
        fingerprint,
        MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
        handledAt,
    )

    private fun activity(eventId: String, occurredAt: Long) = ActivityEventEntity(
        eventId,
        occurredAt,
        occurredAt,
        ActivityFeature.NOTIFICATION,
        ActivityAction.APPLIED,
        ActivityDirection.INBOUND,
        ActivityOutcome.SUCCESS,
        "peer",
        "correlation",
        OperationalDeliveryMode.RELAY_DRAIN,
        1,
        byteArrayOf(1),
        null,
        1,
    )

    private fun receipt(
        messageId: String,
        fingerprintMarker: Int,
        eventId: String,
        occurredAt: Long,
        handledAt: Long = occurredAt,
        expectedGeneration: Long = 1,
        expectedIncarnationId: String = "feature-incarnation",
        feature: ActivityFeature = ActivityFeature.NOTIFICATION,
        action: ActivityAction = ActivityAction.APPLIED,
        outcome: ActivityOutcome = ActivityOutcome.SUCCESS,
    ): PreparedOperationalReceipt = PreparedOperationalReceipt.prepare(
        handled = handled(messageId, fingerprint(fingerprintMarker), handledAt),
        expectedOperationalGeneration = expectedGeneration,
        expectedStorageIncarnationId = expectedIncarnationId,
        activity = activity(eventId, occurredAt).copy(
            feature = feature,
            semanticAction = action,
            outcome = outcome,
        ),
    )

    private fun receiptWithoutActivity(
        messageId: String,
        fingerprintMarker: Int,
        handledAt: Long,
    ): PreparedOperationalReceipt = PreparedOperationalReceipt.prepare(
        handled = handled(messageId, fingerprint(fingerprintMarker), handledAt),
        expectedOperationalGeneration = 1,
        expectedStorageIncarnationId = "feature-incarnation",
        activity = null,
    )

    private fun protectedSealEnrollment(generation: Long) = SealEnrollmentProtectedEntity(
        protectionScheme = ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        protectionVersion = 1,
        protectionKeyRef = "seal-enrollment-generation-$generation",
        protectionGeneration = generation,
        payloadCodecVersion = 1,
        payloadCiphertext = ByteArray(32) { generation.toByte() },
        payloadNonce = ByteArray(12) { (generation + 1).toByte() },
    )

    private fun sealRequest(requestId: String) = SealRequestEntity(
        requestId,
        "requester",
        "requester",
        fingerprint(10),
        1,
        100,
        fingerprint(11),
        SealObjectKind.GIT_COMMIT,
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "seal-display-key",
        1,
        1,
        ByteArray(32) { 1 },
        ByteArray(12) { 2 },
        false,
        SealRequestState.PENDING_REVIEW,
        null,
        null,
        1,
        1,
    )

    private fun sealPending(requestId: String) = SealPendingPayloadEntity(
        requestId,
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "seal-pending-key",
        1,
        1,
        ByteArray(32) { 3 },
        ByteArray(12) { 4 },
        1,
        1,
    )

    private fun sealResponse(
        requestId: String,
        format: SealResponsePayloadFormat,
        fill: Int,
        updatedAt: Long,
    ) = SealResponseCustodyEntity(
        requestId,
        format,
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "seal-response-key",
        1,
        1,
        ByteArray(32) { fill.toByte() },
        ByteArray(12) { (fill + 1).toByte() },
        3,
        updatedAt,
    )

    private fun runState(revision: Long, phase: RunPhaseToken, active: Boolean, receivedAt: Long): RunStateEntity {
        val payload = byteArrayOf(revision.toByte())
        return RunStateEntity(
            "host-1",
            "run-1",
            revision,
            phase,
            -1,
            active,
            revision,
            if (phase in setOf(RunPhaseToken.COMPLETED, RunPhaseToken.FAILED_TO_START)) revision else null,
            receivedAt,
            payload,
            sha256(payload),
        )
    }

    private fun candidate(
        providerKeyId: String,
        purpose: SshLifecycleCandidatePurpose,
        alias: String,
        fill: Byte,
    ) = SshKeyLifecycleCandidateEntity(
        providerKeyId,
        purpose,
        alias,
        ByteArray(16) { fill },
        ByteArray(12) { fill },
        SshSecurityLevelToken.TRUSTED_ENVIRONMENT,
    )

    private fun sshKey(providerKeyId: String): SshKeyEntity {
        val blob = byteArrayOf(1)
        return SshKeyEntity(
            providerKeyId,
            blob,
            sha256(blob),
            SshKeyAlgorithmToken.SSH_ED25519,
            "Test key",
            SshKeyOriginToken.GENERATED,
            SshApprovalPolicyToken.ALWAYS_ASK,
            1,
            null,
            2,
        )
    }

    private fun sshProviderRequest(requestId: String) = SshProviderRequestEntity(
        requestId,
        SshProviderRequestKind.SIGN,
        "requester",
        fingerprint(12),
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "ssh-history-key",
        1,
        1,
        ByteArray(32) { 1 },
        ByteArray(12) { 2 },
        SshProviderRequestState.PENDING_REVIEW,
        null,
        null,
        100,
        1,
        1,
    )

    private fun sshProviderPending(requestId: String) = SshProviderPendingPayloadEntity(
        requestId,
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "ssh-request-key",
        1,
        1,
        ByteArray(32) { 3 },
        ByteArray(12) { 4 },
        1,
    )

    private fun sshProviderResponse(
        requestId: String,
        format: SshProviderResponsePayloadFormat,
        fill: Int,
        updatedAt: Long,
    ) = SshProviderResponseCustodyEntity(
        requestId,
        format,
        ProtectedBlobSchemes.ANDROID_KEYSTORE_AES_GCM,
        1,
        "ssh-response-key",
        1,
        1,
        ByteArray(32) { fill.toByte() },
        ByteArray(12) { (fill + 1).toByte() },
        3,
        updatedAt,
    )

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

}
