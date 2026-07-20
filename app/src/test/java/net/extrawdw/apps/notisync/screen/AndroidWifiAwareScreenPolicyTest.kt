package net.extrawdw.apps.notisync.screen

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.SessionDescriptor
import net.extrawdw.notisync.screen.SessionKeyDeriver

class AndroidWifiAwareScreenPolicyTest {
    @Test
    fun `aware PMK is stable 32 bytes and independent from both TLS channel keys`() {
        val master = ByteArray(32) { it.toByte() }
        val token = ByteArray(16) { (0xa0 + it).toByte() }
        val masterBefore = master.copyOf()
        val tokenBefore = token.copyOf()

        val pmk = AndroidWifiAwarePmkDeriver.derive(master, token, descriptor)

        assertEquals(32, pmk.size)
        assertEquals("98117a1f74a8302b3ccfdfb6994ebbef4bf3a039ca5d0964d0c87ba03717bf60", pmk.toHex())
        assertArrayEquals(masterBefore, master)
        assertArrayEquals(tokenBefore, token)
        assertFalse(pmk.contentEquals(SessionKeyDeriver.derive(master, token, descriptor, ScreenChannel.VIDEO)))
        assertFalse(pmk.contentEquals(SessionKeyDeriver.derive(master, token, descriptor, ScreenChannel.CONTROL)))
        assertArrayEquals(pmk, AndroidWifiAwarePmkDeriver.derive(master, token, descriptor))
    }

    @Test
    fun `aware PMK binds routing token and complete descriptor context`() {
        val master = ByteArray(32) { (it * 3).toByte() }
        val token = ByteArray(16) { (it * 5).toByte() }
        val baseline = AndroidWifiAwarePmkDeriver.derive(master, token, descriptor)

        assertFalse(
            baseline.contentEquals(
                AndroidWifiAwarePmkDeriver.derive(master, token.copyOf().also { it[0]++ }, descriptor),
            ),
        )
        assertFalse(
            baseline.contentEquals(
                AndroidWifiAwarePmkDeriver.derive(master, token, descriptor.copy(maxFps = 59)),
            ),
        )
        assertFalse(
            baseline.contentEquals(
                AndroidWifiAwarePmkDeriver.derive(master, token, descriptor.copy(sessionId = "screen:other")),
            ),
        )
    }

    @Test
    fun `service name is fixed-shape lowercase ASCII with 128 bits of entropy`() {
        val entropy = ByteArray(16) { it.toByte() }
        val name = AndroidWifiAwareServiceNames.fromEntropy(entropy)

        assertEquals("notisync-screen-000102030405060708090a0b0c0d0e0f", name)
        assertTrue(AndroidWifiAwareServiceNames.isValid(name))
        assertNotEquals(name, AndroidWifiAwareServiceNames.fromEntropy(entropy.copyOf().also { it[15]++ }))
        assertFalse(AndroidWifiAwareServiceNames.isValid("notisync-screen-session-id"))
    }

    @Test
    fun `only signed positive aware candidate ports are accepted`() {
        val serviceName = AndroidWifiAwareServiceNames.fromEntropy(ByteArray(16))

        assertTrue(AndroidWifiAwareEndpointPolicy.validSignedCandidate(serviceName, 1))
        assertTrue(AndroidWifiAwareEndpointPolicy.validSignedCandidate(serviceName, 65_535))
        assertFalse(AndroidWifiAwareEndpointPolicy.validSignedCandidate(serviceName, null))
        assertFalse(AndroidWifiAwareEndpointPolicy.validSignedCandidate(serviceName, 0))
        assertFalse(AndroidWifiAwareEndpointPolicy.validSignedCandidate(serviceName, 65_536))
        assertFalse(AndroidWifiAwareEndpointPolicy.validSignedCandidate("other-service", 443))
    }

