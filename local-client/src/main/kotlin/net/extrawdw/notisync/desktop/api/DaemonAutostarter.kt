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
    System.getProperty("notisyncd.executable")?.let { configured ->
        Path.of(configured).takeIf(::isLauncherFile)?.let { return it }
    }
    ProcessHandle.current().info().command().orElse(null)?.let { current ->
        Path.of(current).parent?.let(::findLauncher)?.let { return it }
    }
    // Gradle's application scripts exec java, so ProcessHandle points at the JVM rather than bin/nsrun.
    // In an installed distribution the code source is lib/<jar>; resolve the sibling bin launcher.
    runCatching {
        val location = DaemonAutostarter::class.java.protectionDomain.codeSource.location.toURI()
        val codeSource = Path.of(location)
        val distribution = if (Files.isDirectory(codeSource)) codeSource else codeSource.parent?.parent
        distribution?.resolve("bin")?.let(::findLauncher)
    }.getOrNull()?.let { return it }
    val path = System.getenv("PATH").orEmpty()
    for (directory in path.split(java.io.File.pathSeparatorChar)) {
        if (directory.isBlank()) continue
        findLauncher(Path.of(directory))?.let { return it }
    }
    return null
}

private fun findLauncher(directory: Path): Path? = listOf("notisyncd", "notisyncd.bat", "notisyncd.cmd")
    .asSequence()
    .map(directory::resolve)
    .firstOrNull(::isLauncherFile)

private fun isLauncherFile(path: Path): Boolean =
    Files.isRegularFile(path) && (Files.isExecutable(path) || isWindows())

private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

private fun nullDevice(): java.io.File = if (isWindows()) Path.of("NUL").toFile() else Path.of("/dev/null").toFile()
