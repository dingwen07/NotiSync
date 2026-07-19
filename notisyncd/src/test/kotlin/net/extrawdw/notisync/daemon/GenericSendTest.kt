package net.extrawdw.notisync.daemon

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.QueuePolicy
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSendResolverTest {
    private val applications = applications("nsrun", "other")

    @Test
    fun `type defaults resolve to explicit audience urgency and operational signer`() {
        val resolver = GenericSendResolver(applications, ActionOriginPolicy { false })

        val notification = resolver.resolveAll(listOf(request(MessageType.NOTIFICATION))).single()
        assertEquals(
            Recipients.OwnMeshFiltered(requiredCapabilities = setOf(Capability.DISPLAY)),
            notification.scope,
        )
        assertEquals(Urgency.HIGH, notification.urgency)
        assertEquals(SignerSelection.OPERATIONAL, notification.signWith)

        val dismissal = resolver.resolveAll(listOf(request(MessageType.DISMISSAL))).single()
        assertEquals(
            Recipients.OwnMeshFiltered(requiredCapabilities = setOf(Capability.DISMISS_SYNC)),
            dismissal.scope,
        )
        assertEquals(Urgency.NORMAL, dismissal.urgency)
        assertEquals(SignerSelection.OPERATIONAL, dismissal.signWith)

        val dataSync = resolver.resolveAll(listOf(request(MessageType.DATA_SYNC))).single()
        assertEquals(Recipients.OwnMesh, dataSync.scope)
        assertEquals(Urgency.NORMAL, dataSync.urgency)
        assertEquals(SignerSelection.OPERATIONAL, dataSync.signWith)
    }

    @Test
    fun `action body selects only its trusted own capture origin`() {
        val source = ClientId("source")
        val action = ActionEvent(
            sourceClientId = source,
            sourceKey = "notification-key",
            kind = ActionKind.TAP,
            actedAt = 123,
        )
        val resolver = GenericSendResolver(applications, ActionOriginPolicy { it == source })

        val resolved = resolver.resolveAll(
            listOf(request(MessageType.ACTION, ProtocolCodec.encodeToCbor(action))),
        ).single()

        assertEquals(Recipients.Only(source), resolved.scope)
        assertEquals(Urgency.HIGH, resolved.urgency)
        assertEquals(SignerSelection.OPERATIONAL, resolved.signWith)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveAll(
                listOf(
                    request(
                        MessageType.ACTION,
                        ProtocolCodec.encodeToCbor(action),
                        scope = Recipients.Only(ClientId("somewhere-else")),
                    ),
                ),
            )
        }
    }

    @Test
    fun `action rejects malformed CBOR and a source without trusted own CAPTURE`() {
        val source = ClientId("source")
        val encoded = ProtocolCodec.encodeToCbor(
            ActionEvent(source, "key", ActionKind.TAP, actedAt = 123),
        )

        assertThrows(IllegalArgumentException::class.java) {
            GenericSendResolver(applications, ActionOriginPolicy { true })
                .resolveAll(listOf(request(MessageType.ACTION, byteArrayOf(1))))
        }
        val rejection = assertThrows(IllegalArgumentException::class.java) {
            GenericSendResolver(applications, ActionOriginPolicy { false })
                .resolveAll(listOf(request(MessageType.ACTION, encoded)))
        }
        assertTrue(rejection.message!!.contains("trusted own peer with CAPTURE"))
    }

    @Test
    fun `identity signing is selected only by the explicit request field`() {
        val resolver = GenericSendResolver(applications, ActionOriginPolicy { false })

        val implicit = resolver.resolveAll(listOf(request(MessageType.NOTIFICATION))).single()
        val explicit = resolver.resolveAll(
            listOf(request(MessageType.NOTIFICATION, signWith = SignerSelection.IDENTITY)),
        ).single()

        assertEquals(SignerSelection.OPERATIONAL, implicit.signWith)
        assertEquals(SignerSelection.IDENTITY, explicit.signWith)
    }

    @Test
    fun `high data sync requires a routed wakeable filtering audience`() {
        val resolver = GenericSendResolver(applications, ActionOriginPolicy { false })
        val required = setOf(
            Capability.DISPLAY,
            Capability.BACKGROUND_WAKE,
            Capability.PUSH_FILTERING,
            Capability.RECEIVE_RUNS,
        )
        val validScope = Recipients.OwnMeshFiltered(
            requiredCapabilities = required,
            requireCapabilityRoutingV1 = true,
        )

        val resolved = resolver.resolveAll(
            listOf(request(MessageType.DATA_SYNC, urgency = Urgency.HIGH, scope = validScope)),
        ).single()
        assertEquals(validScope, resolved.scope)
        assertEquals(Urgency.HIGH, resolved.urgency)

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveAll(listOf(request(MessageType.DATA_SYNC, urgency = Urgency.HIGH)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveAll(
                listOf(
                    request(
                        MessageType.DATA_SYNC,
                        urgency = Urgency.HIGH,
                        scope = validScope.copy(requireCapabilityRoutingV1 = false),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveAll(
                listOf(
                    request(
                        MessageType.DATA_SYNC,
                        urgency = Urgency.HIGH,
                        scope = validScope.copy(
                            requiredCapabilities = required - Capability.PUSH_FILTERING,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `a request is nonempty registered and belongs to one application`() {
        val resolver = GenericSendResolver(applications, ActionOriginPolicy { false })

        assertThrows(IllegalArgumentException::class.java) { resolver.resolveAll(emptyList()) }
        assertThrows(ApplicationNotRegisteredException::class.java) {
            resolver.resolveAll(listOf(request(MessageType.DATA_SYNC, applicationId = "missing")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveAll(
                listOf(
                    request(MessageType.DATA_SYNC, applicationId = "nsrun"),
                    request(MessageType.DATA_SYNC, applicationId = "other"),
                ),
            )
        }

        assertEquals(
            listOf("nsrun", "nsrun"),
            resolver.resolveAll(
                listOf(request(MessageType.DATA_SYNC), request(MessageType.NOTIFICATION)),
            ).map { it.applicationId },
        )
    }

    private fun request(
        messageType: MessageType,
        body: ByteArray = byteArrayOf(7),
        applicationId: String = "nsrun",
        scope: Recipients? = null,
        urgency: Urgency? = null,
        signWith: SignerSelection? = null,
    ) = SendRequest(
        applicationId = applicationId,
        messageType = messageType,
        body = Base64.getEncoder().encodeToString(body),
        scope = scope,
        urgency = urgency,
        signWith = signWith,
    )
}

class GenericSendDispatcherTest {
    @Test
    fun `dispatch preserves global order and batches only maximal consecutive groups`() = runBlocking {
        val outbox = MemoryOutbox(
            pending("data-1"),
            pending("data-2"),
            pending("notification", MessageType.NOTIFICATION, urgency = Urgency.HIGH),
            pending("data-3"),
        )
        val calls = mutableListOf<List<String>>()
        val finished = CompletableDeferred<Unit>()
        val sender = GenericBatchSender { batch, onAccepted ->
            calls += batch.map { it.messageId }
            batch.forEach(onAccepted)
            if (outbox.pendingCount() == 0) finished.complete(Unit)
        }

        GenericSendDispatcher(
            outbox = outbox,
            sender = sender,
            logger = quietLogger(),
            retryDelayMillis = 1,
        ).use { dispatcher ->
            dispatcher.start()
            dispatcher.wake()
            withTimeout(5_000) { finished.await() }
        }

        assertEquals(
            listOf(
                listOf("data-1", "data-2"),
                listOf("notification"),
                listOf("data-3"),
            ),
            calls,
        )
        assertEquals(listOf("data-1", "data-2", "notification", "data-3"), outbox.checkpoints)
    }

    @Test
    fun `a partial strict failure checkpoints accepted prefix and retries only its suffix`() = runBlocking {
        val outbox = MemoryOutbox(pending("first"), pending("second"), pending("third"))
        val calls = mutableListOf<List<String>>()
        val attempts = AtomicInteger()
        val finished = CompletableDeferred<Unit>()
        val sender = GenericBatchSender { batch, onAccepted ->
            calls += batch.map { it.messageId }
            if (attempts.getAndIncrement() == 0) {
                onAccepted(batch.first())
                throw IllegalStateException("broker rejected the second item")
            }
            batch.forEach(onAccepted)
            finished.complete(Unit)
        }

        GenericSendDispatcher(
            outbox = outbox,
            sender = sender,
            logger = quietLogger(),
            retryDelayMillis = 1,
        ).use { dispatcher ->
            dispatcher.start()
            dispatcher.wake()
            withTimeout(5_000) { finished.await() }
        }

        assertEquals(listOf(listOf("first", "second", "third"), listOf("second", "third")), calls)
        assertEquals(listOf("first", "second", "third"), outbox.checkpoints)
        assertEquals(0, outbox.pendingCount())
    }

    @Test
    fun `coalescing waits for a failed in-flight batch before replacing it`() = runBlocking {
        val snapshotPolicy = QueuePolicy(coalesceKey = "run-snapshot")
        val outbox = MemoryOutbox(pending("old", queuePolicy = snapshotPolicy))
        val calls = mutableListOf<List<String>>()
        val inFlight = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val sender = GenericBatchSender { batch, onAccepted ->
            calls += batch.map { it.messageId }
            if (attempts.getAndIncrement() == 0) {
                inFlight.complete(Unit)
                releaseFailure.await()
                throw IllegalStateException("offline")
            }
            batch.forEach(onAccepted)
            finished.complete(Unit)
        }

        GenericSendDispatcher(
            outbox = outbox,
            sender = sender,
            logger = quietLogger(),
            retryDelayMillis = 1,
        ).use { dispatcher ->
            dispatcher.start()
            dispatcher.wake()
            withTimeout(5_000) { inFlight.await() }

            val replacementStarted = CountDownLatch(1)
            val replacement = async(Dispatchers.IO) {
                replacementStarted.countDown()
                dispatcher.accepted(
                    listOf(
                        resolved(
                            body = byteArrayOf(2),
                            queuePolicy = snapshotPolicy,
                        ),
                    ),
                ).single()
            }
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
            delay(10)
            assertFalse(replacement.isCompleted)

            releaseFailure.complete(Unit)
            val accepted = withTimeout(5_000) { replacement.await() }
            withTimeout(5_000) { finished.await() }
            assertEquals("accepted-1", accepted.messageId)
        }

        assertEquals(listOf(listOf("old"), listOf("accepted-1")), calls)
        assertEquals(listOf("accepted-1"), outbox.checkpoints)
        assertEquals(0, outbox.pendingCount())
    }

    private class MemoryOutbox(vararg initial: PendingSend) : GenericSendOutbox {
        private val queue = initial.toMutableList()
        private var nextId = 1
        val checkpoints = mutableListOf<String>()

        @Synchronized
        override fun accept(sends: List<ResolvedSend>): List<SendAccepted> = sends.map { send ->
            send.queuePolicy?.coalesceKey?.let { key ->
                queue.removeAll {
                    it.applicationId == send.applicationId && it.queuePolicy?.coalesceKey == key
                }
            }
            val id = "accepted-${nextId++}"
            val accepted = SendAccepted(id, nextId.toLong(), send.submissionId)
            queue += PendingSend(
                messageId = id,
                acceptedAtEpochMillis = accepted.acceptedAtEpochMillis,
                applicationId = send.applicationId,
                messageType = send.messageType,
                body = send.body,
                scope = send.scope,
                urgency = send.urgency,
                signWith = send.signWith,
                submissionId = send.submissionId,
                queuePolicy = send.queuePolicy,
            )
            accepted
        }

        @Synchronized
        override fun peekConsecutive(maxItems: Int): List<PendingSend> {
            val first = queue.firstOrNull() ?: return emptyList()
            return queue.takeWhile { first.belongsToSameDispatchGroup(it) }.take(maxItems)
        }

        @Synchronized
        override fun checkpoint(messageId: String): Boolean {
            val removed = queue.removeAll { it.messageId == messageId }
            if (removed) checkpoints += messageId
            return true
        }

        @Synchronized
        override fun pendingCount(): Int = queue.size
    }
}

private fun applications(vararg applicationIds: String): ApplicationRegistry {
    val registered = applicationIds.toSet()
    return object : ApplicationRegistry {
        override fun register(
            applicationId: String,
            registration: ApplicationRegistrationRequest,
        ): ApplicationRegistrationResult = error("not used")

        override fun find(applicationId: String): ApplicationView? = if (applicationId in registered) {
            ApplicationView(applicationId, applicationId, updatedAtEpochMillis = 1)
        } else {
            null
        }

        override fun list(): ApplicationListResponse = error("not used")
        override fun effectiveCapabilities(): List<Capability> = error("not used")
        override fun delete(applicationId: String): Boolean = error("not used")
    }
}

private fun pending(
    id: String,
    messageType: MessageType = MessageType.DATA_SYNC,
    urgency: Urgency = Urgency.NORMAL,
    queuePolicy: QueuePolicy? = null,
) = PendingSend(
    messageId = id,
    acceptedAtEpochMillis = 1,
    applicationId = "nsrun",
    messageType = messageType,
    body = byteArrayOf(1),
    scope = Recipients.OwnMesh,
    urgency = urgency,
    signWith = SignerSelection.OPERATIONAL,
    queuePolicy = queuePolicy,
)

private fun resolved(
    body: ByteArray,
    queuePolicy: QueuePolicy? = null,
) = ResolvedSend(
    applicationId = "nsrun",
    messageType = MessageType.DATA_SYNC,
    body = body,
    scope = Recipients.OwnMesh,
    urgency = Urgency.NORMAL,
    signWith = SignerSelection.OPERATIONAL,
    queuePolicy = queuePolicy,
)

private fun quietLogger() = DaemonLogger("OFF", StringBuilder())
