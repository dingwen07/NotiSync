package net.extrawdw.notisync.protocol

import org.junit.Assert.*
import org.junit.Test

class HotspotProtocolTest {
    private val request = HotspotSync(HotspotAction.QUERY, ClientId("hotspot"), 100_000, 160_000, "refresh")

    @Test fun requestsAndStatusesRoundTripWithoutLosingConfiguration() {
        val status = request.copy(action = HotspotAction.STATUS, result = HotspotResult.OK,
            snapshot = HotspotSnapshot(HotspotState.DISABLED, "Test Wi-Fi", "test-password", 2, false, true))
        for (sync in listOf(request, request.copy(action = HotspotAction.REFRESH),
            request.copy(action = HotspotAction.SET_ENABLED, enabled = false), status)) {
            val data = DataSync(DataSyncKind.HOTSPOT, hotspot = sync)
            assertEquals(data, ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(data)))
            assertTrue(sync.isValid(100_000, 100_000))
        }
        assertFalse(status.toString().contains("test-password"))
        assertFalse(status.toString().contains("Test Wi-Fi"))
    }

    @Test fun rejectsExpiredFutureMismatchedAndAmbiguousRequests() {
        assertFalse(request.isValid(160_000, 100_000))
        assertFalse(request.isValid(60_000, 100_000))
        assertFalse(request.isValid(100_000, 69_999))
        for (bad in listOf(
            request.copy(expiresAt = 160_001), request.copy(requestId = ""),
            request.copy(requestId = "line\nfeed"), request.copy(enabled = true),
            request.copy(action = HotspotAction.SET_ENABLED), request.copy(result = HotspotResult.OK),
            request.copy(snapshot = HotspotSnapshot(HotspotState.ENABLED)),
            request.copy(action = HotspotAction.STATUS, result = HotspotResult.UNAUTHORIZED,
                snapshot = HotspotSnapshot(HotspotState.ENABLED, psk = "test-password")),
        )) assertFalse(bad.isValid(100_000, 100_000))
    }

    @Test fun automaticRefreshHasNoCommandOrStatusPayloadButRetainsADurableRequestId() {
        val refresh = request.copy(action = HotspotAction.REFRESH)
        assertTrue(refresh.isValid(100_000, 100_000))
        for (bad in listOf(
            refresh.copy(requestId = null), refresh.copy(enabled = true),
            refresh.copy(snapshot = HotspotSnapshot(HotspotState.ENABLED)),
            refresh.copy(result = HotspotResult.OK), refresh.copy(platformError = 1),
        )) assertFalse(bad.isValid(100_000, 100_000))
    }

    @Test fun onlyQueriesAndRefreshesAllowAnOmittedTarget() {
        for (action in listOf(HotspotAction.QUERY, HotspotAction.REFRESH)) {
            val sync = HotspotSync(action = action, issuedAt = 100_000, expiresAt = 160_000, requestId = "fanout")
            assertNull(sync.hotspotDeviceId)
            assertTrue(sync.isValid(100_000, 100_000))
            val data = DataSync(DataSyncKind.HOTSPOT, hotspot = sync)
            assertEquals(data, ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(data)))
        }
        assertFalse(request.copy(hotspotDeviceId = null, action = HotspotAction.SET_ENABLED, enabled = true)
            .isValid(100_000, 100_000))
        assertFalse(request.copy(hotspotDeviceId = null, action = HotspotAction.STATUS, result = HotspotResult.OK)
            .isValid(100_000, 100_000))
    }

    @Test fun hotspotCapabilitiesUseStableWireIds() {
        assertEquals(listOf(Capability.HOTSPOT_PROVIDER_V1, Capability.HOTSPOT_CONTROL_V1),
            kotlinx.serialization.cbor.Cbor.decodeFromByteArray(
                CapabilityListSerializer, byteArrayOf(0x82.toByte(), 0x18, 25, 0x18, 26)))
    }
}
