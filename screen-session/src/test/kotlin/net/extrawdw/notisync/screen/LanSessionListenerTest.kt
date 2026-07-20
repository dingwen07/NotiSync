package net.extrawdw.notisync.screen

import java.net.InetAddress
import java.net.Inet6Address
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSessionListenerTest {
    @Test
    fun `listener advertises bound candidates and accepts both authenticated channels`() {
        val now = System.currentTimeMillis()
        val descriptor = SessionDescriptor(
            sessionId = "listener-session",
            sourcePeerId = "android",
            requesterPeerId = "desktop",
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            codec = "h264",
            controlEnabled = true,
            clipboardEnabled = true,
            maxDimension = 1_920,
            maxFps = 60,
            videoBitrateBps = 8_000_000,
        )
        val token = ByteArray(ROUTING_TOKEN_BYTES) { 3 }
        val psk = ByteArray(MASTER_PSK_BYTES) { 4 }
        val provider = LanAddressProvider {
            listOf(LanAddress(InetAddress.getLoopbackAddress(), "test", 8))
        }
        LanSessionListener.open(provider).use { listener ->
            PskRegistry().use { registry ->
                registry.register(descriptor, token, psk)
                val endpoint = listener.candidates.single()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val accepted = executor.submit<SecureChannelPair> {
                        listener.acceptPair(descriptor.sessionId, registry, Duration.ofSeconds(5))
                    }
                    val clientVideo = PskTlsClient.connect(
                        Socket(endpoint.host, requireNotNull(endpoint.port)),
                        descriptor,
                        token,
                        psk,
                        ScreenChannel.VIDEO,
                    )
                    val clientControl = PskTlsClient.connect(
                        Socket(endpoint.host, requireNotNull(endpoint.port)),
                        descriptor,
                        token,
                        psk,
                        ScreenChannel.CONTROL,
                    )
                    accepted.get(5, TimeUnit.SECONDS).use { serverPair ->
                        clientVideo.use { assertEquals(ScreenChannel.VIDEO, serverPair.video.channel) }
                        clientControl.use { assertEquals(ScreenChannel.CONTROL, serverPair.control.channel) }
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `candidate ordering prefers direct LAN then DNS-SD then Aware then extensions`() {
        val dns = ScreenConnectionCandidate(ScreenConnectionCandidate.DNS_SD, serviceName = "session")
        val aware = ScreenConnectionCandidate(
            ScreenConnectionCandidate.WIFI_AWARE,
            port = 2345,
            serviceName = "notisync-screen-aware",
        )
        val future = ScreenConnectionCandidate("FUTURE_P2P", serviceName = "future")
        val lan = ScreenConnectionCandidate(ScreenConnectionCandidate.LAN_TCP, "192.0.2.1", 1234)
        assertEquals(
            listOf(lan, dns, aware, future),
            ScreenConnectionCandidate.connectionOrder(listOf(future, aware, dns, lan)),
        )
    }

    @Test
    fun `listener close interrupts an in-progress unauthenticated handshake`() {
        val provider = LanAddressProvider {
            listOf(LanAddress(InetAddress.getLoopbackAddress(), "test", 8))
        }
        val listener = LanSessionListener.open(provider)
        val registry = PskRegistry()
        val executor = Executors.newSingleThreadExecutor()
        val socket = Socket(listener.candidates.single().host, requireNotNull(listener.candidates.single().port))
        try {
            val accepted = executor.submit<SecureChannelPair> {
                listener.acceptPair("pending-session", registry, Duration.ofSeconds(30))
            }
            socket.getOutputStream().write(1)
            socket.getOutputStream().flush()
            listener.close()

            assertThrows(ExecutionException::class.java) {
                accepted.get(2, TimeUnit.SECONDS)
            }
        } finally {
            socket.close()
            listener.close()
            registry.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stalled ClientHello does not delay an authenticated channel pair`() {
        val fixture = ListenerFixture("parallel-handshakes")
        LanSessionListener.open(fixture.provider).use { listener ->
            PskRegistry().use { registry ->
                registry.register(fixture.descriptor, fixture.token, fixture.psk)
                val endpoint = listener.candidates.single()
                val executor = Executors.newSingleThreadExecutor()
                val stalled = Socket(endpoint.host, requireNotNull(endpoint.port))
                try {
                    val accepted = executor.submit<SecureChannelPair> {
                        listener.acceptPair(
                            fixture.descriptor.sessionId,
                            registry,
                            Duration.ofSeconds(6),
                            handshakeTimeout = Duration.ofSeconds(5),
                        )
                    }
                    // Start a TLS record and then stop. This occupies one handshake worker
                    // until cancellation or timeout, but must not head-of-line block peers.
                    stalled.getOutputStream().write(0x16)
                    stalled.getOutputStream().flush()
                    Thread.sleep(100)

                    val video = fixture.connect(endpoint, ScreenChannel.VIDEO)
                    val control = fixture.connect(endpoint, ScreenChannel.CONTROL)
                    accepted.get(2, TimeUnit.SECONDS).use { pair ->
                        video.use { assertEquals(ScreenChannel.VIDEO, pair.video.channel) }
                        control.use { assertEquals(ScreenChannel.CONTROL, pair.control.channel) }
                    }
                } finally {
                    stalled.close()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `wrong routing token flood does not abort pending authenticated pair`() {
        val fixture = ListenerFixture("wrong-token-flood")
        LanSessionListener.open(fixture.provider).use { listener ->
            PskRegistry(maximumAttemptsPerChannel = 1).use { registry ->
                registry.register(fixture.descriptor, fixture.token, fixture.psk)
                val endpoint = listener.candidates.single()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val accepted = executor.submit<SecureChannelPair> {
                        listener.acceptPair(
                            fixture.descriptor.sessionId,
                            registry,
                            Duration.ofSeconds(15),
                            handshakeTimeout = Duration.ofSeconds(2),
                        )
                    }
                    repeat(8) { attempt ->
                        val wrongToken = fixture.token.copyOf().also {
                            it[0] = (it[0].toInt() xor (attempt + 1)).toByte()
                        }
                        assertThrows(Exception::class.java) {
                            PskTlsClient.connect(
                                Socket(endpoint.host, requireNotNull(endpoint.port)),
                                fixture.descriptor,
                                wrongToken,
                                fixture.psk,
                                ScreenChannel.VIDEO,
                                Duration.ofSeconds(2),
                            )
                        }
                    }

                    val video = fixture.connect(endpoint, ScreenChannel.VIDEO)
                    val control = fixture.connect(endpoint, ScreenChannel.CONTROL)
                    accepted.get(4, TimeUnit.SECONDS).use { pair ->
                        video.use { assertEquals(ScreenChannel.VIDEO, pair.video.channel) }
                        control.use { assertEquals(ScreenChannel.CONTROL, pair.control.channel) }
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `overall deadline clamps a stalled handshake`() {
        val fixture = ListenerFixture("overall-deadline")
        LanSessionListener.open(fixture.provider).use { listener ->
            PskRegistry().use { registry ->
                registry.register(fixture.descriptor, fixture.token, fixture.psk)
                val endpoint = listener.candidates.single()
                val executor = Executors.newSingleThreadExecutor()
                val startedAt = System.nanoTime()
                val stalled = Socket(endpoint.host, requireNotNull(endpoint.port))
                try {
                    val accepted = executor.submit<SecureChannelPair> {
                        listener.acceptPair(
                            fixture.descriptor.sessionId,
                            registry,
                            Duration.ofMillis(350),
                            handshakeTimeout = Duration.ofSeconds(10),
                        )
                    }
                    stalled.getOutputStream().write(0x16)
                    stalled.getOutputStream().flush()
                    val failure = assertThrows(ExecutionException::class.java) {
                        accepted.get(2, TimeUnit.SECONDS)
                    }
                    assertTrue(failure.cause is java.io.IOException)
                    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    assertTrue("deadline took ${elapsedMillis}ms", elapsedMillis < 1_500)
                } finally {
                    stalled.close()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `one failed interface bind does not suppress another usable LAN address`() {
        val unassigned = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 123))
        val provider = LanAddressProvider {
            listOf(
                LanAddress(unassigned, "unassigned", 24),
                LanAddress(InetAddress.getLoopbackAddress(), "test", 8),
            )
        }
        LanSessionListener.open(provider).use { listener ->
            assertEquals(listOf("test"), listener.candidates.mapNotNull { it.interfaceName })
        }
    }

    @Test
    fun `system provider admits physical LAN names and rejects virtual discovery surfaces`() {
        listOf("en0", "en7", "eth0", "enp4s0", "eno1", "wlan0", "wlp2s0", "em1").forEach {
            assertTrue(it, isPlausibleLanInterface(it))
        }
        listOf(
            "lo0", "utun3", "tun0", "tap0", "wg0", "ppp0", "docker0", "veth1234",
            "virbr0", "br-deadbeef", "bridge0", "awdl0", "llw0", "p2p0", "tailscale0",
            "vmnet8", "vboxnet0",
        ).forEach {
            assertFalse(it, isPlausibleLanInterface(it))
        }
    }

    @Test
    fun `bound prefixes admit only same-link IPv4 and IPv6 peers`() {
        val ethernet = LanAddress(InetAddress.getByName("192.168.40.12"), "en0", 24)
        assertTrue(ethernet.admits(InetAddress.getByName("192.168.40.240")))
        assertFalse(ethernet.admits(InetAddress.getByName("192.168.41.2")))
        assertFalse(ethernet.admits(InetAddress.getByName("8.8.8.8")))

        val ipv6 = LanAddress(InetAddress.getByName("2001:db8:1234:5678::10"), "en0", 64)
        assertTrue(ipv6.admits(InetAddress.getByName("2001:db8:1234:5678::99")))
        assertFalse(ipv6.admits(InetAddress.getByName("2001:db8:1234:5679::99")))
        assertFalse(ipv6.admits(InetAddress.getByName("192.168.40.99")))

        val wifi = LanAddress(InetAddress.getByName("10.22.0.4"), "wlan0", 16)
        assertTrue(wifi.admits(InetAddress.getByName("10.22.250.8")))
        assertFalse(wifi.admits(InetAddress.getByName("192.168.40.240")))
        assertFalse(ethernet.admits(InetAddress.getByName("10.22.250.8")))
    }

    @Test
    fun `IPv6 link-local admission requires the binding scope`() {
        val local = scopedIpv6("fe80::10", 7)
        val binding = LanAddress(local, "en0", 64)

        assertTrue(binding.admits(scopedIpv6("fe80::20", 7)))
        assertFalse(binding.admits(scopedIpv6("fe80::20", 8)))
        assertFalse(binding.admits(InetAddress.getByName("fe80::20")))
        assertFalse(binding.admits(InetAddress.getByName("2001:db8::20")))
    }

    private fun scopedIpv6(value: String, scopeId: Int): Inet6Address = Inet6Address.getByAddress(
        null,
        InetAddress.getByName(value).address,
        scopeId,
    )

    private class ListenerFixture(sessionId: String) {
        private val now = System.currentTimeMillis()
        val descriptor = SessionDescriptor(
            sessionId = sessionId,
            sourcePeerId = "android",
            requesterPeerId = "desktop",
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            codec = "h264",
            controlEnabled = true,
            clipboardEnabled = true,
            maxDimension = 1_920,
            maxFps = 60,
            videoBitrateBps = 8_000_000,
        )
        val token = ByteArray(ROUTING_TOKEN_BYTES) { 31 }
        val psk = ByteArray(MASTER_PSK_BYTES) { 47 }
        val provider = LanAddressProvider {
            listOf(LanAddress(InetAddress.getLoopbackAddress(), "test", 8))
        }

        fun connect(endpoint: ScreenConnectionCandidate, channel: ScreenChannel): SecureSessionChannel =
            PskTlsClient.connect(
                Socket(requireNotNull(endpoint.host), requireNotNull(endpoint.port)),
                descriptor,
                token,
                psk,
                channel,
                Duration.ofSeconds(3),
            )
    }
}
