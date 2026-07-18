package net.extrawdw.notisync.desktop.config

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStoresTest {
    @Test
    fun `daemon device name defaults to the operating system hostname`() {
        assertEquals(
            "build-host",
            defaultDeviceName(resolveHostname = { "  build-host  " }, environmentHostname = "fallback-host"),
        )
        assertEquals(
            "fallback-host",
            defaultDeviceName(resolveHostname = { throw IllegalStateException("unavailable") }, environmentHostname = "fallback-host"),
        )
    }

    @Test
    fun `complete daemon config does not resolve dynamic defaults`() {
        val path = Files.createTempDirectory("notisync-complete-config-test").toRealPath().resolve("notisyncd.conf")
        val config = NotisyncdConfigCodec.decodeWithDefaults(
            text = """
                broker-url wss://example.test
                device-name Configured Desktop
                platform-name Configured Platform
                auto-apply-trusted-device-tables yes
                log-level DEBUG
                websocket-ping-seconds 45
            """.trimIndent(),
            path = path,
            deviceNameDefault = { error("device-name default must remain lazy") },
            platformNameDefault = { error("platform-name default must remain lazy") },
        )

        assertEquals("Configured Desktop", config.deviceName)
        assertEquals("Configured Platform", config.platformName)
        assertEquals(45, config.websocketPingSeconds)
    }

    @Test
    fun `daemon and run stores are independent files`() {
        val root = Files.createTempDirectory("notisync-config-test").toRealPath()
        val daemon = NotisyncdConfigStore(root.resolve("notisyncd.conf"))
        val run = NSRunConfigStore(root.resolve("nsrun.conf"))

        daemon.save(daemon.load().copy(deviceName = "desktop"))
        run.save(run.load().copy(updateIntervalSeconds = 45))

        assertEquals("desktop", daemon.load().deviceName)
        assertEquals(45L, run.load().updateIntervalSeconds)
        assertFalse(Files.readString(daemon.path).contains("llm-"))
        assertFalse(Files.readString(run.path).contains("broker-url"))
        assertTrue(Files.readString(run.path).startsWith("# NotiSync Run"))
    }

    @Test
    fun `missing configuration uses safe defaults without writing a file`() {
        val path = Files.createTempDirectory("notisync-default-test").toRealPath().resolve("nsrun.conf")
        val config = NSRunConfigStore(path).load()
        assertEquals(30L, config.updateIntervalSeconds)
        assertEquals(300L, config.stuckAfterSeconds)
        assertFalse(Files.exists(path))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `symbolic link configuration is rejected`() {
        val root = Files.createTempDirectory("notisync-symlink-test").toRealPath()
        val target = root.resolve("target.json")
        Files.writeString(target, "{}")
        val link = root.resolve("nsrun.conf")
        Files.createSymbolicLink(link, target)
        NSRunConfigStore(link).load()
    }

    @Test
    fun `quoted values comments and escapes round trip`() {
        val root = Files.createTempDirectory("notisync-conf-quote").toRealPath()
        val path = root.resolve("notisyncd.conf")
        Files.writeString(
            path,
            """
                # hand-written configuration
                broker-url "wss://example.test/socket#fragment"
                device-name "Workstation \"A\""
                platform-name "macOS\narm64"
                auto-apply-trusted-device-tables no # secure default
            """.trimIndent(),
        )
        val config = NotisyncdConfigStore(path).load()
        assertEquals("wss://example.test/socket#fragment", config.brokerUrl)
        assertEquals("Workstation \"A\"", config.deviceName)
        assertEquals("macOS\narm64", config.platformName)
    }

    @Test
    fun `off stuck setting remains disabled`() {
        val root = Files.createTempDirectory("notisync-conf-off").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "stuck-after-seconds off\n")
        assertEquals(null, NSRunConfigStore(path).load().stuckAfterSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown options are rejected`() {
        val root = Files.createTempDirectory("notisync-conf-unknown").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "mystery-option yes\n")
        NSRunConfigStore(path).load()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate options are rejected`() {
        val root = Files.createTempDirectory("notisync-conf-duplicate").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "pty auto\npty never\n")
        NSRunConfigStore(path).load()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `symbolic link ancestor is rejected`() {
        val root = Files.createTempDirectory("notisync-conf-ancestor").toRealPath()
        val real = Files.createDirectory(root.resolve("real"))
        val link = root.resolve("linked")
        Files.createSymbolicLink(link, real)
        NSRunConfigStore(link.resolve("nsrun.conf")).load()
    }

    @Test
    fun `runtime recovery archives malformed run config while strict load still rejects it`() {
        val root = Files.createTempDirectory("nsrun-conf-recovery").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "unknown-option yes\n")
        val store = NSRunConfigStore(path)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { store.load() }

        val warnings = mutableListOf<String>()
        val recovered = store.loadRecovering(warnings::add)
        assertEquals(NSRunConfig(), recovered)
        assertEquals(NSRunConfig(), store.load())
        assertTrue(warnings.single().contains("using safe defaults"))
        assertTrue(Files.list(root).use { files ->
            files.anyMatch { it.fileName.toString().startsWith("nsrun.conf.corrupt-") }
        })
    }

    @Test
    fun `daemon runtime recovery is independent from run config`() {
        val root = Files.createTempDirectory("notisyncd-conf-recovery").toRealPath()
        val daemonPath = root.resolve("notisyncd.conf")
        val runPath = root.resolve("nsrun.conf")
        Files.writeString(daemonPath, "broker-url definitely-not-a-websocket\n")
        Files.writeString(runPath, "pty never\n")

        val recovered = NotisyncdConfigStore(daemonPath).loadRecovering()
        assertEquals(NotisyncdConfig().brokerUrl, recovered.brokerUrl)
        assertEquals(PtyMode.NEVER, NSRunConfigStore(runPath).load().pty)
        assertEquals("pty never\n", Files.readString(runPath))
    }
}
