package net.extrawdw.notisync.peer.transport

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpNoDelaySocketFactoryTest {
    @Test
    fun `relay sockets disable Nagle before use`() {
        val socket = RecordingSocket()
        val factory = TcpNoDelaySocketFactory(FixedSocketFactory(socket))

        assertSame(socket, factory.createSocket())
        assertTrue(socket.noDelayEnabled)
    }

    private class RecordingSocket : Socket() {
        var noDelayEnabled = false

        override fun setTcpNoDelay(on: Boolean) {
            noDelayEnabled = on
        }
    }

    private class FixedSocketFactory(
        private val socket: Socket,
    ) : SocketFactory() {
        override fun createSocket(): Socket = socket

        override fun createSocket(host: String, port: Int): Socket = socket

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = socket

        override fun createSocket(host: InetAddress, port: Int): Socket = socket

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = socket
    }
}
