package net.extrawdw.notisync.run.process

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import net.extrawdw.notisync.desktop.config.PtyMode

class ChildLauncherTest {
    @Test
    fun `non-PTY execution merges stderr and stdout and preserves argv`() {
        val child = ChildLauncher(terminalAttached = { false }).launch(
            listOf("/bin/sh", "-c", "printf 'out\\n'; printf 'err\\n' >&2"),
            Path.of("/tmp"),
            System.getenv(),
            PtyMode.AUTO,
        )
        val output = child.mergedOutput.bufferedReader().use { it.readText() }
        assertEquals(0, child.waitFor())
        assertFalse(child.usesPty)
        assertEquals("out\nerr\n", output)
        child.close()
    }

    @Test
    fun `terminate resumes a stopped PTY foreground group so it can exit`() {
        assumeFalse(System.getProperty("os.name").contains("windows", ignoreCase = true))
        val inspector = ProcessInspectors.system()
        val child = ChildLauncher(terminalAttached = { false }, inspector = inspector).launch(
            listOf("/bin/sh", "-c", "trap 'exit 42' TERM; kill -STOP \$\$; exit 99"),
            Path.of("/tmp"),
            System.getenv(),
            PtyMode.ALWAYS,
        )
        val waiter = Executors.newSingleThreadExecutor()
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
            while (inspector.snapshot(child.pid)?.state != 'T' && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals('T', inspector.snapshot(child.pid)?.state)

            val result = waiter.submit<Int> { child.waitFor() }
            child.signal(ChildSignal.TERMINATE)

            assertEquals(42, result.get(5, TimeUnit.SECONDS))
        } finally {
            if (child.isAlive()) ProcessHandle.of(child.pid).ifPresent(ProcessHandle::destroyForcibly)
            child.close()
            waiter.shutdownNow()
            assertTrue(waiter.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
