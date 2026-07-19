package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.ResolvedSend
import net.extrawdw.notisync.daemon.StaleSendSequenceException
import net.extrawdw.notisync.daemon.SubmissionConflictException
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.QueuePolicy
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationBridgeStorageTest : StorageTestSupport() {
    @Test
    fun `registrations persist and effective capabilities use protocol order`() {
        val layout = layout()
        val clock = MutableClock(100)
        val store = store(layout, clock, "unused")

        val first = store.register(
            "zeta",
            ApplicationRegistrationRequest(
                displayName = "Zeta",
                capabilities = setOf(Capability.PUBLISH_RUNS, Capability.CAPTURE),
            ),
        )
        assertTrue(first.capabilitiesChanged)
        clock.millis = 200
        val metadataOnly = store.register(
            "zeta",
            ApplicationRegistrationRequest(
                displayName = "Zeta renamed",
                capabilities = setOf(Capability.CAPTURE, Capability.PUBLISH_RUNS),
            ),
        )
        assertFalse(metadataOnly.capabilitiesChanged)
        store.register("alpha", ApplicationRegistrationRequest("Alpha"))

        val recreated = store(layout, clock, "unused-2")
        assertEquals(listOf("alpha", "zeta"), recreated.list().applications.map { it.applicationId })
        assertEquals(
            listOf(
                Capability.CAPTURE,
                Capability.FOREGROUND_CONNECTION,
                Capability.CAPABILITY_ROUTING_V1,
                Capability.PUBLISH_RUNS,
            ),
            recreated.effectiveCapabilities(),
        )
        assertEquals(200, recreated.find("zeta")!!.updatedAtEpochMillis)
    }

    @Test
    fun `overlapping capability claims remain effective until the final claimant is removed`() {
        val store = store(layout(), MutableClock(1_000), "unused")
        val first = store.register(
            "first",
            ApplicationRegistrationRequest("First", capabilities = setOf(Capability.CAPTURE)),
        )
        val overlapping = store.register(
            "second",
            ApplicationRegistrationRequest("Second", capabilities = setOf(Capability.CAPTURE)),
        )

        assertTrue(first.capabilitiesChanged)
        assertFalse(overlapping.capabilitiesChanged)
        assertTrue(store.delete("first"))
        assertEquals(
            listOf(
                Capability.CAPTURE,
                Capability.FOREGROUND_CONNECTION,
                Capability.CAPABILITY_ROUTING_V1,
            ),
            store.effectiveCapabilities(),
        )

        assertTrue(store.delete("second"))
        assertEquals(
            listOf(Capability.FOREGROUND_CONNECTION, Capability.CAPABILITY_ROUTING_V1),
            store.effectiveCapabilities(),
        )
    }

    @Test
    fun `submission idempotency survives dispatch and recreation while conflicting reuse is rejected`() {
        val layout = layout()
        val clock = MutableClock(1_000)
        val first = store(layout, clock, "message-1")
        first.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        val send = resolved(body = byteArrayOf(1, 2), submissionId = "submission-1")

        val accepted = first.accept(send)
        assertEquals("message-1", accepted.messageId)
        assertTrue(first.checkpoint("message-1"))
        assertEquals(0, first.pendingCount())

        clock.millis = 2_000
        val recreated = store(layout, clock, "message-2")
        assertEquals(accepted, recreated.accept(send))
        assertEquals(0, recreated.pendingCount())

        assertThrows(SubmissionConflictException::class.java) {
            recreated.accept(send.copy(body = byteArrayOf(9)))
        }
        assertEquals(0, recreated.pendingCount())
    }

    @Test
    fun `submission ids are scoped to applications and queue policy validation is atomic`() {
        val store = store(layout(), MutableClock(1_000), "nsrun-message", "other-message")
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        store.register("other", ApplicationRegistrationRequest("Other"))
        val nsrun = resolved(submissionId = "shared")
        val other = nsrun.copy(applicationId = "other")

        assertEquals("nsrun-message", store.accept(nsrun).messageId)
        assertEquals("other-message", store.accept(other).messageId)
        assertThrows(IllegalArgumentException::class.java) {
            store.accept(
                listOf(
                    resolved(queuePolicy = QueuePolicy(streamKey = "missing-sequence")),
                    resolved(body = byteArrayOf(2)),
                ),
            )
        }
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun `pending message id and body survive daemon repository recreation`() {
        val layout = layout()
        val first = store(layout, MutableClock(1_000), "stable-message")
        first.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        first.accept(resolved(body = byteArrayOf(7, 8, 9)))

        val pending = store(layout, MutableClock(2_000), "unused").peekConsecutive().single()
        assertEquals("stable-message", pending.messageId)
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(pending.body))
    }

    @Test
    fun `multi-record acceptance rolls back queue changes and sequence state on one stale record`() {
        val layout = layout()
        val store = store(layout, MutableClock(1_000), "old", "new-a", "new-b", "after")
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        store.accept(
            resolved(
                body = byteArrayOf(1),
                queuePolicy = QueuePolicy(streamKey = "run", sequence = 5, coalesceKey = "snapshot"),
            ),
        )

        assertThrows(StaleSendSequenceException::class.java) {
            store.accept(
                listOf(
                    resolved(
                        body = byteArrayOf(2),
                        queuePolicy = QueuePolicy(streamKey = "other", sequence = 1, coalesceKey = "snapshot"),
                    ),
                    resolved(
                        body = byteArrayOf(3),
                        queuePolicy = QueuePolicy(streamKey = "run", sequence = 5),
                    ),
                ),
            )
        }

        assertEquals(listOf("old"), store.peekConsecutive().map { it.messageId })
        // The first record's stream sequence was also rolled back, so sequence 1 is still acceptable.
        store.accept(resolved(queuePolicy = QueuePolicy(streamKey = "other", sequence = 1)))
        assertEquals(2, store.pendingCount())
    }

    @Test
    fun `coalesce and supersede affect only pending records from the same application`() {
        val layout = layout()
        val store = store(
            layout,
            MutableClock(1_000),
            "nsrun-snapshot",
            "other-snapshot",
            "nsrun-summary",
            "nsrun-replacement",
            "terminal",
        )
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        store.register("other", ApplicationRegistrationRequest("Other"))
        store.accept(resolved(body = byteArrayOf(1), queuePolicy = QueuePolicy(coalesceKey = "snapshot")))
        store.accept(
            resolved(body = byteArrayOf(2), queuePolicy = QueuePolicy(coalesceKey = "snapshot"))
                .copy(applicationId = "other"),
        )
        store.accept(resolved(body = byteArrayOf(3), queuePolicy = QueuePolicy(coalesceKey = "summary")))
        store.accept(resolved(body = byteArrayOf(4), queuePolicy = QueuePolicy(coalesceKey = "snapshot")))
        assertEquals(
            listOf("other-snapshot", "nsrun-summary", "nsrun-replacement"),
            store.peekConsecutive().map { it.messageId },
        )

        store.accept(
            resolved(
                body = byteArrayOf(5),
                queuePolicy = QueuePolicy(
                    coalesceKey = "terminal",
                    supersedeKeys = setOf("summary", "snapshot"),
                ),
            ),
        )
        assertEquals(listOf("other-snapshot", "terminal"), allPending(store))
    }

    @Test
    fun `outbox returns only the maximal consecutive dispatch group and checkpoints individually`() {
        val layout = layout()
        val store = store(layout, MutableClock(1_000), "data-1", "data-2", "notification", "data-3")
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        store.accept(
            listOf(
                resolved(body = byteArrayOf(1)),
                resolved(body = byteArrayOf(2)),
                resolved(
                    body = byteArrayOf(3),
                    messageType = MessageType.NOTIFICATION,
                    urgency = Urgency.HIGH,
                ),
                resolved(body = byteArrayOf(4)),
            ),
        )

        assertEquals(listOf("data-1", "data-2"), store.peekConsecutive().map { it.messageId })
        assertTrue(store.checkpoint("data-1"))
        assertEquals(listOf("data-2"), store.peekConsecutive().map { it.messageId })
        assertTrue(store.checkpoint("missing"))
    }

    @Test
    fun `deleting an application clears its pending idempotency and sequence state`() {
        val layout = layout()
        val store = store(layout, MutableClock(1_000), "before", "after")
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        val send = resolved(
            submissionId = "same-submission",
            queuePolicy = QueuePolicy(streamKey = "run", sequence = 7, coalesceKey = "snapshot"),
        )
        store.accept(send)

        assertTrue(store.delete("nsrun"))
        assertFalse(store.delete("nsrun"))
        assertEquals(0, store.pendingCount())
        store.register("nsrun", ApplicationRegistrationRequest("NotiSync Run"))
        assertEquals("after", store.accept(send).messageId)
    }

    @Test
    fun `profile publication state persists and validates pending monotonic revision`() {
        val layout = layout()
        val store = store(layout, MutableClock(1_000), "unused")
        val updated = store.updateProfilePublicationState {
            ApplicationProfilePublicationState(
                cardCreatedAtFloorEpochMillis = 500,
                profileFingerprint = "sha256:abc",
                profileUpdatedAtEpochMillis = 1_000,
                publicationRevision = 3,
                pendingPublicationRevision = 3,
            )
        }
        assertEquals(updated, store(layout, MutableClock(2_000), "unused-2").profilePublicationState())

        assertThrows(IllegalArgumentException::class.java) {
            store.updateProfilePublicationState {
                it.copy(publicationRevision = 2, pendingPublicationRevision = 3)
            }
        }
        assertEquals(updated, store.profilePublicationState())
    }

    @Test
    fun `schema one fails with manual database-only removal guidance and retains trust`() {
        val layout = layout()
        val trust = FileTrustPersistence(layout)
        trust.write(mapOf("entries" to "trusted"))
        SecureFileSystem().atomicWrite(layout.databaseFile, "{\"schemaVersion\":1}".encodeToByteArray())

        val failure = assertThrows(IllegalStateException::class.java) {
            DaemonDatabaseRepository(layout)
        }
        assertTrue(failure.message!!.contains("Remove only ${layout.databaseFile} manually"))
        assertEquals("trusted", FileTrustPersistence(layout).read("entries"))
        assertTrue(Files.exists(layout.trustStateFile))
    }

    private fun resolved(
        body: ByteArray = byteArrayOf(1),
        messageType: MessageType = MessageType.DATA_SYNC,
        urgency: Urgency = Urgency.NORMAL,
        submissionId: String? = null,
        queuePolicy: QueuePolicy? = null,
    ) = ResolvedSend(
        applicationId = "nsrun",
        messageType = messageType,
        body = body,
        scope = Recipients.OwnMesh,
        urgency = urgency,
        signWith = SignerSelection.OPERATIONAL,
        submissionId = submissionId,
        queuePolicy = queuePolicy,
    )

    private fun store(
        layout: DaemonStorageLayout,
        clock: Clock,
        vararg ids: String,
    ): PersistentApplicationBridgeStore {
        val pendingIds = ArrayDeque(ids.toList())
        return PersistentApplicationBridgeStore(
            database = DaemonDatabaseRepository(layout, clock),
            clock = clock,
            messageIdFactory = { pendingIds.removeFirst() },
        )
    }

    private fun allPending(store: PersistentApplicationBridgeStore): List<String> {
        val result = mutableListOf<String>()
        while (store.pendingCount() > 0) {
            val next = store.peekConsecutive().first()
            result += next.messageId
            store.checkpoint(next.messageId)
        }
        return result
    }

    private fun layout() = DaemonStorageLayout(temporaryDirectory.resolve(".notisync"))

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }
}
