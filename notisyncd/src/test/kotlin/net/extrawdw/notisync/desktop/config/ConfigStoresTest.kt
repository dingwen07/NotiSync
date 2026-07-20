package net.extrawdw.notisync.desktop.config

import java.nio.file.Files
import net.extrawdw.notisync.desktop.PrivateFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStoresTest {
    @Test
    fun `legacy production broker is upgraded and persisted once`() {
        val path = Files.createTempDirectory("notisyncd-broker-migration-test").toRealPath().resolve("notisyncd.conf")
        Files.writeString(path, "broker-url wss://notisync-api.extrawdw.net\n")

        val config = NotisyncdConfigStore(path).load()

        assertEquals("https://notisync-api-v2.extrawdw.net", config.brokerUrl)
        assertFalse(config.unverifiedDeviceCleanupV1Completed)
        val persisted = Files.readString(path)
        assertTrue(persisted.contains("https://notisync-api-v2.extrawdw.net"))
        assertFalse(persisted.contains("notisync-api.extrawdw.net"))
    }

    @Test
    fun `custom broker is not changed by default migration`() {
        val path = Files.createTempDirectory("notisyncd-custom-broker-test").toRealPath().resolve("notisyncd.conf")
        Files.writeString(path, "broker-url https://notisync-api.extrawdw.net.evil.example\n")

        val config = NotisyncdConfigStore(path).load()

        assertEquals("https://notisync-api.extrawdw.net.evil.example", config.brokerUrl)
    }

    @Test
    fun `unverified device cleanup marker is independent and persists once`() {
        val path = Files.createTempDirectory("notisyncd-device-cleanup-test").toRealPath().resolve("notisyncd.conf")
        val store = NotisyncdConfigStore(path)

        assertFalse(store.load().unverifiedDeviceCleanupV1Completed)
        store.markUnverifiedDeviceCleanupV1Completed()
        assertTrue(store.load().unverifiedDeviceCleanupV1Completed)

        store.save(store.load().copy(brokerUrl = "wss://notisync-api.extrawdw.net"))
        val reloaded = store.load()
        assertEquals("https://notisync-api-v2.extrawdw.net", reloaded.brokerUrl)
        assertTrue(reloaded.unverifiedDeviceCleanupV1Completed)
    }

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
                auto-apply-trusted-device-tables yes
                log-level DEBUG
                websocket-ping-seconds 45
            """.trimIndent(),
            path = path,
            deviceNameDefault = { error("device-name default must remain lazy") },
        )

        assertEquals("Configured Desktop", config.deviceName)
        assertEquals(45, config.websocketPingSeconds)
    }

    @Test
    fun `daemon and run stores are independent files`() {
        val root = Files.createTempDirectory("notisync-config-test").toRealPath()
        val daemon = NotisyncdConfigStore(root.resolve("notisyncd.conf"))
        val runPath = root.resolve("nsrun.conf")

        daemon.save(daemon.load().copy(deviceName = "desktop"))
        PrivateFiles.atomicWrite(
            runPath,
            "# NotiSync Run configuration\nupdate-interval-seconds 45\n".encodeToByteArray(),
        )

        assertEquals("desktop", daemon.load().deviceName)
        assertFalse(Files.readString(daemon.path).contains("llm-"))
        assertFalse(Files.readString(runPath).contains("broker-url"))
        assertTrue(Files.readString(runPath).startsWith("# NotiSync Run"))
    }

    @Test
    fun `daemon log level defaults to warn`() {
        val path = Files.createTempDirectory("notisyncd-default-test").toRealPath().resolve("notisyncd.conf")

        assertEquals("WARN", NotisyncdConfigStore(path).load().logLevel)
        assertFalse(Files.exists(path))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid daemon log level is rejected`() {
        NotisyncdConfig(logLevel = "verbose").validate()
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
                auto-apply-trusted-device-tables no # secure default
            """.trimIndent(),
        )
        val config = NotisyncdConfigStore(path).load()
        assertEquals("wss://example.test/socket#fragment", config.brokerUrl)
        assertEquals("Workstation \"A\"", config.deviceName)
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
        assertEquals("pty never\n", Files.readString(runPath))
    }
}
