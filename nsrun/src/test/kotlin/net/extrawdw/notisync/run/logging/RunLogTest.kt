package net.extrawdw.notisync.run.logging

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.localapi.LocalApiJson

class RunLogTest {
    @Test
    fun `raw output remains lossless in NDJSON`() {
        val root = Files.createTempDirectory("nsrun-log").toRealPath()
        val paths = DesktopPaths(root)
        val bytes = byteArrayOf(0, 1, 13, 10, -1)
        val log = RunLog.create(NSRunConfig(), paths, pid = 42, processStartMillis = 99)
        log.header(Path.of("/tmp"), listOf("echo", "x"))
        log.output(bytes)
        log.progress(2, 10)
        log.completed(0)
        log.close()

        val records = Files.readAllLines(log.path).map { LocalApiJson.decodeFromString<RunLogRecord>(it) }
        assertEquals(listOf("start", "output", "progress", "complete"), records.map { it.type })
        assertArrayEquals(bytes, Base64.getDecoder().decode(records[1].outputBase64))
        assertEquals(2L, records[2].progressCurrent)
    }

    @Test
    fun `active run never grows beyond configured byte budget`() {
        val root = Files.createTempDirectory("nsrun-log-budget").toRealPath()
        val maximum = 700L
        val log = RunLog.create(NSRunConfig(logMaxBytes = maximum), DesktopPaths(root), pid = 7, processStartMillis = 8)
        log.header(Path.of("/tmp"), listOf("noisy"))
        repeat(100) { log.output(ByteArray(256) { it.toByte() }) }
        log.completed(0)
        log.close()

        assertTrue(Files.size(log.path) <= maximum)
        // The limit is applied at record boundaries, so every retained NDJSON line remains valid.
        Files.readAllLines(log.path).forEach { LocalApiJson.decodeFromString<RunLogRecord>(it) }
    }
}
