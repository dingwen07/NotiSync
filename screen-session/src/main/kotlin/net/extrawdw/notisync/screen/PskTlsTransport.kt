package net.extrawdw.notisync.screen

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.time.Duration
import java.util.Vector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.bouncycastle.tls.AbstractTlsClient
import org.bouncycastle.tls.AbstractTlsServer
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.NamedGroup
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolName
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.PskIdentity
import org.bouncycastle.tls.PskKeyExchangeMode
import org.bouncycastle.tls.SecurityParameters
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsPSK
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.TlsSecret
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

class SecureSessionChannel internal constructor(
    val descriptor: SessionDescriptor,
    val channel: ScreenChannel,
    val input: InputStream,
    val output: OutputStream,
    private val closeProtocol: () -> Unit,
    private val socket: Socket?,
    private val closeTransport: (() -> Unit)?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        // Bouncy Castle synchronizes protocol reads and close_notify writes. Closing
        // the transport first releases a thread blocked in a TLS read. Protocol shutdown is only
        // best effort after that abort and must never hold up closure of the session's other
        // channel on an OEM where the reader is slow to unwind.
        runCatching { socket?.close() ?: closeTransport?.invoke() }
        closeProtocolInBackground(closeProtocol)
    }

    companion object {
        /**
         * VIDEO-only Relay channel whose records are independently PSK-derived and AEAD-authenticated.
         * CONTROL continues to use [PskTlsServer], so callers cannot accidentally use this as a
         * general replacement for the bidirectional authenticated stream.
         */
        fun authenticatedRelayVideo(
            descriptor: SessionDescriptor,
            input: InputStream,
            closeTransport: () -> Unit,
        ): SecureSessionChannel = SecureSessionChannel(
            descriptor = descriptor,
            channel = ScreenChannel.VIDEO,
            input = input,
            output = object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("Relay video is source-to-viewer only")
                }
            },
            closeProtocol = {},
            socket = null,
            closeTransport = closeTransport,
        )
    }
}

object PskTlsClient {
    @Throws(IOException::class)
    fun connect(
        socket: Socket,
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
        channel: ScreenChannel,
        handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
        random: SecureRandom = SecureRandom(),
    ): SecureSessionChannel {
        require(!handshakeTimeout.isNegative && !handshakeTimeout.isZero)
        tuneScreenSocket(socket, channel)
        val identity = RoutingIdentity.encode(routingToken, channel)
        val key = SessionKeyDeriver.derive(masterPsk, routingToken, descriptor, channel)
        val originalTimeout = socket.soTimeout
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        val peer = RestrictedPskClient(identity, key, random, handshakeTimeout)
        return try {
            socket.soTimeout = handshakeTimeout.asTimeoutMillis()
            protocol.connect(peer)
            val localBinding = ChannelBinding(descriptor, channel, SessionRole.SOURCE)
            ChannelBindingCodec.write(protocol.outputStream, localBinding)
            val remoteBinding = ChannelBindingCodec.read(protocol.inputStream)
            requireBinding(remoteBinding, ChannelBinding(descriptor, channel, SessionRole.VIEWER))
            socket.soTimeout = originalTimeout
            SecureSessionChannel(
                descriptor,
                channel,
                protocol.inputStream,
                protocol.outputStream,
                protocol::close,
                socket,
                null,
            )
        } catch (error: Throwable) {
            runCatching { socket.close() }
            closeProtocolInBackground(protocol::close)
            throw error
        } finally {
            identity.fill(0)
            key.fill(0)
            peer.destroyPsk()
        }
    }

