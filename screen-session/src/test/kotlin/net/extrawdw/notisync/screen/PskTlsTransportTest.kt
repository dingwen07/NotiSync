package net.extrawdw.notisync.screen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PskTlsTransportTest {
    @Test
    fun `channel close releases socket before protocol and is idempotent`() {
        val fixture = Fixture()
        val closeOrder = mutableListOf<String>()
        val socket = object : Socket() {
            override fun close() {
                closeOrder += "socket"
            }
        }
        val channel = SecureSessionChannel(
            descriptor = fixture.descriptor,
            channel = ScreenChannel.VIDEO,
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            closeProtocol = { closeOrder += "protocol" },
            socket = socket,
        )

        channel.close()
        channel.close()

        assertEquals(listOf("socket", "protocol"), closeOrder)
    }

    @Test
    fun `TLS 1_3 external PSK connects and binds independent channels`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            for (channel in ScreenChannel.entries) {
                connectOnce(registry, fixture, channel).use { (client, server) ->
                    assertEquals(channel, client.channel)
                    assertEquals(channel, server.channel)
                    client.output.write(byteArrayOf(4, 5, 6))
                    client.output.flush()
                    assertArrayEquals(byteArrayOf(4, 5, 6), server.input.readNBytes(3))
                }
            }
        }
    }

    @Test
    fun `wrong PSK cannot complete handshake and does not consume valid channel`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock, maximumAttemptsPerChannel = 1).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            repeat(8) { attempt ->
                val badKey = fixture.masterPsk.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                badKey[1] = attempt.toByte()
                assertHandshakeRejected(registry, fixture, masterPsk = badKey)
            }
            // Bad binders are admission failures, not authenticated attempts.
            connectOnce(registry, fixture, ScreenChannel.VIDEO).use { }
        }
    }

    @Test
    fun `wrong token cannot select a session and does not consume valid channel`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            assertHandshakeRejected(
                registry = registry,
                fixture = fixture,
                token = fixture.token.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            )
            connectOnce(registry, fixture, ScreenChannel.VIDEO).use { }
        }
    }

    @Test
    fun `peer identity substitution fails before application data and leaves one retry`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            assertHandshakeRejected(
                registry = registry,
                fixture = fixture,
                descriptor = fixture.descriptor.copy(requesterPeerId = "substituted-viewer"),
            )
            connectOnce(registry, fixture, ScreenChannel.VIDEO).use { }
        }
    }

    @Test
    fun `successfully consumed channel cannot be resumed or reused`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            connectOnce(registry, fixture, ScreenChannel.VIDEO).use { }

            assertHandshakeRejected(registry, fixture)
            // A consumed video identity does not consume the separately-derived control channel.
            connectOnce(registry, fixture, ScreenChannel.CONTROL).use { }
        }
    }

    @Test
    fun `video backpressure cannot delay the independent control connection`() {
        val fixture = Fixture()
        PskRegistry(fixture.clock).use { registry ->
            registry.register(fixture.descriptor, fixture.token, fixture.masterPsk)
            connectOnce(registry, fixture, ScreenChannel.VIDEO).use { video ->
                connectOnce(registry, fixture, ScreenChannel.CONTROL).use { control ->
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val saturatingWrite = executor.submit {
                            video.client.output.write(ByteArray(8 * 1024 * 1024))
                            video.client.output.flush()
                        }
                        control.client.output.write(0x5a)
                        control.client.output.flush()
                        assertEquals(0x5a, control.server.input.read())
                        // Whether loopback accepted all bytes or is blocked is immaterial; it runs on a
                        // physically separate TLS connection and cannot hold the control write above.
                        saturatingWrite.cancel(true)
                    } finally {
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    @Test
    fun `key derivation separates channels and binds peer identities`() {
        val fixture = Fixture()
        val video = SessionKeyDeriver.derive(
            fixture.masterPsk,
            fixture.token,
            fixture.descriptor,
            ScreenChannel.VIDEO,
        )
        val control = SessionKeyDeriver.derive(
            fixture.masterPsk,
            fixture.token,
            fixture.descriptor,
            ScreenChannel.CONTROL,
        )
        val changedPeer = SessionKeyDeriver.derive(
            fixture.masterPsk,
            fixture.token,
            fixture.descriptor.copy(sourcePeerId = "another-source"),
            ScreenChannel.VIDEO,
        )
        assertEquals(CHANNEL_PSK_BYTES, video.size)
        assertFalse(video.contentEquals(control))
        assertFalse(video.contentEquals(changedPeer))
    }

    @Test
    fun `routing identities are strict and channel-specific`() {
        val token = ByteArray(ROUTING_TOKEN_BYTES) { it.toByte() }
        val video = RoutingIdentity.encode(token, ScreenChannel.VIDEO)
        assertArrayEquals(token, RoutingIdentity.parse(video)?.token)
        assertEquals(ScreenChannel.VIDEO, RoutingIdentity.parse(video)?.channel)
        assertNull(RoutingIdentity.parse("nss1.bad.video".encodeToByteArray()))
        assertNull(RoutingIdentity.parse(video.decodeToString().replace(".video", "==.video").encodeToByteArray()))
        assertNull(RoutingIdentity.parse(video + byteArrayOf(0)))
    }

    @Test
    fun `replay protector records only live pair once`() {
        val clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC)
        val replay = InMemoryReplayProtector(clock)
        val token = ByteArray(ROUTING_TOKEN_BYTES) { 7 }
        assertTrue(replay.record("session", token, 20_000))
        assertFalse(replay.record("session", token, 20_000))
        assertFalse(replay.record("session", ByteArray(ROUTING_TOKEN_BYTES) { 8 }, 20_000))
        assertFalse(replay.record("another-session", token, 20_000))
        assertFalse(replay.record("expired", token, 9_999))
        assertFalse(replay.record("too-long", ByteArray(ROUTING_TOKEN_BYTES) { 9 }, 400_001))
    }

    private fun connectOnce(
        registry: PskRegistry,
        fixture: Fixture,
        channel: ScreenChannel,
    ): ChannelPair {
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val serverFuture = executor.submit<SecureSessionChannel> {
                PskTlsServer.accept(listener.accept(), registry, Duration.ofSeconds(3))
            }
            val client = PskTlsClient.connect(
                Socket(InetAddress.getLoopbackAddress(), listener.localPort),
                fixture.descriptor,
                fixture.token,
                fixture.masterPsk,
                channel,
                Duration.ofSeconds(3),
            )
            ChannelPair(client, serverFuture.get(4, TimeUnit.SECONDS))
        } finally {
            listener.close()
            executor.shutdown()
        }
    }

    private fun assertHandshakeRejected(
        registry: PskRegistry,
        fixture: Fixture,
        descriptor: SessionDescriptor = fixture.descriptor,
        token: ByteArray = fixture.token,
        masterPsk: ByteArray = fixture.masterPsk,
        channel: ScreenChannel = ScreenChannel.VIDEO,
    ) {
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val rejected = executor.submit<Throwable?> {
                runCatching {
                    PskTlsServer.accept(listener.accept(), registry, Duration.ofSeconds(2)).close()
                }.exceptionOrNull()
            }
            assertThrows(Exception::class.java) {
                PskTlsClient.connect(
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort),
                    descriptor,
                    token,
                    masterPsk,
                    channel,
                    Duration.ofSeconds(2),
                )
            }
            assertTrue(rejected.get(3, TimeUnit.SECONDS) != null)
        } finally {
            listener.close()
            executor.shutdownNow()
        }
    }

    private data class ChannelPair(
        val client: SecureSessionChannel,
        val server: SecureSessionChannel,
    ) : AutoCloseable {
        override fun close() {
            client.close()
            server.close()
        }
    }

    private class Fixture {
        val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC)
        val token = ByteArray(ROUTING_TOKEN_BYTES) { (it + 1).toByte() }
        val masterPsk = ByteArray(MASTER_PSK_BYTES) { (it + 2).toByte() }
        val descriptor = SessionDescriptor(
            sessionId = "session-1",
            sourcePeerId = "android-a",
            requesterPeerId = "desktop-b",
            issuedAtEpochMillis = 1_000_000,
            expiresAtEpochMillis = 1_300_000,
            codec = "h264",
            controlEnabled = true,
            clipboardEnabled = true,
            maxDimension = 1_920,
            maxFps = 60,
            videoBitrateBps = 8_000_000,
        )
    }
}
