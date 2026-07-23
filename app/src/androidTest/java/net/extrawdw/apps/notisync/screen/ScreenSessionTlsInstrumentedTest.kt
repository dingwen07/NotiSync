package net.extrawdw.apps.notisync.screen

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.screen.MASTER_PSK_BYTES
import net.extrawdw.notisync.screen.PskRegistry
import net.extrawdw.notisync.screen.PskTlsClient
import net.extrawdw.notisync.screen.PskTlsServer
import net.extrawdw.notisync.screen.ROUTING_TOKEN_BYTES
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.SessionDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * API 34-37 device gate for the exact Bouncy Castle artifact packaged in the Play APK. The host-side
 * :screen-session tests exercise the JVM 21 endpoint and downgrade/replay failures; this verifies that
 * Android's desugaring, DEX packaging, sockets, and crypto runtime complete the same TLS 1.3 profile.
 */
class ScreenSessionTlsInstrumentedTest {
    @Test
    fun externalPskDheChannelsInteroperateOnAndroidRuntime() {
        val now = System.currentTimeMillis()
        val descriptor = SessionDescriptor(
            sessionId = "android-instrumented-session",
            sourcePeerId = "android-source",
            requesterPeerId = "jvm-viewer",
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + 60_000,
            codec = "h264",
            controlEnabled = true,
            clipboardEnabled = true,
            maxDimension = 1_920,
            maxFps = 60,
            videoBitrateBps = 8_000_000,
        )
        val token = ByteArray(ROUTING_TOKEN_BYTES) { (it + 1).toByte() }
        val psk = ByteArray(MASTER_PSK_BYTES) { (it + 17).toByte() }

        PskRegistry().use { registry ->
            registry.register(descriptor, token, psk)
            ScreenChannel.entries.forEach { expectedChannel ->
                val listener = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val accepted = executor.submit {
                        PskTlsServer.accept(listener.accept(), registry, Duration.ofSeconds(10))
                    }
                    PskTlsClient.connect(
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort),
                        descriptor,
                        token,
                        psk,
                        expectedChannel,
                        Duration.ofSeconds(10),
                    ).use { client ->
                        accepted.get(12, TimeUnit.SECONDS).use { server ->
                            assertEquals(expectedChannel, client.channel)
                            assertEquals(expectedChannel, server.channel)
                            val plaintext = byteArrayOf(0, 1, 2, 3, 0x7f)
                            client.output.write(plaintext)
                            client.output.flush()
                            assertArrayEquals(plaintext, server.input.readNBytes(plaintext.size))
                        }
                    }
                } finally {
                    listener.close()
                    executor.shutdownNow()
                }
            }
        }
    }
}
