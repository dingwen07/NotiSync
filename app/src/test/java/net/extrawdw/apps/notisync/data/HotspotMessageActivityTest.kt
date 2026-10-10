package net.extrawdw.apps.notisync.data

import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.HotspotAction
import net.extrawdw.notisync.protocol.HotspotResult
import net.extrawdw.notisync.protocol.HotspotSnapshot
import net.extrawdw.notisync.protocol.HotspotState
import net.extrawdw.notisync.protocol.HotspotSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotMessageActivityTest {
    private val request = HotspotSync(HotspotAction.QUERY, ClientId("hotspot"), 100_000, 160_000, "request")
    private fun labels(sync: HotspotSync) = messageActivityLabels(DataSync(DataSyncKind.HOTSPOT, hotspot = sync))

    @Test fun requestLabelsDescribeIntentWithoutClaimingCompletion() {
        assertEquals(R.string.activity_message_hotspot to R.string.activity_message_hotspot_refresh, labels(request))
        assertEquals(R.string.activity_message_hotspot to R.string.activity_message_hotspot_refresh,
            labels(request.copy(action = HotspotAction.REFRESH)))
        assertEquals(R.string.activity_message_hotspot to R.string.activity_message_hotspot_enable,
            labels(request.copy(action = HotspotAction.SET_ENABLED, enabled = true)))
        assertEquals(R.string.activity_message_hotspot to R.string.activity_message_hotspot_disable,
            labels(request.copy(action = HotspotAction.SET_ENABLED, enabled = false)))
        assertNull(labels(request.copy(action = HotspotAction.SET_ENABLED)))
        assertNull(messageActivityLabels(DataSync(DataSyncKind.HOTSPOT)))
    }

    @Test fun statusLabelsNeverIncludeCredentialsOrPeerProvidedDiagnostics() {
        val expected = R.string.activity_message_hotspot to R.string.activity_message_hotspot_status
        val status = request.copy(action = HotspotAction.STATUS, result = HotspotResult.OK,
            snapshot = HotspotSnapshot(HotspotState.ENABLED, "private-network", "test-password", 2, true))
        assertEquals(expected, labels(status))
        assertEquals(expected, labels(status.copy(snapshot = status.snapshot?.copy(ssid = "other", psk = "other-password"))))
        assertEquals(expected, labels(status.copy(result = HotspotResult.FAILED, snapshot = null, platformError = 14)))
    }
}
