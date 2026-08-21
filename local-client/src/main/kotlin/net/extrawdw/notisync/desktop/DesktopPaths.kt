package net.extrawdw.notisync.desktop

import java.nio.file.Path

data class DesktopPaths(
    val dataDirectory: Path,
    /** Explicit roots stay self-contained; [default] uses the platform's user log location. */
    val logDirectory: Path = dataDirectory.resolve("logs"),
) {
    val stateDirectory: Path = dataDirectory.resolve("state")
    val socket: Path = dataDirectory.resolve("S.notisyncd")
    val daemonConfig: Path = dataDirectory.resolve("notisyncd.conf")
    val nsrunConfig: Path = dataDirectory.resolve("nsrun.conf")
    val notisyncGpgConfig: Path = dataDirectory.resolve("notisync-gpg.conf")
    val sshAgentSocket: Path = dataDirectory.resolve("S.ssh-agent")
    val sshAgentConfig: Path = dataDirectory.resolve("notisync-ssh-agent.conf")
    val sshAgentDatabase: Path = stateDirectory.resolve("notisync-ssh-agent.db")
    val sshAgentPid: Path = dataDirectory.resolve("notisync-ssh-agent.pid")
    val sshAgentLock: Path = dataDirectory.resolve("notisync-ssh-agent.lock")
    val sshAgentLog: Path = logDirectory.resolve("notisync-ssh-agent.log")
    val runsDirectory: Path = dataDirectory.resolve("runs")

    companion object {
        fun default(): DesktopPaths = defaults(
            userHome = Path.of(System.getProperty("user.home")),
            osName = System.getProperty("os.name"),
            configuredDataDirectory = System.getProperty("notisync.dataDir"),
            configuredLogDirectory = System.getProperty("notisync.logDir"),
            xdgStateHome = System.getenv("XDG_STATE_HOME"),
            localAppData = System.getenv("LOCALAPPDATA"),
        )

        internal fun defaults(
            userHome: Path,
            osName: String,
            configuredDataDirectory: String? = null,
            configuredLogDirectory: String? = null,
            xdgStateHome: String? = null,
            localAppData: String? = null,
        ): DesktopPaths {
            val windows = osName.isWindows()
            val configuredData = configuredDataDirectory?.takeIf(String::isNotBlank)?.let(Path::of)
            val data = configuredData ?: if (windows) {
                localAppData
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?.takeIf(Path::isAbsolute)
                    ?.resolve("NotiSync")
                    ?: userHome.resolve("AppData/Local/NotiSync")
            } else {
                userHome.resolve(".notisync")
            }
            val logs = configuredLogDirectory?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: if (configuredData != null) {
                    data.resolve("logs")
                } else if (osName.isMacOs()) {
                    userHome.resolve("Library/Logs/NotiSync")
                } else if (windows) {
                    data.resolve("logs")
                } else {
                    xdgStateHome
                        ?.takeIf(String::isNotBlank)
                        ?.let(Path::of)
                        ?.takeIf(Path::isAbsolute)
                        ?.resolve("notisync/log")
                        ?: userHome.resolve(".local/state/notisync/log")
                }
            return DesktopPaths(data, logs)
        }

        private fun String.isMacOs(): Boolean = lowercase().let { it.contains("mac") || it.contains("darwin") }

        private fun String.isWindows(): Boolean = lowercase().contains("windows")
    }
}

object PrivateFiles {
    private val secure = SecureFileSystem()

    fun ensureDirectory(path: Path): Path = secure.ensurePrivateDirectory(path)

    fun validatePrivateDirectory(path: Path): Path = secure.validatePrivateDirectory(path)

    fun validatePrivateFile(path: Path) {
        secure.validatePrivateFile(path)
    }

    fun atomicWrite(path: Path, bytes: ByteArray) {
        secure.atomicWrite(path, bytes)
    }
}
