package net.extrawdw.notisync.desktop.api

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import net.extrawdw.notisync.desktop.DesktopPaths

class DaemonAutostarter(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val clientFactory: () -> DaemonLocalApi = { UnixDaemonClient(paths.socket) },
    private val executableResolver: () -> Path? = ::resolveNotisyncdExecutable,
    private val startTimeout: Duration = Duration.ofSeconds(10),
) {
    fun connect(): DaemonLocalApi {
        val client = clientFactory()
        if (runCatching { client.status() }.isSuccess) return client

        val executable = executableResolver()
            ?: throw IllegalStateException("notisyncd is not running and its executable was not found")
        val process = ProcessBuilder(executable.toString(), "start")
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

        val deadline = System.nanoTime() + startTimeout.toNanos()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                client.status()
                return client
            } catch (error: Throwable) {
                lastFailure = error
                Thread.sleep(50)
            }
        }
        throw IllegalStateException("notisyncd did not become ready", lastFailure)
    }
}

private fun resolveNotisyncdExecutable(): Path? {
    val osName = System.getProperty("os.name")
    System.getProperty("notisyncd.executable")?.let { configured ->
        Path.of(configured).takeIf { isLauncherFile(it, osName) }?.let { return it }
    }
    ProcessHandle.current().info().command().orElse(null)?.let { current ->
        Path.of(current).parent?.let { findLauncher(it, osName) }?.let { return it }
    }
    // Gradle's application scripts exec java, so ProcessHandle points at the JVM rather than bin/nsrun.
    // In an installed distribution the code source is lib/<jar>; resolve the sibling bin launcher.
    runCatching {
        val location = DaemonAutostarter::class.java.protectionDomain.codeSource.location.toURI()
        val codeSource = Path.of(location)
        val distribution = if (Files.isDirectory(codeSource)) codeSource else codeSource.parent?.parent
        distribution?.resolve("bin")?.let { findLauncher(it, osName) }
    }.getOrNull()?.let { return it }
    val path = System.getenv("PATH").orEmpty()
    for (directory in path.split(java.io.File.pathSeparatorChar)) {
        if (directory.isBlank()) continue
        findLauncher(Path.of(directory), osName)?.let { return it }
    }
    return null
}

internal fun findLauncher(directory: Path, osName: String): Path? = launcherNames(osName)
    .asSequence()
    .map(directory::resolve)
    .firstOrNull { isLauncherFile(it, osName) }

internal fun launcherNames(osName: String): List<String> = if (isWindows(osName)) {
    // Gradle installs the POSIX shell launcher beside the Windows batch launcher. Never pass that
    // extensionless shell script to CreateProcess; it fails with ERROR_BAD_EXE_FORMAT (193).
    listOf("notisyncd.exe", "notisyncd.bat", "notisyncd.cmd")
} else {
    listOf("notisyncd")
}

private fun isLauncherFile(path: Path, osName: String): Boolean =
    Files.isRegularFile(path) && (Files.isExecutable(path) || isWindows(osName))

private fun isWindows(osName: String): Boolean = osName.contains("windows", ignoreCase = true)

private fun nullDevice(): java.io.File =
    if (isWindows(System.getProperty("os.name"))) Path.of("NUL").toFile() else Path.of("/dev/null").toFile()