    @Test
    fun `subscriber requires aware TCP metadata to match the signed port and scoped peer`() {
        val peer = linkLocal(lastByte = 2, scopeId = 7)
        val local = linkLocal(lastByte = 1, scopeId = 7)

        assertEquals(
            AndroidWifiAwareEndpointDecision.ACCEPT,
            evaluate(peer = peer, local = local),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.REJECT,
            evaluate(peer = peer, local = local, signedPort = 44_444, discoveredPort = 44_445),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.REJECT,
            evaluate(peer = peer, local = local, protocol = 17),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.REJECT,
            evaluate(peer = peer, local = local, aware = false),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.REJECT,
            evaluate(peer = linkLocal(lastByte = 2, scopeId = 0), local = local),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.REJECT,
            evaluate(peer = peer, local = linkLocal(lastByte = 1, scopeId = 8)),
        )
    }

    @Test
    fun `subscriber waits for complete capabilities and link properties`() {
        val peer = linkLocal(lastByte = 2, scopeId = 7)

        assertEquals(
            AndroidWifiAwareEndpointDecision.WAIT,
            AndroidWifiAwareEndpointPolicy.evaluate(
                signedPort = 44_444,
                hasAwareTransport = true,
                discoveredPort = null,
                transportProtocol = null,
                peerAddress = null,
                interfaceName = null,
                localAddresses = null,
            ),
        )
        assertEquals(
            AndroidWifiAwareEndpointDecision.WAIT,
            AndroidWifiAwareEndpointPolicy.evaluate(
                signedPort = 44_444,
                hasAwareTransport = true,
                discoveredPort = 44_444,
                transportProtocol = 6,
                peerAddress = peer,
                interfaceName = null,
                localAddresses = null,
            ),
        )
    }

    @Test
    fun `publisher admits only the expected peer on the exact aware local address`() {
        val peer = linkLocal(lastByte = 2, scopeId = 7)
        val local = linkLocal(lastByte = 1, scopeId = 7)

        assertTrue(
            AndroidWifiAwareEndpointPolicy.acceptsSocket(peer, listOf(local), peer, local),
        )
        assertFalse(
            AndroidWifiAwareEndpointPolicy.acceptsSocket(
                peer,
                listOf(local),
                linkLocal(lastByte = 3, scopeId = 7),
                local,
            ),
        )
        assertFalse(
            AndroidWifiAwareEndpointPolicy.acceptsSocket(
                peer,
                listOf(local),
                linkLocal(lastByte = 2, scopeId = 8),
                local,
            ),
        )
        assertFalse(
            AndroidWifiAwareEndpointPolicy.acceptsSocket(
                peer,
                listOf(local),
                peer,
                linkLocal(lastByte = 4, scopeId = 7),
            ),
        )
    }

    private fun evaluate(
        peer: InetAddress,
        local: InetAddress,
        signedPort: Int = 44_444,
        discoveredPort: Int = signedPort,
        protocol: Int = 6,
        aware: Boolean = true,
    ) = AndroidWifiAwareEndpointPolicy.evaluate(
        signedPort = signedPort,
        hasAwareTransport = aware,
        discoveredPort = discoveredPort,
        transportProtocol = protocol,
        peerAddress = peer,
        interfaceName = "aware_data0",
        localAddresses = listOf(local),
    )

    private fun linkLocal(lastByte: Int, scopeId: Int): Inet6Address {
        val bytes = ByteArray(16)
        bytes[0] = 0xfe.toByte()
        bytes[1] = 0x80.toByte()
        bytes[15] = lastByte.toByte()
        return Inet6Address.getByAddress(null, bytes, scopeId)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val descriptor = SessionDescriptor(
        sessionId = "screen:aware-vector",
        sourcePeerId = "source-peer",
        requesterPeerId = "viewer-peer",
        issuedAtEpochMillis = 1_000_000L,
        expiresAtEpochMillis = 1_120_000L,
        codec = "h265",
        controlEnabled = true,
        clipboardEnabled = false,
        maxDimension = 1_920,
        maxFps = 60,
        videoBitrateBps = 8_000_000,
    )
}
