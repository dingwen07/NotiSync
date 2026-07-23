package net.extrawdw.apps.notisync.screen

import java.io.IOException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.peer.transport.BrokerClient
import net.extrawdw.notisync.peer.transport.BrokerRelayConnection
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenRelayChannel
import net.extrawdw.notisync.protocol.ScreenRelayJoin
import net.extrawdw.notisync.protocol.ScreenRelayRole
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.PskTlsServer
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.ScreenConnectionCandidate
import net.extrawdw.notisync.screen.ScreenSessionListener
import net.extrawdw.notisync.screen.SecureChannelPair
import net.extrawdw.notisync.screen.SecureSessionChannel
import net.extrawdw.notisync.screen.SessionDescriptor

/** Requester-side listener whose two ordered streams rendezvous through the authenticated broker. */
internal class BrokerRelayScreenSessionListener(
    private val broker: BrokerClient,
    private val relayId: String,
    private val requesterId: ClientId,
    private val sourceId: ClientId,
    private val expiresAt: Long,
    private val descriptor: SessionDescriptor,
    routingToken: ByteArray,
    masterPsk: ByteArray,
) : ScreenSessionListener {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var videoConnection: BrokerRelayConnection? = null
    private var controlConnection: BrokerRelayConnection? = null
    private var relayRoutingToken: ByteArray? = routingToken.copyOf()
    private var relayMasterPsk: ByteArray? = masterPsk.copyOf()

    override val candidates = listOf(
        ScreenConnectionCandidate(
            kind = ScreenConnectionCandidate.BROKER_RELAY,
            serviceName = relayId,
        ),
    )

    override fun acceptPair(
        sessionId: String,
        registry: PskRegistry,
        timeout: Duration,
        handshakeTimeout: Duration,
        maximumAcceptedSockets: Int,
    ): SecureChannelPair {
        require(sessionId.isNotBlank())
        require(!timeout.isZero && !timeout.isNegative)
        if (closed.get()) throw IOException("broker relay listener is closed")
        var videoSecure: SecureSessionChannel? = null
        var controlSecure: SecureSessionChannel? = null
        try {
            val video = open(ScreenRelayChannel.VIDEO).also { track(ScreenChannel.VIDEO, it) }
            val control = open(ScreenRelayChannel.CONTROL).also { track(ScreenChannel.CONTROL, it) }
            controlSecure = PskTlsServer.accept(
                input = control.input,
                output = control.output,
                closeTransport = control::close,
                registry = registry,
                handshakeTimeout = minOf(handshakeTimeout, timeout),
            )
            check(controlSecure.descriptor.sessionId == sessionId && descriptor.sessionId == sessionId)
            val secrets = synchronized(lock) {
                val token = requireNotNull(relayRoutingToken) { "Relay video secrets were already consumed" }
                val psk = requireNotNull(relayMasterPsk) { "Relay video secrets were already consumed" }
                relayRoutingToken = null
                relayMasterPsk = null
                token to psk
            }
            try {
                val input = RelayVideoInputStream(video, descriptor, secrets.first, secrets.second)
                videoSecure = SecureSessionChannel.authenticatedRelayVideo(
                    descriptor = descriptor,
                    input = input,
                    closeTransport = input::close,
                )
            } finally {
                secrets.first.fill(0)
                secrets.second.fill(0)
            }
            synchronized(lock) {
                videoConnection = null
                controlConnection = null
            }
            return SecureChannelPair(videoSecure, controlSecure)
        } catch (error: Throwable) {
            runCatching { videoSecure?.close() }
            runCatching { controlSecure?.close() }
            close()
            throw error
        }
    }

    private fun open(channel: ScreenRelayChannel): BrokerRelayConnection = runBlocking {
        broker.openScreenRelay(
            ScreenRelayJoin(
                relayId = relayId,
                requesterPeerId = requesterId,
                sourcePeerId = sourceId,
                role = ScreenRelayRole.REQUESTER,
                channel = channel,
                expiresAt = expiresAt,
            ),
        )
    }.also {
        if (closed.get()) {
            it.close()
            throw IOException("broker relay listener is closed")
        }
    }

    private fun track(channel: ScreenChannel, connection: BrokerRelayConnection) = synchronized(lock) {
        if (closed.get()) {
            connection.close()
            throw IOException("broker relay listener is closed")
        }
        if (channel == ScreenChannel.VIDEO) videoConnection = connection else controlConnection = connection
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val connections = synchronized(lock) {
            listOfNotNull(videoConnection, controlConnection).also {
                videoConnection = null
                controlConnection = null
                relayRoutingToken?.fill(0)
                relayMasterPsk?.fill(0)
                relayRoutingToken = null
                relayMasterPsk = null
            }
        }
        connections.forEach { runCatching { it.close() } }
    }

    companion object {
        fun randomRelayId(random: SecureRandom): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24).also(random::nextBytes))
    }
}
