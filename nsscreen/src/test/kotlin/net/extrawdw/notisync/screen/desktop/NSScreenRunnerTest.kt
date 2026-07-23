package net.extrawdw.notisync.screen.desktop

import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.Capability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NSScreenRunnerTest {
    @Test
    fun `device listing requires the complete v1 source capability set`() {
        val full = capabilities()
        val daemon = DeviceDaemon(
            listOf(
                device("complete", full),
                device("missing-control", full - Capability.SCREEN_MIRROR_CONTROL_V1.name),
                device("missing-clipboard", full - Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1.name),
            ),
        )
        val output = StringBuilder()

        NSScreenRunner(
            daemonConnector = { daemon },
            helper = { _, _, _ -> error("unused") },
        ).listDevices(output)

        assertTrue(output.contains("complete"))
        assertFalse(output.contains("missing-control"))
        assertFalse(output.contains("missing-clipboard"))
    }

    private fun capabilities(): Set<String> = setOf(
        Capability.CAPABILITY_ROUTING_V1,
        Capability.SCREEN_MIRROR_SOURCE_V1,
        Capability.SCREEN_MIRROR_CONTROL_V1,
        Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
        Capability.SCREEN_MIRROR_ENCODER_H264_HW,
    ).mapTo(mutableSetOf()) { it.name }

    private fun device(id: String, capabilities: Set<String>) = DeviceView(
        clientId = id,
        name = id,
        classification = DeviceClassification.OWN,
        trustStatus = DeviceTrustStatus.TRUSTED,
        capabilities = capabilities,
        identityFingerprint = "fingerprint-$id",
        keyAvailable = true,
        verified = true,
    )

    private class DeviceDaemon(devices: List<DeviceView>) : DaemonLocalApi {
        private val response = DeviceListResponse(devices)
        override fun devices() = response
        override fun status(): DaemonStatus = error("unused")
        override fun putApplication(applicationId: String, request: ApplicationRegistrationRequest): ApplicationView =
            error("unused")
        override fun listApplications(): ApplicationListResponse = error("unused")
        override fun deleteApplication(applicationId: String) = error("unused")
        override fun send(request: SendRequest): SendAccepted = error("unused")
        override fun sendAll(requests: List<SendRequest>): List<SendAccepted> = error("unused")
        override fun openReceive(request: ReceiveRequest): ReceiveStream = error("unused")
        override fun unregisterReceive(request: ReceiveRequest) = error("unused")
        override fun ack(applicationId: String, envelopeId: String) = error("unused")
        override fun complete(applicationId: String, envelopeId: String, sends: List<SendRequest>) = error("unused")
    }
}
