package net.extrawdw.apps.notisync.screen

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidScreenRequesterLanTest {
    @Test
    fun `link properties keep only valid unicast addresses from the selected interface`() {
        val addresses = androidLanAddresses(
            interfaceName = "wlan0",
            addresses = listOf(
                InetAddress.getByName("192.168.7.12") to 24,
                InetAddress.getByName("0.0.0.0") to 0,
                InetAddress.getByName("224.0.0.251") to 24,
                InetAddress.getByName("192.168.7.13") to 40,
            ),
        )

        assertEquals(1, addresses.size)
        assertEquals("192.168.7.12", addresses.single().address.hostAddress)
        assertEquals("wlan0", addresses.single().interfaceName)
        assertEquals(24, addresses.single().prefixLength)
    }

    @Test
    fun `missing interface name cannot produce a routable candidate`() {
        assertTrue(
            androidLanAddresses(
                interfaceName = null,
                addresses = listOf(InetAddress.getByName("192.168.7.12") to 24),
            ).isEmpty(),
        )
    }
}
