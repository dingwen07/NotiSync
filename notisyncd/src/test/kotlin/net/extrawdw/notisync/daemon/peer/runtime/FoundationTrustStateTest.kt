package net.extrawdw.notisync.daemon.peer.runtime

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.ports.TrustPersistence
import org.junit.Assert.assertThrows
import org.junit.Test

class FoundationTrustStateTest {
    @Test
    fun `durable trust write failure is translated to relay retry`() {
        val persistence = ClassifiedTrustPersistence(object : TrustPersistence {
            override fun read(key: String): String? = null
            override fun write(values: Map<String, String?>) {
                throw IOException("disk unavailable")
            }
        })

        assertThrows(RetryableDeliveryException::class.java) {
            retryableTrustMutation(ReentrantLock()) {
                persistence.write(mapOf("trust_entries_json" to "[]"))
            }
        }
    }
}