    /** TLS over an ordered broker-relay byte stream instead of a directly connected TCP socket. */
    @Throws(IOException::class)
    fun connect(
        input: InputStream,
        output: OutputStream,
        closeTransport: () -> Unit,
        descriptor: SessionDescriptor,
        routingToken: ByteArray,
        masterPsk: ByteArray,
        channel: ScreenChannel,
        handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
        random: SecureRandom = SecureRandom(),
    ): SecureSessionChannel {
        require(!handshakeTimeout.isNegative && !handshakeTimeout.isZero)
        val identity = RoutingIdentity.encode(routingToken, channel)
        val key = SessionKeyDeriver.derive(masterPsk, routingToken, descriptor, channel)
        val protocol = TlsClientProtocol(input, output)
        val peer = RestrictedPskClient(identity, key, random, handshakeTimeout)
        val guard = HandshakeTransportGuard(handshakeTimeout, closeTransport)
        return try {
            protocol.connect(peer)
            val localBinding = ChannelBinding(descriptor, channel, SessionRole.SOURCE)
            ChannelBindingCodec.write(protocol.outputStream, localBinding)
            val remoteBinding = ChannelBindingCodec.read(protocol.inputStream)
            requireBinding(remoteBinding, ChannelBinding(descriptor, channel, SessionRole.VIEWER))
            guard.complete()
            SecureSessionChannel(
                descriptor = descriptor,
                channel = channel,
                input = protocol.inputStream,
                output = protocol.outputStream,
                closeProtocol = protocol::close,
                socket = null,
                closeTransport = closeTransport,
            )
        } catch (error: Throwable) {
            guard.complete()
            runCatching(closeTransport)
            closeProtocolInBackground(protocol::close)
            throw error
        } finally {
            identity.fill(0)
            key.fill(0)
            peer.destroyPsk()
        }
    }
}

object PskTlsServer {
    @Throws(IOException::class)
    fun accept(
        socket: Socket,
        registry: PskRegistry,
        handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
        random: SecureRandom = SecureRandom(),
    ): SecureSessionChannel {
        require(!handshakeTimeout.isNegative && !handshakeTimeout.isZero)
        val originalTimeout = socket.soTimeout
        val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
        val peer = RestrictedPskServer(registry, random, handshakeTimeout)
        var lease: PskRegistry.PskLease? = null
        return try {
            socket.soTimeout = handshakeTimeout.asTimeoutMillis()
            protocol.accept(peer)
            lease = peer.requireLease()
            tuneScreenSocket(socket, lease.channel)
            // Only a peer that proved possession of the derived PSK consumes the
            // registry's per-channel authenticated-attempt budget. Unknown identities
            // and bad binders are handled by the listener's admission limits instead.
            lease.markAuthenticated()
            val remoteBinding = ChannelBindingCodec.read(protocol.inputStream)
            requireBinding(
                remoteBinding,
                ChannelBinding(lease.descriptor, lease.channel, SessionRole.SOURCE),
            )
            ChannelBindingCodec.write(
                protocol.outputStream,
                ChannelBinding(lease.descriptor, lease.channel, SessionRole.VIEWER),
            )
            lease.finish(true)
            socket.soTimeout = originalTimeout
            SecureSessionChannel(
                lease.descriptor,
                lease.channel,
                protocol.inputStream,
                protocol.outputStream,
                protocol::close,
                socket,
                null,
            )
        } catch (error: Throwable) {
            lease?.finish(false)
            peer.releaseLease()
            runCatching { socket.close() }
            closeProtocolInBackground(protocol::close)
            throw error
        } finally {
            peer.destroyPsk()
        }
    }

