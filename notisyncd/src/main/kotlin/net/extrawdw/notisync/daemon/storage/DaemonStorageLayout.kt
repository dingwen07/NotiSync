package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The daemon-owned portion of `~/.notisync`. It intentionally has no `nsrun.conf` path. */
data class DaemonStorageLayout(
    val dataDirectory: Path,
) {
    val socketFile: Path = dataDirectory.resolve("S.notisyncd")
    val lockFile: Path = dataDirectory.resolve("notisyncd.lock")
    val pidFile: Path = dataDirectory.resolve("notisyncd.pid")
    val daemonConfigFile: Path = dataDirectory.resolve("notisyncd.conf")
    val daemonLogFile: Path = dataDirectory.resolve("notisyncd.log")

    val privateKeysDirectory: Path = dataDirectory.resolve("private-keys-v1")
    val stateDirectory: Path = dataDirectory.resolve("state")
    val trustStateFile: Path = stateDirectory.resolve("trust.json")
    val authStateFile: Path = stateDirectory.resolve("auth.json")
    val databaseFile: Path = stateDirectory.resolve("notisyncd.db")
    val runsDirectory: Path = dataDirectory.resolve("runs")

    /** Create the directory skeleton and validate any existing daemon-managed nodes. */
    fun prepare(fileSystem: SecureFileSystem = SecureFileSystem()): DaemonStorageLayout {
        fileSystem.ensurePrivateDirectory(dataDirectory)
        fileSystem.ensurePrivateDirectory(privateKeysDirectory)
        fileSystem.ensurePrivateDirectory(stateDirectory)
        fileSystem.ensurePrivateDirectory(runsDirectory)

        listOf(
            lockFile,
            pidFile,
            daemonConfigFile,
            daemonLogFile,
            trustStateFile,
            authStateFile,
            databaseFile,
        ).forEach { path ->
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) fileSystem.validatePrivateFile(path)
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

    fun runDirectory(runId: String, fileSystem: SecureFileSystem = SecureFileSystem()): Path {
        require(SAFE_COMPONENT.matches(runId)) { "invalid run id" }
        prepare(fileSystem)
        return fileSystem.ensurePrivateDirectory(runsDirectory.resolve(runId))
    }

    fun privateKeyFile(name: String): Path {
        require(SAFE_COMPONENT.matches(name)) { "invalid private-key name" }
        return privateKeysDirectory.resolve(name)
    }

    companion object {
        private val SAFE_COMPONENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun default(): DaemonStorageLayout = DaemonStorageLayout(
            Path.of(System.getProperty("user.home"), ".notisync"),
        )
    }
}
