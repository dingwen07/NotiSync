package net.extrawdw.apps.notisync.messaging.inbound

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import net.extrawdw.apps.notisync.data.relay.RelayStableCode
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayHealth
import net.extrawdw.apps.notisync.data.storage.operational.ScreenSecurityStateEntity
import net.extrawdw.apps.notisync.messaging.AuthenticatedInboundDelivery
import net.extrawdw.apps.notisync.messaging.DecodedInboundPayload
import net.extrawdw.apps.notisync.messaging.InboundDeliveryMode
import net.extrawdw.apps.notisync.messaging.PlannedInboundCommand
import net.extrawdw.apps.notisync.messaging.ProtocolLeaf
import net.extrawdw.apps.notisync.messaging.routingDescriptor
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.AssetSync
import net.extrawdw.notisync.protocol.AssetSyncKind
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ScreenMirrorAction
import net.extrawdw.notisync.protocol.ScreenMirrorSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomOperationalInboundDispatchAndroidTest {
    @Test
    fun notificationOwnerCommitsMirrorActivityAndHandledBeforePresentationAndReplaysSafely() = runBlocking {
        withDatabase { database ->
            database.initializeDispatchState()
            val presentations = AtomicInteger()
            val effects = object : OperationalInboundEffects {
                override suspend fun presentNotification(
                    notification: CapturedNotification,
                    forceSilent: Boolean,
                ): InboundEffectResult {
                    presentations.incrementAndGet()
                    return InboundEffectResult.COMPLETED
                }

                override suspend fun dismissNotification(
                    event: net.extrawdw.notisync.protocol.DismissEvent,
                ): InboundEffectResult = InboundEffectResult.NO_OP

                override suspend fun performAction(
                    event: net.extrawdw.notisync.protocol.ActionEvent,
                ): InboundEffectResult = InboundEffectResult.NO_OP
            }
            val dispatch = RoomOperationalInboundDispatch(database, effects)
            val command = notificationCommand()
            val receipt = InboundOwnerReceipt(
                messageId = "message-1",
                authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 1 }),
                continuity = RelayOperationalContinuity(1, "dispatch-test"),
                handledAt = 11,
            )

            assertEquals(
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED),
                dispatch.dispatch(command, receipt, InboundPresentationPolicy.COMPLETE_BEFORE_ACK),
            )
            assertEquals(1, presentations.get())
            assertEquals(10L, database.mirrorLifecycleDao().findLifecycle("source", "source-key")?.postTime)
            assertNotNull(database.relayDao().findHandled("message-1"))
            assertEquals(1, database.activityDao().observeNewest(10).first().size)

            // The owner transaction, not a second generic finalizer, controls the replay result. A direct
            // re-dispatch is a duplicate and cannot repeat the external effect; the explicit replay port is the
            // only path that re-presents an exact handled notification.
            assertEquals(
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE),
                dispatch.dispatch(command, receipt, InboundPresentationPolicy.COMPLETE_BEFORE_ACK),
            )
            assertEquals(1, presentations.get())
            assertEquals(
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE),
                dispatch.reconcile(command, receipt.copy(handledAt = 12)),
            )
            assertEquals(2, presentations.get())
        }
    }

    @Test
    fun compatibilityEffectSucceedsBeforeHandledReceiptIsWritten() = runBlocking {
        withDatabase { database ->
            database.initializeDispatchState()
            var receiptSeenDuringEffect = false
            val compatibility = OperationalInboundCompatibilityEffects { command ->
                assertNull(database.relayDao().findHandled(command.delivery.messageId))
                receiptSeenDuringEffect = true
                InboundEffectResult.COMPLETED
            }
            val dispatch = RoomOperationalInboundDispatch(
                database = database,
                effects = OperationalInboundEffects.NO_OP,
                compatibilityEffects = compatibility,
            )

            assertEquals(
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.APPLIED),
                dispatch.dispatch(
                    compatibilityCommand(),
                    dispatchReceipt("compat-1"),
                    InboundPresentationPolicy.COMPLETE_BEFORE_ACK,
                ),
            )
            assertTrue(receiptSeenDuringEffect)
            assertNotNull(database.relayDao().findHandled("compat-1"))
        }
    }

    @Test
    fun compatibilityEffectFailureDoesNotWriteHandledReceipt() = runBlocking {
        withDatabase { database ->
            database.initializeDispatchState()
            val dispatch = RoomOperationalInboundDispatch(
                database = database,
                effects = OperationalInboundEffects.NO_OP,
                compatibilityEffects = OperationalInboundCompatibilityEffects {
                    InboundEffectResult.RETRY_REQUIRED
                },
            )

            assertEquals(
                InboundOwnerCommitResult.RetryRequired(RelayStableCode.of("compatibility_effect_retry")),
                dispatch.dispatch(
                    compatibilityCommand(),
                    dispatchReceipt("compat-1"),
                    InboundPresentationPolicy.COMPLETE_BEFORE_ACK,
                ),
            )
            assertNull(database.relayDao().findHandled("compat-1"))
        }
    }

    @Test
    fun compatibilityEffectCancellationPropagatesWithoutWritingHandledReceipt() = runBlocking {
        withDatabase { database ->
            database.initializeDispatchState()
            val cancellation = CancellationException("compatibility effect cancelled")
            val dispatch = RoomOperationalInboundDispatch(
                database = database,
                effects = OperationalInboundEffects.NO_OP,
                compatibilityEffects = OperationalInboundCompatibilityEffects {
                    throw cancellation
                },
            )

            try {
                dispatch.dispatch(
                    compatibilityCommand(),
                    dispatchReceipt("compat-1"),
                    InboundPresentationPolicy.COMPLETE_BEFORE_ACK,
                )
                throw AssertionError("CancellationException expected")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertNull(database.relayDao().findHandled("compat-1"))
        }
    }

    @Test
    fun screenOwnerRunsCompatibilityEffectAfterCommitAndReplaysItOnExactDuplicate() = runBlocking {
        withDatabase { database ->
            database.initializeDispatchState()
            database.screenDao().replaceSecurityState(
                ScreenSecurityStateEntity(
                    enabled = true,
                    replayHealth = ScreenReplayHealth.HEALTHY,
                    quarantineDigest = null,
                    quarantinedAt = null,
                    authorizationRevision = 0,
                    updatedAt = 1,
                ),
            )
            var effectCalls = 0
            var receiptWasVisibleToEffect = false
            val compatibility = OperationalInboundCompatibilityEffects {
                effectCalls += 1
                receiptWasVisibleToEffect = database.relayDao().findHandled("screen-compat") != null
                if (effectCalls == 1) InboundEffectResult.RETRY_REQUIRED else InboundEffectResult.COMPLETED
            }
            val dispatch = RoomOperationalInboundDispatch(
                database = database,
                effects = OperationalInboundEffects.NO_OP,
                compatibilityEffects = compatibility,
            )
            val command = screenRequestCommand()
            val receipt = dispatchReceipt("screen-compat")

            assertEquals(
                InboundOwnerCommitResult.RetryRequired(RelayStableCode.of("compatibility_effect_retry")),
                dispatch.dispatch(command, receipt, InboundPresentationPolicy.COMPLETE_BEFORE_ACK),
            )
            assertTrue(receiptWasVisibleToEffect)
            assertNotNull(database.relayDao().findHandled("screen-compat"))

            assertEquals(
                InboundOwnerCommitResult.AcknowledgementReady(RelayHandledDisposition.DUPLICATE),
                dispatch.dispatch(
                    command,
                    receipt,
                    InboundPresentationPolicy.COMPLETE_BEFORE_ACK,
                ),
            )
            assertEquals(2, effectCalls)
        }
    }

    private fun notificationCommand(): PlannedInboundCommand {
        val delivery = AuthenticatedInboundDelivery(
            messageId = "message-1",
            messageType = MessageType.NOTIFICATION,
            senderId = ClientId("source"),
            senderOwnDevice = true,
            signerEpoch = 1,
            signedSequence = 1,
            signedCreatedAt = 10,
            recipientEpoch = 1,
            encodedBody = byteArrayOf(),
            acceptedAt = 11,
            deliveryMode = InboundDeliveryMode.RELAY_DRAIN,
            forceSilent = false,
        )
        val notification = CapturedNotification(
            sourceClientId = ClientId("source"),
            sourceKey = "source-key",
            packageName = "net.example",
            appLabel = "Example",
            postTime = 10,
        )
        return PlannedInboundCommand(
            delivery = delivery,
            descriptor = ProtocolLeaf.Notification.routingDescriptor(),
            payload = DecodedInboundPayload.Notification(notification),
        )
    }

    private fun compatibilityCommand(): PlannedInboundCommand {
        val delivery = AuthenticatedInboundDelivery(
            messageId = "compat-1",
            messageType = MessageType.DATA_SYNC,
            senderId = ClientId("source"),
            senderOwnDevice = true,
            signerEpoch = 1,
            signedSequence = 1,
            signedCreatedAt = 10,
            recipientEpoch = 1,
            encodedBody = byteArrayOf(),
            acceptedAt = 11,
            deliveryMode = InboundDeliveryMode.RELAY_DRAIN,
            forceSilent = false,
        )
        val data = DataSync(
            kind = DataSyncKind.ASSET,
            asset = AssetSync(AssetSyncKind.ASSET_MISSING, emptyList()),
        )
        return PlannedInboundCommand(
            delivery = delivery,
            descriptor = ProtocolLeaf.Asset(AssetSyncKind.ASSET_MISSING).routingDescriptor(),
            payload = DecodedInboundPayload.Data(data),
        )
    }

    private fun screenRequestCommand(): PlannedInboundCommand {
        val delivery = AuthenticatedInboundDelivery(
            messageId = "screen-compat",
            messageType = MessageType.DATA_SYNC,
            senderId = ClientId("requester"),
            senderOwnDevice = true,
            signerEpoch = 1,
            signedSequence = 1,
            signedCreatedAt = 10,
            recipientEpoch = 1,
            encodedBody = byteArrayOf(),
            acceptedAt = 11,
            deliveryMode = InboundDeliveryMode.RELAY_DRAIN,
            forceSilent = false,
        )
        val request = ScreenMirrorSync(
            action = ScreenMirrorAction.REQUEST,
            sessionId = "screen-session",
            requesterPeerId = ClientId("requester"),
            sourcePeerId = ClientId("source"),
            issuedAt = 10,
            expiresAt = 100,
            routingToken = ByteArray(16) { it.toByte() },
        )
        return PlannedInboundCommand(
            delivery = delivery,
            descriptor = ProtocolLeaf.Screen(ScreenMirrorAction.REQUEST).routingDescriptor(),
            payload = DecodedInboundPayload.Data(
                DataSync(kind = DataSyncKind.SCREEN_MIRRORING, screenMirror = request),
            ),
        )
    }

    private fun dispatchReceipt(messageId: String): InboundOwnerReceipt = InboundOwnerReceipt(
        messageId = messageId,
        authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 1 }),
        continuity = RelayOperationalContinuity(1, "dispatch-test"),
        handledAt = 11,
    )

    private suspend fun OperationalDatabase.initializeDispatchState() {
        profileDao().initializeMaintenance(
            MaintenanceStateEntity(
                operationalGeneration = 1,
                storageIncarnationId = "dispatch-test",
                postCutoverWriteAt = null,
                lastIntegrityCheckAt = null,
                updatedAt = 1,
            ),
        )
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
}