    /** Accept TLS over an ordered broker-relay byte stream instead of an accepted TCP socket. */
    @Throws(IOException::class)
    fun accept(
        input: InputStream,
        output: OutputStream,
        closeTransport: () -> Unit,
        registry: PskRegistry,
        handshakeTimeout: Duration = DEFAULT_HANDSHAKE_TIMEOUT,
        random: SecureRandom = SecureRandom(),
    ): SecureSessionChannel {
        require(!handshakeTimeout.isNegative && !handshakeTimeout.isZero)
        val protocol = TlsServerProtocol(input, output)
        val peer = RestrictedPskServer(registry, random, handshakeTimeout)
        val guard = HandshakeTransportGuard(handshakeTimeout, closeTransport)
        var lease: PskRegistry.PskLease? = null
        return try {
            protocol.accept(peer)
            lease = peer.requireLease()
            lease.markAuthenticated()
            val remoteBinding = ChannelBindingCodec.read(protocol.inputStream)
            requireBinding(
                remoteBinding,
                ChannelBinding(lease.descriptor, lease.channel, SessionRole.SOURCE),
            )
            ChannelBindingCodec.write(
                protocol.outputStream,
                ChannelBinding(lease.descriptor, lease.channel, SessionRole.VIEWER),
            )
            lease.finish(true)
            guard.complete()
            SecureSessionChannel(
                descriptor = lease.descriptor,
                channel = lease.channel,
                input = protocol.inputStream,
                output = protocol.outputStream,
                closeProtocol = protocol::close,
                socket = null,
                closeTransport = closeTransport,
            )
        } catch (error: Throwable) {
            guard.complete()
            lease?.finish(false)
            peer.releaseLease()
            runCatching(closeTransport)
            closeProtocolInBackground(protocol::close)
            throw error
        } finally {
            peer.destroyPsk()
        }
    }
}

