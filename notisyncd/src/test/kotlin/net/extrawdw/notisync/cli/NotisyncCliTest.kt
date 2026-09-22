package net.extrawdw.notisync.cli

import java.io.IOException
import java.nio.file.Path
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotisyncCliTest {
    @Test
    fun `default rendezvous QR fits a small terminal`() {
        val link = BrokerPairingLink("abcdefghijklmnopqrstuv", "A".repeat(43), "a".repeat(32)).encode()
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 1, 1, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        ))
        assertTrue("QR width ${matrix.width}", matrix.width <= 57)
        assertTrue("Terminal rows ${(matrix.height + 1) / 2}", (matrix.height + 1) / 2 <= 29)
    }

    @Test
    fun `default pair exchanges cards displays received identity and asks before trusting`() {
        for ((choice, classification) in listOf("1" to DeviceClassification.OWN, "2" to DeviceClassification.OTHER)) {
            val admin = FakeAdministration(mutableListOf())
            val fixture = CliFixture(admin, readPairingChoice = { choice }, brokerPairing = { broker, payload, ready ->
                assertEquals(admin.config().brokerUrl, broker)
                assertEquals("payload", payload)
                ready(BrokerPairingLink("abcdefghijklmnopqrstuv", "A".repeat(43), "a".repeat(32)))
                "received-card"
            })
            assertEquals(0, fixture.cli.run(arrayOf("devices", "pair")))
            assertEquals(listOf("received-card"), admin.inspectedPairings)
            assertEquals(listOf(PairingAcceptRequest("received-card", classification)), admin.acceptedPairings)
            val text = fixture.output.toString()
            assertTrue(text.contains("Scan this QR code with NotiSync on the other device."))
            assertTrue(!text.contains("Pairing code:"))
            assertTrue(text.indexOf("name: Phone") < text.indexOf("Trust this device?"))
        }
    }

    @Test
    fun `cancel or absent terminal input never adds trust`() {
        for (choice in listOf(null, "", "yes")) {
            val admin = FakeAdministration(mutableListOf())
            val fixture = CliFixture(admin, readPairingChoice = { choice }, brokerPairing = { _, _, _ -> "received" })
            assertEquals(0, fixture.cli.run(arrayOf("devices", "pair")))
            assertTrue(admin.acceptedPairings.isEmpty())
            assertTrue(fixture.output.toString().contains("Device was not trusted"))
        }
    }

    @Test
    fun `legacy show keeps CARD QR and payload without contacting broker`() {
        val fixture = CliFixture()
        assertEquals(0, fixture.cli.run(arrayOf("devices", "pair", "show", "--payload")))
        assertTrue(fixture.output.toString().contains("https://notisync.invalid/pair"))
        assertTrue(fixture.output.toString().contains("payload: payload"))
    }

    @Test
    fun `failed key exchange never inspects or trusts a CARD`() {
        val admin = FakeAdministration(mutableListOf())
        val fixture = CliFixture(admin, brokerPairing = { _, _, _ -> error("Pairing authentication failed") })
        assertEquals(1, fixture.cli.run(arrayOf("devices", "pair")))
        assertTrue(admin.inspectedPairings.isEmpty())
        assertTrue(admin.acceptedPairings.isEmpty())
    }

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
    fun `skills command stays local and does not autostart daemon`() {
        val skillCommands = mutableListOf<List<String>>()
        var autostarts = 0
        val fixture = CliFixture(
            autostart = { autostarts += 1 },
            skillsRunner = {
                skillCommands += it
                0
            },
        )

        assertEquals(0, fixture.cli.run(arrayOf("skills", "add", "notisync-seal")))
        assertEquals(listOf(listOf("add", "notisync-seal")), skillCommands)
        assertEquals(0, autostarts)
        assertEquals("", fixture.error.toString())
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
        skillsRunner: (List<String>) -> Int = { 0 },
        readPairingChoice: () -> String? = { null },
        brokerPairing: (String, String, (BrokerPairingLink) -> Unit) -> String = { _, _, _ ->
            error("Unexpected broker pairing connection")
        },
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
            skillsRunner = skillsRunner,
            readPairingChoice = readPairingChoice,
            brokerPairing = brokerPairing,
        )
    }

    private class FakeAdministration(
        private val deviceState: MutableList<DeviceView>,
        private var statusFailures: Int = 0,
        private val applicationState: MutableList<ApplicationView> = mutableListOf(),
    ) : DaemonAdministration {
        val actions = mutableListOf<Pair<String, DeviceAction>>()
        val inspectedPairings = mutableListOf<String>()
        val acceptedPairings = mutableListOf<PairingAcceptRequest>()
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

        override fun acceptPairing(request: PairingAcceptRequest): PairingCandidate {
            acceptedPairings += request
            return candidate()
        }

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
