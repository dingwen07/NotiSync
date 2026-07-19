package net.extrawdw.notisync.daemon.storage

import net.extrawdw.notisync.desktop.SecureFileSystem

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The daemon's private data and log roots. It intentionally has no `nsrun.conf` path. */
data class DaemonStorageLayout(
    val dataDirectory: Path,
    val logDirectory: Path = dataDirectory.resolve("logs"),
) {
    val socketFile: Path = dataDirectory.resolve("S.notisyncd")
    val lockFile: Path = dataDirectory.resolve("notisyncd.lock")
    val pidFile: Path = dataDirectory.resolve("notisyncd.pid")
    val daemonConfigFile: Path = dataDirectory.resolve("notisyncd.conf")
    val daemonLogFile: Path = logDirectory.resolve("notisyncd.log")

    val privateKeysDirectory: Path = dataDirectory.resolve("private-keys-v1")
    val stateDirectory: Path = dataDirectory.resolve("state")
    val trustStateFile: Path = stateDirectory.resolve("trust.json")
    val authStateFile: Path = stateDirectory.resolve("auth.json")
    val databaseFile: Path = stateDirectory.resolve("notisyncd.db")

    /** Create the directory skeleton and validate any existing daemon-managed nodes. */
    fun prepare(fileSystem: SecureFileSystem = SecureFileSystem()): DaemonStorageLayout {
        fileSystem.ensurePrivateDirectory(dataDirectory)
        fileSystem.ensurePrivateDirectory(logDirectory)
        fileSystem.ensurePrivateDirectory(privateKeysDirectory)
        fileSystem.ensurePrivateDirectory(stateDirectory)

        listOf(
            lockFile,
            pidFile,
            daemonConfigFile,
            trustStateFile,
            authStateFile,
            databaseFile,
        ).forEach { path ->
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) fileSystem.validatePrivateFile(path)
        }
        if (Files.exists(daemonLogFile, LinkOption.NOFOLLOW_LINKS)) {
            fileSystem.validatePrivateFile(daemonLogFile)
        }
        if (Files.exists(socketFile, LinkOption.NOFOLLOW_LINKS)) {
            fileSystem.validatePrivateNode(socketFile)
        }
        return this
    }

    /** Validate and chmod the socket after the UDS listener has bound it. */
    fun secureSocket(fileSystem: SecureFileSystem = SecureFileSystem()) {
        fileSystem.validatePrivateNode(socketFile)
    }

    fun privateKeyFile(name: String): Path {
        require(SAFE_COMPONENT.matches(name)) { "invalid private-key name" }
        return privateKeysDirectory.resolve(name)
    }

    companion object {
        private val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun default(): DaemonStorageLayout = net.extrawdw.notisync.desktop.DesktopPaths.default().let {
            DaemonStorageLayout(it.dataDirectory, it.logDirectory)
        }
    }
}
