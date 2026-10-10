package net.extrawdw.apps.notisync.hotspot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import net.extrawdw.apps.notisync.hotspot.provider.*
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.protocol.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotProviderTest {
    private val device = ClientId("hotspot")
    private val requester = ClientId("requester")
    private val viewer = ClientId("viewer")
    private val stranger = ClientId("stranger")
    private val snapshot = HotspotSnapshot(HotspotState.ENABLED, "Test Wi-Fi", "test-password", 2, true)
    private fun request(enabled: Boolean? = null) = HotspotSync(
        if (enabled == null) HotspotAction.QUERY else HotspotAction.SET_ENABLED,
        device, 100_000, 160_000, "request", enabled,
    )
    private class Backend(var reading: HotspotReading) : HotspotBackend {
        var reads = 0
        var writes = 0
        var beforeMutation: suspend () -> Unit = {}
        override suspend fun query(): HotspotReading { reads++; return reading }
        override suspend fun setEnabled(enabled: Boolean, stillAuthorized: () -> Boolean): HotspotReading {
            beforeMutation()
            if (!stillAuthorized()) return HotspotReading(HotspotResult.UNAUTHORIZED)
            writes++
            return reading
        }
    }

    @Test fun queryBroadcastsOnlyToCurrentlyAuthorizedPeersAndCorrelatesRequester() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        val permitted = mutableSetOf(requester, viewer)
        val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { it in permitted }, recipients = { listOf(requester, viewer, stranger) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { 100_000 },
            send = { id, sync, priority -> assertEquals(Urgency.NORMAL, priority); sent += id to sync; id.size })
        provider.executeScheduledRequest(requester, request(), 100_000)
        assertEquals(1, backend.reads)
        assertEquals(setOf(requester, viewer), sent.single().first)
        assertEquals("request", sent.first().second.requestId)
        assertEquals(snapshot, sent.last().second.snapshot)
        permitted.clear()
        sent.clear()
        provider.executeScheduledRequest(requester, request(), 100_000)
        assertEquals(1, backend.reads)
        assertEquals(HotspotResult.UNAUTHORIZED, sent.single().second.result)
        assertNull(sent.single().second.snapshot)
    }

    @Test fun revocationDuringPlatformReadRemovesPeerFromTheSinglePublication() = runTest {
        val permitted = mutableSetOf(requester, viewer)
        val sent = mutableListOf<Set<ClientId>>()
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        backend.beforeMutation = { permitted.remove(viewer) }
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { it in permitted }, recipients = { listOf(requester, viewer) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { 100_000 },
            send = { ids, _, _ -> sent.add(ids); ids.size })
        provider.executeScheduledRequest(requester, request(true), 100_000)
        assertEquals(listOf(setOf(requester)), sent)
    }

    @Test fun commandsFromAuthorizedRequesterOutsideControllerAudienceExecuteAndReceiveCorrelatedStatus() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        val scheduled = mutableListOf<Triple<ClientId, HotspotSync, Long>>()
        val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { it != stranger },
            // The requester's profile has not announced controller support yet.
            recipients = { listOf(viewer, stranger) }, consumeCommand = { _, _ -> true },
            scheduleRequest = { peer, sync, createdAt -> scheduled += Triple(peer, sync, createdAt) },
            now = { 100_000 }, send = { peer, sync, _ -> sent += peer to sync; peer.size })

        for ((index, enabled) in listOf(true, false).withIndex()) {
            val command = request(enabled).copy(issuedAt = 100_000L + index, requestId = "command-$index")
            provider.onSync(InboundMessage(requester, true, MessageType.DATA_SYNC, byteArrayOf(), createdAt = 100_000),
                DataSync(DataSyncKind.HOTSPOT, hotspot = command))
            val (peer, sync, createdAt) = scheduled.last()
            provider.executeScheduledRequest(peer, sync, createdAt)
            assertEquals(index + 1, backend.writes)
            assertEquals(setOf(viewer, requester), sent.single().first)
            assertEquals(command.requestId, sent.single().second.requestId)
            assertTrue(sent.all { it.second.snapshot == snapshot })
            sent.clear()
        }
        assertEquals(2, scheduled.size)
    }

    @Test fun mutationsRejectReplayExpiredQueueEntriesAndRevocationWhileBinding() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        var permitted = true
        var consumed = false
        var time = 100_000L
        val sent = mutableListOf<HotspotSync>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { permitted }, recipients = { listOf(requester) },
            consumeCommand = { _, _ -> (!consumed).also { consumed = true } },
            scheduleRequest = { _, _, _ -> }, now = { time }, send = { ids, sync, _ -> sent += sync; ids.size })
        provider.executeScheduledRequest(requester, request(true), 100_000)
        provider.executeScheduledRequest(requester, request(false), 100_000)
        assertEquals(1, backend.writes)
        assertEquals(HotspotResult.EXPIRED, sent.last().result)
        time = 160_000
        consumed = false
        provider.executeScheduledRequest(requester, request(false), 100_000)
        assertFalse(consumed)
        time = 100_000
        backend.beforeMutation = { permitted = false }
        provider.executeScheduledRequest(requester, request(false), 100_000)
        assertEquals(1, backend.writes)
    }

    @Test fun inboundSchedulingRequiresOwnTrustedSenderMatchingDeviceAndValidBody() = runTest {
        var scheduled = 0
        val provider = HotspotProvider(device, backgroundScope, Backend(HotspotReading(HotspotResult.OK, snapshot)),
            trustedPeer = { it == requester }, authorized = { true }, recipients = { emptyList() },
            consumeCommand = { _, _ -> true }, now = { 100_000 }, send = { ids, _, _ -> ids.size },
            scheduleRequest = { _, _, _ -> scheduled++ })
        fun receive(sender: ClientId = requester, own: Boolean = true, sync: HotspotSync = request()) {
            provider.onSync(InboundMessage(sender, own, MessageType.DATA_SYNC, byteArrayOf(), createdAt = 100_000),
                DataSync(DataSyncKind.HOTSPOT, hotspot = sync))
        }
        receive(own = false)
        receive(sender = stranger)
        receive(sync = request().copy(hotspotDeviceId = viewer))
        receive(sync = request().copy(expiresAt = 99_000))
        receive(sync = request(true).copy(hotspotDeviceId = null))
        assertEquals(0, scheduled)
        receive()
        assertEquals(1, scheduled)
        receive(sync = request().copy(hotspotDeviceId = null))
        receive(sync = request().copy(action = HotspotAction.REFRESH, hotspotDeviceId = null))
        assertEquals(3, scheduled)
    }

    @Test fun fanoutRequestsUseEachProvidersOwnIdentityAndRetainAuthorizationAndCooldown() = runTest {
        for (providerId in listOf(device, ClientId("second-hotspot"))) {
            val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
            val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
            var permitted = true
            val provider = HotspotProvider(providerId, backgroundScope, backend,
                trustedPeer = { it != stranger }, authorized = { permitted }, recipients = { listOf(requester, viewer) },
                consumeCommand = { _, _ -> error("A targetless command must never execute") },
                scheduleRequest = { _, _, _ -> }, now = { 100_000 },
                send = { peer, sync, _ -> sent += peer to sync; peer.size })
            val refresh = request().copy(action = HotspotAction.REFRESH, hotspotDeviceId = null)
            provider.executeScheduledRequest(requester, refresh, 100_000)
            assertEquals(1, backend.reads)
            assertEquals(setOf(requester, viewer), sent.single().first)
            assertTrue(sent.all { it.second.hotspotDeviceId == providerId && it.second.requestId == null })
            sent.clear()
            provider.executeScheduledRequest(requester, refresh, 100_000)
            assertTrue(sent.isEmpty())
            provider.executeScheduledRequest(requester, refresh.copy(action = HotspotAction.QUERY), 100_000)
            assertEquals(2, backend.reads)
            assertEquals(setOf(requester, viewer), sent.single().first)
            assertTrue(sent.all { it.second.hotspotDeviceId == providerId })
            assertEquals("request", sent.first().second.requestId)

            sent.clear()
            provider.executeScheduledRequest(requester, request(true).copy(hotspotDeviceId = null), 100_000)
            provider.executeScheduledRequest(requester, refresh.copy(hotspotDeviceId = stranger), 100_000)
            provider.executeScheduledRequest(stranger, refresh.copy(action = HotspotAction.QUERY), 100_000)
            assertTrue(sent.isEmpty())
            permitted = false
            provider.executeScheduledRequest(requester, refresh.copy(action = HotspotAction.QUERY), 100_000)
            assertEquals(2, backend.reads)
            assertEquals(HotspotResult.UNAUTHORIZED, sent.single().second.result)
            assertNull(sent.single().second.snapshot)
        }
    }

    @Test fun toggleCompletionAndQueuedCallbacksProduceOneBroadcastForEveryPeer() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { true }, recipients = { listOf(requester, viewer) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { 100_000 },
            send = { id, sync, _ -> sent += id to sync; id.size })
        backend.beforeMutation = {
            provider.onPlatformChanged()
            delay(500) // Callback processing reaches the operation lock before the command completes.
            provider.onPlatformChanged()
        }

        for (enabled in listOf(true, false)) {
            sent.clear()
            backend.reading = HotspotReading(HotspotResult.OK, snapshot.copy(
                state = if (enabled) HotspotState.ENABLED else HotspotState.DISABLED,
                wifiTethered = enabled,
            ))
            val command = request(enabled).copy(requestId = "toggle-$enabled")
            provider.executeScheduledRequest(requester, command, 100_000)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(setOf(requester, viewer), sent.single().first)
            assertEquals(command.requestId, sent.first().second.requestId)
            assertTrue(sent.all { it.second.snapshot == backend.reading.snapshot })
        }
        assertTrue(backend.reads > 0)
    }

    @Test fun changedReadingOrAudienceAndExplicitRefreshAlwaysBroadcastToAllEligiblePeers() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        val permitted = mutableSetOf(requester, viewer)
        val sent = mutableListOf<Set<ClientId>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { it in permitted }, recipients = { listOf(requester, viewer, stranger) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { 100_000 },
            send = { id, _, _ -> sent.add(id); id.size })
        suspend fun platformChanged() {
            provider.onPlatformChanged()
            advanceTimeBy(301)
            runCurrent()
        }
        platformChanged()
        assertEquals(listOf(setOf(requester, viewer)), sent)
        sent.clear()
        platformChanged()
        assertTrue(sent.isEmpty())

        // Credentials can change without changing the enabled state.
        backend.reading = HotspotReading(HotspotResult.OK, snapshot.copy(psk = "changed-test-password"))
        platformChanged()
        assertEquals(listOf(setOf(requester, viewer)), sent)
        sent.clear()
        permitted += stranger
        platformChanged()
        assertEquals(listOf(setOf(requester, viewer, stranger)), sent)
        sent.clear()
        provider.executeScheduledRequest(requester, request(), 100_000)
        assertEquals(listOf(setOf(requester, viewer, stranger)), sent)
    }

    @Test fun partialBroadcastDoesNotSuppressTheNextFullBroadcast() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
        var failViewer = false
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { true }, recipients = { listOf(requester, viewer) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { 100_000 },
            send = { id, sync, _ -> sent += id to sync; id.size - if (failViewer && viewer in id) 1 else 0 })
        suspend fun platformChanged() {
            provider.onPlatformChanged()
            advanceTimeBy(301)
            runCurrent()
        }
        platformChanged()
        failViewer = true
        backend.reading = HotspotReading(HotspotResult.OK,
            snapshot.copy(state = HotspotState.DISABLED, wifiTethered = false))
        platformChanged()
        sent.clear()
        failViewer = false
        platformChanged()
        assertEquals(setOf(requester, viewer), sent.single().first)
        sent.clear()
        platformChanged()
        assertTrue(sent.isEmpty())

        // A failed intervening publication must also invalidate the previous complete reading.
        failViewer = true
        backend.reading = HotspotReading(HotspotResult.OK, snapshot)
        platformChanged()
        sent.clear()
        failViewer = false
        backend.reading = HotspotReading(HotspotResult.OK,
            snapshot.copy(state = HotspotState.DISABLED, wifiTethered = false))
        platformChanged()
        assertEquals(setOf(requester, viewer), sent.single().first)
    }

    @Test fun tenConcurrentRefreshesShareOneBroadcastAndQueriesBypassTheTenSecondCooldown() = runTest {
        val peers = listOf(requester, viewer) + (1..8).map { ClientId("controller-$it") }
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        var time = 100_000L
        val sent = mutableListOf<Pair<Set<ClientId>, HotspotSync>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { true }, recipients = { peers },
            consumeCommand = { _, _ -> error("Refresh must not consume the mutation watermark") },
            scheduleRequest = { _, _, _ -> }, now = { time },
            send = { id, sync, priority ->
                assertEquals(Urgency.NORMAL, priority)
                delay(1) // Other workers enter while the first multicast is still sending.
                sent += id to sync
                id.size
            })
        val refresh = request().copy(action = HotspotAction.REFRESH)
        peers.map { peer -> async {
            provider.executeScheduledRequest(peer, refresh.copy(requestId = peer.value), 100_000)
        } }.awaitAll()
        assertEquals(1, backend.reads)
        assertEquals(peers.toSet(), sent.single().first)
        assertTrue(sent.all { it.second.requestId == null })

        sent.clear()
        time = 109_999
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertTrue(sent.isEmpty())
        time = 110_000
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertEquals(2, backend.reads)
        assertEquals(peers.toSet(), sent.single().first)

        sent.clear()
        time = 110_001
        repeat(2) { provider.executeScheduledRequest(requester, request(), 100_000) }
        assertEquals(4, backend.reads)
        assertEquals(listOf(peers.toSet(), peers.toSet()), sent.map { it.first })
        assertTrue(sent.all { it.second.requestId == "request" })

        sent.clear()
        time = 120_000
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertTrue(sent.isEmpty())
        time = 120_001
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertEquals(5, backend.reads)
        assertEquals(peers.toSet(), sent.single().first)
    }

    @Test fun commandAndPlatformBroadcastsResetCooldownButSuppressedCallbacksDoNot() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        var time = 100_000L
        val sent = mutableListOf<Set<ClientId>>()
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { true }, authorized = { true }, recipients = { listOf(requester, viewer) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { time },
            send = { id, _, _ -> sent.add(id); id.size })
        val refresh = request().copy(action = HotspotAction.REFRESH)
        provider.executeScheduledRequest(requester, request(true), 100_000)
        sent.clear()
        time = 109_999
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertEquals(0, backend.reads)
        assertTrue(sent.isEmpty())

        backend.reading = HotspotReading(HotspotResult.OK,
            snapshot.copy(state = HotspotState.DISABLED, wifiTethered = false))
        provider.onPlatformChanged()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(setOf(requester, viewer)), sent)
        sent.clear()
        time = 119_998
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertTrue(sent.isEmpty())
        provider.onPlatformChanged()
        advanceTimeBy(301)
        runCurrent()
        assertTrue(sent.isEmpty())
        time = 119_999
        provider.executeScheduledRequest(viewer, refresh, 100_000)
        assertEquals(listOf(setOf(requester, viewer)), sent)
    }

    @Test fun rejectedRefreshIsSilentAndACompletelyFailedBroadcastDoesNotStartCooldown() = runTest {
        val backend = Backend(HotspotReading(HotspotResult.OK, snapshot))
        var time = 100_000L
        var permitted = false
        var sendSucceeds = false
        var sends = 0
        val provider = HotspotProvider(device, backgroundScope, backend,
            trustedPeer = { it != stranger }, authorized = { permitted }, recipients = { listOf(requester, viewer) },
            consumeCommand = { _, _ -> true }, scheduleRequest = { _, _, _ -> }, now = { time },
            send = { ids, _, _ -> sends++; if (sendSucceeds) ids.size else 0 })
        val refresh = request().copy(action = HotspotAction.REFRESH)
        provider.executeScheduledRequest(requester, refresh, 100_000)
        permitted = true
        provider.executeScheduledRequest(stranger, refresh, 100_000)
        time = 160_000
        provider.executeScheduledRequest(requester, refresh, 100_000)
        assertEquals(0, backend.reads)
        assertEquals(0, sends)

        time = 100_000
        provider.executeScheduledRequest(requester, refresh, 100_000)
        assertEquals(1, backend.reads)
        sendSucceeds = true
        provider.executeScheduledRequest(requester, refresh, 100_000)
        assertEquals(2, backend.reads)
        assertEquals(2, sends)
    }
}
