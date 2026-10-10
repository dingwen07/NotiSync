package net.extrawdw.apps.notisync.hotspot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import net.extrawdw.apps.notisync.hotspot.controller.HotspotController
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.protocol.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotControllerTest {
    private val device = ClientId("hotspot")
    private val snapshot = HotspotSnapshot(HotspotState.ENABLED, "Test Wi-Fi", "test-password", 2, true)
    private fun status(issued: Long = 100_000, id: String? = null) = HotspotSync(
        HotspotAction.STATUS, device, issued, issued + 60_000, id, snapshot = snapshot, result = HotspotResult.OK,
    )
    private fun deliver(controller: HotspotController, status: HotspotSync, own: Boolean = true) = controller.onSync(
        InboundMessage(device, own, MessageType.DATA_SYNC, byteArrayOf(), createdAt = status.issuedAt),
        DataSync(DataSyncKind.HOTSPOT, hotspot = status),
    )

    @Test fun highRefreshKeepsPendingUntilMatchingResponseAndPreservesLatestBroadcast() = runTest {
        val sent = mutableListOf<HotspotSync>()
        var persisted = 0
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> persisted++ },
            send = { _, sync, urgency -> assertEquals(Urgency.HIGH, urgency); sent += sync; true },
            now = { 100_000 }, requestId = { "request" })
        controller.refresh(device)
        controller.refresh(device)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(HotspotAction.QUERY, sent.single().action)
        deliver(controller, status())
        assertEquals("request", controller.remote.value[device]?.pendingRequest)
        deliver(controller, status(100_001, "request"))
        assertNull(controller.remote.value[device]?.pendingRequest)
        deliver(controller, status(99_999))
        assertEquals(100_001L, controller.remote.value[device]?.status?.issuedAt)
        assertTrue(persisted >= 2)
        advanceTimeBy(45_001)
        assertNull(controller.remote.value[device]?.failure)
    }

    @Test fun spoofedExpiredAndNonOwnStatusNeverReachesPersistence() = runTest {
        var writes = 0
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> writes++ },
            send = { _, _, _ -> true }, now = { 100_000 })
        deliver(controller, status().copy(hotspotDeviceId = ClientId("another-device")))
        deliver(controller, status().copy(hotspotDeviceId = null))
        deliver(controller, status(), own = false)
        deliver(controller, status(1))
        assertEquals(0, writes)
        assertTrue(controller.remote.value.isEmpty())
    }

    @Test fun sharedStatusCorrelatesOnlyTheControllerWithTheMatchingPendingRequest() = runTest {
        val first = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { _, _, _ -> true }, now = { 100_000 }, requestId = { "first-request" })
        val second = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { _, _, _ -> true }, now = { 100_000 }, requestId = { "second-request" })
        first.refresh(device)
        second.setEnabled(device, false)
        runCurrent()
        val broadcast = status(id = "first-request")
        deliver(first, broadcast)
        deliver(second, broadcast)
        assertNull(first.remote.value[device]?.pendingRequest)
        assertEquals("second-request", second.remote.value[device]?.pendingRequest)
        assertEquals(snapshot, first.remote.value[device]?.status?.snapshot)
        assertEquals(snapshot, second.remote.value[device]?.status?.snapshot)
    }

    @Test fun sheetRefreshReusesBroadcastUntilTwoMinuteBoundaryWithoutWaitingForReply() = runTest {
        var now = 100_000L
        val sent = mutableListOf<HotspotSync>()
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { _, sync, urgency -> assertEquals(Urgency.HIGH, urgency); sent += sync; true },
            now = { now })
        deliver(controller, status())
        controller.refreshIfStale(device)
        now = 219_999L
        controller.refreshIfStale(device)
        runCurrent()
        assertTrue(sent.isEmpty())

        now = 220_000L
        controller.refreshIfStale(device)
        runCurrent()
        assertEquals(HotspotAction.REFRESH, sent.single().action)
        assertNull(controller.remote.value[device]?.pendingRequest)
        advanceTimeBy(45_001)
        assertNull(controller.remote.value[device]?.failure)
    }

    @Test fun missingStatusRefreshesAndExplicitRefreshBypassesFreshness() = runTest {
        val sent = mutableListOf<HotspotSync>()
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { _, sync, _ -> sent += sync; true }, now = { 100_000 })
        controller.refreshIfStale(device)
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(HotspotAction.REFRESH, sent.single().action)
        assertTrue(controller.remote.value.isEmpty())
        deliver(controller, status())

        controller.refreshIfStale(device)
        runCurrent()
        assertEquals(1, sent.size)
        controller.refresh(device)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(HotspotAction.QUERY, sent.last().action)
    }

    @Test fun automaticRefreshSendFailureHasNoPendingStateAndCannotReplaceACommand() = runTest {
        val sent = mutableListOf<HotspotSync>()
        var sendSucceeds = false
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { _, sync, _ -> sent += sync; sendSucceeds }, now = { 100_000 })
        controller.refreshIfStale(device)
        runCurrent()
        advanceTimeBy(45_001)
        assertTrue(controller.remote.value.isEmpty())
        assertEquals(HotspotAction.REFRESH, sent.single().action)

        sendSucceeds = true
        controller.setEnabled(device, true)
        val pending = controller.remote.value[device]?.pendingRequest
        controller.refreshIfStale(device)
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(HotspotAction.SET_ENABLED, sent.last().action)
        assertEquals(pending, controller.remote.value[device]?.pendingRequest)
    }

    @Test fun timeoutAndPermissionFailureLeavePreviouslyReceivedDetailsAvailable() = runTest {
        var persisted: HotspotSnapshot? = null
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, value, _ -> persisted = value },
            send = { _, _, _ -> true }, now = { 100_000 }, requestId = { "request" })
        deliver(controller, status())
        controller.setEnabled(device, false)
        runCurrent()
        advanceTimeBy(45_001)
        assertEquals(HotspotResult.TIMEOUT, controller.remote.value[device]?.failure)
        deliver(controller, status(100_001).copy(result = HotspotResult.UNAUTHORIZED, snapshot = null))
        assertEquals(snapshot, persisted)
    }

    @Test fun explicitCommandsWorkWithoutStatusAndAfterTimeoutButDoNotOverlap() = runTest {
        val sent = mutableListOf<HotspotSync>()
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { peer, sync, urgency ->
                assertEquals(device, peer)
                assertEquals(Urgency.HIGH, urgency)
                sent += sync
                true
            }, now = { 100_000 + testScheduler.currentTime }, requestId = { "command-${sent.size}" })

        controller.setEnabled(device, true)
        controller.setEnabled(device, false)
        runCurrent()
        assertEquals(HotspotAction.SET_ENABLED, sent.single().action)
        assertEquals(true, sent.single().enabled)
        deliver(controller, status(id = sent.single().requestId))

        controller.refresh(device)
        runCurrent()
        advanceTimeBy(45_001)
        assertEquals(HotspotResult.TIMEOUT, controller.remote.value[device]?.failure)
        assertEquals(snapshot, controller.remote.value[device]?.status?.snapshot)
        controller.setEnabled(device, false)
        controller.setEnabled(device, true)
        runCurrent()
        assertEquals(3, sent.size)
        assertEquals(HotspotAction.SET_ENABLED, sent.last().action)
        assertEquals(false, sent.last().enabled)
        assertNull(controller.remote.value[device]?.failure)
        assertEquals(sent.last().requestId, controller.remote.value[device]?.pendingRequest)
    }

    @Test fun startupSendsOneNormalPriorityFanoutWithoutWaitingForAResponse() = runTest {
        val sent = mutableListOf<HotspotSync>()
        val controller = HotspotController(backgroundScope,
            { error("The channel resolves the fanout audience") }, { error("The channel filters capabilities") },
            { _, _, _ -> }, send = { peer, sync, urgency ->
                assertNull(peer)
                assertEquals(Urgency.NORMAL, urgency)
                sent += sync
                true
            }, now = { 100_000 })
        controller.refreshProviders()
        runCurrent()
        assertEquals(HotspotAction.REFRESH, sent.single().action)
        assertNull(sent.single().hotspotDeviceId)
        assertTrue(sent.single().isValid(100_000, 100_000))
        assertTrue(controller.remote.value.isEmpty())
        advanceTimeBy(45_001)
        assertTrue(controller.remote.value.isEmpty())
    }

    @Test fun listActionQueriesUnknownUnavailableAndTransitionalStatusWithoutDuplicatingPendingRequests() = runTest {
        val sent = mutableListOf<HotspotSync>()
        val controller = HotspotController(backgroundScope, { true }, { true }, { _, _, _ -> },
            send = { peer, sync, urgency ->
                assertEquals(device, peer)
                assertEquals(Urgency.HIGH, urgency)
                sent += sync
                true
            }, now = { 100_000 }, requestId = { "query" })
        val states = listOf(null,
            status(100_001, "query").copy(result = HotspotResult.UNAVAILABLE, snapshot = null),
            status(100_002, "query").copy(snapshot = snapshot.copy(state = HotspotState.ENABLING)),
            status(100_003, "query").copy(snapshot = snapshot.copy(state = HotspotState.FAILED)))
        for (state in states) {
            state?.let { deliver(controller, it) }
            sent.clear()
            controller.toggleOrQuery(device)
            controller.toggleOrQuery(device)
            runCurrent()
            assertEquals(HotspotAction.QUERY, sent.single().action)
        }
    }

    @Test fun listActionTogglesKnownStateAndQueriesAfterTimeout() = runTest {
        val sent = mutableListOf<HotspotSync>()
        var trusted = true
        val controller = HotspotController(backgroundScope, { trusted }, { true }, { _, _, _ -> },
            send = { _, sync, _ -> sent += sync; true }, now = { 100_000 }, requestId = { "toggle" })
        deliver(controller, status())
        controller.toggleOrQuery(device)
        runCurrent()
        assertEquals(HotspotAction.SET_ENABLED, sent.last().action)
        assertEquals(false, sent.last().enabled)
        deliver(controller, status(100_001, "toggle").copy(snapshot = snapshot.copy(state = HotspotState.DISABLED)))
        controller.toggleOrQuery(device)
        runCurrent()
        assertEquals(HotspotAction.SET_ENABLED, sent.last().action)
        assertEquals(true, sent.last().enabled)
        advanceTimeBy(45_001)
        controller.toggleOrQuery(device)
        runCurrent()
        assertEquals(HotspotAction.QUERY, sent.last().action)
        deliver(controller, status(100_002, "toggle"))
        trusted = false
        controller.toggleOrQuery(device)
        runCurrent()
        assertEquals(3, sent.size)
    }
}
