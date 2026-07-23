package net.extrawdw.notisync.screen.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

class NSScreenCliTest {
    @Test
    fun `connect defaults are bounded and prefer h264 later`() {
        val invocation = NSScreenCli.parse(arrayOf("connect", "phone")) as ScreenInvocation.Connect
        assertEquals("phone", invocation.options.deviceId)
        assertEquals(null, invocation.options.codec)
        assertEquals(1_920, invocation.options.maxDimension)
        assertEquals(60, invocation.options.maxFps)
        assertEquals(8_000_000, invocation.options.bitrateBps)
        assertTrue(invocation.options.control)
        assertTrue(invocation.options.clipboard)
    }

    @Test
    fun `connect parses codec and quality`() {
        val invocation = NSScreenCli.parse(
            arrayOf(
                "connect", "--codec", "av1", "--max-dimension", "1280",
                "--max-fps", "30", "--bitrate", "4000000", "phone",
            ),
        ) as ScreenInvocation.Connect
        assertEquals(ScreenMirrorCodec.AV1, invocation.options.codec)
        assertEquals(1_280, invocation.options.maxDimension)
        assertEquals(30, invocation.options.maxFps)
        assertEquals(4_000_000, invocation.options.bitrateBps)
    }

    @Test
    fun `clipboard cannot remain enabled without control channel`() {
        assertThrows(ScreenCliException::class.java) {
            NSScreenCli.parse(arrayOf("connect", "phone", "--no-control"))
        }
        val invocation = NSScreenCli.parse(
            arrayOf("connect", "phone", "--no-control", "--no-clipboard"),
        ) as ScreenInvocation.Connect
        assertEquals(false, invocation.options.control)
        assertEquals(false, invocation.options.clipboard)
    }

    @Test
    fun `quality values outside Android protocol bounds are rejected locally`() {
        listOf(
            arrayOf("connect", "phone", "--max-dimension", "239"),
            arrayOf("connect", "phone", "--max-dimension", "8193"),
            arrayOf("connect", "phone", "--bitrate", "127999"),
            arrayOf("connect", "phone", "--bitrate", "100000001"),
            arrayOf("connect", "phone", "--max-fps", "241"),
        ).forEach { arguments ->
            assertThrows(ScreenCliException::class.java) { NSScreenCli.parse(arguments) }
        }
    }
}
