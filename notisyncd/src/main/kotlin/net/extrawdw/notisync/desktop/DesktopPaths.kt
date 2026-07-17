package net.extrawdw.notisync.desktop

import java.nio.file.Path
import net.extrawdw.notisync.daemon.storage.SecureFileSystem

data class DesktopPaths(val dataDirectory: Path) {
    val socket: Path = dataDirectory.resolve("S.notisyncd")
    val daemonConfig: Path = dataDirectory.resolve("notisyncd.conf")
    val nsrunConfig: Path = dataDirectory.resolve("nsrun.conf")
    val runsDirectory: Path = dataDirectory.resolve("runs")

    companion object {
        fun default(): DesktopPaths = DesktopPaths(
            System.getProperty("notisync.dataDir")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".notisync"),
        )
    }
}

object PrivateFiles {
    private val secure = SecureFileSystem()

    fun ensureDirectory(path: Path): Path = secure.ensurePrivateDirectory(path)

    fun validatePrivateFile(path: Path) {
        secure.validatePrivateFile(path)
    }

    fun atomicWrite(path: Path, bytes: ByteArray) {
        secure.atomicWrite(path, bytes)
    }
}
