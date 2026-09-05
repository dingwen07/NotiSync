package net.extrawdw.notisync.gpg

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class GpgExecutableResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val osName: String = System.getProperty("os.name"),
) {
    fun findOnPath(): Path? {
        val pathValue = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
            ?: return null
        val windows = osName.contains("windows", ignoreCase = true)
        val executableName = if (windows) "gpg.exe" else "gpg"
        return pathValue.split(File.pathSeparatorChar).asSequence()
            .filter(String::isNotBlank)
            .mapNotNull { directory ->
                try {
                    val candidate = Path.of(directory).resolve(executableName)
                    if (!Files.isRegularFile(candidate) || (!windows && !Files.isExecutable(candidate))) {
                        return@mapNotNull null
                    }
                    candidate.toRealPath().normalize()
                } catch (_: InvalidPathException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            .firstOrNull { !it.isNotisyncGpgExecutable() }
    }
}

internal fun Path.isNotisyncGpgExecutable(): Boolean = fileName.toString().lowercase().let {
    it == "notisync-gpg" || it == "notisync-gpg.bat" || it == "notisync-gpg.exe"
}