/** A blocking stream has no SO_TIMEOUT, so close it if the TLS handshake stalls. */
private class HandshakeTransportGuard(
    timeout: Duration,
    closeTransport: () -> Unit,
) {
    private val completed = CountDownLatch(1)

    init {
        Thread(
            {
                val finished = runCatching {
                    completed.await(timeout.toMillis().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                }.getOrDefault(true)
                if (!finished) runCatching(closeTransport)
            },
            "notisync-tls-handshake-timeout",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun complete() {
        completed.countDown()
    }
}

private fun closeProtocolInBackground(closeProtocol: () -> Unit) {
    // Thread construction/start itself is best effort as well. Closing a session must never throw
    // merely because the runtime cannot create this disposable close_notify worker.
    runCatching {
        Thread(
            { runCatching { closeProtocol() } },
            "notisync-tls-protocol-close",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

/** Keep tiny control records out of deep per-socket queues when video saturates a weak link. */
private fun tuneScreenSocket(socket: Socket, channel: ScreenChannel) {
    runCatching { socket.tcpNoDelay = true }
    val bufferBytes = if (channel == ScreenChannel.CONTROL) {
        CONTROL_SOCKET_BUFFER_BYTES
    } else {
        VIDEO_SOCKET_BUFFER_BYTES
    }
    runCatching { socket.sendBufferSize = bufferBytes }
    runCatching { socket.receiveBufferSize = bufferBytes }
    if (channel == ScreenChannel.CONTROL) {
        runCatching { socket.trafficClass = IPTOS_LOWDELAY }
    }
}

private const val CONTROL_SOCKET_BUFFER_BYTES = 16 * 1024
private const val VIDEO_SOCKET_BUFFER_BYTES = 128 * 1024
private const val IPTOS_LOWDELAY = 0x10

private abstract class RestrictedTlsProfile {
    protected val alpn: ProtocolName = ProtocolName.asUtf8Encoding(ScreenSessionProtocol.ALPN)

    protected fun validate(parameters: SecurityParameters) {
        if (parameters.negotiatedVersion != ProtocolVersion.TLSv13 ||
            parameters.negotiatedGroup != NamedGroup.x25519 ||
            parameters.applicationProtocol != alpn ||
            parameters.isResumedSession ||
            parameters.cipherSuite !in ALLOWED_CIPHER_SUITES
        ) {
            throw TlsFatalAlert(AlertDescription.handshake_failure)
        }
    }
}

private class RestrictedPskClient(
    private val identity: ByteArray,
    key: ByteArray,
    random: SecureRandom,
    private val timeout: Duration,
) : AbstractTlsClient(BcTlsCrypto(random)) {
    private var secret: TlsSecret? = crypto.createSecret(key.copyOf())
    private val profile = object : RestrictedTlsProfile() {
        fun check(parameters: SecurityParameters) = validate(parameters)
        fun protocolName(): ProtocolName = alpn
    }

    override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

    override fun getSupportedCipherSuites(): IntArray =
        TlsUtils.getSupportedCipherSuites(crypto, ALLOWED_CIPHER_SUITES)

    override fun getPskKeyExchangeModes(): ShortArray = shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)

    override fun getProtocolNames(): Vector<ProtocolName> = Vector<ProtocolName>().apply {
        add(profile.protocolName())
    }

    override fun getSupportedGroups(namedGroupRoles: Vector<*>?): Vector<Int> = Vector<Int>().apply {
        add(NamedGroup.x25519)
    }

    override fun getEarlyKeyShareGroups(): Vector<Int> = Vector<Int>().apply { add(NamedGroup.x25519) }

    override fun getExternalPSKs(): Vector<TlsPSKExternal> = Vector<TlsPSKExternal>().apply {
        add(BasicTlsPSKExternal(identity.copyOf(), requireNotNull(secret), PRFAlgorithm.tls13_hkdf_sha256))
    }

    override fun notifySelectedPSK(selectedPSK: TlsPSK?) {
        if (selectedPSK == null || !java.security.MessageDigest.isEqual(identity, selectedPSK.identity)) {
            throw TlsFatalAlert(AlertDescription.handshake_failure)
        }
    }

    override fun getAuthentication(): TlsAuthentication = throw TlsFatalAlert(AlertDescription.internal_error)

    override fun getSessionToResume() = null

    override fun getHandshakeTimeoutMillis(): Int = timeout.asTimeoutMillis()

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        profile.check(context.securityParametersConnection)
    }

    fun destroyPsk() {
        secret?.destroy()
        secret = null
    }
}

private class RestrictedPskServer(
    private val registry: PskRegistry,
    random: SecureRandom,
    private val timeout: Duration,
) : AbstractTlsServer(BcTlsCrypto(random)) {
    private var lease: PskRegistry.PskLease? = null
    private var secret: TlsSecret? = null
    private val profile = object : RestrictedTlsProfile() {
        fun check(parameters: SecurityParameters) = validate(parameters)
        fun protocolName(): ProtocolName = alpn
    }

    override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

    override fun getSupportedCipherSuites(): IntArray =
        TlsUtils.getSupportedCipherSuites(crypto, ALLOWED_CIPHER_SUITES)

    override fun preferLocalCipherSuites(): Boolean = true

    override fun getPskKeyExchangeModes(): ShortArray = shortArrayOf(PskKeyExchangeMode.psk_dhe_ke)

    override fun getProtocolNames(): Vector<ProtocolName> = Vector<ProtocolName>().apply {
        add(profile.protocolName())
    }

    override fun getSupportedGroups(): IntArray = intArrayOf(NamedGroup.x25519)

    override fun getExternalPSK(identities: Vector<*>): TlsPSKExternal? {
        val offered = identities.mapNotNull { (it as? PskIdentity)?.identity }
        val selected = registry.reserve(offered) ?: return null
        lease = selected
        secret = crypto.createSecret(selected.key.copyOf())
        return BasicTlsPSKExternal(selected.identity.copyOf(), requireNotNull(secret), PRFAlgorithm.tls13_hkdf_sha256)
    }

    override fun getCredentials(): TlsCredentials? = null

    override fun getSessionToResume(sessionID: ByteArray?) = null

    override fun getNewSessionID(): ByteArray? = null

    override fun getHandshakeTimeoutMillis(): Int = timeout.asTimeoutMillis()

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        profile.check(context.securityParametersConnection)
        if (lease == null) throw TlsFatalAlert(AlertDescription.handshake_failure)
    }

    fun requireLease(): PskRegistry.PskLease = lease ?: throw IOException("TLS did not select an external PSK")

    fun releaseLease() {
        lease?.finish(false)
    }

    fun destroyPsk() {
        secret?.destroy()
        secret = null
    }
}

private val ALLOWED_CIPHER_SUITES = intArrayOf(
    CipherSuite.TLS_AES_128_GCM_SHA256,
    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
)

val DEFAULT_HANDSHAKE_TIMEOUT: Duration = Duration.ofSeconds(10)

private fun Duration.asTimeoutMillis(): Int = toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
