package net.extrawdw.notisync.cli

import java.io.IOException
import java.nio.file.Path
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.localapi.ApplicationListResponse
import net.extrawdw.notisync.localapi.ApplicationView
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DaemonConfigView
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.DeviceView
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.PairingPayloadResponse
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.protocol.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotisyncCliTest {
    @Test
    fun `help lists every device action inline`() {
        val fixture = CliFixture()

        assertEquals(0, fixture.cli.run(arrayOf("--help")))
        assertTrue(
            fixture.output.toString().contains(
                "devices action approve|reject|revoke|confirm-revoke|decline-revoke|restore|keep|purge DEVICE_ID",
            ),
        )
    }

    @Test
    fun `daemon restart delegates without autostarting first`() {
        val daemonCommands = mutableListOf<List<String>>()
        var autostarts = 0
        val fixture = CliFixture(
            autostart = { autostarts += 1 },
            daemonRunner = {
                daemonCommands += it.toList()
                0
            },
        )

        assertEquals(0, fixture.cli.run(arrayOf("daemon", "restart")))
        assertEquals(listOf(listOf("restart")), daemonCommands)
        assertEquals(0, autostarts)
        assertEquals("", fixture.error.toString())
    }

    @Test
    fun `daemon status is nested and root status remains an alias`() {
        val fixture = CliFixture()

        assertEquals(0, fixture.cli.run(arrayOf("daemon", "status")))
        assertTrue(fixture.output.toString().contains("notisyncd 1.2.3: connected"))
        fixture.output.clear()

        assertEquals(0, fixture.cli.run(arrayOf("status")))
        assertTrue(fixture.output.toString().contains("notisyncd 1.2.3: connected"))
    }

    @Test
    fun `daemon status does not autostart and daemon start is explicit`() {
        val administration = FakeAdministration(mutableListOf(), statusFailures = 1)
        var autostarts = 0
        val daemonCommands = mutableListOf<List<String>>()
        val fixture = CliFixture(
            administration,
            autostart = { autostarts += 1 },
            daemonRunner = {
                daemonCommands += it.toList()
                0
            },
        )

        assertEquals(1, fixture.cli.run(arrayOf("daemon")))
        assertEquals(0, autostarts)
        assertTrue(fixture.error.toString().contains("notisyncd is not running"))

        assertEquals(0, fixture.cli.run(arrayOf("daemon", "start")))
        assertEquals(listOf(listOf("start")), daemonCommands)
    }

    @Test
    fun `device action accepts action followed by device id`() {
        val administration = FakeAdministration(mutableListOf(pending("pending-a"), pending("pending-b")))
        val fixture = CliFixture(administration)

        assertEquals(0, fixture.cli.run(arrayOf("devices", "action", "reject", "pending-a")))
        assertEquals("pending-a" to DeviceAction.REJECT, administration.actions[0])
        assertEquals("", fixture.error.toString())
    }

    @Test
    fun `device action rejects device id before action`() {
        val administration = FakeAdministration(mutableListOf(pending("pending-a")))
        val fixture = CliFixture(administration)

        assertEquals(1, fixture.cli.run(arrayOf("devices", "action", "pending-a", "approve")))
        assertTrue(fixture.error.toString().contains("unknown device action: pending-a"))
        assertTrue(administration.actions.isEmpty())
    }

    @Test
    fun `approve all acts on every pending device and no trusted devices`() {
        val administration = FakeAdministration(
            mutableListOf(
                pending("pending-a"),
                trusted("trusted-a"),
                pending("pending-b"),
            ),
        )
        val fixture = CliFixture(administration)

        assertEquals(0, fixture.cli.run(arrayOf("devices", "action", "approve", "--all")))
        assertEquals(
            listOf(
                "pending-a" to DeviceAction.APPROVE,
                "pending-b" to DeviceAction.APPROVE,
            ),
            administration.actions,
        )
        assertTrue(fixture.output.toString().contains("Approved 2 pending devices."))
        assertEquals("", fixture.error.toString())
    }

    @Test
    fun `devices list prints platform and sorted capabilities`() {
        val device = trusted("trusted-a").copy(
            platform = "android",
            capabilities = setOf("PUSH_FILTERING", "DISPLAY"),
        )
        val fixture = CliFixture(FakeAdministration(mutableListOf(device)))

        assertEquals(0, fixture.cli.run(arrayOf("devices", "list")))
        assertTrue(
            fixture.output.toString().contains(
                "  platform: android\n  capabilities: DISPLAY, PUSH_FILTERING\n",
            ),
        )
        assertEquals("", fixture.error.toString())
    }

    @Test
    fun `pairing is nested under devices and unavailable at the root`() {
        val administration = FakeAdministration(mutableListOf())
        val fixture = CliFixture(administration)

        assertEquals(0, fixture.cli.run(arrayOf("devices", "pair", "inspect", "nested-payload")))
        assertEquals(listOf("nested-payload"), administration.inspectedPairings)

        assertEquals(1, fixture.cli.run(arrayOf("pair", "inspect", "root-payload")))
        assertTrue(fixture.error.toString().contains("unknown command: pair"))
    }

    @Test
    fun `applications list prints registrations and deterministic effective capabilities`() {
        val administration = FakeAdministration(
            mutableListOf(),
            applicationState = mutableListOf(
                ApplicationView(
                    applicationId = "nsrun",
                    displayName = "NotiSync Run",
                    version = "1.2.3",
                    capabilities = listOf(Capability.CAPTURE, Capability.PUBLISH_RUNS),
                    updatedAtEpochMillis = 123,
                ),
            ),
        )
        val fixture = CliFixture(administration)

        assertEquals(0, fixture.cli.run(arrayOf("applications", "list")))
        assertTrue(fixture.output.toString().contains("nsrun\tNotiSync Run\tCAPTURE,PUBLISH_RUNS"))
        assertTrue(fixture.output.toString().contains("  version: 1.2.3"))
        assertTrue(
            fixture.output.toString().contains(
                "effective capabilities: CAPTURE, FOREGROUND_CONNECTION, CAPABILITY_ROUTING_V1, PUBLISH_RUNS",
            ),
        )
        assertEquals("", fixture.error.toString())
    }

    @Test
    fun `applications remove delegates the exact application id`() {
        val administration = FakeAdministration(
            mutableListOf(),
            applicationState = mutableListOf(
                ApplicationView("nsrun", "NotiSync Run", capabilities = emptyList(), updatedAtEpochMillis = 1),
            ),
        )
        val fixture = CliFixture(administration)

        assertEquals(0, fixture.cli.run(arrayOf("apps", "remove", "nsrun")))
        assertEquals(listOf("nsrun"), administration.removedApplications)
        assertTrue(fixture.output.toString().contains("Removed application nsrun."))
        assertEquals("", fixture.error.toString())
    }

    private class CliFixture(
        administration: FakeAdministration = FakeAdministration(mutableListOf()),
        autostart: () -> Unit = {},
        daemonRunner: (Array<String>) -> Int = { 0 },
    ) {
        val output = StringBuilder()
        val error = StringBuilder()
        val cli = NotisyncCli(
            paths = DesktopPaths(Path.of("/private/tmp/notisync-cli-test")),
            output = output,
            error = error,
            clientFactory = { administration },
            autostart = autostart,
            daemonRunner = daemonRunner,
        )
    }

    private class FakeAdministration(
        private val deviceState: MutableList<DeviceView>,
        private var statusFailures: Int = 0,
        private val applicationState: MutableList<ApplicationView> = mutableListOf(),
    ) : DaemonAdministration {
        val actions = mutableListOf<Pair<String, DeviceAction>>()
        val inspectedPairings = mutableListOf<String>()
        val removedApplications = mutableListOf<String>()

        override fun status(): DaemonStatus {
            if (statusFailures > 0) {
                statusFailures -= 1
                throw DaemonConnectionException(Path.of("/private/tmp/missing-notisyncd"), IOException("missing"))
            }
            return DaemonStatus(
                version = "1.2.3",
                clientId = "desktop",
                deviceName = "Desktop",
                connectionState = DaemonConnectionState.CONNECTED,
                brokerUrl = "wss://notisync.invalid",
            )
        }

        override fun config() = DaemonConfigView(
            brokerUrl = "wss://notisync.invalid",
            deviceName = "Desktop",
            platformName = "desktop",
            automaticallyApplyTrustedDeviceTables = false,
            logLevel = "info",
            websocketPingSeconds = 30,
        )

        override fun patchConfig(patch: DaemonConfigPatch): DaemonConfigView = config()

        override fun pairing() = PairingPayloadResponse("payload", "https://notisync.invalid/pair")

        override fun inspectPairing(payload: String): PairingCandidate {
            inspectedPairings += payload
            return candidate()
        }

        override fun acceptPairing(request: PairingAcceptRequest): PairingCandidate = candidate()

        override fun devices() = DeviceListResponse(deviceState.toList())

        override fun deviceAction(clientId: String, action: DeviceActionRequest): DeviceListResponse {
            actions += clientId to action.action
            val index = deviceState.indexOfFirst { it.clientId == clientId }
            check(index >= 0) { "unknown device $clientId" }
            val status = when (action.action) {
                DeviceAction.APPROVE -> DeviceTrustStatus.TRUSTED
                DeviceAction.REJECT -> DeviceTrustStatus.REVOKED
                else -> deviceState[index].trustStatus
            }
            deviceState[index] = deviceState[index].copy(trustStatus = status, allowedActions = emptySet())
            return devices()
        }

        override fun quarantine(request: QuarantineActionRequest): DeviceListResponse = devices()

        override fun applications() = ApplicationListResponse(
            applications = applicationState.toList(),
            effectiveCapabilities = listOf(
                Capability.CAPTURE,
                Capability.FOREGROUND_CONNECTION,
                Capability.CAPABILITY_ROUTING_V1,
                Capability.PUBLISH_RUNS,
            ),
        )

        override fun removeApplication(applicationId: String) {
            removedApplications += applicationId
            applicationState.removeIf { it.applicationId == applicationId }
        }

        override fun shutdown() = Unit

        private fun candidate() = PairingCandidate(
            clientId = "phone",
            name = "Phone",
            identityFingerprint = "fingerprint",
        )
    }

    private companion object {
        fun pending(clientId: String) = DeviceView(
            clientId = clientId,
            name = clientId,
            classification = DeviceClassification.OWN,
            trustStatus = DeviceTrustStatus.PENDING,
            identityFingerprint = "fingerprint-$clientId",
            allowedActions = setOf(DeviceAction.APPROVE, DeviceAction.REJECT),
        )

        fun trusted(clientId: String) = DeviceView(
            clientId = clientId,
            name = clientId,
            classification = DeviceClassification.OWN,
            trustStatus = DeviceTrustStatus.TRUSTED,
            identityFingerprint = "fingerprint-$clientId",
            allowedActions = setOf(DeviceAction.REVOKE),
        )
    }
}
