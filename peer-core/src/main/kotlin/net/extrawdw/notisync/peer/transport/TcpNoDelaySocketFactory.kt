package net.extrawdw.notisync.peer.transport

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/** Creates relay sockets with Nagle disabled before OkHttp starts the connection. */
internal class TcpNoDelaySocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
) : SocketFactory() {
    override fun createSocket(): Socket = delegate.createSocket().lowLatency()

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).lowLatency()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(host, port, localHost, localPort).lowLatency()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).lowLatency()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort).lowLatency()

    private fun Socket.lowLatency(): Socket = apply {
        tcpNoDelay = true
    }
}
