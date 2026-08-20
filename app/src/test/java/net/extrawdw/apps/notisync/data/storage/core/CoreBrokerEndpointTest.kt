package net.extrawdw.apps.notisync.data.storage.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreBrokerEndpointTest {
    @Test
    fun equivalentOriginsHaveOneCanonicalIdentity() {
        assertEquals("https://broker.example.test", canonicalizeBrokerEndpoint(" HTTPS://BROKER.EXAMPLE.TEST:443/ "))
        assertEquals("https://broker.example.test", canonicalizeBrokerEndpoint("wss://broker.example.test"))
        assertEquals("http://10.0.2.2:8080", canonicalizeBrokerEndpoint("ws://10.0.2.2:8080/"))
        assertEquals("https://broker.example.test", canonicalizeBrokerEndpoint("https://broker.example.test./"))
        assertEquals(
            "https://broker.example.test/Api/%2Ftenant",
            canonicalizeBrokerEndpoint("wss://BROKER.EXAMPLE.TEST:443/Api/%2Ftenant///"),
        )
    }

    @Test
    fun meaningfulBasePathsRemainDistinctAndBytePreserving() {
        assertEquals(
            "https://broker.example.test/Api/%2Ftenant",
            canonicalizeBrokerEndpoint("https://broker.example.test/Api/%2Ftenant"),
        )
        assertEquals(
            "https://broker.example.test/api/%2ftenant",
            canonicalizeBrokerEndpoint("https://broker.example.test/api/%2ftenant"),
        )
    }

    @Test
    fun endpointIdentityRejectsUnsafeAndAmbiguousInputs() {
        listOf(
            "",
            "broker.example.test",
            "ftp://broker.example.test",
            "https://user@broker.example.test",
            "https://broker.example.test?query=1",
            "https://broker.example.test/#fragment",
            "https://broker.example.test:0",
            "https://broker.example.test/./tenant",
            "https://broker.example.test/../tenant",
            "https://broker.example.test/%2e/tenant",
            "https://broker.example.test/.%2E/tenant",
            "https://broker.example.test/%5Ctenant",
            "https://broker.example.test/tenant\\child",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { canonicalizeBrokerEndpoint(value) }
        }
    }
}
