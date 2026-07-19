package net.extrawdw.notisync.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.daemon.DesktopProcessTitle
import net.extrawdw.notisync.daemon.NotisyncdCli
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonAutostarter
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.DeviceAction
import net.extrawdw.notisync.localapi.DeviceActionRequest
import net.extrawdw.notisync.localapi.DeviceClassification
import net.extrawdw.notisync.localapi.DeviceListResponse
import net.extrawdw.notisync.localapi.DeviceTrustStatus
import net.extrawdw.notisync.localapi.PairingAcceptRequest
import net.extrawdw.notisync.localapi.PairingCandidate
import net.extrawdw.notisync.localapi.QuarantineAction
import net.extrawdw.notisync.localapi.QuarantineActionRequest
import net.extrawdw.notisync.localapi.LocalApiJson

fun main(arguments: Array<String>) {
    DesktopProcessTitle.set("notisync")
    exitProcess(NotisyncCli().run(arguments))
}

class NotisyncCli(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val output: Appendable = System.out,
    private val error: Appendable = System.err,
    private val clientFactory: (Path) -> DaemonAdministration = ::DaemonAdminClient,
    private val autostart: () -> Unit = { DaemonAutostarter(paths).connect() },
    private val daemonRunner: (Array<String>) -> Int = { NotisyncdCli(paths, output, error).run(it) },
) {
    fun run(arguments: Array<String>): Int = try {
        if (arguments.isEmpty() || arguments[0] in setOf("-h", "--help", "help")) {
            output.appendLine(usage())
            0
        } else if (arguments[0] == "daemon") {
            daemon(arguments.drop(1))
        } else {
            val client = clientFactory(paths.socket)
            when (arguments[0]) {
                "status" -> status(client)
                "config" -> withDaemon(client) { config(it, arguments.drop(1)) }
                "devices" -> withDaemon(client) { devices(it, arguments.drop(1)) }
                "quarantine" -> withDaemon(client) { quarantine(it, arguments.drop(1)) }
                else -> throw CliError("unknown command: ${arguments[0]}")
            }
            0
        }
    } catch (failure: Throwable) {
        error.appendLine("notisync: ${failure.message ?: failure.javaClass.simpleName}")
        1
    }

    private fun withDaemon(client: DaemonAdministration, block: (DaemonAdministration) -> Unit) {
        if (runCatching { client.status() }.isFailure) autostart()
        block(client)
    }

    private fun daemon(arguments: List<String>): Int {
        val command = arguments.firstOrNull() ?: "status"
        if (arguments.size > 1) throw CliError("daemon $command takes no arguments")
        return when (command) {
            "status" -> status(clientFactory(paths.socket)).let { 0 }
            "start", "stop", "restart" -> daemonRunner(arrayOf(command))
            "help", "--help", "-h" -> output.appendLine(daemonUsage()).let { 0 }
            else -> throw CliError("daemon requires status, start, stop, or restart")
        }
    }

    private fun status(client: DaemonAdministration) {
        val status = try {
            client.status()
        } catch (failure: DaemonConnectionException) {
            val message = if (failure.daemonNotRunning) {
                "notisyncd is not running"
            } else {
                "unable to connect to notisyncd"
            }
            throw CliError(message)
        }
        output.appendLine("notisyncd ${status.version}: ${status.connectionState.name.lowercase()}")
        output.appendLine("device: ${status.deviceName ?: "unknown"}")
        output.appendLine("client: ${status.clientId ?: "initializing"}")
        output.appendLine("broker: ${status.brokerUrl ?: "not configured"}")
        output.appendLine("capabilities: ${status.capabilities.sorted().joinToString(", ")}")
        if (status.trustStoreQuarantined) output.appendLine("trust store: QUARANTINED")
        status.message?.let { output.appendLine("detail: $it") }
    }

    private fun config(client: DaemonAdministration, arguments: List<String>) {
        when (arguments.firstOrNull() ?: "get") {
            "get" -> {
                if (arguments.size != 1 && arguments.isNotEmpty()) {
                    throw CliError("config get takes no arguments")
                }
                output.appendLine(LocalApiJson.encodeToString(client.config()))
            }
            "set" -> {
                if (arguments.size != 3 || arguments[1] != "device-name") {
                    throw CliError("config set requires device-name NAME")
                }
                output.appendLine(
                    LocalApiJson.encodeToString(
                        client.patchConfig(DaemonConfigPatch(deviceName = arguments[2])),
                    ),
                )
            }
            else -> throw CliError("config requires get or set")
        }
    }

    private fun pairing(client: DaemonAdministration, arguments: List<String>) {
        when (arguments.firstOrNull() ?: "show") {
            "show", "qr" -> {
                val pairing = client.pairing()
                output.appendLine(pairing.deepLink)
                output.appendLine()
                output.append(TerminalQr.render(pairing.deepLink))
                if ("--payload" in arguments.drop(1)) output.appendLine("payload: ${pairing.payload}")
            }
            "inspect" -> printCandidate(client.inspectPairing(pairingInput(arguments.drop(1))))
            "accept" -> {
                val rest = arguments.drop(1)
                val classification = if ("--other" in rest) DeviceClassification.OTHER else DeviceClassification.OWN
                val values = rest.filterNot { it == "--own" || it == "--other" }
                val candidate = client.acceptPairing(PairingAcceptRequest(pairingInput(values), classification))
                output.appendLine("trusted ${candidate.name} as ${classification.name.lowercase()} device")
                printCandidate(candidate)
            }
            else -> throw CliError("pair requires show, inspect, or accept")
        }
    }

    private fun devices(client: DaemonAdministration, arguments: List<String>) {
        when (arguments.firstOrNull() ?: "list") {
            "list" -> printDevices(client.devices())
            "action" -> deviceAction(client, arguments.drop(1))
            "pair" -> pairing(client, arguments.drop(1))
            else -> throw CliError("devices requires list, action, or pair")
        }
    }

    private fun deviceAction(client: DaemonAdministration, arguments: List<String>) {
        val approveAll = "--all" in arguments
        if (arguments.count { it == "--all" } > 1) throw CliError("--all may only be specified once")
        val values = arguments.filterNot { it == "--all" }
        if (approveAll) {
            if (values.singleOrNull()?.toDeviceActionOrNull() != DeviceAction.APPROVE) {
                throw CliError("--all is only valid with devices action approve")
            }
            val initial = client.devices()
            val pending = initial.devices.filter {
                it.trustStatus == DeviceTrustStatus.PENDING && DeviceAction.APPROVE in it.allowedActions
            }
            if (pending.isEmpty()) {
                output.appendLine("No pending devices.")
                return
            }
            var response = initial
            for (device in pending) {
                response = client.deviceAction(device.clientId, DeviceActionRequest(DeviceAction.APPROVE))
            }
            output.appendLine("Approved ${pending.size} pending ${if (pending.size == 1) "device" else "devices"}.")
            printDevices(response)
            return
        }

        if (values.size != 2) throw CliError("devices action requires ACTION and DEVICE_ID, or approve --all")
        val action = values[0].toDeviceActionOrNull()
            ?: throw CliError("unknown device action: ${values[0]}")
        printDevices(client.deviceAction(values[1], DeviceActionRequest(action)))
    }

    private fun quarantine(client: DaemonAdministration, arguments: List<String>) {
        val action = when (arguments.singleOrNull()) {
            "approve", "approve-and-resign" -> QuarantineAction.APPROVE_AND_RESIGN
            "clear" -> QuarantineAction.CLEAR
            else -> throw CliError("quarantine requires approve or clear")
        }
        printDevices(client.quarantine(QuarantineActionRequest(action)))
    }

    private fun pairingInput(arguments: List<String>): String {
        if (arguments.size != 1) throw CliError("pairing command requires one link, payload, or '-' for stdin")
        return if (arguments[0] == "-") {
            generateSequence(::readLine).joinToString("\n").trim().takeIf(String::isNotEmpty)
                ?: throw CliError("stdin contained no pairing link")
        } else {
            arguments[0]
        }
    }

    private fun printCandidate(candidate: PairingCandidate) {
        output.appendLine("name: ${candidate.name}")
        output.appendLine("client: ${candidate.clientId}")
        output.appendLine("fingerprint: ${candidate.identityFingerprint}")
        output.appendLine("capabilities: ${candidate.capabilities.sorted().joinToString(", ")}")
    }

    private fun printDevices(response: DeviceListResponse) {
        if (response.devices.isEmpty()) {
            output.appendLine("No paired devices.")
            return
        }
        for (device in response.devices) {
            output.appendLine(
                "${device.clientId}\t${device.name}\t${device.classification.name.lowercase()}\t" +
                    device.trustStatus.name.lowercase(),
            )
            output.appendLine("  identity fingerprint: ${device.identityFingerprint}")
            output.appendLine("  key available: ${device.keyAvailable}")
            output.appendLine("  verified: ${device.verified}")
            output.appendLine("  current epoch: ${device.currentEpoch}")
            output.appendLine("  signing fingerprint: ${device.signingKeyFingerprint ?: "unavailable"}")
            output.appendLine("  HPKE fingerprint: ${device.hpkeKeyFingerprint ?: "unavailable"}")
            if (device.allowedActions.isNotEmpty()) {
                output.appendLine("  actions: ${device.allowedActions.joinToString(", ") { it.name.lowercase() }}")
            }
        }
    }

    private fun usage(): String = """
        Usage: notisync COMMAND

          status
          daemon [status]
          daemon start|stop|restart
          config get
          config set device-name NAME
          devices [list]
          devices pair show [--payload]
          devices pair inspect LINK|PAYLOAD|-
          devices pair accept [--own|--other] LINK|PAYLOAD|-
          devices action approve|reject|revoke|confirm-revoke|decline-revoke|restore|keep|purge DEVICE_ID
          devices action approve --all
          quarantine approve|clear
    """.trimIndent()

    private fun daemonUsage(): String = """
        Usage: notisync daemon [status|start|stop|restart]
    """.trimIndent()
}

private fun String.toDeviceActionOrNull(): DeviceAction? = runCatching {
    DeviceAction.valueOf(replace('-', '_').uppercase())
}.getOrNull()

private object TerminalQr {
    fun render(value: String): String {
        val matrix = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        return buildString {
            var y = 0
            while (y < matrix.height) {
                for (x in 0 until matrix.width) {
                    val top = matrix[x, y]
                    val bottom = y + 1 < matrix.height && matrix[x, y + 1]
                    append(
                        when {
                            top && bottom -> '█'
                            top -> '▀'
                            bottom -> '▄'
                            else -> ' '
                        },
                    )
                }
                append('\n')
                y += 2
            }
        }
    }
}

private class CliError(message: String) : IllegalArgumentException(message)
