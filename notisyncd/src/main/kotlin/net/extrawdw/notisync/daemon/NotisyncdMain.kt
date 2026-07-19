package net.extrawdw.notisync.daemon

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.encodeToString
import net.extrawdw.notisync.cli.DaemonConnectionException
import net.extrawdw.notisync.cli.DaemonAdminClient
import net.extrawdw.notisync.cli.NotisyncCli
import net.extrawdw.notisync.daemon.peer.runtime.createFileBackedDesktopPeer
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.daemon.storage.DaemonAlreadyRunningException
import net.extrawdw.notisync.daemon.storage.DaemonInstanceLock
import net.extrawdw.notisync.daemon.storage.DaemonPidRecord
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.desktop.DesktopProcessTitle
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.desktop.config.NotisyncdConfigStore
import net.extrawdw.notisync.localapi.DaemonConfigPatch
import net.extrawdw.notisync.localapi.LocalApiJson

fun main(arguments: Array<String>) {
    DesktopProcessSession.detachIfRequested()
    exitProcess(NotisyncdCli().run(arguments))
}

class NotisyncdCli(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val output: Appendable = System.out,
    private val error: Appendable = System.err,
) {
    private val layout = DaemonStorageLayout(paths.dataDirectory, paths.logDirectory)
    private val fileSystem = SecureFileSystem()
    private val daemonLogger = DaemonLogger("WARN", error)
    private var runningForeground = false

    fun run(arguments: Array<String>): Int {
        val command = arguments.firstOrNull() ?: "foreground"
        runningForeground = command == "foreground" || command == "run"
        return try {
            when (command) {
                "foreground", "run" -> foreground()
                "start" -> start()
                "stop" -> stop()
                "restart" -> restart()
                "status" -> status()
                "config" -> config(arguments.drop(1))
                "pair", "pairing" ->
                    NotisyncCli(paths, output, error).run(arrayOf("devices", "pair") + arguments.drop(1))
                "devices", "peers" ->
                    NotisyncCli(paths, output, error).run(arrayOf("devices") + arguments.drop(1))
                "help", "--help", "-h" -> output.appendLine(usage()).let { 0 }
                else -> throw IllegalArgumentException("unknown command: ${arguments[0]}")
            }
        } catch (running: DaemonAlreadyRunningException) {
            reportFailure(running.message ?: "notisyncd is already running", warning = true)
            2
        } catch (failure: Throwable) {
            reportFailure(failure.message ?: failure.javaClass.simpleName, warning = false)
            1
        }
    }

    private fun foreground(): Int {
        requireSupportedDesktop()
        DesktopProcessTitle.set("notisyncd")
        layout.prepare(fileSystem)
        if (daemonResponds()) throw DaemonAlreadyRunningException(DaemonInstanceLock.readPidRecord(layout.pidFile))
        DaemonInstanceLock.acquire(layout, fileSystem).use {
            removeStaleSocket()
            val configStore = NotisyncdConfigStore(layout.daemonConfigFile)
            val recoveryMessages = mutableListOf<String>()
            val config = configStore.loadRecovering(recoveryMessages::add)
            daemonLogger.updateLevel(config.logLevel)
            recoveryMessages.forEach(daemonLogger::warn)
            daemonLogger.info(
                "Starting notisyncd (PID ${ProcessHandle.current().pid()}); " +
                    "data=${layout.dataDirectory}, logs=${layout.logDirectory}, level=${config.logLevel.uppercase()}",
            )
            val identityResolver = ProcessIdentityResolver()
            val peer = createFileBackedDesktopPeer(
                layout = layout,
                configStore = configStore,
                identityResolver = identityResolver,
                fileSystem = fileSystem,
                logger = daemonLogger,
            )
            val sendResolver = GenericSendResolver(peer.applications, peer.actionOrigins)
            val sendDispatcher = GenericSendDispatcher(
                outbox = peer.outbox,
                sender = peer.sender,
                logger = daemonLogger,
            )
            val service = DaemonService(
                configStore = configStore,
                applications = peer.applications,
                receiver = peer.receiver,
                sendResolver = sendResolver,
                sendDispatcher = sendDispatcher,
                peerAdministration = peer.administration,
                logger = daemonLogger,
                onMaterialProfileChanged = peer.runtime::requestProfilePublication,
                onConfigChanged = { old, updated ->
                    daemonLogger.updateLevel(updated.logLevel)
                    daemonLogger.info(
                        "Configuration updated" +
                            if (old.logLevel != updated.logLevel) "; log level is ${updated.logLevel.uppercase()}" else "",
                    )
                },
            )
            val server = UnixHttpServer(paths.socket, service, identityResolver, logger = daemonLogger)
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    daemonLogger.info("Shutdown requested")
                    service.requestShutdown()
                    runCatching { server.close() }
                    runCatching { sendDispatcher.close() }
                    runCatching { peer.runtime.close() }
                }, "notisyncd-shutdown"),
            )
            peer.runtime.start()
            sendDispatcher.start()
            daemonLogger.info("Daemon ready on ${paths.socket}")
            try {
                server.run()
            } finally {
                service.requestShutdown()
                sendDispatcher.close()
                peer.runtime.close()
                daemonLogger.info("notisyncd stopped")
            }
        }
        return 0
    }

    private fun start(): Int {
        requireSupportedDesktop()
        layout.prepare(fileSystem)
        if (daemonResponds()) {
            output.appendLine("notisyncd is already running")
            return 0
        }
        rotateDaemonLogIfNeeded()
        fileSystem.ensurePrivateFile(layout.daemonLogFile)
        val currentExecutable = ProcessHandle.current().info().command().orElse(null)?.let(Path::of)
        val nativeLauncher = currentExecutable?.let { executable ->
            when (executable.fileName.toString()) {
                "notisyncd" -> executable
                "notisync" -> executable.resolveSibling("notisyncd").takeIf(Files::isExecutable)
                else -> null
            }
        }
        val command = if (nativeLauncher != null) {
            mutableListOf(nativeLauncher.toString(), "foreground")
        } else {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            mutableListOf(
                java,
                "-Dnotisync.dataDir=${paths.dataDirectory.toAbsolutePath().normalize()}",
                "-Dnotisync.logDir=${paths.logDirectory.toAbsolutePath().normalize()}",
                "-D${DesktopProcessSession.DETACH_PROPERTY}=${DesktopProcessSession.DETACH_VALUE}",
                "-cp",
                System.getProperty("java.class.path"),
                "net.extrawdw.notisync.daemon.NotisyncdMainKt",
                "foreground",
            )
        }
        val nullDevice = Path.of("/dev/null").toFile()
        val log = layout.daemonLogFile.toFile()
        val processBuilder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
        if (nativeLauncher != null) {
            DesktopProcessSession.requestNativeDetach(processBuilder)
            processBuilder.environment()["NOTISYNC_DATA_DIR"] = paths.dataDirectory.toAbsolutePath().normalize().toString()
            processBuilder.environment()["NOTISYNC_LOG_DIR"] = paths.logDirectory.toAbsolutePath().normalize().toString()
        }
        val process = processBuilder.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (daemonResponds()) {
                output.appendLine("started notisyncd (PID ${process.pid()})")
                return 0
            }
            if (!process.isAlive) throw IllegalStateException("notisyncd exited during startup; see ${layout.daemonLogFile}")
            Thread.sleep(50)
        }
        throw IllegalStateException("notisyncd did not become ready; see ${layout.daemonLogFile}")
    }

    private fun stop(): Int {
        val record = DaemonInstanceLock.readPidRecord(layout.pidFile, fileSystem)
        val process = record?.let(::matchingProcess)
        val client = DaemonAdminClient(paths.socket)
        client.shutdown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (
            System.nanoTime() < deadline &&
            (daemonResponds() || process?.isAlive == true)
        ) {
            Thread.sleep(50)
        }
        check(!daemonResponds()) { "notisyncd did not stop within 10 seconds" }
        check(process?.isAlive != true) { "notisyncd process did not exit within 10 seconds" }
        output.appendLine("stopped notisyncd")
        return 0
    }

    private fun matchingProcess(record: DaemonPidRecord): ProcessHandle? {
        val process = ProcessHandle.of(record.pid).orElse(null) ?: return null
        val recordedStart = record.processStartTime ?: return process
        val actualStart = process.info().startInstant().orElse(null)?.toString()
        return process.takeIf { actualStart == recordedStart }
    }

    private fun restart(): Int {
        if (daemonResponds()) stop()
        return start()
    }

    private fun status(): Int {
        return try {
            val status = DaemonAdminClient(paths.socket).status()
            output.appendLine(LocalApiJson.encodeToString(status))
            0
        } catch (failure: DaemonConnectionException) {
            val message = if (failure.daemonNotRunning) {
                "notisyncd: not running"
            } else {
                "notisyncd: unable to connect"
            }
            error.appendLine(message)
            1
        }
    }

    private fun config(arguments: List<String>): Int {
        val client = DaemonAdminClient(paths.socket)
        when (arguments.firstOrNull() ?: "get") {
            "get" -> output.appendLine(LocalApiJson.encodeToString(client.config()))
            "set" -> {
                if (arguments.size != 3) throw IllegalArgumentException("config set requires OPTION VALUE")
                val value = arguments[2]
                val patch = when (arguments[1]) {
                    "broker-url" -> DaemonConfigPatch(brokerUrl = value)
                    "device-name" -> DaemonConfigPatch(deviceName = value)
                    "auto-apply-trusted-device-tables" -> DaemonConfigPatch(
                        automaticallyApplyTrustedDeviceTables = parseBoolean(value),
                    )
                    "log-level" -> DaemonConfigPatch(logLevel = value)
                    "websocket-ping-seconds" -> DaemonConfigPatch(
                        websocketPingSeconds = value.toIntOrNull()
                            ?: throw IllegalArgumentException("websocket-ping-seconds must be an integer"),
                    )
                    else -> throw IllegalArgumentException("unknown daemon config option: ${arguments[1]}")
                }
                output.appendLine(LocalApiJson.encodeToString(client.patchConfig(patch)))
            }
            else -> throw IllegalArgumentException("config requires get or set")
        }
        return 0
    }

    private fun daemonResponds(): Boolean =
        runCatching { DaemonAdminClient(paths.socket).readinessStatus() }.isSuccess

    private fun removeStaleSocket() {
        if (!Files.exists(paths.socket, LinkOption.NOFOLLOW_LINKS)) return
        fileSystem.validatePrivateNode(paths.socket)
        Files.delete(paths.socket)
    }

    /** Size-bounded rotation performed before the detached process opens its append streams. */
    private fun rotateDaemonLogIfNeeded() {
        val active = layout.daemonLogFile
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return
        fileSystem.validatePrivateFile(active)
        if (Files.size(active) < DAEMON_LOG_ROTATE_BYTES) return

        for (index in DAEMON_LOG_RETAINED_FILES downTo 1) {
            val source = if (index == 1) active else rotatedLog(index - 1)
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue
            fileSystem.validatePrivateFile(source)
            val target = rotatedLog(index)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                fileSystem.deletePrivateFileIfExists(target)
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            fileSystem.validatePrivateFile(target)
        }
    }

    private fun rotatedLog(index: Int): Path =
        layout.daemonLogFile.resolveSibling("${layout.daemonLogFile.fileName}.$index")

    private fun requireSupportedDesktop() {
        val os = System.getProperty("os.name").lowercase()
        require(os.contains("linux") || os.contains("mac") || os.contains("darwin")) {
            "notisyncd currently supports Linux and macOS"
        }
    }

    private fun parseBoolean(value: String): Boolean = when (value.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> throw IllegalArgumentException("expected true/false")
    }

    private fun reportFailure(message: String, warning: Boolean) {
        if (runningForeground) {
            if (warning) daemonLogger.warn(message) else daemonLogger.error(message)
        } else {
            error.appendLine("notisyncd: $message")
        }
    }

    private fun usage(): String = """
        Usage: notisyncd [foreground|start|stop|restart|status]
               notisyncd config get
               notisyncd config set OPTION VALUE
               notisyncd pair ...
               notisyncd devices ...

        With no command, notisyncd runs in the foreground.
    """.trimIndent()

    private companion object {
        const val DAEMON_LOG_ROTATE_BYTES = 10L * 1024 * 1024
        const val DAEMON_LOG_RETAINED_FILES = 5
    }
}
