package net.extrawdw.notisync.daemon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationReceiveRouterTest {
    private val resolver = ProcessIdentityResolver()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC)
    private val registered = linkedSetOf("app")

    @Test
    fun `receive validates registration and filter contract`() {
        val router = router()
        assertThrows(ApplicationNotRegisteredException::class.java) {
            router.open(peer(), ReceiveRequest("missing"))
        }
        assertInvalid(router, ReceiveRequest("app", messageTypes = emptyList()))
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                filters = listOf(filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("RUN"))),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.NOTIFICATION),
                filters = listOf(filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("RUN"))),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(
                    filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("RUN")),
                    filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("PROFILE")),
                ),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/kind", JsonNull)),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(MessageFilter(MessageType.DATA_SYNC, "/kind", emptyList())),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(
                    filter(MessageType.DATA_SYNC, "/kind", JsonObject(mapOf("bad" to JsonPrimitive(1)))),
                ),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "kind", JsonPrimitive("RUN"))),
            ),
        )
        assertInvalid(
            router,
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/bad~2escape", JsonPrimitive("RUN"))),
            ),
        )
    }

    @Test
    fun `message type target allows every notification but filters data sync`() {
        val router = router()
        val handle = router.open(
            peer(),
            ReceiveRequest(
                applicationId = "app",
                messageTypes = listOf(MessageType.NOTIFICATION, MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("RUN"))),
            ),
        )

        val malformedNotification = byteArrayOf(1, 2, 3)
        assertTrue(router.accept(inbound("notification", MessageType.NOTIFICATION, malformedNotification)))
        assertArrayEquals(malformedNotification, decodeBody(requireNotNull(handle.pollRecord()?.body)))

        val profile = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.PROFILE))
        assertFalse(router.accept(inbound("profile", MessageType.DATA_SYNC, profile)))
        assertNull(handle.pollRecord())

        val run = ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.RUN))
        assertTrue(router.accept(inbound("run", MessageType.DATA_SYNC, run)))
        assertEquals("run", handle.pollRecord()?.envelopeId)
    }

    @Test
    fun `filters AND while accepted values OR and use typed nested projection`() {
        val router = router()
        val handle = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(
                    MessageFilter(
                        MessageType.DATA_SYNC,
                        "/kind",
                        listOf(JsonPrimitive("PROFILE"), JsonPrimitive("RUN")),
                    ),
                    filter(MessageType.DATA_SYNC, "/run/kind", JsonPrimitive("CONTROL")),
                    filter(MessageType.DATA_SYNC, "/run/control/runId", JsonPrimitive("run-1")),
                ),
            ),
        )

        assertFalse(router.accept(dataSyncInbound("profile", DataSync(DataSyncKind.PROFILE))))
        assertTrue(router.accept(dataSyncInbound("control", runControlSync("run-1"))))
        assertEquals("control", handle.pollRecord()?.envelopeId)
        assertFalse(router.accept(dataSyncInbound("other", runControlSync("run-2"))))
    }

    @Test
    fun `canonical ordering shares one process interest while sockets fan out independently`() {
        val router = router()
        val first = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC, MessageType.NOTIFICATION),
                filters = listOf(
                    MessageFilter(
                        MessageType.DATA_SYNC,
                        "/kind",
                        listOf(JsonPrimitive("PROFILE"), JsonPrimitive("RUN")),
                    ),
                ),
            ),
        )
        val second = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.NOTIFICATION, MessageType.DATA_SYNC, MessageType.DATA_SYNC),
                filters = listOf(
                    MessageFilter(
                        MessageType.DATA_SYNC,
                        "/kind",
                        listOf(JsonPrimitive("RUN"), JsonPrimitive("PROFILE"), JsonPrimitive("RUN")),
                    ),
                ),
            ),
        )

        assertEquals(1, router.interestCount("app"))
        assertTrue(router.accept(dataSyncInbound("fanout", DataSync(DataSyncKind.RUN))))
        assertEquals("fanout", first.pollRecord()?.envelopeId)
        assertEquals("fanout", second.pollRecord()?.envelopeId)
    }

    @Test
    fun `socket detach retains process interest and reconnect replays pending`() {
        val router = router()
        val request = ReceiveRequest("app", messageTypes = listOf(MessageType.DATA_SYNC))
        val first = router.open(peer(), request)
        first.close()

        assertFalse(first.isAttached())
        assertEquals(1, router.interestCount("app"))
        assertTrue(router.accept(dataSyncInbound("pending", DataSync(DataSyncKind.PROFILE))))
        assertEquals(1, router.pendingCount("app"))

        val reconnected = router.open(peer(), request)
        assertEquals("pending", reconnected.pollRecord()?.envelopeId)
    }

    @Test
    fun `application ack clears all of its streams but not another application`() {
        registered += "other"
        val router = router()
        val appOne = router.open(peer(), ReceiveRequest("app"))
        val appTwo = router.open(peer(), ReceiveRequest("app"))
        val other = router.open(peer(), ReceiveRequest("other"))
        assertTrue(router.accept(dataSyncInbound("shared", DataSync(DataSyncKind.RUN))))
        assertTrue(router.hasPending("app", "shared"))
        assertTrue(router.hasPending("other", "shared"))

        assertTrue(router.ack("app", "shared"))
        assertNull(appOne.pollRecord())
        assertNull(appTwo.pollRecord())
        assertEquals(0, router.pendingCount("app"))
        assertFalse(router.hasPending("app", "shared"))
        assertEquals("shared", other.pollRecord()?.envelopeId)
        assertEquals(1, router.sharedMessageCount())

        assertFalse(router.ack("app", "shared"))
        assertTrue(router.ack("other", "shared"))
        assertEquals(0, router.sharedMessageCount())
    }

    @Test
    fun `duplicate envelope creates one ref and is not reoffered`() {
        val router = router()
        val handle = router.open(peer(), ReceiveRequest("app"))
        val inbound = dataSyncInbound("duplicate", DataSync(DataSyncKind.RUN))
        assertTrue(router.accept(inbound))
        assertTrue(router.accept(inbound))

        assertEquals(1, router.pendingCount("app"))
        assertEquals("duplicate", handle.pollRecord()?.envelopeId)
        assertNull(handle.pollRecord())
    }

    @Test
    fun `capacity failure rolls back fanout to every application`() {
        registered += "other"
        val router = router(maximumPending = 1)
        router.open(peer(), ReceiveRequest("app", messageTypes = listOf(MessageType.DATA_SYNC)))
        router.open(peer(), ReceiveRequest("other"))
        assertTrue(router.accept(inbound("fill", MessageType.NOTIFICATION, byteArrayOf(1))))

        assertThrows(LocalEventQueueFullException::class.java) {
            router.accept(dataSyncInbound("must-retry", DataSync(DataSyncKind.RUN)))
        }
        assertEquals(0, router.pendingCount("app"))
        assertEquals(1, router.pendingCount("other"))
        assertEquals(1, router.sharedMessageCount())
    }

    @Test
    fun `explicit unregister and dead process cleanup retain pending inbox data`() {
        var processAlive = true
        val router = router(processStillMatches = { processAlive })
        val request = ReceiveRequest("app")
        val handle = router.open(peer(), request)
        assertTrue(router.accept(dataSyncInbound("kept", DataSync(DataSyncKind.PROFILE))))

        processAlive = false
        assertEquals(1, router.cleanupDeadProcesses())
        assertFalse(handle.isAttached())
        assertEquals(0, router.interestCount("app"))
        assertEquals(1, router.pendingCount("app"))

        processAlive = true
        val replay = router.open(peer(), request)
        assertEquals("kept", replay.pollRecord()?.envelopeId)
        assertTrue(router.unregister(peer(), request))
        assertFalse(replay.isAttached())
        assertEquals(1, router.pendingCount("app"))
        assertFalse(router.accept(dataSyncInbound("ignored", DataSync(DataSyncKind.PROFILE))))
    }

    @Test
    fun `application removal detaches streams and releases only its refs`() {
        registered += "other"
        val router = router()
        val removed = router.open(peer(), ReceiveRequest("app"))
        val retained = router.open(peer(), ReceiveRequest("other"))
        assertTrue(router.accept(dataSyncInbound("event", DataSync(DataSyncKind.RUN))))

        router.removeApplication("app")
        assertFalse(removed.isAttached())
        assertEquals(0, router.pendingCount("app"))
        assertEquals(1, router.sharedMessageCount())
        assertEquals("event", retained.pollRecord()?.envelopeId)

        router.removeApplication("other")
        assertEquals(0, router.sharedMessageCount())
    }

    @Test
    fun `await record wakes a blocked stream`() {
        val router = router()
        val handle = router.open(peer(), ReceiveRequest("app"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit(Callable<ReceiveRecord?> { handle.awaitRecord(5_000) })
            assertTrue(router.accept(dataSyncInbound("wake", DataSync(DataSyncKind.PROFILE))))
            assertEquals("wake", waiting.get(1, TimeUnit.SECONDS)?.envelopeId)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `typed projection exposes custom ClientId strings and normalized numbers`() {
        val router = router()
        val handle = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.NOTIFICATION),
                filters = listOf(
                    filter(MessageType.NOTIFICATION, "/sourceClientId", JsonPrimitive("source")),
                    filter(MessageType.NOTIFICATION, "/postTime", JsonPrimitive(12.0)),
                ),
            ),
        )
        val notification = CapturedNotification(
            sourceClientId = ClientId("source"),
            sourceKey = "key",
            packageName = "example",
            appLabel = "Example",
            postTime = 12,
        )

        assertTrue(
            router.accept(
                inbound(
                    "notification",
                    MessageType.NOTIFICATION,
                    ProtocolCodec.encodeToCbor(notification),
                ),
            ),
        )
        val record = requireNotNull(handle.pollRecord())
        assertEquals(ReceiveRecordType.MESSAGE, record.recordType)
        assertEquals("source", record.senderClientId)
        assertTrue(record.senderOwnDevice == true)
        assertEquals(7, record.signerEpoch)
        assertEquals(DeliveryMode.WEBSOCKET.name, record.deliveryMode)
        assertEquals(1_234, record.receivedAtEpochMillis)
        assertEquals(777, record.envelopeCreatedAtEpochMillis)
    }

    @Test
    fun `dismissal and action filters use their declared typed serializers`() {
        val router = router()
        val handle = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DISMISSAL, MessageType.ACTION),
                filters = listOf(
                    filter(MessageType.DISMISSAL, "/sourceKey", JsonPrimitive("dismiss-key")),
                    filter(MessageType.ACTION, "/sourceKey", JsonPrimitive("action-key")),
                ),
            ),
        )
        val dismissal = DismissEvent(ClientId("source"), "dismiss-key", dismissedAt = 8)
        val action = ActionEvent(
            sourceClientId = ClientId("source"),
            sourceKey = "action-key",
            kind = ActionKind.TAP,
            actedAt = 9,
        )

        assertTrue(
            router.accept(
                inbound("dismissal", MessageType.DISMISSAL, ProtocolCodec.encodeToCbor(dismissal)),
            ),
        )
        assertTrue(router.accept(inbound("action", MessageType.ACTION, ProtocolCodec.encodeToCbor(action))))
        assertEquals("dismissal", handle.pollRecord()?.envelopeId)
        assertEquals("action", handle.pollRecord()?.envelopeId)
    }

    @Test
    fun `RFC 6901 escaping arrays and projection are evaluated once`() {
        val projections = AtomicInteger()
        val projector = InboundBodyProjector { _, _, _ ->
            projections.incrementAndGet()
            JsonObject(
                mapOf(
                    "a/b" to JsonObject(mapOf("~key" to JsonArray(listOf(JsonPrimitive("miss"), JsonPrimitive(2))))),
                ),
            )
        }
        val router = router(projector = projector)
        val first = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/a~1b/~0key/1", JsonPrimitive(2.0))),
            ),
        )
        assertTrue(router.accept(inbound("pointer", MessageType.DATA_SYNC, byteArrayOf(9))))
        assertEquals("pointer", first.pollRecord()?.envelopeId)

        val replay = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/a~1b/~0key/1", JsonPrimitive(2))),
            ),
        )
        assertEquals("pointer", replay.pollRecord()?.envelopeId)
        assertEquals(1, projections.get())
    }

    @Test
    fun `RFC 6901 array indexes accept ASCII digits only`() {
        val router = router(
            projector = InboundBodyProjector { _, _, _ ->
                JsonArray(listOf(JsonPrimitive("zero"), JsonPrimitive("one")))
            },
        )
        val handle = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/+1", JsonPrimitive("one"))),
            ),
        )

        assertFalse(router.accept(inbound("plus-index", MessageType.DATA_SYNC, byteArrayOf(1))))
        assertNull(handle.pollRecord())
    }

    @Test
    fun `malformed filtered body does not match but decoded DataSync can be reused`() {
        val malformed = byteArrayOf(0x7f)
        val router = router()
        val handle = router.open(
            peer(),
            ReceiveRequest(
                "app",
                messageTypes = listOf(MessageType.DATA_SYNC),
                filters = listOf(filter(MessageType.DATA_SYNC, "/kind", JsonPrimitive("RUN"))),
            ),
        )
        assertFalse(router.accept(inbound("malformed", MessageType.DATA_SYNC, malformed)))
        assertTrue(
            router.accept(
                inbound("decoded", MessageType.DATA_SYNC, malformed),
                decodedDataSync = DataSync(DataSyncKind.RUN),
            ),
        )
        val record = requireNotNull(handle.pollRecord())
        assertEquals("decoded", record.envelopeId)
        assertArrayEquals(malformed, decodeBody(requireNotNull(record.body)))
    }

    @Test
    fun `message with no live interest is not retained`() {
        val router = router()
        assertFalse(router.accept(dataSyncInbound("ignored", DataSync(DataSyncKind.RUN))))
        assertEquals(0, router.sharedMessageCount())
        assertEquals(0, router.pendingCount("app"))
    }

    private fun router(
        maximumPending: Int = ApplicationReceiveRouter.DEFAULT_MAXIMUM_PENDING_PER_APPLICATION,
        projector: InboundBodyProjector = ProtocolInboundBodyProjector,
        processStillMatches: (LocalPeer) -> Boolean = { true },
    ) = ApplicationReceiveRouter(
        applications = RegisteredApplicationLookup { it in registered },
        identityResolver = resolver,
        clock = clock,
        maximumPendingPerApplication = maximumPending,
        projector = projector,
        processStillMatches = processStillMatches,
    )

    private fun peer(): LocalPeer {
        val pid = ProcessHandle.current().pid()
        return LocalPeer(uid = 123, pid = pid, startTime = requireNotNull(resolver.startTime(pid)))
    }

    private fun inbound(id: String, type: MessageType, body: ByteArray) = InboundMessage(
        senderId = ClientId("source"),
        senderOwnDevice = true,
        typ = type,
        body = body,
        signerEpoch = 7,
        messageId = id,
        deliveryMode = DeliveryMode.WEBSOCKET,
        createdAt = 777,
    )

    private fun dataSyncInbound(id: String, sync: DataSync) = inbound(
        id,
        MessageType.DATA_SYNC,
        ProtocolCodec.encodeToCbor(sync),
    )

    private fun runControlSync(runId: String): DataSync = DataSync(
        kind = DataSyncKind.RUN,
        run = RunSync(
            kind = RunSyncKind.CONTROL,
            control = RunControl(
                requestId = UUID.randomUUID().toString(),
                hostClientId = ClientId("host"),
                runId = runId,
                kind = RunControlKind.REFRESH,
                requestedAt = 1,
            ),
        ),
    )

    private fun filter(type: MessageType, path: String, value: kotlinx.serialization.json.JsonElement) =
        MessageFilter(type, path, listOf(value))

    private fun assertInvalid(router: ApplicationReceiveRouter, request: ReceiveRequest) {
        assertThrows(IllegalArgumentException::class.java) { router.open(peer(), request) }
    }

    private fun decodeBody(body: String): ByteArray = java.util.Base64.getDecoder().decode(body)
}
