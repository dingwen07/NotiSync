package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.desktop.SecureFileSystem
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

class UnixAgentEndpoint(
    socketPath: Path,
    private val handler: (AFUNIXSocket) -> Unit,
    private val maximumConnections: Int,
) : AgentEndpoint {
    private val socketPath = socketPath.toAbsolutePath().normalize()
    private val files = SecureFileSystem()
    private val closed = AtomicBoolean(false)
    private val clients = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notisync-ssh-agent-", 0).factory())
    private val active = ConcurrentHashMap.newKeySet<AFUNIXSocket>()
    private lateinit var server: AFUNIXServerSocket

    override fun run(onReady: () -> Unit) {
        check(!closed.get())
        val parent = requireNotNull(socketPath.parent)
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            PrivateFiles.validatePrivateDirectory(parent)
        } else {
            PrivateFiles.ensureDirectory(parent)
        }
        removeValidatedStaleSocket()
        val ownerUid = runCatching {
            (Files.getAttribute(socketPath.parent, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull()
        server = AFUNIXServerSocket.newInstance()
        try {
            server.bind(AFUNIXSocketAddress.of(socketPath.toFile()))
            files.validatePrivateNode(socketPath)
            onReady()
            while (!closed.get()) {
                val socket = try {
                    server.accept()
                } catch (failure: java.net.SocketException) {
                    if (closed.get()) break else throw failure
                }
                if (active.size >= maximumConnections || !sameOwner(socket, ownerUid)) {
                    socket.close()
                    continue
                }
                active += socket
                clients.submit {
                    socket.use { runCatching { handler(it) } }
                    active -= socket
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (::server.isInitialized) runCatching { server.close() }
        active.forEach { runCatching { it.close() } }
        clients.shutdown()
        if (!clients.awaitTermination(5, TimeUnit.SECONDS)) clients.shutdownNow()
        deleteSocket()
    }

    private fun sameOwner(socket: AFUNIXSocket, expectedUid: Long?): Boolean {
        if (expectedUid == null) return true
        val peerUid = runCatching { socket.peerCredentials.uid }.getOrNull() ?: return false
        return peerUid == expectedUid
    }

    private fun deleteSocket() {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(socketPath)) return
        if (files.isSocketNode(socketPath)) Files.deleteIfExists(socketPath)
    }

    private fun removeValidatedStaleSocket() {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(socketPath)) { "SSH agent socket path is a symbolic link: $socketPath" }
        require(files.isSocketNode(socketPath)) { "SSH agent endpoint path is not a socket: $socketPath" }
        files.validatePrivateNode(socketPath)
        val socketUid = runCatching {
            (Files.getAttribute(socketPath, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull()
        val directoryUid = runCatching {
            (Files.getAttribute(socketPath.parent, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        }.getOrNull()
        require(socketUid == null || directoryUid == null || socketUid == directoryUid) {
            "stale SSH agent socket has a different owner"
        }
        Files.delete(socketPath)
    }
}
