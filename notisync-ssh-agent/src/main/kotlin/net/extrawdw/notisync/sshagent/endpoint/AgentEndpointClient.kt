package net.extrawdw.notisync.sshagent.endpoint

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

internal class AgentEndpointConnection(
    val input: InputStream,
    val output: OutputStream,
    private val closeConnection: () -> Unit,
) : AutoCloseable {
    override fun close() = closeConnection()
}

internal fun connectAgentEndpoint(address: String): AgentEndpointConnection {
    if (isWindowsNamedPipeAddress(address)) return WindowsNamedPipeEndpoint.connectClient(address)
    val socket = AFUNIXSocket.newInstance()
    return try {
        socket.soTimeout = AGENT_CONTROL_TIMEOUT_MILLIS
        socket.connect(AFUNIXSocketAddress.of(Path.of(address).toFile()))
        AgentEndpointConnection(socket.getInputStream(), socket.getOutputStream(), socket::close)
    } catch (failure: Throwable) {
        runCatching { socket.close() }
        throw failure
    }
}

private const val AGENT_CONTROL_TIMEOUT_MILLIS = 5_000
